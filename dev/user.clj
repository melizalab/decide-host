(ns user
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [clojure.repl :refer :all]
            [clojure.tools.namespace.repl :refer [set-refresh-dirs refresh refresh-all]]
            [clojure.stacktrace :refer [e]]
            [monger.core :as mg]
            [monger.collection :as mc]
            [monger.query :as mq]
            [clj-time.core :as t]
            [decide-host.config :refer [config]]))

(set-refresh-dirs "src")
