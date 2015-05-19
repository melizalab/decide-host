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
    (println "D:" zmq-id "last seen" interval "ms ago")
      (cond
       (> interval (* heartbeat-maxcount heartbeat-ms)) (connection-error! controller)
       (> interval heartbeat-ms) (async/put! zmq-in [zmq-id "HUGZ"]))))

(defn decode-pub
  "Decodes payload of a PUB message, returning nil on errors"
  [bytes]
  (try
    (json/parse-string (to-string bytes) true)
    (catch Exception e nil)))

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
        (case (db/log-message! data-type data-id data)
          :ack ["ACK" data-id]
          :dup ["DUP" data-id]
          :rtfm ["RTFM" (str "unsupported data type " data-type)])))))

;;; the zmq message handler
(defn process-message!
  [id & data]
  (let [[ps s1 s2 s3] (map to-string data)]
    (match [ps s1 s2 s3]
           ;; open-peering
           ["OHAI" protocol addr _]
           (if (connect! id addr)
             ["OHAI-OK"]
             ["WTF" (str addr " is already connected")])
           ["OHAI" wrong-protocol _ _]
           ["WTF" (str " server supports " protocol)]

           ;; use-peering
           ["PUB" data-type data-id data-str]
           (do
             (db/controller-alive! id)

             (store-data! id data-type data-id data-str))
           ["HUGZ" _ _ _]
           (do
             (db/controller-alive! id)
             ["HUGZ-OK"])
           ["HUGZ-OK" _ _ _] (db/controller-alive! id)

           ;; close-peering
           ["OKTHXBAI" _ _ _] (disconnect! id)

           ;; error message
           :else ["RTFM" "unrecognized command"])))


(defn start-zmq-server [addr]
  (register-socket! {:in zmq-in :out zmq-out :socket-type :router
                     :configurator (fn [socket] (.bind socket addr))})
  (println "I: bound decide-host to" addr)
  (let [running (atom true)]
    (async/go
      (loop [[id & data] (<! zmq-out)]
        (when-let [id (to-string id)]
          (when-let [result (apply process-message! id data)]
            (>! zmq-in (cons id result)))
          (recur (<! zmq-out))))
      (println "I: unbound decide-host socket")
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
