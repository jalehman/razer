(ns razer.core
  (:require-macros [cljs.core.async.macros :refer [go-loop go alt!]]
                   [cljs.core.match.macros :refer [match]])
  (:require [cljs.core.match]
            [cljs.core.async :as async :refer [put! <! >! chan]]
            [sablono.core :as html :refer-macros [html]]
            [om.core :as om :include-macros true]
            [taoensso.sente :as sente :refer [cb-success?]]
            [cljs-http.client :as http]
            [razer.utils :refer [log start-router-loop!]]))

;; Lets you do (prn "stuff") to the console
(enable-console-print!)

(def timeout 8000)

;; =============================================================================
;; Util

(defn- success? [cb-reply]
  (if (map? cb-reply)
    (not (apply #{:chsk/closed :chsk/timeout :chsk/error} (keys cb-reply)))
    true))

(defn rollback!
  [cursor tx-data]
  (om/transact! cursor (:path tx-data)
                (fn [_]
                  (:old-value tx-data)) {:type :ignore}))

(defn ignore? [tx-data] (= :ignore (get-in tx-data [:tag :type])))

;; =============================================================================
;; Event Handlers

(defn- annotate-data
  "Provide a consistent view of the data being sent to the server."
  [data & {:keys [tx?]}]
  (if tx?
    (assoc {} :tx-data data :route (:tag data))
    (assoc {} :route data)))

(defn handle-tx
  "All transactions excepting those explicitly marked ':ignore' will
   pass their data through this handler to the server. On
   success (lack of failure), the reply is logged. On error the cursor
   is rolled back to its former state."

  [owner cursor data]
  (let [chsk-send! (om/get-shared owner :chsk-send!)]
    (chsk-send! [:cursor/tx (annotate-data data :tx? true)] timeout
                (fn [edn-reply]
                  (if (success? edn-reply)
                    (log edn-reply)
                    (rollback! cursor data))))))

(defn handle-send
  "Similar to handle-tx, but used for server-side operations that do
   not affect the application state (cursor)."
  [owner cursor data]
  (let [chsk-send! (om/get-shared owner :chsk-send!)]
    (chsk-send! [:chsk/send (annotate-data data :tx? false)] timeout
                (fn [edn-reply]
                  (if (success? edn-reply)
                    (log edn-reply)
                    (do (prn "ERRORS:")
                        (log (:chsk/error edn-reply))))))))

;; What would data retrieval look like?
(comment
  (put! (om/get-shared owner :ch-chsk)
        {:type :db/crud  ;; retrieval from db
         :topic :thing   ;; dealing with 'things'
         :action :get    ;; get data
         :path [:things] ;; put the data in the cursor here on success
         }))

(defn handle-event
  "Handle top-level UI events and websocket events."
  [[id data :as ev] _ {:keys [owner cursor]}]
  (let [root-ui (om/get-state owner :root-ui)]
    (match [id data]
      [:chsk/tx       _] (handle-tx owner cursor data)
      [:chsk/send     _] (handle-send owner cursor data)

      ;; [:root-ui/send-message msg] (do (om/set-state! owner :message-text "")
      ;;                                 (put! root-ui [:chsk/send {:params {:message msg}
      ;;                                                            :topic :chat
      ;;                                                            :action :broadcast}]))

      [:chsk/state   _] (prn "Chsk state change: " data)
      [:chsk/recv    _] (prn "Receieved message from server: " data)
      :else (prn "unmatched event " ev))))

;; =============================================================================
;; Components

(defn message
  [{:keys [time sender message]} owner]
  (om/component
   (html [:li (str "message from " sender " received on "
                   time ": " message)])))

(defn messages
  [messages owner]
  (om/component
   (html
    [:ul
     (om/build-all message messages)])))

(defn razer-app [app owner]
  (reify
    om/IInitState
    (init-state [_]
      {:root-ui (chan)})
    om/IWillMount
    (will-mount [_]
      (let [{:keys [ch-chsk ch-send!]} (om/get-shared owner)
            root-ui                    (om/get-state owner :root-ui)
            c                          (async/merge [root-ui ch-chsk])]
        (start-router-loop! handle-event c {:owner owner :cursor app})))
    om/IRenderState
    (render-state [_ {:keys [root-ui] :as state}]
      (html
       [:div
        [:h1 "Coffee Log"]

        ]))))

;; =============================================================================
;; App Entry

(def app-state
  (atom {}))

(let [{:keys [chsk ch-recv send-fn]} (sente/make-channel-socket!
                                      "/chsk" {:has-uid? true}
                                      {:type :auto})]
  (om/root razer-app app-state
           {:target (.getElementById js/document "content")
            :shared {:chsk chsk          :ch-chsk ch-recv
                     :chsk-send! send-fn}
            :tx-listen (fn [tx-data root-cursor]
                         (when-not (ignore? tx-data)
                           (put! ch-recv [:chsk/tx tx-data])))}))

(comment
  {:route {:topic :models, :type :review, :action :create}
   :handlers {:on-error (fn [tx-data errors]
                          (om/set-state! owner :errors errors)
                          (rollback! tx-data))}})
