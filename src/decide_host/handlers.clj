(ns decide-host.handlers
  "Functions that perform asynchronous, non-core responses to controller events"
  (:require [decide-host.database :as db]
            [clojure.core.async :as async :refer [>! <! >!! <!!]]
            [clojure.core.match :refer [match]]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]))

(defn update-subject!
  "Updates subject documents with event data"
  [data]
  (println "D: update-subject!" data)
  (match [data]

         [{:topic :state-changed :name "experiment"
           :subject s :procedure p :user u :time t :addr a}]
         (if-not (nil? s)
             (db/start-subject! s {:procedure p :controller a :user u :start-time t})
             (db/stop-subject! a t))

         [{:topic :state-changed :name "hopper" :up state :addr a :time t}]
         (when state
           (db/update-subject-by-controller! a {:last-fed t}))

         [{:topic :state-changed :addr a :time t}]
         (db/update-subject-by-controller! a {:last-event t})

         [{:topic :trial-data :subject s :trial n :time t}]
         (db/update-subject! s {:last-trial t})

         [{:topic :trial-data :subject s :experiment e}]
         (db/update-subject! s {:experiment e})

         :else nil))
