(ns decide-host.handlers
  "Functions that perform asynchronous, non-core responses to controller events"
  (:require [decide-host.database :as db]
            [clojure.core.async :as async :refer [>! <! >!! <!!]]
            [clojure.core.match :refer [match]]))

(defn add-handler
  "Adds an asynchronous handler f to pub for one or more topics"
  [context f & topics]
  (let [{{pub :event-pub} :msg-handler} context]
    (when-let [tops (seq topics)]
      (let [chan (async/chan)]
        (doseq [topic topics] (async/sub pub topic chan))
        (async/go-loop [data (<! chan)]
          (when data
            (f context data)
            (recur (<! chan))))))))

(defn update-subject!
  "Updates subject documents with event data"
  [context data]

  (let [{{db :db} :database} context]
    (match
     [data]
     [{:topic :state-changed :name "experiment"
       :subject s :procedure p :user u :time t :addr a}]
     (let [{subj :_id proc :procedure} (db/find-subject-by-addr db a)]
       (println "D: update-subject!" data)
       (cond
         (and s (not= p proc))
         (do
           (println "I:" a "-" s "started running" p)
           (db/start-subject! db s {:procedure p :controller a :user u :start-time t}))
         (and (nil? s) proc)
         (do
           (println "I:" a "-" subj "stopped running" proc)
           (db/stop-subject! db a t))))

     [{:topic :state-changed :name "hopper" :up state :addr a :time t}]
     (when state
       (db/update-subject-by-controller! db a {:last-fed t}))

     [{:topic :state-changed :addr a :time t}]
     (db/update-controller! db a {:last-event t})

     [{:topic :trial-data :subject s :trial n :time t}]
     (db/update-subject! db s {:last-trial t})

     [{:topic :trial-data :subject s :experiment e}]
     (db/update-subject! db s {:experiment e})

     :else nil)))
