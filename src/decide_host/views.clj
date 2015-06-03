(ns decide-host.views
  "HTML views"
  (:require [decide-host.core :refer [print-kv]]
            [clj-time.format :as tf]
            [hiccup.core :refer [html]]
            [hiccup.page :refer [html5 include-css include-js]]))

(def ^:private time-formatter (tf/formatter "HH:mm:SS MM-dd-YYYY"))

(defn mailto-link [user] [:a {:href (str "mailto:" user)} user])
(defn controller-link [{addr :addr}] [:a {:href (str "/controllers/" addr "/device")} addr])
(defn span-value [v] [:span.success (if (map? v) (print-kv v) v)])
(defn span-time [t] [:span.success (tf/unparse time-formatter t)])

(defn controller-list [controllers]
  (for [c controllers]
    [:li (controller-link c)
     [:ul.property-list
      (when-let [t (:last-event c)]
        [:li "last event: " (span-time t)])]]))

(defn subject
  [{s :_id a :controller p :procedure e :experiment u :user
    t :last-trial today :today last-hour :last-hour :as subj}]
  (println "D: subject:" subj)
  [:li s
   [:ul.property-list
    (when a [:li "controller: " (controller-link {:addr a})])
    (when u [:li "user: " (mailto-link u)])
    (when p
      (list
       [:li "procedure: " (span-value p)]
       [:li "experiment: " (span-value e)]
       [:li "today: " (span-value today)]
       [:li "last hour: " (span-value last-hour)]))
    [:li "last trial: " (span-time t)]]])

(defn subject-list [subjs] (map subject subjs))

(defn index [{:keys [controllers active-subjects inactive-subjects]}]
  (html5
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:content "initial-scale=1.0, user-scalable=no", :name "viewport"}]
    [:meta {:content "yes", :name "apple-mobile-web-app-capable"}]
    [:meta {:content "black", :name "apple-mobile-web-app-status-bar-style"}]
    [:title "Starboard Directory"]
    [:link {:rel "icon" :type "image/ico" :href "/images/favicon.ico"}]
    (include-css "/css/jquery.mobile-1.3.1.min.css")
    (include-css "/css/directory.css")]
   [:body
    [:div#page1 {:data-role "page"}
     [:div {:data-role "header"}
      [:h3 "Starboard Directory"]]
     [:div#console
      [:p.ahead "Registered Controllers"]
      [:ul#controller-list.item-list (controller-list controllers)]
      [:p.ahead "Active Subjects"]
      [:ul#active-subject-list.item-list (subject-list active-subjects)]
      [:p.ahead "Inactive Subjects"]
      [:ul#inactive-subject-list.item-list (subject-list inactive-subjects)]]]]))
