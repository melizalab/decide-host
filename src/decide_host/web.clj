(ns decide-host.web
  (:gen-class)
  (:require [frodo.web :refer [App]]
            [ring.util.response :refer [response content-type]]
            [ring.middleware.format :refer [wrap-restful-format]]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [compojure.core :refer [routes context GET]]
            [compojure.route :refer [resources]]
            [decide-host.config :as cfg]
            [decide-host.host :as host]
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
  [subj-id params]
  (str "trials for " subj-id ", params: " params))

(defn site-routes []
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
      (context "/:id" [id]
        (GET "/" [] (subject-view id))
        (GET "/trials" [ :as params] (trial-view id params))))
   (resources "/")))

(defn init-context []
  (select-keys (cfg/config) [:database :host :email]))

(def app
  (reify App
    (start! [_]
      (let [context (host/start! (init-context))]
        (add-handler context update-subject! :state-changed :trial-data)
        {:context context
         :frodo/handler (wrap-defaults (site-routes) api-defaults)}))
    (stop! [_ system]
      (host/stop! (:context system)))))
