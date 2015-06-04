(ns decide-host.web-test
  (:require [midje.sweet :refer :all]
            [decide-host.web :refer :all]
            [decide-host.test-data :refer :all]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [clj-time.coerce :as tc]
            [cheshire.core :as json]
            [ring.mock.request :as mock]))

(defn req
  [app method uri]
  (let [result (app (mock/request method uri))]
    (if (= (:status result) 200)
      (:body result)
      result)))

(defn url
  [base & more]
  (apply str "/api" base more))

(defn body=?
  [body]
  (fn [x] (= (:body x) body)))

;; most functionality is tested in unit tests; focus on query parameters here
(let [db (setup-db)
      addr (:addr controller)
      app (wrap-defaults (api-routes {:database {:db db}}) api-defaults)]
  (fact "controllers"
      (req app :get (url "/controllers/" addr)) =>
      (contains (select-keys controller [:addr]))

      (req app :get (url "/controllers/nobody")) => (contains {:status 404})
      (req app :get (url "/controllers/nobody/events")) => empty?)
  (fact "subjects"
      (count (req app :get (url "/subjects"))) => 1
      (count (req app :get (url "/subjects/active"))) => 1
      (count (req app :get (url "/subjects/inactive"))) => 0
      (count (req app :get (url "/subjects?controller=" (:controller subject)))) => 1
      (count (req app :get (url "/subjects?controller=xyzzy"))) => 0
      (req app :get (url "/subjects/" subj-id)) => (contains {:_id subj-uuid})
      (req app :get (url "/subjects/nobody")) => (contains {:status 404})
      (req app :get (url "/subjects/nobody/trials")) => empty?)
  (fact "events"
      (let [uri (url "/controllers/" addr "/events")]
        (count (req app :get uri)) => 3
        (count (req app :get (str uri "?name=experiment"))) => 1
        (count (req app :get (str uri "?name=experiment&name=cue_right_blue"))) => 2
        (count (req app :get (str uri "?before=" (tc/to-long this-hour)))) => 1
        ))
  (fact "trials"
      (let [uri (url "/subjects/" subj-id "/trials")]
        (count (req app :get (str uri))) => 5
        (count (req app :get (str uri "?comment=true"))) => 7
        (count (req app :get (str uri "?comment=starting"))) => 1
        (count (req app :get (str uri "?after=" (tc/to-long this-hour)))) => 4
        (count (req app :get (str uri "?before=" (tc/to-long this-hour)))) => 1))
  (fact "stats"
      (let [uri (url "/subjects/" subj-id "/stats")]
        (req app :get (str uri "/today")) => (contains {:correct 3, :feed-ops 2, :trials 5})
        (req app :get (str uri "/last-hour")) =not=> nil
        (req app :get (str uri "/no-such-thing")) => (contains {:status 404}))))
