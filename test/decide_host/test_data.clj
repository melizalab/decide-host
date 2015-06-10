(ns decide-host.test-data
  (:require [decide-host.core :refer [uuid]]
            [clj-time.core :as t]
            [decide-host.database :as db :refer [ctrl-coll subj-coll trial-coll event-coll]]
            [monger.core :as mg]
            [monger.collection :as mc]))

(def test-db "decide-test")
(def test-uri (str "mongodb://localhost/" test-db))
(def midnight (t/today))
(def this-hour (t/today-at (t/hour (t/now)) 0))
(def subj-id "bef9a524-10cf-4cb2-8f6d-d1eeed3d3725")
(def subj-uuid (uuid subj-id))
(def controller {:addr "pica",
                 :zmq-id "706963612d6374726c",
                 :alive 10,
                 :last-seen midnight})

(def subject {:_id subj-uuid
              :controller "pica"
              :procedure "gng"
              :experiment "gng-example"
              :user "user@host.com"})

(def trial-data [{:name "gng",
                  :time midnight
                  :params
                  {:max_corrections 10,
                   :response_window 2000,
                   :hoppers ["feeder_left" "feeder_right"],
                   :feed_duration 4000,
                   :feed_delay 0,
                   :max_hopper_duty 1,
                   :rand_replace true,
                   :correct_timeout false,
                   :user "dmeliza@gmail.com",
                   :subject "bef9a524-10cf-4cb2-8f6d-d1eeed3d3725",
                   :punish_duration 10000,
                   :init_key "peck_center"},
                  :addr "pica",
                  :experiment "gng-example",
                  :comment "starting",
                  :version "2.0.0-SNAPSHOT",
                  :subject subj-uuid,
                  :stimset
                  [{:name "st15_ac3",
                    :frequency 1,
                    :category "S+",
                    :cue_stim ["center_blue"],
                    :cue_resp ["right_green"],
                    :responses
                    {:peck_right {:p_reward 1, :correct true},
                     :timeout {:correct false}}}
                   {:name ["st15_ac4" "st15_ac8"],
                    :frequency 1,
                    :category "S-",
                    :cue_stim ["center_blue"],
                    :cue_resp ["right_green"],
                    :responses
                    {:peck_right {:p_punish 1, :correct false},
                     :timeout {:correct true}}}]}
                 {:response "peck_right",
                  :category "S+",
                  :name "gng",
                  :time (t/minus this-hour (t/hours 2))
                  :addr "pica",
                  :correction 0,
                  :experiment "gng-example",
                  :result "feed",
                  :trial 1,
                  :stimulus "st15_ac3",
                  :program "gng",
                  :rtime 817852,
                  :subject subj-uuid,
                  :correct true}
                 {:usec 217,
                  :addr "pica",
                  :name "gng",
                  :time (t/minus this-hour (t/minutes 10))
                  :subject subj-uuid
                  :comment "heartbeat"}
                 {:response "peck_right",
                  :category "S-",
                  :name "gng",
                  :time (t/plus this-hour (t/minutes 10))
                  :addr "pica",
                  :correction 0,
                  :experiment "gng-example",
                  :result "punish",
                  :trial 2,
                  :stimulus ["st15_ac4" "st15_ac8"],
                  :program "gng",
                  :rtime 422333,
                  :subject subj-uuid,
                  :correct false}
                 {:response "peck_right",
                  :category "S-",
                  :name "gng",
                  :time (t/plus this-hour (t/minutes 11))
                  :addr "pica",
                  :correction 1,
                  :experiment "gng-example",
                  :result "punish",
                  :trial 3,
                  :stimulus ["st15_ac4" "st15_ac8"],
                  :program "gng",
                  :rtime 354824,
                  :subject subj-uuid,
                  :correct false}
                 {:response "timeout",
                  :category "S-",
                  :name "gng",
                  :time (t/plus this-hour (t/minutes 12))
                  :addr "pica",
                  :correction 2,
                  :experiment "gng-example",
                  :result "none",
                  :trial 4,
                  :stimulus ["st15_ac4" "st15_ac8"],
                  :program "gng",
                  :rtime nil,
                  :subject subj-uuid,
                  :correct true}
                 {:response "peck_right",
                  :category "S+",
                  :name "gng",
                  :time (t/plus this-hour (t/minutes 13))
                  :addr "pica",
                  :correction 0,
                  :experiment "gng-example",
                  :result "feed",
                  :trial 5,
                  :stimulus "st15_ac3",
                  :program "gng",
                  :rtime 682408,
                  :subject subj-uuid,
                  :correct true}])

(def event-data  [{:usec 375,
                    :addr "pica",
                    :name "cue_right_green",
                    :time (t/minus this-hour (t/minutes 10))
                    :trigger "none",
                   :brightness 0}
                  {:usec 752,
                   :addr "pica",
                   :name "cue_right_blue",
                   :time (t/plus this-hour (t/minutes 11))
                   :trigger "none",
                   :brightness 0}
                  {:usec 748,
                   :addr "pica",
                   :name "experiment",
                   :time (t/plus this-hour (t/minutes 12))
                   :procedure nil,
                   :pid nil,
                   :subject nil,
                   :user nil}])

(defn setup-db []
  (let [{:keys [conn db]} (db/connect! test-uri)]
    (mg/drop-db conn test-db)
    (mc/insert db ctrl-coll controller)
    (mc/insert db subj-coll subject)
    (mc/insert-batch db trial-coll trial-data)
    (mc/insert-batch db event-coll event-data)
    db))

(defn populate-db
  "sets up the database with n sequential trial and event entries"
  [n]
  (let [{:keys [conn db]} (db/connect! test-uri)
        trial (get trial-data 1)
        event (get event-data 1)]
    (mg/drop-db conn test-db)
    (doseq [i (range n)]
      (mc/insert db trial-coll (assoc trial :trial i :time (t/plus midnight (t/seconds 1))))
      (mc/insert db event-coll (assoc event :time (t/plus midnight (t/seconds 1)))))
    db))
