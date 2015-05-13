(ns decide-host.core
  (:gen-class)
  (:require [com.keminglabs.zmq-async.core :refer [register-socket!]]
            [clojure.core.async :as async :refer [>! <! >!! <!!]]
            [clojure.core.match :refer [match]]
            [cheshire.core :as json]))

(def config (json/parse-string (slurp "config/host-config.json")))

(defn to-string
  "safely converts a byte array or nil to a string; nil => ''"
  [x] (apply str (map char x)))

(defn decode-data
  [bytes]
  (json/parse-string (to-string bytes)))

(defn zmq-listener
  "Starts listening for messages on addr. Returns input and output channels"
  [addr]
  (let [in (async/chan (async/sliding-buffer 64))
        out (async/chan (async/sliding-buffer 64))]
    (register-socket! {:in in :out out :socket-type :router
                       :configurator (fn [socket] (.bind socket addr))})
    [in out]))

(defn zmq-handler
  [[in out]]
  (async/go-loop [[id & msg] (<! out)]
    (case (to-string (first msg))
      "OHAI"
      (let [client-name (to-string (second msg))]
        (println "OHAI" client-name)
        (>! in [id "OHAI-OK"]))
      "PUB"
      (let [data (decode-data (second msg))]
        (println "PUB" data)
        (>! in [id "ACK" "insert-hash-here"]))
      "HUGZ"
      (do
        (println "HUGZ from" (to-string id))
        (>! in [id "HUGZ-OK"]))
      "BYE"
      (println "BYE from" (to-string) id))
    (recur (<! out))))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]

  )

(comment
  (require '[clojure.pprint :refer [pprint]]
           '[clojure.stacktrace :refer [e]]
           '[clojure.tools.namespace.repl :refer [refresh refresh-all]])
  (clojure.tools.namespace.repl/refresh)

  )
