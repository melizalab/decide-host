(ns user
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [clojure.repl :refer :all]
            [clojure.tools.namespace.repl :refer [set-refresh-dirs refresh refresh-all]]
            [clojure.stacktrace :refer [e]]
            [decide-host.config :refer [config]]))

(set-refresh-dirs "src")

(def context nil)

(defn init []
  (let [cfg (config)]
    (alter-var-root #'context (constantly [(get-in cfg [:database :uri])
                                           (get-in cfg [:zmq :endpoint])]))))

;; (defn start
;;   []
;;   (println "I: this is decide-host, version" version)
;;   (alter-var-root #'context host/start!))

;; (defn stop
;;   []
;;   (alter-var-root #'context host/stop!))
