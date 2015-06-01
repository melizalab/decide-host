(ns decide-host.web-test
  (:require [midje.sweet :refer :all]
            [decide-host.web :refer :all]
            [decide-host.test-data :refer :all]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [cheshire.core :as json]
            [clj-time.coerce :as tc]
            [ring.mock.request :as mock]))

(defn req
  [app method uri]
  (:body (app (mock/request method uri))))

(defn body=?
  [body]
  (fn [x] (= (:body x) body)))

(fact "about parse-comment-constraint"
    (parse-comment-constraint {:a 1}) => {:a 1 :comment nil}
    (parse-comment-constraint {:a 1 :comment "true"}) => {:a 1}
    (parse-comment-constraint {:a 1 :comment "starting"}) => {:a 1 :comment "starting"})

;; most functionality is tested in unit tests; focus on query parameters here
(let [db (setup-db)
      addr (:addr controller)
      app (wrap-defaults (api-routes {:database {:db db}}) api-defaults)]
  (fact "controllers"
      (req app :get (str "/controllers/" addr)) =>
      (contains (select-keys controller [:addr]))

      (req app :get (str "/controllers/nobody")) => nil
      (req app :get (str "/controllers/nobody/events")) => empty?)
  (fact "subjects"
      (count (req app :get "/subjects")) => 1
      (count (req app :get "/subjects/active")) => 1
      (count (req app :get "/subjects/inactive")) => 0
      (count (req app :get (str "/subjects?controller=" (:controller subject)))) => 1
      (count (req app :get "/subjects?controller=xyzzy")) => 0
      (req app :get (str "/subjects/" subj-id)) => (contains {:_id subj-uuid})
      (req app :get (str "/subjects/nobody")) => nil
      (req app :get (str "/subjects/nobody/trials")) => empty?
      (req app :get (str "/subjects/nobody/stats")) => empty?)
  (fact "events"
      (let [uri (str "/controllers/" addr "/events")]
        (count (req app :get uri)) => 3
        (count (req app :get (str uri "?name=experiment"))) => 1
        (count (req app :get (str uri "?name=experiment&name=cue_right_blue"))) => 2
        (count (req app :get (str uri "?before=" (tc/to-long this-hour)))) => 1
        ))
  (fact "trials"
      (let [uri (str "/subjects/" subj-id "/trials")]
        (count (req app :get uri)) => 5
        (count (req app :get (str uri "?comment=true"))) => 7
        (count (req app :get (str uri "?comment=starting"))) => 1
        (count (req app :get (str uri "?after=" (tc/to-long this-hour)))) => 4
        (count (req app :get (str uri "?before=" (tc/to-long this-hour)))) => 1))
  (fact "stats"
      (let [uri (str "/subjects/" subj-id "/stats")]
        (count (req app :get uri)) => 3
        (count (req app :get (str uri "?after=" (tc/to-long this-hour)))) => 1
        (count (req app :get (str uri "?before=" (tc/to-long this-hour)))) => 2)))
