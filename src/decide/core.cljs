(ns decide.core
  (:require [decide.json :as json]
            [goog.string :as gstring]
            [goog.string.format]
            [shodan.console :as console :include-macros true]))

(def os (js/require "os"))
(def path (js/require "path"))
(def version (aget (js/require "../package.json") "version"))

(defn config-path [name] (.resolve path js/__dirname ".." "config" name))
(def config (json/read-json (config-path "host-config.json")))

(defn strkey
  "Helper fn that converts keywords into strings"
  [x]
  (if (keyword? x)
    (name x)
    x))

;; from http://keminglabs.com/blog/angular-cljs-weather-app
;; allows js objects to be treated like maps
(extend-type object
  ILookup
  (-lookup
    ([o k]
       (aget o (strkey k)))
    ([o k not-found]
       (let [s (strkey k)]
         (if (goog.object.containsKey o s)
           (aget o s)
           not-found)))))

(def mailer (.createTransport (js/require "nodemailer") (:mail_transport config)))
(defn mail [from to subject message]
  (.sendMail mailer (js-obj "from" (str from "@" (.hostname os))
                            "to" to
                            "subject" subject
                            "text" message)
             (fn [err info]
               (if err
                 (console/error "unable to send email:" err)
                 (console/log "sent email to" to)))))

(defn format [fmt & args]
  (apply gstring/format fmt args))
