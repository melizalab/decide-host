(ns decide-host.database
  (:require [decide-host.config :refer [config]]
            [monger.joda-time]
            [monger.core :as mg]
            [monger.collection :as mc]
            [monger.result :refer [ok? updated-existing?]]
            [monger.operators :refer :all]
            [clj-time.core :as t])
  (:import [org.bson.types ObjectId]
           [java.util UUID]))

;; collection names
(def event-coll "events")
(def trial-coll "trials")
(def subj-coll "subjects")
(def ctrl-coll "controllers")

(def ^:private init-alive (get (config) :heartbeat-max-ping 10))

(defn object-id
  "Returns a new BSON ObjectID, either newly generated or from a string
  argument. If the string can't be converted to an ObjectID, it's returned
  as-is."
  ([] (ObjectId.))
  ([x] (try (ObjectId. x)
            (catch IllegalArgumentException e x))))

(defn uuid
  "Converts s to UUID type if possible. Otherwise returns the original argument"
  [s]
  (try (UUID/fromString s)
       (catch IllegalArgumentException e s)
       (catch NullPointerException e s)))

(defn convert-subject-uuid
  "Attempts to convert :subject field if present to a uuid"
  [map]
  (if-let [subj (uuid (:subject map))]
    (assoc map :subject subj)
    map))

(defn connect!
  "Connect to a mongodb database. Returns map with :conn and :db"
  [uri]
  (let [res (mg/connect-via-uri uri)]
    (mg/get-db-names (:conn res))       ; will throw error for bad connection
    (println "I: connected to database at" uri)
    (assoc res :uri uri)))

(defn add-controller!
  [db sock-id addr]
  (mc/update db ctrl-coll
             {:zmq-id sock-id}
             {:addr addr :zmq-id sock-id}
             {:upsert true}))

(defn remove-controller!
  "Removes controller from database"
  [db sock-id] (mc/remove db ctrl-coll {:zmq-id sock-id}))

(defn update-controller!
  "Updates database entry for controller"
  [db sock-id kv] (mc/update db ctrl-coll {:zmq-id sock-id} {$set kv}))

(defn set-alive!
  "Sets aliveness for controller entry"
  [db sock-id] (mc/update db ctrl-coll
                          {:zmq-id sock-id}
                          {$set {:alive init-alive :last-seen (t/now)}}))

(defn dec-alive!
  "Decrements aliveness counter for controller"
  [db sock-id] (mc/update db ctrl-coll {:zmq-id sock-id} {$inc {:alive -1}}))

(defn get-controller-by-socket
  [db sock-id]
  (mc/find-one-as-map db ctrl-coll {:zmq-id sock-id}))
(defn get-controller-by-addr [db addr] (mc/find-one-as-map db ctrl-coll {:addr addr}))

(defn get-living-controllers [db] (mc/find-maps db ctrl-coll {:alive {$gt 0}}))
(defn get-controllers [db] (mc/find-maps db ctrl-coll))

(defn start-subject!
  "Updates database when subject starts running an experiment"
  [db subject data]
  (mc/update db subj-coll {:_id (uuid subject)} {$set data} {:upsert true}))

(defn stop-subject!
  "Updates database when subject stops running an experiment"
  ([db addr] (stop-subject! db addr (t/now)))
  ([db addr time] (mc/update db subj-coll
                          {:controller addr}
                          {$set {:controller nil :procedure nil :stop-time time} })))

(defn update-subject!
  "Updates subject record in database"
  [db subject data]
  (mc/update-by-id db subj-coll (uuid subject) {$set data}))

(defn update-subject-by-controller!
  [db addr data]
  (mc/update db subj-coll {:controller addr} {$set data}))

(defn get-subject [db subject] (when subject (mc/find-map-by-id db subj-coll (uuid subject))))
(defn get-subject-by-addr [db addr] (mc/find-one-as-map db subj-coll {:controller addr}))
(defn get-procedure
  "Gets currently running experiment for subject iff the associated controller is alive"
  [db subject]
  (let [{:keys [controller procedure]} (get-subject db subject)
        ctrl (mc/find-one-as-map db ctrl-coll {:addr controller :alive {$gt 0}})]
    (when ctrl procedure)))

(defn log-message! [db data-type data-id data]
  (if-let [coll (case data-type
                    "state-changed" event-coll
                    "trial-data" trial-coll
                    nil)]
      (let [obj-id (object-id data-id)]
        (if (updated-existing? (mc/update db coll
                                          {:_id obj-id}
                                          (convert-subject-uuid data)
                                          {:upsert true}))
          :dup :ack))
      :rtfm-dtype))
