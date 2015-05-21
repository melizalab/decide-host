(ns decide-host.database
  (:require [monger.joda-time]
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

(def db (atom nil))

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
  "Connect to a mongodb database"
  [uri]
  (let [res (mg/connect-via-uri uri)]
    (mg/get-db-names (:conn res))       ; will throw error for bad connection
    (println "I: connected to database at" uri)
    ;; ensure indices here or in a setup script?
    (reset! db (:db res))
    res))

(defn add-controller!
  [sock-id addr & kv]
  (mc/update @db ctrl-coll
             {:zmq-id sock-id}
             (apply assoc {:addr addr} :zmq-id sock-id kv)
             {:upsert true}))

(defn remove-controller!
  "Removes controller from database"
  [sock-id] (mc/remove @db ctrl-coll {:zmq-id sock-id}))

(defn update-controller!
  "Updates database entry for controller"
  [sock-id kv] (mc/update @db ctrl-coll {:zmq-id sock-id} {$set kv} {:multi true}))

(defn dec-alive!
  "Decrements aliveness counter for controller"
  [sock-id] (mc/update @db ctrl-coll {:zmq-id sock-id} {$inc {:alive -1}}))

(defn get-controller-by-socket [sock-id] (mc/find-one-as-map @db ctrl-coll {:zmq-id sock-id}))
(defn get-controller-by-addr [addr] (mc/find-one-as-map @db ctrl-coll {:addr addr}))
(defn get-living-controllers [] (mc/find-maps @db ctrl-coll {:alive {$gt 0}}))

(defn start-subject!
  "Updates database when subject starts running an experiment"
  [subject data]
  (mc/update @db subj-coll {:_id (uuid subject)} {$set data} {:upsert true}))

(defn stop-subject!
  "Updates database when subject stops running an experiment"
  ([addr] (stop-subject! addr (t/now)))
  ([addr time] (mc/update @db subj-coll
                          {:controller addr}
                          {$set {:controller nil :procedure nil :stop-time time} })))

(defn get-subject [subject] (when subject (mc/find-map-by-id @db subj-coll (uuid subject))))
(defn get-subject-by-addr [addr] (mc/find-one-as-map @db subj-coll {:controller addr}))
(defn get-procedure
  "Gets currently running experiment for subject iff the associated controller is alive"
  [subject]
  (let [{:keys [controller procedure]} (get-subject subject)
        ctrl (mc/find-one-as-map @db ctrl-coll {:addr controller :alive {$gt 0}})]
    (when ctrl procedure)))

(defn log-message! [data-type data-id data]
  (try
    (let [coll (case data-type
                    "state-changed" event-coll
                    "trial-data" trial-coll)
          obj-id (object-id data-id)]
      (if (updated-existing? (mc/update @db coll
                                        {:_id obj-id}
                                        (convert-subject-uuid data)
                                        {:upsert true}))
        :dup :ack))
    (catch IllegalArgumentException e :rtfm)))
