(ns decide-host.web
  (:gen-class)
  (:require [clojure.core.match :refer [match]]
            [cheshire.core :as json]
            [frodo.web :refer [App]]
            [ring.util.response :refer [response content-type]]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [ring.middleware.format-response :refer [wrap-json-response]]
            [ring.middleware.cors :refer [wrap-cors]]
            [compojure.core :refer [routes context GET]]
            [compojure.route :refer [resources not-found]]
            [org.httpkit.server :refer [with-channel on-close send! close]]
            [hiccup.core :refer [html]]
            [decide-host.config :refer [init-context]]
            [decide-host.core :refer [version log]]
            [decide-host.host :as host]
            [decide-host.query :as query]
            [decide-host.database :as db]
            [decide-host.aggregators :as agg]
            [decide-host.views :as views]
            [decide-host.handlers :refer [add-handler update-subject!]]))

(defn stream-response
  [req {:keys [header body] :as response}]
  (with-channel req chan
    (send! chan
           (-> response
               (assoc :body nil)
               (content-type "application/json; charset=utf-8"))
           false)
    (doseq [rec body
            :let [body* (str (json/encode rec {:pretty true}) "\r\n")]]
      (send! chan body* false))
    (close chan))
  ;; stupidly, with-channel returns the channel, so we need to avoid passing
  ;; anything to other handlers that may try to return it.
  nil)


(defn wrap-streaming-response
  "Middleware that will return seq responses as streams of JSON objects
  separated by '\r\n'"
  [handler]
  (fn [req]
    (let [{:keys [headers body] :as response} (handler req)]
      (if (seq? body)
        (stream-response req response)
        response))))


(defn controller-list-view
  "Returns a list of all controllers in the database"
  [db {params :params}]
  (let [params (query/parse params :actions [:sequences])]
    (map (fn [c] (assoc (select-keys c [:addr :last-seen :last-event])
                       :connected (not (nil? (:zmq-id c)))))
         (db/find-controllers db (:match params)))))

(defn controller-view
  [db addr]
  (when-let [result (first (controller-list-view db {:params {:addr addr}}))]
    {:body result}))

(defn subjects-view
  [db {params :params}]
  (let [params (query/parse params :actions [:sequences :uuid])]
    #_(println "D: subjects-view" params)
    (db/find-subjects db (:match params))))
(defn active-subjects-view [db] (subjects-view db {:params {:controller {"$ne" nil}}}))
(defn inactive-subjects-view [db] (subjects-view db {:params {:controller nil}}))

(defn subject-view
  [db subject]
  (when-let [result (db/find-subject db subject)]
    {:body result}))

(defn event-view
  [db {params :params}]
  (let [params (query/parse params)]
    #_(println "D: event-view" params)
    (db/find-events db params)))

(defn trial-view
  [db {params :params}]
  #_(println "D: trial-view" params)
  (let [params (query/parse params)]
    (db/find-trials db params)))

(defn stats-view
  [db {params :params :as req}]
  (let [params (query/parse params)]
    #_(println "D: stats-view" params)
    (agg/hourly-stats db params)))

(defn api-routes
  [{{db :db} :database}]
  (routes
   (context "/api" []
     (context "/controllers" []
       (GET "/" req (controller-list-view db req))
       (context "/:addr" [addr]
         (GET "/" [] (controller-view db addr))
         (GET "/events" req (event-view db req))
         (GET "/device" [] {:status 501
                            :body "Configure proxy server to redirect to controller"})))
     (context "/subjects" []
       (GET "/" req (subjects-view db req))
       (GET "/active" [] (active-subjects-view db))
       (GET "/inactive" [] (inactive-subjects-view db))
       (context "/:subject" [subject]
         (GET "/" [] (subject-view db subject))
         (GET "/trials" req (trial-view db req))
         (context "/stats" []
           (GET "/" req (stats-view db req))
           (GET "/today" [] {:body (agg/activity-stats-today db subject)})
           (GET "/last-hour" [] {:body (agg/activity-stats-last-hour db subject)}))))
     (not-found nil))))

(defn front-page-data
  [db]
  (let [active-subjects (active-subjects-view db)]
    {:controllers (db/find-connected-controllers db)
     :inactive-subjects (inactive-subjects-view db)
     :active-subjects (map #(agg/join-activity db %) active-subjects)}))

(defn encode-for-ws
  [map]
  (json/encode (for [[k v] map] [k (html v)])))

(defn update-clients!
  "Sends new HTML over websockets to update their DOMs. HTML is keyed by id."
  [{{db :db} :database clients :ws-clients} data]
  #_(println "D: update-clients!" data)
  (when-let [clients (seq @clients)]
    (let [{:keys [topic addr name time trial subject]} data
          cdata (cond
                  (and (= topic :state-changed) (not= name "experiment"))
                  {:#time (views/server-time)
                   (str "#" addr) (views/controller {:addr addr
                                                     :last-event time})}
                  (and (= topic :trial-data) (not (nil? trial)))
                  (let [subj (assoc (agg/join-activity db (db/find-subject db subject))
                                    :last-trial time)]
                    {:#time (views/server-time)
                     (str "#" subject) (views/subject subj)})
                  :else
                  {:#console (views/console (front-page-data db))})]
      (doseq [client clients]
        (send! (key client) (encode-for-ws cdata))))))

(defn update-handler
  [req clients]
  (with-channel req chan
    (swap! clients assoc chan req)
    #_(println "D: http/ws connection from" (:remote-addr req))
    (on-close chan (fn [status]
                      (swap! clients dissoc chan)
                      #_(println "D:" (:remote-addr req) "disconnected")))))

(defn site-routes
  [{{db :db} :database ws-clients :ws-clients}]
  (routes
   (GET "/" [] (views/index (front-page-data db)))
   (GET "/ws" req (update-handler req ws-clients))
   (resources "/static")
   (not-found "No such page!")))


(def app
  (reify App
    (start! [_]
      (log "version:" version)
      (let [ctx (-> (init-context)
                    (db/connect!)
                    (host/start!))]
        (when (get-in ctx [:host :addr])
          (add-handler ctx update-subject! :state-changed :trial-data)
          (add-handler ctx update-clients! :state-changed :trial-data :connect :disconnect))
        {:context ctx
         :frodo/handler
         (routes
          (-> (api-routes ctx)
              (wrap-defaults api-defaults)
              (wrap-cors :access-control-allow-origin #".+"
                         :access-control-allow-methods [:get])
              (wrap-streaming-response)
              (wrap-json-response :pretty true))
          (site-routes ctx))}))
    (stop! [_ system]
      (host/stop! (:context system)))))
