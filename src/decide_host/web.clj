(ns decide-host.web
  (:gen-class)
  (:require [frodo.web :refer [App]]
            [ring.util.response :refer [response content-type]]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [ring.middleware.format-response :refer [wrap-restful-response wrap-json-response]]
            [compojure.core :refer [routes context GET]]
            [compojure.route :refer [resources]]
            [decide-host.config :refer [init-context]]
            [decide-host.core :refer [merge-in]]
            [decide-host.host :as host]
            [decide-host.database :as db]
            [decide-host.aggregators :as agg]
            [decide-host.handlers :refer [add-handler update-subject!]]))

(defn parse-comment-constraint
  "If :comment is 'true', removes any filter for comments; otherwise "
  [params]
  (case (:comment params)
    "true" (dissoc params :comment)
    nil (assoc params :comment nil)
    params))

(defn controller-list-view
  "Returns a list of all controllers in the database"
  [db params]
  (map #(select-keys % [:addr :last-seen]) (db/find-controllers db params)))

(defn controller-view
  [db addr]
  {:body (first (controller-list-view db {:addr addr}))})

(defn subject-list-view
  [db params]
  (println "D: subject-list-view" params)
  (map (partial agg/join-all db) (db/find-subjects db params)))

(defn subject-view
  [db subject]
  {:body (agg/join-all db (db/find-subject db subject))})

(defn event-view
  [db params]
  (let [params (-> params
                   (db/parse-time-constraint :before)
                   (db/parse-time-constraint :after))]
    (println "D: event-view" params)
    (db/find-events db params)))

(defn trial-view
  [db params]
  (let [params (-> params
                   (parse-comment-constraint)
                   (db/parse-time-constraint :before)
                   (db/parse-time-constraint :after))]
    (println "D: trial-view" params)
    (db/find-trials db params)))

(defn stats-view
  [db params]
  (let [params (-> params
                   (db/parse-time-constraint :before)
                   (db/parse-time-constraint :after))]
    (println "D: stats-view" params)
    (agg/hourly-stats db params)))

(defn site-routes [ctx]
  (let [{{db :db} :database} ctx]
    (routes
     (GET "/" [] "Hello world")
     (context "/controllers" [:as {params :params}]
       (GET "/" [] (controller-list-view db params))
       (context "/:addr" [addr]
         (GET "/" [] (controller-view db addr))
         (GET "/events" [] (event-view db params))))
     (context "/subjects" [:as {params :params}]
       (GET "/" [] (subject-list-view db params))
       (GET "/active" [] (subject-list-view db (merge params {:controller {"$ne" nil}})))
       (context "/:subject" [subject ]
         (GET "/" [] (subject-view db subject))
         (GET "/trials" [] (trial-view db params))
         (GET "/stats" [] (stats-view db params))))
     (resources "/"))))

(def app
  (reify App
    (start! [_]
      (let [ctx (host/start! (init-context))]
        (add-handler ctx update-subject! :state-changed :trial-data)
        {:context ctx
         :frodo/handler (-> (site-routes ctx)
                            (wrap-defaults api-defaults)
                            #_(wrap-restful-response :formats [:json-kw :edn
                                                             :transit-json :transit-msgpack])
                            (wrap-json-response :pretty true))}))
    (stop! [_ system]
      (host/stop! (:context system)))))
