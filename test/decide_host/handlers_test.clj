(ns decide-host.handlers-test
  (:require [midje.sweet :refer :all]
            [decide-host.handlers :refer :all]
            [monger.core :as mg]
            [decide-host.database :as db]))

(def test-db "decide-test")
(def test-uri (str "mongodb://localhost/" test-db))

(let [{:keys [conn db]} (db/connect! test-uri)]
  (mg/drop-db conn test-db)
  (let [sock-id "test-ctrl"
        addr "test"
        subject "tester"
        procedure "testing"
        data {:topic :state-changed
              :addr addr
              :name "experiment"
              :time 1432069827152495
              :user "me@host.com"
              :procedure procedure
              :subject subject}]
    (facts "about update-subject"
       (fact "handles experiment state-changed updates"
          (db/add-controller! sock-id addr)
         (db/update-controller! sock-id {:alive 10})
         (db/get-procedure subject) => nil
         (update-subject! data)
         (db/get-procedure subject) => procedure)
       (fact "updates last-fed for hopper events"
         (update-subject! {:topic :state-changed :name "hopper" :up 1 :addr addr :time 1234})
         (db/get-subject subject) => (contains {:last-fed 1234}))
       (fact "updates last-trial for trial events"
         (update-subject! {:topic :trial-data :subject subject :trial 10 :time 4567})
         (db/get-subject subject) => (contains {:last-trial 4567}))
       (fact "update-subject updates current experiment"
         (update-subject! {:topic :trial-data :subject subject :experiment "blah"})
         (db/get-subject subject) => (contains {:experiment "blah"})))))
