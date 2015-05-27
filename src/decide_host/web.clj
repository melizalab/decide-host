(ns decide-host.web
  (:gen-class)
  (:require [frodo.web :refer [App]]
            [ring.util.response :refer [response content-type]]
            [ring.middleware.format :refer [wrap-restful-format]]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [compojure.core :refer [routes context GET]]
            [compojure.route :refer [resources]]
            [decide-host.config :refer [init-context]]
            [decide-host.core :refer [merge-in]]
            [decide-host.host :as host]
            [decide-host.database :as db]
            [decide-host.aggregators :as agg]
            [decide-host.handlers :refer [add-handler update-subject!]]))

(defn controller-view
  [addr]
  (str "controller data for " addr))

(defn event-view
  [addr]
  (str "events for " addr))

(defn subject-view
  [subj-id]
  (str "subjedt data for " subj-id))

(defn trial-view
  [db params]
  (let [params (-> params
                     (db/parse-time-constraint :before)
                     (db/parse-time-constraint :after))]
    #_(println "D: trial-view" params)
    (db/find-trials db params)))

(defn site-routes [ctx]
  (let [{{db :db} :database} ctx]
    (routes
     (GET "/" [] "hello world")
     (context "/controllers" []
       (GET "/" [] "all controllers")
       (GET "/active" [] "active controllers")
       (context "/:addr" [addr]
         (GET "/" [] (controller-view addr))))
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
                            (wrap-restful-format :formats [:json-kw :edn]))}))
    (stop! [_ system]
      (host/stop! (:context system)))))
