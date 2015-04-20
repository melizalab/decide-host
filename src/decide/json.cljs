(ns decide.json
  "Functions for writing records to newline-delimited JSON format"
  (:require [cljs.nodejs :as node]))

(def fs (js/require "fs"))
(def console (js/require "winston"))

(defn- write-stream [path]
  (.info console "opened" path "for logging")
  (.createWriteStream fs path (clj->js {:flags "a" :encoding "utf-8"})))

(def logfiles (atom {}))

(defn write-record!
  "Writes object or map [record] as serialized, line-delimited JSON to [file]"
  [path record]
  (let [*w* (or (get @logfiles path)
                (get (swap! logfiles assoc path (write-stream path)) path))]
    (.write *w* (str (JSON/stringify (clj->js record)) "\n"))))

(defn read-json
  "Reads json from [file] and returns as map"
  [file]
  (js->clj (JSON/parse (.readFileSync fs file)) :keywordize-keys true))
