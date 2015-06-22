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
            [cheshire.core :as json])
  (:import (com.fasterxml.jackson.core JsonParseException)))

(defn pub
  "Publishes an event to subscribers. Data should be a map"
  [chan topic data]
  (when chan (async/put! chan (assoc data :topic (keyword topic)))))

(defn decode-json
  "Decodes payload of a PUB message"
  [bytes]
  (json/parse-string (to-string bytes) true))

(defn clock-err
  "Returns difference between time (in ms since epoch) and host time"
  [time]
  (- time (tc/to-long (t/now))))

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
                   (log ctrl-addr ": connected")
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
        (when-let [subj (db/find-subject-by-addr db addr)]
          (error-msg context (str addr " disconnected unexpectedly!") (:user subj)))
        (log addr ": disconnected"))
      (pub events :disconnect {:addr addr}))
    (db/remove-controller! db sock-id)) nil)

(defn open-peering
  [context id addr data-str]
  #_(println "D: open-peering" id addr data-str)
  (try
    (let [clock-tol (get-in context [:host :clock-tolerance])
          data (decode-json data-str)
          clock-diff (clock-err (:time data))]
      (if (and (> clock-diff (- clock-tol)) (< clock-diff clock-tol))
        (case (connect! context id addr)
             :ok ["OHAI-OK"]
             :wtf ["WTF" (str addr " is already connected")])
        ["WTF" (str "clock is off by too much (" clock-diff " ms)")]))
    (catch JsonParseException e ["RTFM" "error parsing JSON"])
    (catch NullPointerException e ["RTFM" "missing time field in message"])))

(defn store-data
  "Stores PUB data in the database. Returns :ack on success, :dup for duplicate
  messages (which are ignored, :rtfm for unsupported data types, and nil for
  invalid/unparseable data"
  [context id data-type data-id data]
  #_(println "D: storing data" id data-type data-id data)
  (let [{{db :db} :database events :event-chan} context]
    (set-alive! context id)
    (if-let [addr (:addr (db/find-controller-by-socket db id))]
      (try
        (let [data (decode-json data)
              time (:time data)
              data (assoc data
                          :addr addr
                          :time (tc/from-long (long (/ time 1000)))
                          :usec (mod time 1000))]
          (pub events data-type data)
          (case (db/log-message! db data-type data-id data)
            :ack ["ACK" data-id]
            :dup ["DUP" data-id]
            ["RTFM" (str "unsupported data type " data-type)]))
        (catch JsonParseException e ["RTFM" "error parsing JSON"])
        (catch NullPointerException e ["RTFM" "missing time field in message"]))
      ["WHO?"])))

(defn process-message!
  "Processes decide-host messages from clients. Returns the reply message."
  [context id & data]
  (let [{{db :db} :database
         {protocol :protocol} :host} context
        [ps s1 s2 s3] (map to-string data)]
    (match [ps s1 s2 s3]
           ;; open-peering
           ["OHAI" protocol (addr :guard (complement nil?)) data-str]
           (open-peering context id addr data-str)
           ["OHAI" wrong-protocol _ _]
           ["RTFM" (str "wrong protocol or handshake")]

           ;; use-peering
           ["PUB" data-type data-id data-str]
           (store-data context id data-type data-id data-str)
           ["HUGZ" _ _ _]
           (do
             (set-alive! context id)
             ["HUGZ-OK"])
           ["HUGZ-OK" _ _ _] (set-alive! context id)

           ;; close-peering
           ["KTHXBAI" _ _ _] (disconnect! context id)

           ;; error message
           :else ["RTFM" (str "unrecognized or invalid command syntax for '" ps "'")])))

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
      (log "released socket")
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
    (log "listening to controllers on" addr)
    (merge-in context {:host {:in zmq-in
                              :out zmq-out}})))

(defn start! [context]
  (when (get-in context [:host :addr])
    (-> context
        (start-zmq-server)
        (start-heartbeat 2000)
        (start-message-handler))))

(defn stop! [context]
  (when-let [chan (get-in context [:host :in])]
    (async/close! chan))
  (when-let [chan (get-in context [:heartbeat :ctrl])]
    (async/put! chan :stop)))
