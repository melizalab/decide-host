(ns user
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [clojure.repl :refer :all]
            [clojure.tools.namespace.repl :refer [set-refresh-dirs refresh refresh-all]]
            [clojure.stacktrace :refer [e]]))

(set-refresh-dirs "src")
