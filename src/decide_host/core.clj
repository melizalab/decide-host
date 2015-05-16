(ns decide-host.core
  (:gen-class)
  (:require [decide-host.database :as db]
            [com.keminglabs.zmq-async.core :refer [register-socket!]]
            [clojure.core.async :as async :refer [>! <! >!! <!!]]
            [clojure.core.match :refer [match]]
            [clj-time.core :as t]
            [digest]
            [cheshire.core :as json]))

(def config (json/parse-string (slurp "config/host-config.json") true))

(def zmq-in (async/chan (async/sliding-buffer 64)))
(def zmq-out (async/chan (async/sliding-buffer 64)))

(defn to-string [x] (when-not (nil? x) (String. x)))
(defn bad-message [msg] (println "E: bad message:" msg))

(defn connect!
  "Registers a controller as connected. Returns true if successful, nil if the address is taken"
  [sock-id ctrl-addr]
  (let [controller (db/get-controller-by-addr ctrl-addr)]
    (when 1 ;(or (nil? controller) (not (:alive controller)))
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
  ;; TODO notify user
  (db/controller-alive! (:zmq-id controller) false))

(defn check-connection
  [controller]
  (let [{:keys [last-seen zmq-id]} controller
        {:keys [heartbeat-ms heartbeat-maxcount]} config
        interval (t/in-millis (t/interval last-seen (t/now)))]
      (cond
       (> interval (* heartbeat-maxcount heartbeat-ms)) (connection-error! controller)
       (> interval heartbeat-ms) (async/put! zmq-in [zmq-id "HUGZ"]))))

(defn decode-pub
  "Decodes payload of a PUB message, returning nil on errors"
  [bytes]
  (try
    (json/parse-string (to-string bytes) true)
    (catch Exception e nil)))

(defn store-event!
  [id data]
  (println "D:" id "state-changed:" data)
  true)

(defn store-trial!
  [id data]
  (println "D:" id "trial-data:" data)
  true)

(defn store-data!
  [id data-type data]
  (when-let [address (:_id (db/get-controller-by-socket id))]
    (when-let [payload (decode-pub data)]
      (case data-type
        "state-changed" (store-event! id payload)
        "trial-data" (store-trial! id payload)
        (bad-message data-type)))))

;;; the zmq message handling loop
(async/go-loop [[id mtype & data] (<! zmq-out)]
  (when-let [id (to-string id)]
    (case (to-string mtype)
      "OHAI" (when-let [ctrl-addr (to-string (first data))]
               (let [out-msg (if (connect! id ctrl-addr) "OHAI-OK" "WTF")]
                 (>! zmq-in [id out-msg])))
      "PUB" (let [[data-type payload] (map to-string data)]
              (db/controller-alive! id)
              (when-not (nil? (store-data! id data-type payload))
                (async/put! zmq-in [id "ACK" (digest/md5 payload)])))
      "HUGZ" (do
               (db/controller-alive! id)
               (>! zmq-in [id "HUGZ-OK"]))
      "HUGZ-OK" (db/controller-alive! id)
      "BYE" (disconnect! id)
      (bad-message mtype)))
  (recur (<! zmq-out)))


(defn start-zmq-server [addr]
  (register-socket! {:in zmq-in :out zmq-out :socket-type :router
                     :configurator (fn [socket] (.bind socket addr))})
  #_(async/go-loop []
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
