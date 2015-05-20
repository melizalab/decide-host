(ns decide-host.core
  (:gen-class)
  (:require [decide-host.database :as db]
            [com.keminglabs.zmq-async.core :refer [register-socket!]]
            [clojure.core.async :as async :refer [>! <! >!! <!!]]
            [clojure.core.match :refer [match]]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]
            [cheshire.core :as json]))

(def protocol "decide-host@1")
(def config (json/parse-string (slurp "config/host-config.json") true))
(def zmq-in (async/chan (async/sliding-buffer 64)))
(def zmq-out (async/chan (async/sliding-buffer 64)))

(defn to-string [x] (when-not (nil? x) (String. x)))
(defn bytes-to-hex [x] (when-not (nil? x) (apply str (map #(format "%02x" %) x))))
(defn hex-to-bytes [x] (.toByteArray (BigInteger. x 16)))
(defn bad-message [msg] (println "E: bad message:" msg))

(defn decode-pub
  "Decodes payload of a PUB message, returning nil on errors"
  [bytes]
  (try
    (json/parse-string (to-string bytes) true)
    (catch Exception e nil)))

(defn connect!
  "Registers a controller as connected. :ok if successful, :wtf if the address is taken"
  [sock-id ctrl-addr]
  (let [controller (db/get-controller-by-addr ctrl-addr)]
    (if (or (nil? controller) (not (:alive controller)))
      (do
        (println "I:" ctrl-addr "connected")
        (db/remove-controller! sock-id)
        (db/add-controller! sock-id ctrl-addr)
        :ok)
      :wtf)))

(defn disconnect!
  [sock-id]
  (when-let [controller (db/get-controller-by-socket sock-id)]
    (println "I:" (:_id controller) "disconnected"))
  (db/remove-controller! sock-id))

(defn connection-error!
  [controller]
  (println "W:" (:_id controller) "disconnected unexpectedly")
  ;; TODO notify user
  (db/controller-alive! (:zmq-id controller) false))

(defn check-connection
  [controller]
  (let [{:keys [_id last-seen zmq-id]} controller
        {:keys [heartbeat-ms heartbeat-maxcount]} config
        interval (t/in-millis (t/interval last-seen (t/now)))]
    (println "D:" _id "last seen" interval "ms ago")
      (cond
       (> interval (* heartbeat-maxcount heartbeat-ms)) (connection-error! controller)
       (> interval heartbeat-ms) (async/put! zmq-in [zmq-id "HUGZ"]))))

(defn update-subject!
  [data]
  (let [{:keys [name subject procedure user addr time]} data]
    (when (= name "experiment")
      (if-not (nil? subject)
        (db/start-subject! subject {:procedure procedure :controller addr
                                    :user user :start-time time})
        (db/stop-subject! addr time)))))

(defn store-data!
  [id data-type data-id data]
  (println "D:" data-type data)
  (when-let [addr (:_id (db/get-controller-by-socket id))]
    (when-let [data (decode-pub data)]
      (let [time (:time data)
            data (assoc data
                        :addr addr
                        :time (tc/from-long (long (/ time 1000)))
                        :usec (mod time 1000))]
        (update-subject! data)
        (db/log-message! data-type data-id data)))))

;;; the zmq message handler
(defn process-message!
  [id & data]
  (let [[ps s1 s2 s3] (map to-string data)
        right-protocol protocol]
    (println "D: received" id ps s1 s2 s3)
    (match [ps s1 s2 s3]
           ;; open-peering
           ["OHAI" right-protocol (addr :guard (complement nil?)) _]
           (case (connect! id addr)
             :ok ["OHAI-OK"]
             :wtf ["WTF" (str addr " is already connected")])
           ["OHAI" wrong-protocol _ _]
           ["RTFM" (str "wrong protocol or handshake")]

           ;; use-peering
           ["PUB" data-type data-id data-str]
           (do
             (db/controller-alive! id)
             (case (store-data! id data-type data-id data-str)
               :ack ["ACK" data-id]
               :dup ["DUP" data-id]
               :rtfm ["RTFM" (str "unsupported data type " data-type)]
               ["RTFM" "data sent before handshake"]))
           ["HUGZ" _ _ _]
           (do
             (db/controller-alive! id)
             ["HUGZ-OK"])
           ["HUGZ-OK" _ _ _] (db/controller-alive! id)

           ;; close-peering
           ["KTHXBAI" _ _ _] (disconnect! id)

           ;; error message
           :else ["RTFM" "unrecognized command"])))


(defn start-zmq-server [addr]
  (register-socket! {:in zmq-in :out zmq-out :socket-type :router
                     :configurator (fn [socket] (.bind socket addr))})
  (println "I: bound decide-host to" addr)
  (let [running (atom true)]
    (async/go
      (loop [[id & data] (<! zmq-out)]
        (when-let [id (bytes-to-hex id)]
          (when-let [result (apply process-message! id data)]
            (println "D: sending" id result)
            (>! zmq-in (cons (hex-to-bytes id) result)))
          (recur (<! zmq-out))))
      (println "I: released decide-host socket")
      (reset! running false))
    (async/go
      (while @running
        (<! (async/timeout (config :heartbeat-ms)))
        (dorun (map check-connection (db/get-living-controllers)))))))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (db/connect! (:log-db config))
  (start-zmq-server (:addr-int config)))

(comment
  (require '[clojure.pprint :refer [pprint]]
           '[clojure.stacktrace :refer [e]]
           '[clojure.tools.namespace.repl :refer [refresh refresh-all]])
  (clojure.tools.namespace.repl/refresh)
  )
