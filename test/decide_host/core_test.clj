(ns decide-host.core-test
  (:require [midje.sweet :refer :all]
            [decide-host.core :refer :all]))

(fact "to-string handles byte arrays and nils"
    (to-string nil) => nil
    (to-string "a-string") => "a-string"
    (to-string (.getBytes "a-string")) => "a-string")

(fact "to-hex converts to and from hex representations of a byte array"
    (let [x "abracadabra"]
      (to-string (hex-to-bytes (bytes-to-hex (.getBytes x)))) => x))

(fact "object-id fails gracefully"
    (object-id "garble") => "garble")

(fact "uuid fails gracefully"
    (uuid "garble") => "garble")

(fact "convert-subject-uuid only replaces subject uuid if present"
    (let [uuid-str "b1367245-a528-4e65-82f6-5363f87166ac"
          uuid-obj (uuid uuid-str)]
      (convert-subject-uuid {:unrelated "data"}) => (just {:unrelated "data"})
      (convert-subject-uuid {:subject "not-a-uuid"}) => (just {:subject "not-a-uuid"})
      (convert-subject-uuid {:subject uuid-str}) => {:subject uuid-obj}))

(fact "merge-in merges nested maps"
    (merge-in {:a 1} {}) => {:a 1}
    (merge-in {:a 1} {:b 2}) => {:a 1 :b 2}
    (merge-in {:a {:b 1}} {:a {:c 2}}) => {:a {:b 1 :c 2}})

(fact "print-kv serializes maps and maplikes"
    (print-kv nil) => ""
    (print-kv {}) => ""
    (print-kv {:a 1}) => "a: 1"
    (print-kv {:stuff "and nonsense"}) => "stuff: and nonsense"
    (print-kv {:a 1 :b 2}) => (some-checker "a: 1, b: 2" "b: 2, a: 1")
    (print-kv [[:a 1] [:b 2]]) => "a: 1, b: 2")
