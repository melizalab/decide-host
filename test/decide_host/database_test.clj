(ns decide-host.database-test
  (:require [midje.sweet :refer :all]
            [decide-host.database :refer :all]
            [monger.core :as mg]
            [monger.collection :as mc]
            [monger.result :refer [ok? updated-existing?]]
            [clj-time.core :as t]))

(def test-db "decide-test")
(def test-uri (str "mongodb://localhost/" test-db))

(fact "bad uris generate exceptions"
    (connect! "garbledegook") => (throws Exception)
    (connect! "mongodb://localhost:1234/decide-test") => (throws Exception))

(let [{:keys [conn db]} (connect! test-uri)
      subject "acde" sock-id "test-ctrl" addr "test" tt (t/now)]
  (mg/drop-db conn test-db)
  (with-state-changes [(after :facts (mg/drop-db conn test-db))]
    (fact "about controller state management"
        (fact "bad values return nil"
              (get-controller-by-addr nil) => nil
              (get-controller-by-socket nil) => nil
              (get-controller-by-addr addr) => nil
              (get-controller-by-socket sock-id) => nil)
        (ok? (add-controller! sock-id addr)) => truthy
        (get-controller-by-addr addr) => (contains {:zmq-id sock-id :alive true :_id addr})
        (get-controller-by-socket sock-id) => (contains {:zmq-id sock-id :alive true :_id addr})
        (count (get-living-controllers)) => 1
        (updated-existing? (controller-alive! sock-id)) => truthy
        (:last-seen (get-controller-by-addr addr)) => #(t/after? % tt)
        (updated-existing? (controller-alive! sock-id false)) => truthy
        (count (get-living-controllers)) => 0)
    (fact "about subject state management"
        (fact "bad values return nil"
              (get-subject nil) => nil
              (get-subject subject) => nil)
        (let [data {:controller addr :procedure "testing"}]
          (ok? (start-subject! subject data)) => truthy
          ;; TODO fix time equality checks
          (get-subject subject) => (contains data)
          (get-subject-by-addr (:controller data)) => (contains data)
          (ok? (stop-subject! addr)) => truthy
          (get-subject subject) => (contains {:controller nil :procedure nil})))
    (fact "about logging messages"
        (let [oid (.toString (object-id))
              message {:subject "acde" :time 12345}]
          (log-message! "nonsense" oid message) => :rtfm
          (log-message! "state-changed" oid message) => :ack
          (log-message! "state-changed" oid message) => :dup
          (log-message! "trial-data" oid message) => :ack
          (log-message! "trial-data" oid message) => :dup
          (mc/count db trial-coll {:subject "acde"}) => 1
          (mc/count db event-coll {:subject "acde"}) => 1))
    (fact "about integrated subject/controller state"
        (let [data {:controller addr :procedure "testing"}]
          (ok? (add-controller! sock-id addr)) => truthy
          (ok? (start-subject! subject data)) => truthy
          (get-procedure subject) => (:procedure data)
          (get-procedure "some-other-subject") => nil
          (updated-existing? (controller-alive! sock-id false)) => truthy
          (get-procedure subject) => nil
          ))))
