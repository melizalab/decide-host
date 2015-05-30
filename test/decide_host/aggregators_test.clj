(ns decide-host.aggregators-test
  (:require [midje.sweet :refer :all]
            [decide-host.database :as db]
            [decide-host.test-data :refer :all]
            [decide-host.aggregators :refer :all]
            [clj-time.core :as t]))

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
      (join-all db {:_id "doofus"}) => (just {:_id "doofus"})
      (join-all db {}) => {})
  (fact "hourly-stats summarizes correctly"
      (let [stats (hourly-stats db {:subject subj-id})]
        (count stats) => 2
        (first stats) => (contains {:trials 1
                                    :correct 1
                                    :feed-ops 1})
        (second stats) => (contains {:trials 4
                                    :correct 2
                                    :feed-ops 1}))))
