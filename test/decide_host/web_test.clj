(ns decide-host.web-test
  (:require [midje.sweet :refer :all]
            [decide-host.web :refer :all]))

(fact "about parse-comment-constraint"
    (parse-comment-constraint {:a 1}) => {:a 1 :comment nil}
    (parse-comment-constraint {:a 1 :comment "true"}) => {:a 1}
    (parse-comment-constraint {:a 1 :comment "starting"}) => {:a 1 :comment "starting"})
