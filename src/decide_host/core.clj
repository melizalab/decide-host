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
(def INIT-ALIVE (get config :heartbeat-max-ping 10))
(def version (-> "project.clj" slurp read-string (nth 2)))

;; channel and publication used to distribute non-core processing of events
(def ^:private events (async/chan 64))
(def ^:private events-pub (async/pub events :topic))

(defn to-string [x] (when-not (nil? x) (String. x)))
(defn bytes-to-hex [x] (when-not (nil? x) (apply str (map #(format "%02x" %) x))))
(defn hex-to-bytes [x] (.toByteArray (BigInteger. x 16)))

(defn decode-pub
  "Decodes payload of a PUB message, returning nil on errors"
  [bytes]
  (try
    (json/parse-string (to-string bytes) true)
    (catch Exception e nil)))

;; TODO can be decoupled with channel
(defn update-subject!
  [data]
  (let [{:keys [name subject procedure user addr time]} data]
    (when (= name "experiment")
      (if-not (nil? subject)
        (db/start-subject! subject {:procedure procedure :controller addr
                                    :user user :start-time time})
        (db/stop-subject! addr time)))))

(defn controller-alive!
  "Updates database with connection status of controller"
  [sock-id] (db/update-controller! sock-id {:alive INIT-ALIVE :last-seen (t/now)}) nil)

(defn connect!
  "Registers a controller as connected. :ok if successful, :wtf if the address is taken"
  [sock-id ctrl-addr]
  (let [{:keys [alive zmq-id]} (db/get-controller-by-addr ctrl-addr)]
    (match [(or alive 0) zmq-id]
           ;; another OHAI from existing client - noop
           [(_ :guard pos?) sock-id] :ok
           ;; socket does not match controller
           [(_ :guard pos?) wrong-id] :wtf
           ;; otherwise, add or update existing record
           :else (do
                   (println "I:" ctrl-addr "connected")
                   (db/add-controller! sock-id ctrl-addr)
                   (controller-alive! sock-id)
                   :ok))))

(defn disconnect!
  [sock-id & flags]
  (let [flags (set flags)
        err (:err flags)]
    (when-let [controller (db/get-controller-by-socket sock-id)]
      (println "I:" (:addr controller) "disconnected" (if err "unexpectedly" ""))
      ;; TODO notify user if error
      )
    (db/remove-controller! sock-id)) nil)

(defn check-connection
  [controller]
  (let [{:keys [addr alive last-seen zmq-id]} controller
        {:keys [heartbeat-ms]} config
        interval (t/in-millis (t/interval last-seen (t/now)))]
    #_(println "D:" addr "last seen" interval "ms ago")
    (cond
      (not (pos? alive)) (disconnect! zmq-id :err)
      (> interval heartbeat-ms) (do
                                  (db/dec-alive! zmq-id)
                                  zmq-id))))

(defn store-data!
  "Stores PUB data in the database. Returns :ack on success, :dup for duplicate
  messages (which are ignored, :rtfm for unsupported data types, and nil for
  invalid/unparseable data"
  [id data-type data-id data]
  #_(println "D: storing data" id data-type data-id data)
  (controller-alive! id)
  (if-let [addr (:addr (db/get-controller-by-socket id))]
    (if-let [data (decode-pub data)]
      (let [time (:time data)
            data (assoc data
                        :addr addr
                        :time (tc/from-long (long (/ time 1000)))
                        :usec (mod time 1000))]
        (update-subject! data)
        (db/log-message! data-type data-id data))
      :rtfm-fmt)
    :who?))

(defn process-message!
  "Processes decide-host messages from clients. Returns the reply message."
  [id & data]
  (let [[ps s1 s2 s3] (map to-string data)
        right-protocol protocol]
    #_(println "D: received" id ps s1 s2 s3)
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
           (case (store-data! id data-type data-id data-str)
             :ack ["ACK" data-id]
             :dup ["DUP" data-id]
             :rtfm-dtype ["RTFM" (str "unsupported data type " data-type)]
             :who?  ["WHO?"]
             :rtfm-fmt   ["RTFM" "error parsing data"])
           ["HUGZ" _ _ _]
           (do
             (controller-alive! id)
             ["HUGZ-OK"])
           ["HUGZ-OK" _ _ _] (controller-alive! id)

           ;; close-peering
           ["KTHXBAI" _ _ _] (disconnect! id)

           ;; error message
           :else ["RTFM" (str "unrecognized command '" ps "'")])))

(defn start-zmq-server
  "Starts a server that will bind a zeromq socket to addr. Received messages are
  passed to process-message!. A separate event loop sends heartbeats to
  connected clients. Closing the returned input channel will shut down the
  server."
  [addr]
  (let [zmq-in (async/chan (async/sliding-buffer 64))
        zmq-out (async/chan (async/sliding-buffer 64))]
    (register-socket! {:in zmq-in :out zmq-out :socket-type :router
                       :configurator (fn [socket] (.bind socket addr))})
    (println "I: bound decide-host to" addr)
    (let [running (atom true)]
      (async/go
        (while @running
          (<! (async/timeout (config :heartbeat-ms)))
          (dorun (for [zmq-id (map check-connection (db/get-controllers))]
                   (when zmq-id
                     (println "D: sending" zmq-id "HUGZ")
                     (async/put! zmq-in [(hex-to-bytes zmq-id) "HUGZ"]))))))
      ;; main handler runs in its own thread so that process continues when main
      ;; thread terminates in app
      (doto (Thread. (fn []
                       (loop [[id & data] (<!! zmq-out)]
                         (when-let [id (bytes-to-hex id)]
                           (when-let [result (apply process-message! id data)]
                             #_(println "D: sending" id result)
                             (>!! zmq-in (cons (hex-to-bytes id) result)))
                           (recur (<!! zmq-out))))
                       (println "I: released decide-host socket")
                       (reset! running false)))
        (.setName "decide-host message handler")
        (.start)))
    ;; return input channel of server; closing this will terminate the server
    zmq-in))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (println "I: this is decide-host, version" version)
  (db/connect! (:log-db config))
  (start-zmq-server (:addr-int config))
  ;; this doesn't seem to work
  #_(let [server ]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(async/close! server)))))

(comment
  (require '[clojure.pprint :refer [pprint]]
           '[clojure.stacktrace :refer [e]]
           '[clojure.tools.namespace.repl :refer [refresh refresh-all]])
  (clojure.tools.namespace.repl/refresh)
  )
