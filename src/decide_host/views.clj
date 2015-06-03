(ns decide-host.views
  "HTML views"
  (:require [hiccup.page :refer [html5 include-css include-js]]))

(defn controllers
  [controllers]
  (html5
   ))

(defn index []
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
      [:ul#controller-list]
      [:p.ahead "Active Subjects"]
      [:ul#active-subject-list]
      [:p.ahead "Inactive Subjects"]
      [:ul#inactive-subject-list]]
     (include-js "/js/d3.v3.min.js")]]))
