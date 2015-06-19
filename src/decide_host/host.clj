(ns decide-host.host
  "Functions for processing messages from controllers to host"
  (:require [decide-host.core :refer :all]
            [decide-host.database :as db]
            [decide-host.notifications :refer [error-msg]]
            [com.keminglabs.zmq-async.core :refer [register-socket!]]
            [clojure.core.async :as async :refer [>! <! >!! <!!]]
            [clojure.core.match :refer [match]]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]
            [cheshire.core :as json]))

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

(defn set-alive! [context id]
  (let [{{db :db} :database {val :heartbeat-init-alive} :host} context]
    (db/set-alive! db id val)) nil)

(defn connect!
  "Registers a controller as connected. :ok if successful, :wtf if the address is taken"
  [context sock-id ctrl-addr]
  (let [{{db :db} :database events :event-chan} context
        {:keys [alive zmq-id]} (db/find-controller-by-addr db ctrl-addr)]
    (match [(or alive 0) zmq-id]
           ;; another OHAI from existing client - noop
           [(_ :guard pos?) sock-id] :ok
           ;; socket does not match controller
           [(_ :guard pos?) (wrong-id :guard (complement nil?))] :wtf
           ;; otherwise, add or update existing record
           :else (do
                   (println "I:" ctrl-addr "connected")
                   (pub events :connect {:addr ctrl-addr})
                   (db/remove-controller! db sock-id)
                   (db/add-controller! db sock-id ctrl-addr)
                   (set-alive! context sock-id)
                   :ok))))

(defn disconnect!
  "Removes the controller associated with sock-id from the database. Options:

  :err - if not nil, notifes the admins and any users associated with the subject"
  [context sock-id & {err :err}]
  (let [{{db :db} :database events :event-chan} context
        {addr :addr} (db/find-controller-by-socket db sock-id)]
    (when addr
      (if err
        (let [subj (db/find-subject-by-addr db addr)]
          (error-msg context (str addr " disconnected unexpectedly") (:user subj)))
        (println "I:" addr "disconnected"))
      (pub events :disconnect {:addr addr}))
    (db/remove-controller! db sock-id)) nil)

(defn store-data!
  "Stores PUB data in the database. Returns :ack on success, :dup for duplicate
  messages (which are ignored, :rtfm for unsupported data types, and nil for
  invalid/unparseable data"
  [context id data-type data-id data]
  #_(println "D: storing data" id data-type data-id data)
  (let [{{db :db} :database events :event-chan} context]
    (set-alive! context id)
    (if-let [addr (:addr (db/find-controller-by-socket db id))]
      (if-let [data (decode-pub data)]
        (let [time (:time data)
              data (assoc data
                          :addr addr
                          :time (tc/from-long (long (/ time 1000)))
                          :usec (mod time 1000))]
          (pub events data-type data)
          (db/log-message! db data-type data-id data))
        :rtfm-fmt)
      :who?)))

(defn process-message!
  "Processes decide-host messages from clients. Returns the reply message."
  [context id & data]
  (let [{{db :db} :database {protocol :protocol} :host} context
        [ps s1 s2 s3] (map to-string data)]
    #_(println "D: received" id ps s1 s2 s3)
    (match [ps s1 s2 s3]
           ;; open-peering
           ["OHAI" protocol (addr :guard (complement nil?)) _]
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
             (set-alive! context id)
             ["HUGZ-OK"])
           ["HUGZ-OK" _ _ _] (set-alive! context id)

           ;; close-peering
           ["KTHXBAI" _ _ _] (disconnect! context id)

           ;; error message
           :else ["RTFM" (str "unrecognized command '" ps "'")])))

(defn check-connection!
  [context controller]
  (let [{{db :db} :database {zin :in heartbeat :heartbeat-ms} :host} context
        {:keys [addr alive last-seen zmq-id]} controller
        interval (t/in-millis (t/interval last-seen (t/now)))]
    #_(println "D:" addr "last seen" interval "ms ago")
    (cond
      (not (pos? alive))
      (do
        #_(println "D: lost heartbeat for " addr)
        (disconnect! context zmq-id :err true))
      (> interval heartbeat)
      (do
        (db/dec-alive! db zmq-id)
        (async/put! zin [(hex-to-bytes zmq-id) "HUGZ"])))))

(defn- start-message-handler
  [context]
  (let [{{zin :in zout :out} :host} context
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
    (assoc-in context [:msg-handler :event-pub] (async/pub events :topic))))

(defn- start-heartbeat
  [context interval]
  (let [{{zin :in} :host {db :db} :database} context
        ctrl-chan (async/chan)]
    (async/go
      (loop []
        (let [[x _] (async/alts! [ctrl-chan (async/timeout interval)])]
          (when (not= x :stop)
            (dorun (map #(check-connection! context %) (db/find-connected-controllers db)))
            (recur))))
      #_(println "D: heartbeat handler stopping"))
    (assoc-in context [:heartbeat :ctrl] ctrl-chan)))

(defn- start-zmq-server
  "Starts a server that will bind a zeromq socket to addr."
  [context]
  (let [{{addr :addr} :host} context
        zmq-in (async/chan (async/sliding-buffer 64))
        zmq-out (async/chan (async/sliding-buffer 64))]
    (register-socket! {:in zmq-in :out zmq-out :socket-type :router
                       :configurator (fn [socket] (.bind socket addr))})
    (println "I: bound decide-host to" addr)
    (merge-in context {:host {:in zmq-in
                              :out zmq-out}})))

(defn start! [context]
  (when (get-in context [:host :addr])
    (-> context
        (start-zmq-server)
        (start-heartbeat 2000)
        (start-message-handler))))

(defn stop! [context]
  (async/close! (get-in context [:host :in]))
  (async/put! (get-in context [:heartbeat :ctrl]) :stop))
