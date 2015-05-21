(ns decide-host.client-test
  (:require [midje.sweet :refer :all]
            [decide-host.core :refer [start-zmq-server to-string protocol]]
            [decide-host.database :refer [connect!]]
            [com.keminglabs.zmq-async.core :refer [register-socket!]]
            [clojure.core.async :as async :refer [>! <! >!! <!!]]
            [monger.core :as mg]
            [cheshire.core :as json]))

(def test-db "decide-test")
(def test-uri (str "mongodb://localhost/" test-db))
(def server-address "tcp://127.0.0.1:5556")

(defn connect-client [addr]
  (let [zmq-in (async/chan (async/sliding-buffer 64))
        zmq-out (async/chan (async/sliding-buffer 64))]
    (register-socket! {:in zmq-in :out zmq-out :socket-type :dealer
                       :configurator (fn [socket]
                                       (.setIdentity socket (.getBytes "tester-ctrl"))
                                       (.connect socket addr))})
    {:in zmq-in
     :out zmq-out}))

(defn start-server [addr]
  (let [{:keys [conn db]} (connect! test-uri)]
    (mg/drop-db conn test-db)
    (start-zmq-server addr)))

(defn parse-message [rep]
  (if (seq? rep)
    (map to-string rep)
    (to-string rep)))

;; blocking request for testing communication. Times out after 1 second.
(defn req [in out & msg]
  (>!! in msg)
  (let [[rep c] (async/alts!! [out (async/timeout 1000)])]
    (parse-message rep)))

(defn hugger
  "Replies to heartbeat messages on out. Returns an atom that tracks the count of
  heartbeats received. Set this to -2 or less to stop the loop"
  [in out]
  (let [hugz (atom 0)]
    (async/go
     (while (> @hugz -1)
       (let [msg (parse-message (<! out))]
         (when (= msg "HUGZ")
           (>! in "HUGZ-OK")
           (swap! hugz inc)))))
    hugz))

;; most of the request-reply logic is tested in core-test and is not duplicated
;; here. Mostly we want to check that heartbeating works as expected

(fact-group :integration "client-server integration"
  (let [{:keys [in out]} (connect-client server-address)
        srv-in (start-server server-address)]
    (fact "client connects to server"
        (req in out "OHAI" protocol "test") => "OHAI-OK")
    (fact "client receives heartbeats"
        (let [hugz (hugger in out)]
          (<!! (async/timeout 4000))    ; wait for a few messages to accumulate
          @hugz =not=> 0))
    (fact "server doesn't acknowledge disconnect"
        (req in out "KTHXBAI") => nil)
    (async/close! srv-in)
    (async/close! in)))
