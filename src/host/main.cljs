(ns decide.host
  "Host server; brokers messages from controllers, logs data, and monitors health"
  (:use [decide.core :only [version config mail console]])
  (:require [decide.json :as json]
            [decide.mongo :as mongo]
            [cljs.nodejs :as node]
            [clojure.string :as str]))

(def Date (js* "Date"))
(def JSON (js* "JSON"))
(def __dirname (js* "__dirname"))
(def http (js/require "http"))
(def express (js/require "express"))
(def sock-io (js/require "socket.io"))

;; external http app
(def app (express))
;; sockets
(def io-internal (atom nil))
(def io-external (atom nil))
;; our connected clients
(def controllers (atom {}))
;; database collections for logging
(def events (atom nil))
(def trials (atom nil))
(def subjs (atom nil))

(defn- list-controllers [] (or (keys @controllers) []))

(defn- flatten-record
  "Returns (:data js) plus any of :keys present in js"
  [js & keys]
  (let [js (js->clj js :keywordize-keys true)]
    (merge (select-keys js keys) (:data js))))

(defn- addr-parts
  "Returns the controller and component from a message"
  [msg]
  (str/split (:addr msg) #"\."))

(defn- convert-times
  "Returns a copy of msg with time field converted to javascript Date type"
  [msg]
  (assoc msg :time (Date. (:time msg))))

;;; error handling: The host broker monitors for the following serious errors:
;;; unexpected disconnection by controllers and unexpected termination of
;;; experiment processes. Other, less urgent errors can be detected by analyzing
;;; the log data at intervals.
(defn- error-email
  "Sends an error email to user and to configured admins"
  [user & args]
  (let [msg (apply str args)
        to (str/join ", " (keep identity (conj (:admins config) user)))]
    (.error console msg "- sending email to" to)
    (mail "decide-host" to "error!" msg)))

(defn- log-callback [err msg]
  (when err (.error console "unable to write record to database" err)))

(defn- log-event!
  "Logs msg to the event log and (if connected) the event database"
  [msg]
  (let [[controller component] (addr-parts msg)
        logfile (str "events_" controller ".jsonl")]
    (json/write-record! logfile msg)
    (when @events (mongo/save! @events (convert-times msg) log-callback))))

(defn- log-trial!
  "Logs msg to the trial log and (if connected) the trial database"
  [msg]
  (let [logfile (str (:subject msg) "_" (:program msg) ".jsonl")]
    (json/write-record! logfile msg)
    (when @trials (mongo/save! @trials (convert-times msg) log-callback))))

(defn- update-subject!
  "Updates subject record on experiment start and stop"
  [msg]
  (when @subjs
    (let [subject (:subject msg)
          comment (:comment msg)
          data {:program (:program msg)
                :controller (first (addr-parts msg))
                :running true}
          log-it #(.info console "%s: %s %s on %s"
                         subject comment (:program data) (:controller data))
          do-it #(mongo/update! @subjs {:_id subject} % log-callback)]
      (case comment
        "starting" (do
                     (log-it)
                     (do-it (assoc data :_id subject :user (-> msg :params :user))))
        "stopping" (do
                     (log-it)
                     (do-it (assoc data :running false :user nil)))
        ;; for other messages, just set running to true
        (do-it {"$set" {:running true}})))))

(defn- check-event
  "Checks event data for various error conditions"
  [msg]
  ;; currently the only error checked here is for unexpected termination of an
  ;; experiment process. This can be detected by checking any messages from the
  ;; controller's experiment component and seeing if the database lists a
  ;; running process
  (when-let [[controller component] (addr-parts msg)]
    (when (and @subjs (= component "experiment") (nil? (:procedure msg)))
      (.debug console "experiment stopped on" controller)
      (mongo/find-one @subjs {:controller controller}
                      (fn [result]
                        (.debug console (:_id result) "on" controller "running:" (:running result))
                        (when (:running result)
                          (error-email (:user result)
                                       (:program result) " quit running running unxpectedly on " controller)
                          (mongo/save! @subjs (assoc result :running false) log-callback)))))))

(defn- route-req
  "Generates function to route REQ messages to controller"
  [req]
  (fn [msg rep]
    (let [msg (js->clj msg)
          [addr-1 addr-2] (str/split (:addr msg) #"\.")]
      (if-let [ctrl (@controllers addr-1)]
        (.emit (:socket ctrl) req (clj->js (assoc msg :addr addr-2)) rep)
        (rep "err" (str "no such controller " addr-1 " registered"))))))

(defn state-changed
  [name data]
  (.emit @io-external "state-changed"
           (js-obj "addr" name "time" (.now Date) "data" (clj->js data))))

(defn- remove-controller!
  "Unregisters a controller. Returns the new controller's value if successful; nil if not"
  [name]
  (when-let [data (get @controllers name)]
    (.info console "%s unregistered as" (data :address) name)
    (swap! controllers dissoc name)
    (state-changed "_controllers" (list-controllers))))

(defn- connect-internal
  "Handles connections to internal socket"
  [socket]
  (let [address (or (aget socket "handshake" "headers" "x-forwarded-for")
                    (aget socket "request" "connection" "remoteAddress"))
        key (atom nil)]
    (.info console "connection on internal port from" address)
    (-> socket
        (.on "route"
         (fn [msg rep]
           (let [msg (js->clj msg :keywordize-keys true)
                 from (msg :ret_addr)]
             (cond
              @key (rep "err" (str "connection from " address " already registered as " @key))
              (get @controllers from) (rep "err" (str "address " from " already taken"))
              :else (do
                      (reset! key from)
                      (swap! controllers assoc from {:address address :socket socket})
                      (state-changed "_controllers" (list-controllers))
                      (.info console "%s registered as" address from)
                      (rep "ok"))))))
        (.on "unroute"
             (fn [msg rep]
               (when (remove-controller! @key)
                 (reset! key nil))
               (rep "ok")))
        (.on "disconnect"
         (fn []
           (.info console "disconnection from internal port by" address)
           (when (remove-controller! @key)
             (if @subjs
               (mongo/find-one @subjs {:controller @key}
                               (fn [result]
                                 (error-email (:user result)
                                              "controller " (:controller result) ; @key is nil now
                                              " disconnected unexpectedly")
                                 (mongo/save! @subjs (assoc result :running false) log-callback)))
               (error-email nil "controller " @key " disconnected unexpectedly"))
             (reset! key nil))))
        (.on "state-changed"
         (fn [msg]
           #_(.log console "pub" "state-changed" msg)
           (.emit @io-external "state-changed" msg)
           (let [msg (flatten-record msg :time :addr)]
             (log-event! msg)
             (check-event msg))))
        (.on "trial-data"
         (fn [msg]
           (.log console "pub" "trial-data" msg)
           (.emit @io-external "trial-data" msg)
           (let [msg (flatten-record msg :time :addr)]
             (log-trial! msg)
             (update-subject! msg)))))))


(defn connect-external
  "Handles socket connections from external clients"
  [socket]
  (let [address (or (aget socket "handshake" "headers" "x-forwarded-for")
                    (aget socket "request" "connection" "remoteAddress"))]
    (.info console "connection on external port from" address)
    ;; all req messages get routed
    (map #(.on socket % (route-req %))
         ["change-state" "reset-state" "get-state" "get-meta" "get-params"])
    ;; TODO route external clients? - do they need to be addressed?
    (.on socket "disconnect"
         #(.info console "disconnection from external port by" address))))

;;; HTTP/sockets servers
(defn server []
  (let [server-internal (.createServer http (express))
        server-external (.createServer http app)]
    (reset! io-internal (sock-io server-internal))
    (reset! io-external (sock-io server-external))
    (.enable app "trust proxy")
    (.on server-external "listening"
         (fn []
           (let [address (.address server-external)]
             (.info console "external endpoint is http://%s:%s" (.-address address)
                    (.-port address)))))
    (.on server-internal "listening"
         (fn []
           (let [address (.address server-internal)]
             (.info console "internal endpoint is http://%s:%s" (.-address address)
                    (.-port address)))))
    (.on @io-internal "connection" connect-internal)
    (.on @io-external "connection" connect-external)
    (.listen server-external (:port_ext config) (:addr_ext config))
    (.listen server-internal (:port_int config) (:addr_int config))))

;; HTTP methods
(defn- send-trials
  "Sends all the trials for a subject"
  [req res]
  (let [subject (aget req "params" "subject")
        query (merge {"subject" subject} (js->clj (aget req "query")))]
    (mongo/find-all @trials query
                    (fn [err docs]
                      (.set res "Content-Type" "application/json")
                      (if err
                        (.send res 500 err)
                        (.json res docs))))))

(-> app
    (.get "/" #(.sendfile %2 "host.html"
                          (js-obj "root" (str __dirname "/../static"))))
    (.get "/controllers" #(.send %2 (clj->js (list-controllers))))
    (.get "/trials/:subject" send-trials)
    (.use "/static" ((aget express "static") (str __dirname "/../static"))))



(defn- main [& args]
  (.info console "this is decide-host, version" version)
  (when-let [mongo-uri (:log_db config)]
    (mongo/connect mongo-uri
                   (fn [err db]
                     (if err
                       (.error console "unable to connect to log database at " mongo-uri)
                       (do
                         (.info console "connected to mongodb for logging")
                         (reset! events (mongo/collection db "events"))
                         (reset! trials (mongo/collection db "trials"))
                         (reset! subjs (mongo/collection db "subjects"))))
                     (server)))))
(enable-console-print!)
(set! *main-cli-fn* main)
