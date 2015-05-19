(ns decide-host.core-test
  (:require [midje.sweet :refer :all]
            [decide-host.core :refer :all]
            [decide-host.database :as db]
            [monger.core :as mg]
            [cheshire.core :as json]))

(def test-db "decide-test")
(def test-uri (str "mongodb://localhost/" test-db))

(fact "to-string handles byte arrays and nils"
    (to-string nil) => nil
    (to-string "a-string") => "a-string"
    (to-string (.getBytes "a-string")) => "a-string")

(fact "decode-pub correctly handles correct and incorrect JSON"
    (decode-pub nil) => nil
    (decode-pub "garbage") => nil
    (decode-pub "{\"a\":1}") => {:a 1})

(let [{:keys [conn db]} (db/connect! test-uri)
      subject "acde" sock-id "test-ctrl" addr "test"]
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
        (count (db/get-living-controllers)) => 1)
    (fact "controllers can disconnect and reconnect"
        (connect! sock-id addr) => :ok
        (count (db/get-living-controllers)) => 1
        (disconnect! sock-id) => truthy
        (count (db/get-living-controllers)) => 0
        (connect! sock-id addr) => :ok)
    (fact "controllers can reconnect after errors"
        (connect! sock-id addr) => :ok
        (connection-error! (db/get-controller-by-addr addr)) => truthy
        (count (db/get-living-controllers)) => 0
        (connect! sock-id addr) => :ok)))
