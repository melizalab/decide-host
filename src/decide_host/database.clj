(ns decide-host.database
  (:require [decide-host.core :refer :all]
            [monger.joda-time]
            [monger.core :as mg]
            [monger.collection :as mc]
            [monger.conversion :refer [to-db-object from-db-object]]
            [monger.result :refer [ok? updated-existing?]]
            [monger.operators :refer :all]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]))

;; collection names
(def event-coll "events")
(def trial-coll "trials")
(def subj-coll "subjects")
(def ctrl-coll "controllers")

(def ^:private time-keyops {:before $lte
                            :after $gte})

;;; data functions
(defn parse-time-constraint
  "Parses parameter to appropriate mongodb query operator."
  [params key]
  (let [op (key time-keyops)
        time (key params)]
    (try
      (merge-in (dissoc params key) {:time {op (tc/from-long (Long. time))}})
      (catch NumberFormatException e params))))

(defn to-$in
  [x]
  (if (sequential? x) {$in x} x))

(defn parse-array-constraints
  "Replaces array parameters with $in expressions"
  [params]
  (into {} (map (fn [[k v]] [k (to-$in v)]) params)))

(defn parse-constraints
  [params]
  (-> params
      (parse-time-constraint :before)
      (parse-time-constraint :after)
      (parse-array-constraints)))

(defn- find-and-sort
  [db coll query sort]
  (map from-db-object
       (.sort (mc/find db coll query {:_id 0})
              (to-db-object sort))
       (repeat true)))

;;; database manipulation
;; controllers
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
      (let [obj-id (object-id data-id)]
        (if (updated-existing? (mc/update db coll
                                          {:_id obj-id}
                                          (convert-subject-uuid data)
                                          {:upsert true}))
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
  (mc/find-maps db subj-coll (convert-subject-uuid query)))

;; trials and events
(defn find-trials
  [db & [{:as query}]]
  (let [query (convert-subject-uuid query)]
    (find-and-sort db trial-coll query {:time 1})))

(defn find-events
  [db & [{:as query}]]
  (let [query (convert-subject-uuid query)]
    (find-and-sort db event-coll query {:time 1})))

;;; convenience methods
(defn connect!
  "Connect to a mongodb database. Returns map with :conn and :db"
  [uri]
  (let [res (mg/connect-via-uri uri)]
    (mg/get-db-names (:conn res))       ; will throw error for bad connection
    (println "I: connected to database at" uri)
    (assoc res :uri uri)))
