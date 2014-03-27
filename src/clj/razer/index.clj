(ns razer.index
  (:require [hiccup.core :refer [html]]))

(defn index-page
  [req uuid]
  {:status 200
   :session (assoc (:session req) :uid uuid)
   :body
   (html
    {:mode :html}
    [:html
     [:head
      [:script {:type "text/javascript"}
       (str "var wsUUID=\"" uuid "\";")]
      [:script {:src "http://fb.me/react-0.9.0.js"}]]
     [:body
      [:div#content
       [:h1 "If you see me, something's broken =("]]

      [:script {:src "js/out/goog/base.js" :type "text/javascript"}]
      [:script {:src "js/razer.js" :type "text/javascript"}]
      [:script {:type "text/javascript"}
       "goog.require(\"razer.core\");"]]])})
