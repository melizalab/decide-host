(ns decide-host.handlers
  "Functions that perform asynchronous, non-core responses to controller events"
  (:require [decide-host.database :as db]
            [clojure.core.async :as async :refer [>! <! >!! <!!]]
            [clojure.core.match :refer [match]]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]))

(defn add-handler
  "Adds an asynchronous handler f to pub for one or more topics"
  [pub f & topics]
  (when-let [tops (seq topics)]
    (let [chan (async/chan)]
      (dorun (map #(async/sub pub % chan) topics))
      (async/go-loop [data (<! chan)]
        (when data
          (f data)
          (recur (<! chan)))))))

(defn update-subject!
  "Updates subject documents with event data"
  [db data]
  #_(println "D: update-subject!" data)
  (match [data]

         [{:topic :state-changed :name "experiment"
           :subject s :procedure p :user u :time t :addr a}]
         (if-not (nil? s)
             (db/start-subject! db s {:procedure p :controller a :user u :start-time t})
             (db/stop-subject! db a t))

         [{:topic :state-changed :name "hopper" :up state :addr a :time t}]
         (when state
           (db/update-subject-by-controller! db a {:last-fed t}))

         [{:topic :state-changed :addr a :time t}]
         (db/update-subject-by-controller! db a {:last-event t})

         [{:topic :trial-data :subject s :trial n :time t}]
         (db/update-subject! db s {:last-trial t})

         [{:topic :trial-data :subject s :experiment e}]
         (db/update-subject! db s {:experiment e})

         :else nil))
