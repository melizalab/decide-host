(ns decide-host.query
  "Translating http query parameters to mongodb database queries"
  (:refer-clojure :exclude [time])
  (:require [monger.operators :refer :all]
            [clojure.core.incubator :refer [dissoc-in]]
            [clj-time.coerce :as tc]
            [decide-host.core :refer [uuid]]))

(defn comments
  "If [:match :comment] is 'true', removes any filter for comments, if nil, filters out
  comments; if any other value, queries for match."
  [{m :match :as query}]
  (let [m (case (:comment m)
            ("true" "True" true) (dissoc m :comment)
            nil (assoc m :comment nil)
            m)]
    (assoc query :match m)))

(defn to-$in [x] (if (sequential? x) {$in x} x))

(defn sequences
  "Replaces array parameters in :match with $in expressions"
  [{m :match :as query}]
  (assoc query :match (into {} (map (fn [[k v]] [k (to-$in v)]) m))))

(defn- time
  "Parses parameter to appropriate mongodb query operator."
  [{m :match :as query} key op]
  (try
    (let [{before key time :time} m
          mark (tc/from-long (Long. before))
          m (dissoc m key)]
      (assoc query :match (assoc m :time (assoc time op mark))))
    (catch NumberFormatException e query)))

(defn before-time [query] (time query :before $lte))
(defn after-time [query] (time query :after $gte))

(defn subject-uuid
  [{{u :subject} :match :as query}]
  (if u
    (assoc-in query [:match :subject] (uuid u))
    query))

(defn limit-to
  [{m :match :as query}]
  (if-let [limit (:limit m)]
    (assoc query :match (dissoc m :limit) :limit (Long. limit))
    query))

#_(defn sort-by
  "Sort parameters should look like "
  [{m :match s :sort :as query}]
  (if-let [sort (:sort m)]))

(def ^:private fmap {:before before-time
                     :after after-time
                     :comment comments
                     :limit-to limit-to
                     :uuid subject-uuid
                     :sequences sequences})

(defn parse
  "Parses a map of query parameters and returns map with :match, :sort,
  and :limit set to values that can be used in a mongodb query"
  [params & {:keys [actions]
             :or {actions (keys fmap)}}]
  (assert (map? params) "parameters must be a map")
  ;; start with everything in match
  (loop [acts actions q {:match params}]
    (if-let [f (fmap (first acts))]
      (recur (next acts) (f q))
      q)))
