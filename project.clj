(defproject decide "1.1.0"
  :description "operant control system"
  :url "http://meliza.org/starboard"

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-2173"]
                 [org.clojure/core.async "0.1.338.0-5c5012-alpha"]
                 [org.clojure/data.json "0.2.5"]
                 [ring/ring-core "1.3.1"]
                 [http-kit "2.1.18"]
                 [shodan "0.4.1"]]

  :plugins [[lein-cljsbuild "1.0.2"]]

  :source-paths ["src"]

  :cljsbuild {:builds
              [{:source-paths ["src/host" "src/decide"]
                :compiler
                {:output-to "scripts/decide-host.js"
                 :optimizations :simple
                 :pretty-print true
                 :target :nodejs}}
               ]})
