(ns decide-host.notifications
  (:require [postal.core :as post]))

(defn error-msg
  [{{admins :admins
     send? :send?
     trans :transport
     :or {admins []}} :email} msg & additional-recipients]
  (println "E:" msg)
  (when send?
    (let [to (concat admins additional-recipients)
          msg {:from "decide-host"
               :to to
               :subject (str "ERROR: " msg)
               :body (str "An error occurred:\n\n" msg)}
          result (if trans
                   (post/send-message trans msg)
                   (post/send-message msg))]
      (println "I: sending message to" to ":" (:message result)))))
