(ns decide-host.aggregators
  "Functions that make calculations on aggregate data"
  (:require [clj-time.core :as t]
            [decide-host.core :refer [convert-subject-uuid merge-in
                                      uuid]]
            [decide-host.database :as db :refer [trial-coll]]
            [monger.collection :as mc]
            [monger.operators :refer :all]))

(def trial-projection {:time 1
                       :fed {$cond [{"$eq" ["$result" "feed"]} 1 0]}
                       :correct {$cond ["$correct" 1 0]}
                       :is-trial {$cond [{"$gte" ["$trial" 0]} 1 0]}})

(def trial-grouping {:_id nil
                     :feed-ops {$sum "$fed"}
                     :correct {$sum "$correct"}
                     :trials {$sum "$is-trial"}})

(defn merge-query [base restrict]
  (merge-in base (apply hash-map restrict)))

;; it might be more useful to calculate the total amount of time the hopper has
;; been up, but that's fairly difficult to do with a simple database query
(defn activity-stats
  "Returns map of activity statistics for subject from since until now. Result
  is nil if no trials found for subject."
  [db subject since]
  (let [match {:time {$gte since} :comment nil :subject (uuid subject)}
        [result] (mc/aggregate db trial-coll
                                [{$match match}
                                 {$project trial-projection}
                                 {$group trial-grouping}])]
    (if result
      (dissoc result :_id)
      (into {} (for [k (keys (dissoc trial-grouping :_id))] [k 0])))))

(defn activity-stats-today [db subject] (activity-stats db subject (t/today)))
(defn activity-stats-last-hour [db subject]
  (activity-stats db subject (t/minus (t/now) (t/hours 1))))

(defn join-controller
  [db {a :controller :as subj}]
  (let [ctrl (db/find-controller-by-addr db a {:alive 1 :addr 1 :last-seen 1 :zmq-id 1})]
    (if (and (:alive ctrl) (:zmq-id ctrl))
      (assoc subj :controller (select-keys ctrl [:addr :last-seen]))
      subj)))

(defn join-activity
  [db {s :_id :as subj}]
  (assoc subj
         :today (activity-stats-today db s)
         :last-hour (activity-stats-last-hour db s)))

(defn- convert-time
  [{time :_id :as rec}]
  (let [tt (apply t/date-time ((juxt :year :month :day :hour) time))]
    (-> rec
        (dissoc :_id)
        (assoc :time tt))))

(defn hourly-stats
  [db constraints]
  (let [constraints (merge {:comment {$in [nil "heartbeat"]}}
                           (convert-subject-uuid constraints))
        a (mc/aggregate db trial-coll
                      [{$match constraints}
                       {$project trial-projection}
                       {$group (assoc trial-grouping :_id {:year {"$year" "$time"}
                                                           :month {"$month" "$time"}
                                                           :day {"$dayOfMonth" "$time"}
                                                           :hour {"$hour" "$time"}}) }])]
    (if-let [results (seq a)]
      (sort-by :time (map convert-time results))
      a)))
