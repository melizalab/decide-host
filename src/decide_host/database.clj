(ns decide-host.database
  (:require [monger.joda-time]
            [monger.core :as mg]
            [monger.collection :as mc]
            [clj-time.core :as t]
            [monger.operators :refer :all])
  (:import [com.mongodb MongoOptions ServerAddress]))

;; collection names
(def event-coll "events")
(def trial-coll "trials")
(def subj-coll "subjects")
(def ctrl-coll "controllers")

;; for testing
(def db-conn (mg/connect))
(def db (mg/get-db db-conn "decide"))

(defn add-controller!
  [sock-id addr]
  (mc/update db ctrl-coll
             {:_id addr} {:zmq-id sock-id :alive true :last-seen (t/now)} {:upsert true}))

(defn remove-controller! [sock-id] (mc/remove db ctrl-coll {:zmq-id sock-id}))

(defn update-controller! [sock-id kv] (mc/update db ctrl-coll {:zmq-id sock-id} {$set kv}))

(defn controller-alive!
  ([sock-id] (update-controller! sock-id {:alive true :last-seen (t/now)}))
  ([sock-id alive] (update-controller! sock-id {:alive alive})))

(defn get-controller-by-socket [sock-id] (mc/find-one-as-map db ctrl-coll {:zmq-id sock-id}))

(defn get-controller-by-addr [addr] (mc/find-map-by-id db ctrl-coll addr))

(defn get-living-controllers [] (mc/find-maps db ctrl-coll {:alive true}))

(defn log-event!
  "Inserts an event into the database, returning true on success"
  [db data])
