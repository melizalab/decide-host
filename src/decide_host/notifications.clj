(ns decide-host.notifications
  (require [postal.core :as post]
           [clojure.stacktrace :as st]
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

(defn fatal-error
  "Attempts to send an error message and then terminates the program"
  [context tr]
  (st/print-stack-trace tr)
  (let [msg (with-out-str (st/print-throwable tr))]
    (try (error-msg context (str "uncaught exception: " msg))))
  (System/exit -1))
