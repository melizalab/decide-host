(ns decide-host.aggregators
  "Functions that make calculations on aggregate data"
  (:require [decide-host.core :refer [merge-in convert-subject-uuid]]
            [decide-host.database :as db :refer [trial-coll event-coll]]
            [monger.collection :as mc]
            [monger.operators :refer :all]
            [clj-time.core :as t]))

(def trial-projection {:time 1
                       :fed {$cond [{"$eq" ["$result" "feed"]} 1 0]}
                       :correct {$cond ["$correct" 1 0]}})

(def trial-grouping {:_id nil
                     :feed-ops {$sum "$fed"}
                     :correct {$sum "$correct"}
                     :trials {$sum 1}})

(defn merge-query [base restrict]
  (merge-in base (apply hash-map restrict)))

;; it might be more useful to calculate the total amount of time the hopper has
;; been up, but that's fairly difficult to do
(defn join-activity
  "Calculates statistics for :subject in map and adds result to map
  under :today. Result is nil if no trials found for subject."
  [db since key map]
  (let [{subj :_id :as result} map
        match {:time {$gte since} :comment nil :subject subj}
        [a] (mc/aggregate db trial-coll
                       [{$match match}
                        {$project trial-projection}
                        {$group trial-grouping}])]
    (assoc result key (when a (select-keys a [:feed-ops :correct :trials])))))

(defn join-controller
  [db {a :controller :as subj}]
  (let [ctrl (db/find-controller-by-addr db a {:_id 0})]
    (assoc subj :controller (when (:alive ctrl) (select-keys ctrl [:addr :last-seen])))))

(defn join-all
  [db subject]
  (->> subject
       (join-controller db)
       (join-activity db (t/today) :today)
       (join-activity db (t/minus (t/now) (t/hours 1)) :last-hour)))

(defn- convert-time
  [{time :_id :as rec}]
  (let [tt (apply t/date-time ((juxt :year :month :day :hour) time))]
    (-> rec
        (dissoc :_id)
        (assoc :time tt))))

(defn hourly-stats
  [db constraints]
  (let [constraints (merge {:comment nil} (convert-subject-uuid constraints))
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
