(defproject decide "2.0.0-SNAPSHOT"
  :description "operant control system"
  :url "http://meliza.org/starboard"
  :license {:name "BSD" :url "http://www.opensource.org/licenses/BSD-3-Clause"}

  :min-lein-version "2.0.0"

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]

                 [ring/ring-defaults "0.1.5"]
                 [compojure "1.3.1"]
                 [http-kit "2.1.18"]
                 [hiccup "1.0.5"]
                 [ring-middleware-format "0.4.0"]
                 [ring-cors "0.1.7"]

                 [jarohen/nomad "0.7.1"]

                 [com.novemberain/monger "2.0.0"]
                 [com.keminglabs/zmq-async "0.1.0"]
                 [com.draines/postal "1.11.3"]
                 [digest "1.4.4"]
                 [cheshire "5.4.0"]
                 [clj-time "0.8.0"]]

  :plugins [[jarohen/lein-frodo "0.4.1"]]
  :frodo/config-resource "config/nomad-config.edn"
  ;; :global-vars {*warn-on-reflection* true}

  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}
             :dev {:source-paths ["dev"]
                   :dependencies [[midje "1.5.1"]
                                  [javax.servlet/servlet-api "2.5"]
                                  [ring-mock "0.1.5"]]}})
