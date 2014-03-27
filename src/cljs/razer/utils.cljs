(ns razer.utils
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [cljs.reader :as reader]
            [cljs.core.async :as async :refer [<!]]
            [om.core :as om :include-macros true])
  (:import [goog.ui IdGenerator]))

(defn guid []
  (.getNextUniqueId (.getInstance IdGenerator)))

(defn start-router-loop! [event-handler ch opts]
  (go-loop []
    (let [[id data :as event] (<! ch)]
      ;; Provide ch to handler to allow event injection back into loop:
      (event-handler event ch opts) ; Allow errors to throw
      (recur))))

(defn log [x] (.log js/console (clj->js x)))

(defn handle-cursor-change [e path cursor]
  (om/transact! cursor path (fn [_] (.. e -target -value))))

(defn handle-state-change [e path owner]
  (om/set-state! owner path (.. e -target -value)))
