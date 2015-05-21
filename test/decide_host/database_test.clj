(ns decide-host.database-test
  (:require [midje.sweet :refer :all]
            [decide-host.database :refer :all]
            [monger.core :as mg]
            [monger.collection :as mc]
            [monger.result :refer [ok? updated-existing?]]
            [clj-time.core :as t]))

(def test-db "decide-test")
(def test-uri (str "mongodb://localhost/" test-db))
(def INIT-ALIVE 5)

(fact "object-id fails gracefully"
    (object-id "garble") => "garble")

(fact "uuid fails gracefully"
    (uuid "garble") => "garble")

(fact "convert-subject-uuid only replaces subject uuid if present"
    (let [uuid-str "b1367245-a528-4e65-82f6-5363f87166ac"
          uuid-obj (uuid uuid-str)]
      (convert-subject-uuid {:unrelated "data"}) => (just {:unrelated "data"})
      (convert-subject-uuid {:subject "not-a-uuid"}) => (just {:subject "not-a-uuid"})
      (convert-subject-uuid {:subject uuid-str}) => {:subject uuid-obj}))

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
        (ok? (add-controller! sock-id addr :alive INIT-ALIVE)) => truthy
        (get-controller-by-addr addr) => (contains {:zmq-id sock-id :alive INIT-ALIVE :_id addr})
        (get-controller-by-socket sock-id) => (contains {:zmq-id sock-id :alive INIT-ALIVE :_id addr})
        (count (get-living-controllers)) => 1
        (update-controller! sock-id {:alive 0})
        (count (get-living-controllers)) => 0
        )
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
          (ok? (add-controller! sock-id addr :alive INIT-ALIVE)) => truthy
          (ok? (start-subject! subject data)) => truthy
          (get-procedure subject) => (:procedure data)
          (get-procedure "some-other-subject") => nil
          (update-controller! sock-id {:alive 0})
          (get-procedure subject) => nil
          ))))
