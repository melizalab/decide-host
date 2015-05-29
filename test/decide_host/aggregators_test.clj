(ns decide-host.aggregators-test
  (:require [midje.sweet :refer :all]
            [decide-host.core :refer [uuid]]
            [decide-host.aggregators :refer :all]
            [decide-host.database :as db :refer [ctrl-coll subj-coll trial-coll]]
            [monger.core :as mg]
            [monger.collection :as mc]
            [clj-time.core :as t]))

(def test-db "decide-test")
(def test-uri (str "mongodb://localhost/" test-db))
(def midnight (t/today))
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
                  :time (t/plus midnight (t/hours 1))
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
                 {:response "peck_right",
                  :category "S-",
                  :name "gng",
                  :time (t/minus (t/now) (t/minutes 13))
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
                  :time (t/minus (t/now) (t/minutes 12))
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
                  :time (t/minus (t/now) (t/minutes 11))
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
                  :time (t/minus (t/now) (t/minutes 10))
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

(defn setup-db []
  (let [{:keys [conn db]} (connect! test-uri)]
    (mg/drop-db conn test-db)
    (mc/insert db ctrl-coll controller)
    (mc/insert db subj-coll subject)
    (mc/insert-batch db trial-coll trial-data)
    db))

(let [db (setup-db)
      subj-record (db/find-subject db subj-id)
      last-hour (t/minus (t/now) (t/hours 1))]
  (fact "join-controller"
      (:controller (join-controller db subj-record)) => (contains {:addr "pica"}))
  (fact "join-activity"
      (join-activity db (t/today) :today subj-record) => (contains {:today {:trials 5
                                                                            :feed-ops 2
                                                                            :correct 3}})
      (join-activity db last-hour :recent subj-record) => (contains {:recent {:trials 4
                                                                              :feed-ops 1
                                                                              :correct 2}}))
  (fact "join-all gives nils for missing subjects"
      (join-all db {:_id "doofus"}) => (contains {:today nil
                                                  :last-hour nil
                                                  :controller nil})
      (join-all db {}) => (contains {:today nil
                                     :last-hour nil
                                     :controller nil}))
  (fact "hourly-stats summarizes correctly"
      (let [stats (hourly-stats db {:subject subj-id})]
        (count stats) => 2
        (first stats) => (contains {:trials 1
                                    :correct 1
                                    :feed-ops 1})
        (second stats) => (contains {:trials 4
                                    :correct 2
                                    :feed-ops 1}))))
