(ns decide-host.aggregators
  "Functions that make calculations on aggregate data"
  (:require [decide-host.database :refer [trial-coll event-coll]]
            [monger.collection :as mc]
            [monger.operators :refer :all]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]))

(defn today-local-midnight
  "Returns the time at the start of the day in local time"
  []
  (t/from-time-zone (t/today-at 0 0) (t/default-time-zone)))

(defn merge-query
  "keyval => key val"
  [base keyvals]
  (let [kv (apply hash-map keyvals)]
    (merge-with merge base kv)))

(defn rekey-result
  [key map]
  (let [newkey (key map)]
    (if-let [result (:result map)]
      {newkey result}
      {newkey (dissoc map key)})))

(defn trials-today
  "Returns a sequence of maps giving the number of trials run today by each
  subject in the database. The query can be restricted by providing additional
  query keywords (e.g., :subject subj-id)"
  [db & restrict]
  (let [midnight (today-local-midnight)
        query (merge-query {:time {$gte midnight} :comment nil} restrict)]
    (mc/aggregate db trial-coll [{$match query}
                                 {$group { :_id "$subject" :result {$sum 1}}}]))  )

;; it might be more useful to calculate the total amount of time the hopper has
;; been up, but that's fairly difficult to do
(defn feed-ops-today
  "Returns a sequence of maps giving the number of trials run today by each
  subject in the database. The query can be restricted by providing additional
  query keywords (e.g., :subject subj-id)"
  [db & restrict]
  (let [midnight (today-local-midnight)
        query (merge-query {:time {$gte midnight} :result "feed"} restrict)]
    (mc/aggregate db trial-coll [{$match query}
                                  {$group { :_id "$subject" :result {$sum 1}}}])))

(defn recent-accuracy
  "Returns a sequence of maps giving the number of correct responses given in
  the last interval by each subject in the database. The query can be
  restricted by providing additional query keywords (e.g., :subject subj-id)"
  [db & restrict]
  ;; TODO accept :interval as keyword
  (let [interval (t/hours 1)
        mark (t/minus (t/now) interval)
        query (merge-query {:time {$gte mark} :comment nil} restrict)]
    ;; have to convert booleans to numerical values
    (println "D: query" query)
    (mc/aggregate db trial-coll
                  [{$match query}
                   {$project {:subject 1 :correct {$cond ["$correct" 1 0]}
                              :since {"$literal" mark}}}
                   {$group {:_id "$subject" :since {$first "$since"}
                            :trials {$sum 1} :correct {$sum "$correct"}}}])))

(defn all-stats
  [db & restrict]

  )

#_(defn subjects
  [db & restrict]
  (let [subjects (mc/find db trial-coll (hash-map restrict))
        agg-fun (juxt trials-today feed-ops-today recent-accuracy)
        aggregates (apply agg-fun db restrict)]
    (join-on :_id subjects)))
