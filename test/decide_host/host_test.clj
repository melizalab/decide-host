(ns decide-host.host-test
  (:require [midje.sweet :refer :all]
            [decide-host.host :refer :all]
            [decide-host.database :as db]
            [monger.core :as mg]
            [monger.collection :as mc]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]
            [cheshire.core :as json]))

(def test-db "decide-test")
(def protocol "decide-host@1")

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

(defn init-context []
  (let [ctx {:database {:uri (str "mongodb://localhost/" test-db)}
             :host {:protocol protocol
                    :clock-tolerance 5000
                    :heartbeat-init-alive 1}}]
    (assoc ctx :database (mg/connect-via-uri (get-in ctx [:database :uri])))))

(defn count-controllers [context]
  (count (db/find-connected-controllers (get-in context [:database :db]))))
(defn reset-database [context]
  (mg/drop-db (get-in context [:database :conn]) test-db))

(let [ctx (init-context)
      {{db :db} :database} ctx
      subject "acde" sock-id "test-ctrl" addr "test" procedure "testing"
      data-id "data-id"
      event-msg (json/encode example-event)
      trial-msg (json/encode example-trial)]
  (with-state-changes [(before :facts (reset-database ctx))]
    (fact "only one connection per controller"
        (connect! ctx sock-id addr) => :ok
        (connect! ctx "another-socket" addr) => :wtf
        ;; we don't need to worry about a socket identity connecting from
        ;; another host because zeromq will not allow multiple connections to
        ;; share the same socket id. What it probably means is that the client
        ;; screwed up. However, duplicates have to be dropped or the state
        ;; will get fubared.
        (connect! ctx sock-id "another-address") => :ok
        (count-controllers ctx) => 1)
    (fact "controllers can send duplicate connection messages"
        (connect! ctx sock-id addr) => :ok
        (connect! ctx sock-id addr) => :ok
        (count-controllers ctx) => 1)
    (fact "controllers can disconnect and reconnect"
        (connect! ctx sock-id addr) => :ok
        (count-controllers ctx) => 1
        (disconnect! ctx sock-id)
        (count-controllers ctx) => 0
        (connect! ctx sock-id addr) => :ok))

  (reset-database ctx)
  (let [clock (tc/to-long (t/now))]
    (fact "processing incoming messages"
        (fact "unrecognized messages rejected"
            (process-message! ctx sock-id "WHODAT") => (just ["RTFM" anything]))
      (fact "unpeered PUB messages rejected"
          (process-message! ctx sock-id "PUB" "state-changed" data-id
                            event-msg) => (just ["WHO?"]))
      (fact "open-peering"
          (fact "rejects wrong protocol"
            (process-message! ctx sock-id "OHAI" "garble") => (just ["RTFM" anything]))
        (fact "detects clock differences"
            (process-message! ctx sock-id "OHAI" protocol addr
                              (json/encode {:time (+ clock 15000)}))
          => (just ["WTF" anything]))
        (fact "accepts correct handshake"
            (process-message! ctx sock-id "OHAI" protocol addr (json/encode {:time clock}))
          => ["OHAI-OK"]
          (count-controllers ctx) => 1)
        (fact "does not accept connections from other sockets when controller is alive"
          (process-message! ctx "other-id" "OHAI" protocol addr (json/encode {:time clock}))
          => (just ["WTF" anything])))
      (fact "use-peering"
          (fact "responds to heartbeats"
              (process-message! ctx sock-id "HUGZ") => ["HUGZ-OK"])
        (fact "rejects bad messages"
          (process-message! ctx sock-id "PUB" "state-changed" data-id "garbage")
          => (just ["RTFM" anything]))
        (fact "accepts event data"
          (process-message! ctx sock-id "PUB" "state-changed" data-id event-msg)
          => (just ["ACK" data-id])
          (mc/count db db/event-coll {:_id data-id}) => 1)
        (fact "drops duplicate data"
          (process-message! ctx sock-id "PUB" "state-changed" data-id event-msg)
          => (just ["DUP" data-id])
          (mc/count db db/event-coll {:_id data-id}) => 1)
        (fact "accepts trial data"
          (process-message! ctx sock-id "PUB" "trial-data" data-id trial-msg)
          => (just ["ACK" data-id])
          (mc/count db db/trial-coll {:_id data-id}) => 1))
      (fact "close-peering"
          (fact "goodbye unregisters controller"
              (process-message! ctx sock-id "KTHXBAI")
            (count-controllers ctx) => 0)))))
