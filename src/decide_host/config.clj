(ns decide-host.config
  (:require [clojure.java.io :as io]
            [nomad :refer [defconfig]]))

(defconfig config (io/resource "nomad-config.edn"))
