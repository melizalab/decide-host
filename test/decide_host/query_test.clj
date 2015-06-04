(ns decide-host.query-test
  (:require [midje.sweet :refer :all]
            [decide-host.query :refer :all]
            [clj-time.coerce :as tc]
            [decide-host.test-data :refer [subj-id subj-uuid]]))

(fact "about comments"
    (comments {:match {:a 1}}) => {:match {:a 1 :comment nil}}
    (comments {:match {:a 1 :comment "true"}}) => {:match {:a 1}}
    (comments {:match {:a 1 :comment "starting"}}) => {:match {:a 1 :comment "starting"}})

(fact "about sequences"
    (sequences {:match {:a 1}}) => {:match {:a 1}}
    (sequences {:match {:a [1 2]}}) => {:match {:a {"$in" [1 2]}}}
    (sequences {:match {:a [1 2] :b 3}}) => {:match {:a {"$in" [1 2]} :b 3}})

(fact "about before-time and after-time"
    (before-time {:match {:a 1}}) => {:match {:a 1}}
    (before-time {:match {:a 1 :before "blarg"}}) => {:match {:a 1 :before "blarg"}}
    (let [tt 1432753029026
          tp (tc/from-long tt)]
      (before-time {:match {:a 1 :before (str tt)}}) => {:match {:a 1 :time {"$lte" tp}}}
      (after-time {:match {:a 1 :after (str tt)}}) => {:match {:a 1 :time {"$gte" tp}}}))

(fact "about uuid"
    (subject-uuid {:match {:a 1}}) => {:match {:a 1}}
    (subject-uuid {:match {:subject 1}}) => {:match {:subject 1}}
    (subject-uuid {:match {:subject subj-id}}) => {:match {:subject subj-uuid}})

(fact "about parse"
    (let [tt 1432753029026
          tp (tc/from-long tt)]
      (parse {:a 1 :before (str tt)}) => {:match {:a 1 :time {"$lte" tp} :comment nil}}
      (parse {:a 1 :before (str tt)} :actions [:before]) => {:match {:a 1 :time {"$lte" tp}}}
      (parse {:a 1 :limit 10 :comment true}) => {:limit 10 :match {:a 1}}))
