(ns decide-host.handlers
  "Functions that perform asynchronous, non-core responses to controller events"
  (:gen-class)
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

         :else nil))
