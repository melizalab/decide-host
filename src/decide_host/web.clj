(ns decide-host.web
  (:gen-class)
  (:require [clojure.core.match :refer [match]]
            [cheshire.core :as json]
            [frodo.web :refer [App]]
            [ring.util.response :refer [response content-type]]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [ring.middleware.format-response :refer [wrap-restful-response
                                                     wrap-json-response]]
            [ring.middleware.cors :refer [wrap-cors]]
            [compojure.core :refer [routes context GET]]
            [compojure.route :refer [resources not-found]]
            [org.httpkit.server :refer [with-channel on-close send!]]
            [hiccup.core :refer [html]]
            [decide-host.config :refer [init-context]]
            [decide-host.core :refer [merge-in]]
            [decide-host.host :as host]
            [decide-host.database :as db]
            [decide-host.aggregators :as agg]
            [decide-host.views :as views]
            [decide-host.handlers :refer [add-handler update-subject!]]))

(defn parse-comment-constraint
  "If :comment is 'true', removes any filter for comments; otherwise "
  [params]
  (case (:comment params)
    ("true" "True" true) (dissoc params :comment)
    nil (assoc params :comment nil)
    params))

(defn controller-list-view
  "Returns a list of all controllers in the database"
  [db params]
  (map (fn [c] (assoc (select-keys c [:addr :last-seen :last-event])
                     :connected (not (nil? (:zmq-id c)))))
       (db/find-controllers db params)))

(defn controller-view
  [db addr]
  (when-let [result (first (controller-list-view db {:addr addr}))]
    {:body result}))

(defn subjects-view
  [db params]
  #_(println "D: subjects-view" params)
  (db/find-subjects db params))
(defn active-subjects-view [db] (subjects-view db {:controller {"$ne" nil}}))
(defn inactive-subjects-view [db] (subjects-view db {:controller nil}))

(defn subject-view
  [db subject]
  (when-let [result (db/find-subject db subject)]
    {:body result}))

(defn event-view
  [db params]
  (let [params (db/parse-constraints params)]
    #_(println "D: event-view" params)
    (db/find-events db params)))

(defn trial-view
  [db params]
  (let [params (-> params
                   (parse-comment-constraint)
                   (db/parse-constraints))]
    #_(println "D: trial-view" params)
    (db/find-trials db params)))

(defn stats-view
  [db params]
  (let [params (db/parse-constraints params)]
    #_(println "D: stats-view" params)
    (agg/hourly-stats db params)))

(defn api-routes
  [{{db :db} :database}]
  (routes
   (context "/api" []
     (context "/controllers" [:as {params :params}]
       (GET "/" [] (controller-list-view db params))
       (context "/:addr" [addr :as {params :params}]
         (GET "/" [] (controller-view db addr))
         (GET "/events" [] (event-view db params))
         (GET "/device" [] {:status 501
                            :body "Configure proxy server to redirect to controller"})))
     (context "/subjects" [:as {params :params}]
       (GET "/" [] (subjects-view db params))
       (GET "/active" [] (active-subjects-view db))
       (GET "/inactive" [] (inactive-subjects-view db))
       (context "/:subject" [subject :as {params :params}]
         (GET "/" [] (subject-view db subject))
         (GET "/trials" [] (trial-view db params))
         (context "/stats" []
           (GET "/" [] (stats-view db params))
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
  [context data]
  #_(println "D: update-clients!" data)
  (let [{{db :db} :database clients :ws-clients} context
        {:keys [topic addr name time trial]} data
        cdata (cond
                (and (= topic :state-changed) (not= name "experiment"))
                {:#time (views/server-time)
                 (str "#" addr) (views/controller {:addr addr
                                                   :last-event time})}
                :else
                {:#console (views/console (front-page-data db))})]
    (doseq [client @clients]
        (send! (key client) (encode-for-ws cdata)))))

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
   (resources "/")
   (not-found "No such page!")))


(def app
  (reify App
    (start! [_]
      (let [ctx (host/start! (init-context))]
        (add-handler ctx update-subject! :state-changed :trial-data)
        (add-handler ctx update-clients! :state-changed :trial-data :connect :disconnect)
        {:context ctx
         :frodo/handler
         (routes
          (-> (api-routes ctx)
              (wrap-defaults api-defaults)
              (wrap-cors :access-control-allow-origin #".+"
                         :access-control-allow-methods [:get])
              (wrap-restful-response :formats [:json-kw :edn
                                               :transit-json :transit-msgpack])
              #_(wrap-json-response :pretty true))
          (site-routes ctx))}))
    (stop! [_ system]
      (host/stop! (:context system)))))
