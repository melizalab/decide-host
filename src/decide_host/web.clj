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
  "If :comments is true, removes any filter for comments; otherwise "
  [params]
  (case (:comment params)
    "true" (dissoc params :comment)
    nil (assoc params :comment nil)
    params))

(defn controller-view
  [db params]
  (map #(select-keys % [:addr :last-seen]) (db/find-controllers db params)))

(defn event-view
  [db params]
  (str "events for " (:addr params)))

(defn subject-view
  [subj-id]
  (str "subject data for " subj-id))

(defn trial-view
  [db params]
  (let [params (-> params
                   (parse-comment-constraint)
                   (db/parse-time-constraint :before)
                   (db/parse-time-constraint :after))]
    #_(println "D: trial-view" params)
    (db/find-trials db params)))

(defn stats-view
  [db params]
  (let [params (-> params
                   (db/parse-time-constraint :before)
                   (db/parse-time-constraint :after))]
    (println "D: stats-view" params)
    ))

(defn site-routes [ctx]
  (let [{{db :db} :database} ctx]
    (routes
     (GET "/" [] "hello world")
     (context "/controllers" []
       (GET "/" [] (controller-view db {}))
       (context "/:addr" [addr]
         (GET "/" [] (controller-view db {:addr addr}))
         (GET "/events" [:as {params :params}] (event-view db params))))
     (context "/subjects" []
       (GET "/" [] "all subjects")
       (GET "/active" [] "active subjects")
       (context "/:subject" [subject]
         (GET "/" [] (subject-view subject))
         (GET "/trials" [:as {params :params}] (trial-view db params))))
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
