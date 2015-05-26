(ns decide-host.web
  (:gen-class)
  (:require [frodo.web :refer [App]]
            [ring.util.response :refer [response content-type]]
            [ring.middleware.format :refer [wrap-restful-format]]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [compojure.core :refer [routes context GET]]
            [compojure.route :refer [resources]]
            [decide-host.config :refer [config]]
            [decide-host.host :as host]
            [decide-host.handlers :refer [add-handler update-subject!]]))

(defn site-routes []
  (routes
    (GET "/" [] "hello world")
    (resources "/")))

(def app
  (reify App
    (start! [_]
      (let [{{db-uri :uri} :database {zmq-addr :endpoint} :host} (config)
            context (host/start! db-uri zmq-addr)]
        (add-handler context update-subject! :state-changed :trial-data)
        {:context context
         :frodo/handler (wrap-defaults (site-routes) api-defaults)}))
    (stop! [_ system]
      (host/stop! (:context system)))))
