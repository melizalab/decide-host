(ns decide-host.core
  "Core data functions used by decide-host"
  (:import [org.bson.types ObjectId]
           [java.util UUID]))

(def version (-> "project.clj" slurp read-string (nth 2)))

(defn to-string [x] (when-not (nil? x) (String. x)))

(defn bytes-to-hex [x] (when-not (nil? x) (apply str (map #(format "%02x" %) x))))

(defn hex-to-bytes [x] (.toByteArray (BigInteger. x 16)))

(defn object-id
  "Returns a new BSON ObjectID, either newly generated or from a string
  argument. If the string can't be converted to an ObjectID, it's returned
  as-is."
  ([] (ObjectId.))
  ([x] (try (ObjectId. x)
            (catch IllegalArgumentException e x))))

(defn uuid
  "Converts s to UUID type if possible. Otherwise returns the original argument"
  [s]
  (try (UUID/fromString s)
       (catch IllegalArgumentException e s)
       (catch NullPointerException e s)))

(defn convert-subject-uuid
  "Attempts to convert :subject field if present to a uuid"
  [map]
  (if-let [subj (uuid (:subject map))]
    (assoc map :subject subj)
    map))

(defn merge-in
  "Returns a map that consists of the rest of the maps conj-ed onto the first.
  If a key occurs in more than one map, the mappings from the
  latter (left-to-right) will be combined with the mapping in the result by
  calling merge."
  [& maps]
  (apply merge-with merge maps))
