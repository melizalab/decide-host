(ns decide-host.core-test
  (:require [midje.sweet :refer :all]
            [decide-host.core :refer :all]
            [decide-host.database :as db]
            [monger.core :as mg]
            [monger.collection :as mc]
            [cheshire.core :as json]))

(def test-db "decide-test")
(def test-uri (str "mongodb://localhost/" test-db))

(def example-event {:name "cue_left_green,"
                    :time 1431981358138587
                    :trigger nil
                    :brightness 0})
(def example-trial {:name "gng"
                    :trial 2655
                    :stimulus ["Ar3edit"]
                    :result nil
                    :subject "bef9a524-10cf-4cb2-8f6d-d1eeed3d3725"
                    :time 1431981358138587})

(defn count-controllers [] (count (db/get-living-controllers)))

(fact "to-string handles byte arrays and nils"
    (to-string nil) => nil
    (to-string "a-string") => "a-string"
    (to-string (.getBytes "a-string")) => "a-string")

(fact "to-hex converts to and from hex representations of a byte array"
    (let [x "abracadabra"]
      (to-string (hex-to-bytes (bytes-to-hex (.getBytes x)))) => x))

(fact "decode-pub correctly handles correct and incorrect JSON"
    (decode-pub nil) => nil
    (decode-pub "garbage") => nil
    (decode-pub "{\"a\":1}") => {:a 1})

(let [{:keys [conn db]} (db/connect! test-uri)
      subject "acde" sock-id "test-ctrl" addr "test" procedure "testing"
      data-id "data-id"
      event-msg (json/encode example-event)
      trial-msg (json/encode example-trial)]
  (mg/drop-db conn test-db)
  (with-state-changes [(after :facts (mg/drop-db conn test-db))]
    (fact "only one connection per controller"
        (connect! sock-id addr) => :ok
        (connect! "another-socket" addr) => :wtf
        ;; we don't need to worry about a socket identity connecting from
        ;; another host because zeromq will not allow multiple connections to
        ;; share the same socket id. What it probably means is that the client
        ;; screwed up. However, duplicates have to be dropped or the state
        ;; will get fubared.
        (connect! sock-id "another-address") => :ok
        (count-controllers) => 1)
    (fact "controllers can send duplicate connection messages"
        (connect! sock-id addr) => :ok
        (connect! sock-id addr) => :ok
        (count-controllers) => 1)
    (fact "controllers can disconnect and reconnect"
        (connect! sock-id addr) => :ok
        (count-controllers) => 1
        (disconnect! sock-id)
        (count-controllers) => 0
        (connect! sock-id addr) => :ok))
  (fact "processing incoming messages"
    (fact "unrecognized messages rejected"
        (process-message! sock-id "WHODAT") => (just ["RTFM" anything]))
    (fact "unpeered PUB messages rejected"
        (process-message! sock-id "PUB" "state-changed" data-id
                          event-msg) => (just ["WHO?"]))
    (fact "open-peering"
        (fact "rejects wrong protocol"
            (process-message! sock-id "OHAI" "garble") => (just ["RTFM" anything]))
      (fact "accepts correct handshake"
          (process-message! sock-id "OHAI" protocol addr) => ["OHAI-OK"]
          (count-controllers) => 1)
      (fact "does not accept connections from other sockets when controller is alive"
          (process-message! "other-id" "OHAI" protocol addr) => (just ["WTF" anything])))
    (fact "use-peering"
        (fact "responds to heartbeats"
            (process-message! sock-id "HUGZ") => ["HUGZ-OK"])
      (fact "accepts event data"
          (process-message! sock-id "PUB" "state-changed" data-id
                            event-msg) => (just ["ACK" data-id])
          (mc/count db db/event-coll {:_id data-id}) => 1)
      (fact "drops duplicate data"
          (process-message! sock-id "PUB" "state-changed" data-id
                            event-msg) => (just ["DUP" data-id])
          (mc/count db db/event-coll {:_id data-id}) => 1)
      (fact "accepts trial data"
          (process-message! sock-id "PUB" "trial-data" data-id
                            trial-msg) => (just ["ACK" data-id])
          (mc/count db db/trial-coll {:_id data-id}) => 1))
    (let [data {:name "experiment"
                :time 1432069827152495
                :procedure procedure
                :subject subject}]
      (fact "registers subject from experiment events"
        (db/get-procedure subject) => nil
        (process-message! sock-id "PUB" "state-changed" "expt-1" (json/encode data))
        (db/get-procedure subject) => procedure
        (process-message! sock-id "PUB" "state-changed" "expt-2"
                          (json/encode (assoc data :procedure nil :subject nil)))
        (db/get-procedure subject) => nil))
    (fact "close-peering"
        (fact "goodbye unregisters controller"
            (process-message! sock-id "KTHXBAI")
            (count-controllers) => 0))))
