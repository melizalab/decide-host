(ns decide-host.web
  (:gen-class)
  (:require [org.httpkit.server :refer [run-server]]
            [ring.util.response :refer [response content-type]]
            [ring.middleware.format :refer [wrap-restful-format]]
            [ring.middleware.reload :as reload]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [compojure.core :refer [routes context GET]]
            [compojure.route :refer [resources]]))

(defn site-routes []
  (routes
    (GET "/" [] "hello world")
    (resources "/")))

(defn -main [& args]
  (run-server (wrap-defaults (site-routes) api-defaults) {:port 8080}))

;; (def app
;;   (reify App
;;     (start! [_]
;;       {:frodo/handler (-> (routes
;;                             (site-routes))
;;                           api)})
;;     (stop! [_ system])))
