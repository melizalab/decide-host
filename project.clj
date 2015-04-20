(defproject decide "1.1.0"
  :description "operant control system"
  :url "http://meliza.org/starboard"

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-2173"]]

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
