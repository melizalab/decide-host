(ns decide-host.database
  (:require [monger.joda-time]
            [monger.core :as mg]
            [monger.collection :as mc]
            [monger.result :refer [ok?]]
            [monger.operators :refer :all]
            [clj-time.core :as t])
  (:import [org.bson.types ObjectId]))

;; collection names
(def event-coll "events")
(def trial-coll "trials")
(def subj-coll "subjects")
(def ctrl-coll "controllers")

(def db (atom nil))

(defn connect!
  "Connect to a mongodb database"
  [uri]
  (let [res (mg/connect-via-uri uri)]
    (mg/get-db-names (:conn res))       ; will throw error for bad connection
    (println "I: connected to database at" uri)
    (reset! db (:db res))))

(defn add-controller!
  [sock-id addr]
  (mc/update @db ctrl-coll
             {:_id addr} {:zmq-id sock-id :alive true :last-seen (t/now)} {:upsert true}))

(defn remove-controller!
  "Removes controller from database"
  [sock-id] (mc/remove @db ctrl-coll {:zmq-id sock-id}))

(defn update-controller!
  "Updates database entry for controller"
  [sock-id kv] (mc/update @db ctrl-coll {:zmq-id sock-id} {$set kv} {:multi true}))

(defn controller-alive!
  "Updates database with connection status of controller"
  ([sock-id] (update-controller! sock-id {:alive true :last-seen (t/now)}))
  ([sock-id alive] (update-controller! sock-id {:alive alive})))

(defn get-controller-by-socket [sock-id] (mc/find-one-as-map @db ctrl-coll {:zmq-id sock-id}))
(defn get-controller-by-addr [addr] (mc/find-map-by-id @db ctrl-coll addr))
(defn get-living-controllers [] (mc/find-maps @db ctrl-coll {:alive true}))

(defn start-subject!
  "Updates database when subject starts running an experiment"
  [subject data]
  (mc/update @db subj-coll {:_id subject} {$set data} {:upsert true}))

(defn stop-subject!
  "Updates database when subject stops running an experiment"
  ([addr] (stop-subject! addr (t/now)))
  ([addr time] (mc/update @db subj-coll
                          {:controller addr}
                          {$set {:controller nil :procedure nil :stop-time time} })))

(defn get-subject [subject] (mc/find-map-by-id @db subj-coll subject))
(defn get-subject-by-addr [addr] (mc/find-one-as-map @db subj-coll {:controller addr}))
(defn get-experiment
  "Gets currently running experiment for subject iff the associated controller is alive"
  [subject]
  (let [subj (get-subject subject)
        ctrl (get-controller-by-addr (:controller subj))]
    (when (:alive ctrl) (:experiment subj))))

(defn log-event! [data-id data]
  (let [obj-id (ObjectId. data-id)
        data (assoc data :_id obj-id)]
    (ok? (mc/save @db event-coll data))))

(defn log-trial! [data-id data]
  (let [obj-id (ObjectId. data-id)
        data (assoc data :_id obj-id)]
    (println "D: trial-data:" data)
    (ok? (mc/save @db trial-coll data))))
