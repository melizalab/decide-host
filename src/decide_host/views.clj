(ns decide-host.views
  "HTML views"
  (:require [decide-host.core :refer [print-kv version]]
            [clj-time.format :as tf]
            [clj-time.core :as t]
            [hiccup.core :refer [html]]
            [hiccup.page :refer [html5 include-css include-js]]))

(def ^:private datetime-formatter (tf/formatter-local "hh:mm:ss aa zzz MM-dd-YYYY"))

(defn mailto-link [user] [:a {:href (str "mailto:" user)} user])
(defn span-value [v] [:span.success (if (map? v) (print-kv v) v)])
(defn span-time [t] [:span.success (tf/unparse datetime-formatter
                                               (t/to-time-zone t (t/default-time-zone)))])
(defn server-time [] (list "server time: " (span-time (t/now))))

(defn controller-link [{addr :addr}]
  [:a {:href (str "api/controllers/" addr "/device")} addr])
(defn controller
  [c]
  (list (controller-link c)
        [:ul.property-list
         (when-let [t (:last-event c)]
           [:li "last event: " (span-time t)])]))
(defn controller-list [controllers]
  (for [c controllers] [:li {:id (:addr c)} (controller c)]))

(defn subject
  [{s :_id a :controller p :procedure e :experiment u :user
    t :last-trial today :today last-hour :last-hour :as subj}]
  [:li {:id s} s
   [:ul.property-list
    (when a [:li "controller: " (controller-link {:addr a})])
    (when u [:li "user: " (mailto-link u)])
    (when p
      (list
       [:li "procedure: " (span-value p)]
       [:li "experiment: " (span-value e)]
       [:li "today: " (span-value today)]
       [:li "last hour: " (span-value last-hour)]))
    (when t
      [:li "last trial: " (span-time t)])]])
(defn subject-list [subjs] (map subject subjs))

(defn console
  [{:keys [controllers active-subjects inactive-subjects]}]
  (list
   [:div.container
    [:div.row
     [:p "version: " (span-value version)]
     [:p#time (server-time)]]
    [:div.row
     [:div#subjects.col-md-6
      [:p.ahead "Active Subjects"]
      [:ul#active-subject-list.item-list (subject-list active-subjects)]
      [:a.ahead {:href "inactive" } "Inactive Subjects"]]
     [:div#controllers.col-md-6
      [:p.ahead "Registered Controllers"]
      [:ul#controller-list.item-list (controller-list controllers)]]]]))

(defn index
  [data]
  (html5
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:content "initial-scale=1.0, user-scalable=no", :name "viewport"}]
    [:meta {:content "yes", :name "apple-mobile-web-app-capable"}]
    [:meta {:content "black", :name "apple-mobile-web-app-status-bar-style"}]
    [:title "Decide Directory"]
    [:link {:rel "icon" :type "image/ico" :href "/static/images/favicon.ico"}]
    (include-css "/static/css/bootstrap.min.css")
    (include-css "/static/css/bootstrap-theme.min.css")
    (include-css "/static/css/directory.css")
    (include-js "/static/js/jquery-2.1.4.min.js")
    (include-js "/static/js/interface.js")]
   [:body
    [:div.container
     [:div.row
      [:h3 "Decide Directory"]]
     [:div#console.row (console data)]]]))
