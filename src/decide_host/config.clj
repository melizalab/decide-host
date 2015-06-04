(ns decide-host.config
  (:require [clojure.java.io :as io]
            [nomad :refer [defconfig]]))

(defconfig config (io/resource "config/nomad-config.edn"))

(defn init-context []
  (assoc (select-keys (config) [:database :host :email])
         :ws-clients (atom {})))
