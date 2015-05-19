(ns decide-host.core-test
  (:require [midje.sweet :refer :all]
            [decide-host.core :refer :all]
            [cheshire.core :as json]))

(fact "to-string handles byte arrays and nils"
    (to-string nil) => nil
    (to-string "a-string") => "a-string"
    (to-string (.getBytes "a-string")) => "a-string")

(fact "decode-pub correctly handles correct and incorrect JSON"
    (decode-pub nil) => nil
    (decode-pub "garbage") => nil
    (decode-pub "{\"a\":1}") => {:a 1})
