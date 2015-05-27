(ns decide-host.database-test
  (:require [midje.sweet :refer :all]
            [decide-host.database :refer :all]
            [decide-host.core :refer [object-id]]
            [monger.core :as mg]
            [monger.collection :as mc]
            [monger.operators :refer :all]
            [monger.result :refer [ok? updated-existing?]]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]))

(def test-db "decide-test")
(def test-uri (str "mongodb://localhost/" test-db))
(def INIT-ALIVE 5)

(fact "about parse-time-constraint"
    (parse-time-constraint {:a 1} :after) => {:a 1}
    (parse-time-constraint {:a 1 :after "blarg"} :after) => {:a 1 :after "blarg"}
    (let [tt 1432753029026
          tp (tc/from-long tt)]
      (parse-time-constraint {:a 1 :after (str tt)} :after) => {:a 1 :time {$gte tp}}
      (parse-time-constraint {:a 1 :before (str tt)} :before) => {:a 1 :time {$lte tp}}))

(fact "bad uris generate exceptions"
    (connect! "garbledegook") => (throws Exception)
    (connect! "mongodb://localhost:1234/decide-test") => (throws Exception))

(let [{:keys [conn db]} (connect! test-uri)
      subject "acde" sock-id "test-ctrl" addr "test" tt (t/now)]
  (mg/drop-db conn test-db)
  (with-state-changes [(after :facts (mg/drop-db conn test-db))]
    (fact "about controller state management"
        (fact "bad values return nil"
              (get-controller-by-addr db nil) => nil
              (get-controller-by-socket db nil) => nil
              (get-controller-by-addr db addr) => nil
              (get-controller-by-socket db sock-id) => nil)
        (add-controller! db sock-id addr)
        (get-controller-by-addr db addr) => (contains {:zmq-id sock-id :addr addr})
        (get-controller-by-socket db sock-id) => (contains {:zmq-id sock-id :addr addr})
        (update-controller! db sock-id {:alive INIT-ALIVE})
        (count (get-living-controllers db)) => 1
        (update-controller! db sock-id {:alive 0})
        (count (get-living-controllers db)) => 0
        )
    (fact "about subject state management"
        (fact "bad values return nil"
              (get-subject db nil) => nil
              (get-subject db subject) => nil)
        (let [data {:controller addr :procedure "testing"}]
          (start-subject! db subject data)
          ;; TODO fix time equality checks
          (get-subject db subject) => (contains data)
          (get-subject-by-addr db (:controller data)) => (contains data)
          (stop-subject! db addr)
          (get-subject db subject) => (contains {:controller nil :procedure nil})))
    (fact "about logging messages"
        (let [oid (.toString (object-id))
              message {:subject "acde" :time 12345}]
          (log-message! db "not-a-valid-message-type" oid message) => :rtfm-dtype
          (log-message! db "state-changed" oid message) => :ack
          (log-message! db "state-changed" oid message) => :dup
          (log-message! db "trial-data" oid message) => :ack
          (log-message! db "trial-data" oid message) => :dup
          (mc/count db trial-coll {:subject "acde"}) => 1
          (mc/count db event-coll {:subject "acde"}) => 1))
    (fact "about integrated subject/controller state"
        (let [data {:controller addr :procedure "testing"}]
          (add-controller! db sock-id addr)
          (update-controller! db sock-id {:alive INIT-ALIVE})
          (start-subject! db subject data)
          (get-procedure db subject) => (:procedure data)
          (get-procedure db "some-other-subject") => nil
          (update-controller! db sock-id {:alive 0})
          (get-procedure db subject) => nil))))
