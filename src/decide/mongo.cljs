(ns decide.mongo
  "interface to mongodb")

(def mongodb (js/require "mongodb"))

(def ^:export Client (aget mongodb "MongoClient"))
(def ^:export Collection (aget mongodb "Collection"))
(def ^:export ObjectID (aget mongodb "ObjectID"))

(defn connect
  [uri callback]
  (.connect Client uri callback))

(defn collection [db coll]
  (Collection. db coll))

(defn save!
  "Saves a doc to database coll, optionally calling callback when done"
  ([coll doc]
     (let [doc (clj->js doc)]
       (.save coll doc)))
  ([coll doc callback]
     (let [doc (clj->js doc)
           opts (js-obj "journal" true)]
       (.save coll doc opts callback))))

(defn update!
  "Updates or inserts a document in coll, calling callback when done"
  [coll query update callback]
  (let [opts (js-obj "journal" true "upsert" true)]
    (.update coll (clj->js query) (clj->js update) opts callback)))

(defn find-all
  "Returns all documents matching query in coll"
  [coll query callback]
  (-> (.find coll (clj->js query))
      (.toArray callback)))

(defn find-one
  "Returns first document matching query in coll or nil if no match"
  [coll query callback]
  (.findOne coll (clj->js query)
            (fn [err result] (callback (js->clj result :keywordize-keys true)))))
