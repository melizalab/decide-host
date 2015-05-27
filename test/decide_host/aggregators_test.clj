(ns decide-host.aggregators-test
  (:require [midje.sweet :refer :all]
            [decide-host.aggregators :refer :all]
            [decide-host.database :refer [connect! uuid ctrl-coll subj-coll trial-coll]]
            [monger.core :as mg]
            [monger.collection :as mc]
            [clj-time.core :as t]))

(def test-db "decide-test")
(def test-uri (str "mongodb://localhost/" test-db))
(def tbase (today-local-midnight))
(def my-uuid (uuid "bef9a524-10cf-4cb2-8f6d-d1eeed3d3725"))
(def controller {:addr "pica",
                 :zmq-id "706963612d6374726c",
                 :alive 10,
                 :last-seen tbase})

(def subject {:_id my-uuid
              :controller "pica"
              :procedure "gng"
              :experiment "gng-example"
              :user "user@host.com"})

(def trial-data [{:name "gng",
                  :time tbase
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
                  :subject my-uuid,
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
                  :time (t/plus tbase (t/hours 1))
                  :addr "pica",
                  :correction 0,
                  :experiment "gng-example",
                  :result "feed",
                  :trial 1,
                  :stimulus "st15_ac3",
                  :program "gng",
                  :rtime 817852,
                  :subject my-uuid,
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
                  :subject my-uuid,
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
                  :subject my-uuid,
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
                  :subject my-uuid,
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
                  :subject my-uuid,
                  :correct true}])

(defn setup-db []
  (let [{:keys [conn db]} (connect! test-uri)]
    (mg/drop-db conn test-db)
    (mc/insert db ctrl-coll controller)
    (mc/insert db subj-coll subject)
    (mc/insert-batch db trial-coll trial-data)
    db))

(fact "about rekey-result"
    (rekey-result :_id {:_id my-uuid :result "blah"}) => {my-uuid "blah"}
    (rekey-result :_id {:_id my-uuid :a 1 :b 2}) => {my-uuid {:a 1 :b 2}})

(let [db (setup-db)]
  #_(fact "aggregate results"
      (first (all-stats db)) => (just {:_id my-uuid
                                       :trials-today 5
                                       :feed-ops-today 2
                                       :recent-accuracy {:since anything
                                                         :trials 4
                                                         :correct 2}}))
  (fact "trials-today counts number of trials"
      (trials-today db) => [{:_id my-uuid :result 5}]
      (trials-today db :subject my-uuid) => [{:_id my-uuid :result 5}]
      (trials-today db :subject "blah blah") => [])
  (fact "feed-ops-today counts number of trials with feed outcomes"
      (feed-ops-today db) => [{:_id my-uuid :result 2}]
      (feed-ops-today db :subject my-uuid) => [{:_id my-uuid :result 2}]
      (feed-ops-today db :subject "blah blah") => []))
