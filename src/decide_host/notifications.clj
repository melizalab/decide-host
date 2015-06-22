(ns decide-host.notifications
  (require [postal.core :as post]
           [decide-host.core :refer [log]]))

(defn error-msg
  [{{admins :admins
     send? :send?
     trans :transport
     :or {admins []}} :email} msg & additional-recipients]
  (log "ERROR:" msg)
  (when send?
    (let [to (concat admins additional-recipients)
          msg {:from "decide-host"
               :to to
               :subject (str "ERROR: " msg)
               :body (str "This is decide-host. An error seems to have occurred:\n\n"
                          msg)}
          result (if trans
                   (post/send-message trans msg)
                   (post/send-message msg))]
      (log "sending message to" to ":" (:message result)))))
