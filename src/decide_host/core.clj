(ns decide-host.core
  (:gen-class)
  (:require [decide-host.database :as db]
            [com.keminglabs.zmq-async.core :refer [register-socket!]]
            [clojure.core.async :as async :refer [>! <! >!! <!!]]
            [clojure.core.match :refer [match]]
            [clj-time.core :as t]
            [cheshire.core :as json]))

(def config (json/parse-string (slurp "config/host-config.json") true))

(def zmq-in (async/chan (async/sliding-buffer 64)))
(def zmq-out (async/chan (async/sliding-buffer 64)))

(defn to-string [x] (when-not (nil? x) (String. x)))

(defn decode-pub [bytes] (json/parse-string (to-string bytes)))

(defn connect!
  "Registers a controller as connected. Returns true if successful, nil if the address is taken"
  [sock-id ctrl-addr]
  (let [controller (db/get-controller-by-addr ctrl-addr)]
    (when (or (nil? controller) (not (:alive controller)))
      (println "I:" ctrl-addr "connected")
      (db/add-controller! sock-id ctrl-addr))))

(defn disconnect!
  [sock-id]
  (when-let [controller (db/get-controller-by-socket sock-id)]
    (println "I:" (:_id controller) "disconnected"))
  (db/remove-controller! sock-id))

(defn connection-error!
  [controller]
  (println "E: controller" (:_id controller) "disconnected unexpectedly")
  (db/controller-alive! (:zmq-id controller) false))

(defn check-connection
  [controller]
  (let [{:keys [last-seen zmq-id]} controller
        {:keys [heartbeat-ms heartbeat-maxcount]} config
        interval (t/in-millis (t/interval last-seen (t/now)))]
      (cond
       (> interval (* heartbeat-maxcount heartbeat-ms)) (connection-error! controller)
       (> interval heartbeat-ms) (async/put! zmq-in [zmq-id "HUGZ"]))))

(defn bad-message [msg] (println "E: bad message:" msg))

;;; the zmq message handling loop
(async/go-loop [[id & msg] (<! zmq-out)]
  (when-let [id (String. id)]
    (case (to-string (first msg))
      "OHAI" (let [ctrl-addr (to-string (second msg))]
               (let [out-msg (if (connect! id ctrl-addr) "OHAI-OK" "WTF")]
                 (>! zmq-in [id out-msg])))
      "PUB" (let [data (decode-pub (second msg))]
              (println "PUB" data)
              (db/controller-alive! id)
              (>! zmq-in [id "ACK" "insert-hash-here"]))
      "HUGZ" (do
               (db/controller-alive! id)
               (>! zmq-in [id "HUGZ-OK"]))
      "HUGZ-OK" (db/controller-alive! id)
      "BYE" (disconnect! id)
      (bad-message msg)))
  (recur (<! zmq-out)))


(defn start-zmq-server [addr]
  (register-socket! {:in zmq-in :out zmq-out :socket-type :router
                     :configurator (fn [socket] (.bind socket addr))})
  (async/go-loop []
    (<! (async/timeout (config :heartbeat-ms)))
    (dorun (map check-connection (db/get-living-controllers)))
    (recur)))


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
