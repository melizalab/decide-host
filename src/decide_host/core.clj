(ns decide-host.core
  (:gen-class)
  (:require [com.keminglabs.zmq-async.core :refer [register-socket!]]
            [clojure.core.async :as async :refer [>! <! >!! <!!]]
            [clojure.core.match :refer [match]]
            [clj-time.core :as t]
            [cheshire.core :as json]))

(def config (json/parse-string (slurp "config/host-config.json") true))

(def zmq-in (async/chan (async/sliding-buffer 64)))
(def zmq-out (async/chan (async/sliding-buffer 64)))

(defn to-string
  "safely converts a byte array or nil to a string; nil => ''"
  [x]
  (when-not (nil? x) (String. x)))

(defn decode-pub
  [bytes]
  (json/parse-string (to-string bytes)))

(def controllers (atom {}))

(defn its-alive!
  "Updates the liveness status for client id"
  [id]
  (swap! controllers assoc-in [id :last-seen] (t/now)))

(defn connect!
  "Registers a controller as connected. Returns true if successful, nil if the address is taken"
  [id ctrl-addr]
  (swap! controllers assoc id {:addr ctrl-addr :last-seen (t/now) :alive true})
  true)

(defn disconnect!
  "Unregisters a controller"
  [id]
  (swap! controllers dissoc id))

(defn check-connection
  [id]
  (let [last (get-in @controllers [id :last-seen])
        hb (config :heartbeat-ms)
        hb-max (* hb (config :heartbeat-liveness))
        interval (t/in-millis (t/interval last (t/now)))]
    (cond
     (> interval hb) (async/put! zmq-in [id "HUGZ"]))))

(defn bad-message
  "Handle unrecognized message types"
  [id mtype]
  ;; nil indicates the server is shutting down
 (when-not nil?
   (println "W: bad message:" id mtype)))

;;; the zmq message handling loop
(async/go-loop [[id & msg] (<! zmq-out)]
  (when-let [id (String. id)]
    (case (to-string (first msg))
      "OHAI" (let [ctrl-addr (to-string (second msg))]
               (println "D:" id "OHAI" ctrl-addr)
               (let [out-msg (if (connect! id ctrl-addr) "OHAI-OK" "WTF")]
                 (>! zmq-in [id out-msg])))
      "PUB" (let [data (decode-pub (second msg))]
              (println "PUB" data)
              (>! zmq-in [id "ACK" "insert-hash-here"]))
      "HUGZ" (do
               (its-alive! id)
               (>! zmq-in [id "HUGZ-OK"]))
      "HUGZ-OK" (its-alive! id)
      "BYE" (do
              (println "D:" id "BYE")
              (disconnect! id))
      (bad-message msg)))
  (recur (<! zmq-out)))

#_(async/go-loop []
  (<! (async/timeout (config :heartbeat-ms)))
  (dorun (map check-connection (keys @controllers))))

(defn start-zmq-server [addr]
  (register-socket! {:in zmq-in :out zmq-out :socket-type :router
                       :configurator (fn [socket] (.bind socket addr))}))

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
