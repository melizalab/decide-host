(ns decide-host.host
  "Functions for processing messages from controllers to host"
  (:require [decide-host.config :refer [config]]
            [decide-host.database :as db]
            [com.keminglabs.zmq-async.core :refer [register-socket!]]
            [clojure.core.async :as async :refer [>! <! >!! <!!]]
            [clojure.core.match :refer [match]]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]
            [cheshire.core :as json]))

(defn get-config [& ks] (get-in (config) (cons :zmq ks)))
(def version (-> "project.clj" slurp read-string (nth 2)))

(defn to-string [x] (when-not (nil? x) (String. x)))
(defn bytes-to-hex [x] (when-not (nil? x) (apply str (map #(format "%02x" %) x))))
(defn hex-to-bytes [x] (.toByteArray (BigInteger. x 16)))

;; channel and publication used to distribute non-core processing of events
;; note that events may get dropped on this channel

(defn pub
  "Publishes an event to subscribers. Data should be a map"
  [chan topic data]
  (when chan (async/put! chan (assoc data :topic (keyword topic)))))

(defn decode-pub
  "Decodes payload of a PUB message, returning nil on errors"
  [bytes]
  (try
    (json/parse-string (to-string bytes) true)
    (catch Exception e nil)))

(defn connect!
  "Registers a controller as connected. :ok if successful, :wtf if the address is taken"
  [context sock-id ctrl-addr]
  (let [{{db :db} :database} context
        {:keys [alive zmq-id]} (db/get-controller-by-addr db ctrl-addr)]
    (match [(or alive 0) zmq-id]
           ;; another OHAI from existing client - noop
           [(_ :guard pos?) sock-id] :ok
           ;; socket does not match controller
           [(_ :guard pos?) wrong-id] :wtf
           ;; otherwise, add or update existing record
           :else (do
                   (println "I:" ctrl-addr "connected")
                   (db/add-controller! db sock-id ctrl-addr)
                   (db/set-alive! db sock-id)
                   :ok))))

(defn disconnect!
  [context sock-id & flags]
  (let [{{db :db} :database} context
        flags (set flags)
        err (:err flags)]
    (when-let [controller (db/get-controller-by-socket db sock-id)]
      ;; TODO notify user if error
      (println "I:" (:addr controller) "disconnected" (if err "unexpectedly" "")))
    (db/remove-controller! db sock-id)) nil)

(defn set-alive! [db id] (db/set-alive! db id) nil)

(defn store-data!
  "Stores PUB data in the database. Returns :ack on success, :dup for duplicate
  messages (which are ignored, :rtfm for unsupported data types, and nil for
  invalid/unparseable data"
  [context id data-type data-id data]
  #_(println "D: storing data" id data-type data-id data)
  (let [{{db :db} :database event-c :event-chan} context]
    (set-alive! db id)
    (if-let [addr (:addr (db/get-controller-by-socket db id))]
      (if-let [data (decode-pub data)]
        (let [time (:time data)
              data (assoc data
                          :addr addr
                          :time (tc/from-long (long (/ time 1000)))
                          :usec (mod time 1000))]
          (pub event-c data-type data)
          (db/log-message! db data-type data-id data))
        :rtfm-fmt)
      :who?)))

(defn process-message!
  "Processes decide-host messages from clients. Returns the reply message."
  [context id & data]
  (let [{{db :db} :database} context
        [ps s1 s2 s3] (map to-string data)
        right-protocol (get-config :protocol)]
    #_(println "D: received" id ps s1 s2 s3)
    (match [ps s1 s2 s3]
           ;; open-peering
           ["OHAI" right-protocol (addr :guard (complement nil?)) _]
           (case (connect! context id addr)
             :ok ["OHAI-OK"]
             :wtf ["WTF" (str addr " is already connected")])
           ["OHAI" wrong-protocol _ _]
           ["RTFM" (str "wrong protocol or handshake")]

           ;; use-peering
           ["PUB" data-type data-id data-str]
           (case (store-data! context id data-type data-id data-str)
             :ack ["ACK" data-id]
             :dup ["DUP" data-id]
             :rtfm-dtype ["RTFM" (str "unsupported data type " data-type)]
             :who?  ["WHO?"]
             :rtfm-fmt   ["RTFM" "error parsing data"])
           ["HUGZ" _ _ _]
           (do
             (set-alive! db id)
             ["HUGZ-OK"])
           ["HUGZ-OK" _ _ _] (set-alive! db id)

           ;; close-peering
           ["KTHXBAI" _ _ _] (disconnect! context id)

           ;; error message
           :else ["RTFM" (str "unrecognized command '" ps "'")])))


(defn start-message-handler
  [context]
  (let [{{zin :in zout :out} :server} context
        events (async/chan (async/sliding-buffer 64))
        ctx (assoc context :event-chan events)]
    (async/thread
      (loop [[id & data] (<!! zout)]
        (when-let [id (bytes-to-hex id)]
          (when-let [result (apply process-message! ctx id data)]
            #_(println "D: sending" id result)
            (>!! zin (cons (hex-to-bytes id) result)))
          (recur (<!! zout))))
      (println "I: released decide-host socket")
      (async/close! events))
    {:event-chan events                 ; only used for testing
     :event-pub (async/pub events :topic)}))

(defn check-connection!
  [context controller]
  (let [{{db :db} :database {zin :in} :server} context
        {:keys [addr alive last-seen zmq-id]} controller
        heartbeat-ms (get-config :heartbeat-ms)
        interval (t/in-millis (t/interval last-seen (t/now)))]
    #_(println "D:" addr "last seen" interval "ms ago")
    (cond
      (not (pos? alive)) (disconnect! context zmq-id :err)
      (> interval heartbeat-ms)
      (do
        (db/dec-alive! db zmq-id)
        (async/put! zin [(hex-to-bytes zmq-id) "HUGZ"])))))

(defn start-heartbeat
  [context interval]
  (let [{{zin :in} :server {db :db} :database} context
        ctrl-chan (async/chan)]
    (async/go
      (loop []
        (let [[x _] (async/alts! [ctrl-chan (async/timeout interval)])]
          (when (not= x :stop)
            (dorun (map check-connection! (repeat context) (db/get-controllers db)))
            (recur))))
      #_(println "D: heartbeat handler stopping"))
    {:ctrl ctrl-chan}))

;;(add-handler h/update-subject! :state-changed :trial-data)

(defn start-zmq-server
  "Starts a server that will bind a zeromq socket to addr."
  [addr]
  (let [zmq-in (async/chan (async/sliding-buffer 64))
        zmq-out (async/chan (async/sliding-buffer 64))]
    (register-socket! {:in zmq-in :out zmq-out :socket-type :router
                       :configurator (fn [socket] (.bind socket addr))})
    (println "I: bound decide-host to" addr)
    {:addr addr
     :in zmq-in
     :out zmq-out}))

(defn start! [dburi addr]
  (let [context {:database (db/connect! dburi)
                 :server (start-zmq-server addr)}]
    (assoc context
           :heartbeat (start-heartbeat context 2000)
           :msg-handler (start-message-handler context))))

(defn stop! [context]
  (async/close! (get-in context [:server :in]))
  (async/put! (get-in context [:heartbeat :ctrl]) :stop))


#_(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (println "I: this is decide-host, version" version)
  (db/connect! (:log-db config))
  (start-zmq-server (:addr-int config))
  ;; this doesn't seem to work
  #_(let [server ]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(async/close! server)))))
