(ns decide-host.aggregators
  "Functions that make calculations on aggregate data"
  (:require [decide-host.database :refer [db trial-coll event-coll]]
            [clojure.core.match :refer [match]]
            [monger.collection :as mc]
            [monger.operators :refer :all]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]))

(defn today-local-midnight
  "Returns the time at the start of the day in local time"
  []
  (tc/to-long (t/from-time-zone (t/today-at 0 0) (t/default-time-zone))))

(defn trials-today
  "Returns a sequence of maps giving the number of trials run today by each
  subject in the database. The query can be restricted by providing additional
  query keywords (e.g., :subject subj-id)"
  [& restrict]
  (let [midnight (today-local-midnight)
        query (apply assoc {$gte {:time midnight}} :comment nil restrict)]
    (mc/aggregate @db trial-coll [{$match query}
                                  {$group { :_id "$subject" :trials {$sum 1}}}]))  )

;; it might be more useful to calculate the total amount of time the hopper has
;; been up, but that's fairly difficult to do
(defn feed-ops-today
  "Returns a sequence of maps giving the number of trials run today by each
  subject in the database. The query can be restricted by providing additional
  query keywords (e.g., :subject subj-id)"
  [& restrict]
  (let [midnight (today-local-midnight)
        query (apply assoc {$gte {:time midnight}} :result "feed" restrict)]
    (mc/aggregate @db trial-coll [{$match query}
                                  {$group { :_id "$subject" :feed-ops {$sum 1}}}])))

(defn recent-accuracy
  "Returns a sequence of maps giving the number of correct responses given in
  the last interval ms by each subject in the database. The query can be
  restricted by providing additional query keywords (e.g., :subject subj-id)"
  [interval & restrict]
  (let [mark (t/minus (t/now) (t/millis interval))
        query (apply assoc {$gte {:time mark}} :comment nil restrict)]
    ;; have to convert booleans to numerical values
    (mc/aggregate @db/db "trials"
                  [{$match {:comment nil}}
                   {$project {:subject 1 :correct {$cond ["$correct" 1 0]}}}
                   {$group { :_id "$subject" :trials {$sum 1} :correct {$sum "$correct"}}}])))
