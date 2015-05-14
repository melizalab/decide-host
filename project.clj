(defproject decide "2.0.0-SNAPSHOT"
  :description "operant control system"
  :url "http://meliza.org/starboard"
  :license {:name "BSD" :url "http://www.opensource.org/licenses/BSD-3-Clause"}

  :min-lein-version "2.0.0"

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-2173"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/tools.logging "0.3.1"]

                 [com.novemberain/monger "2.0.0"]
                 [com.keminglabs/zmq-async "0.1.0"]
                 [cheshire "5.4.0"]
                 [clj-time "0.8.0"]]

  :main ^:skip-aot decide-host.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}}
  )
