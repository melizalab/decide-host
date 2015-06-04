(ns decide-host.database
  (:require [decide-host.core :refer :all]
            [monger.joda-time]
            [monger.core :as mg]
            [monger.collection :as mc]
            [monger.operators :refer :all]
            [monger.query :as q]
            [monger.result :refer [ok? updated-existing?]]
            [clj-time.core :as t]))

;; collection names
(def event-coll "events")
(def trial-coll "trials")
(def subj-coll "subjects")
(def ctrl-coll "controllers")

;;; database manipulation
;; controllers
(defn add-controller!
  [db sock-id addr]
  (mc/update db ctrl-coll
             {:addr addr}
             {:addr addr :zmq-id sock-id}
             {:upsert true}))

(defn update-controller!
  "Updates database entry for controller"
  [db addr kv] (mc/update db ctrl-coll {:addr addr} {$set kv}))

(defn remove-controller!
  "Removes controller from database"
  [db sock-id] (mc/remove db ctrl-coll {:zmq-id sock-id}))

(defn set-alive!
  "Sets aliveness for controller entry"
  [db sock-id val] (mc/update db ctrl-coll
                              {:zmq-id sock-id}
                              {$set {:alive val :last-seen (t/now)}}))

(defn dec-alive!
  "Decrements aliveness counter for controller"
  [db sock-id] (mc/update db ctrl-coll {:zmq-id sock-id} {$inc {:alive -1}}))

;; subjects
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

;; events and trials
(defn log-message! [db data-type data-id data]
  (if-let [coll (case data-type
                    "state-changed" event-coll
                    "trial-data" trial-coll
                    nil)]
    (let [obj-id (object-id data-id)
          data (convert-subject-uuid data)]
        (if (updated-existing? (mc/update db coll {:_id obj-id} data {:upsert true}))
          :dup :ack))
      :rtfm-dtype))

;;; database query functions
;; controllers
(defn find-controller-by-socket
  [db sock-id]
  (mc/find-one-as-map db ctrl-coll {:zmq-id sock-id}))
(defn find-controller-by-addr
  ([db addr] (mc/find-one-as-map db ctrl-coll {:addr addr}))
  ([db addr fields] (mc/find-one-as-map db ctrl-coll {:addr addr} fields)))

(defn find-controllers [db & [{:as query}]]
  (mc/find-maps db ctrl-coll query))

(defn find-connected-controllers [db]
  (mc/find-maps db ctrl-coll {:zmq-id {$ne nil}}))

;; subjects
(defn find-subject [db subject]
  (when subject (mc/find-map-by-id db subj-coll (uuid subject))))

(defn find-subject-by-addr [db addr] (mc/find-one-as-map db subj-coll {:controller addr}))

(defn get-procedure
  "Gets currently running experiment for subject iff the associated controller is alive"
  [db subject]
  (let [{:keys [controller procedure]} (find-subject db subject)
        ctrl (mc/find-one-as-map db ctrl-coll {:addr controller :alive {$gt 0}})]
    (when ctrl procedure)))

(defn find-subjects
  "Gets subjects in database, limited by query"
  [db & [{:as query}]]
  (mc/find-maps db subj-coll query))

;; trials and events
(defn- find-generic
  [db coll & [{:keys [match sort limit]
               :or {sort {:time 1}
                    limit 0}}]]
  (q/with-collection db coll
    (q/find match)
    (q/fields {:_id 0})
    (q/sort sort)
    (q/limit limit)))
(defn find-trials [db & [query]] (find-generic db trial-coll query))
(defn find-events [db & [query]] (find-generic db event-coll query))

;;; convenience methods
(defn connect!
  "Connect to a mongodb database. Returns map with :conn and :db"
  [uri]
  (let [res (mg/connect-via-uri uri)]
    (mg/get-db-names (:conn res))       ; will throw error for bad connection
    (println "I: connected to database at" uri)
    (assoc res :uri uri)))
