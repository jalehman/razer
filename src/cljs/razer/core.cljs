(ns razer.core
  (:require-macros [cljs.core.async.macros :refer [go-loop go alt!]]
                   [cljs.core.match.macros :refer [match]])
  (:require [cljs.core.match]
            [cljs.core.async :as async :refer [put! <! >! chan]]
            [sablono.core :as html :refer-macros [html]]
            [om.core :as om :include-macros true]
            [taoensso.sente :as sente :refer [cb-success?]]
            [cljs-http.client :as http]
            [razer.utils :refer [guid]]))

;; Lets you do (prn "stuff") to the console
(enable-console-print!)

(def timeout 8000)

;; =============================================================================
;; Util

(defn- start-router-loop! [event-handler ch opts]
  (go-loop []
    (let [[id data :as event] (<! ch)]
      ;; Provide ch to handler to allow event injection back into loop:
      (event-handler event ch opts) ; Allow errors to throw
      (recur))))

(defn- log [x] (.log js/console (clj->js x)))

(defn- success? [cb-reply]
  (if (map? cb-reply)
    (not (apply #{:chsk/closed :chsk/timeout :chsk/error} (keys cb-reply)))
    true))

(defn- rollback!
  [cursor tx-data]
  (om/transact! cursor (:path tx-data)
                (fn [_]
                  (:old-value tx-data)) {:type :ignore}))

(defn- ignore? [tx-data] (= :ignore (get-in tx-data [:tag :type])))

(defn handle-cursor-change [e path cursor]
  (om/transact! cursor path (fn [_] (.. e -target -value)) {:type :ignore}))

(defn handle-state-change [e path owner]
  (om/set-state! owner path (.. e -target -value)))

;; =============================================================================
;; Event Handlers

(defn handle-tx
  "All transactions excepting those explicitly marked ':ignore' will
   pass their data through this handler to the server. On
   success (lack of failure), the reply is logged. On error the cursor
   is rolled back to its former state."

  [owner cursor data]
  (let [chsk-send! (om/get-shared owner :chsk-send!)
        data' (assoc data :uuid (get-in @cursor [:user :uuid]))]
    (chsk-send! [:cursor/tx data'] timeout
                (fn [edn-reply]
                  (if (success? edn-reply)
                    (log edn-reply)
                    (rollback! cursor data'))))))

(defn handle-send
  "Similar to handle-tx, but used for server-side operations that do
   not affect the application state (cursor)."
  [owner cursor data]
  (let [chsk-send! (om/get-shared owner :chsk-send!)
        data' (assoc data :uuid (get-in @cursor [:user :uuid]))]
    (chsk-send! [:chsk/send {:tag data'}] timeout
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
  (match [id data]
    [:chsk/tx       _] (handle-tx owner cursor data)
    [:chsk/send     _] (handle-send owner cursor data)

    ;; [:root-ui/add-thing _] (om/transact! cursor [:things] #(conj % data)
    ;;                                      {:type   :db/crud :topic :thing
    ;;                                       :action :create})

    [:root-ui/log-in _] (om/transact! cursor [:user :logged-in?] (fn [_] true)
                                      ;; {:type :user :topic :auth :action :log-in}
                                      {:type :log-in})

    [:root-ui/log-out _] (om/transact! cursor [:user :logged-in?] (fn [_] false)
                                       {:type :log-out})

    [:root-ui/send-message _] (do (om/set-state! owner :message-text "")
                                  (handle-send owner cursor
                                               {:type   :send-message
                                                :params {:message data}}))

    [:chsk/state   _] (prn "Chsk state change: " data)
    [:chsk/recv    _] (om/transact! cursor [:messages] #(conj % (second data)) :ignore)
    :else (prn "unmatched event " ev)))

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

(defn auth
  [{:keys [logged-in? name] :as user} owner]
  (reify
    om/IRenderState
    (render-state [_ {:keys [root-ui]}]
      (html
       [:div
        (if-not logged-in?
          [:div
           [:input {:type "text" :placeholder "Your name" :value (:name user)
                    :on-change #(handle-cursor-change % [:name] user)}]
           [:button {:type "button"
                     :on-click (fn [_]
                                 (put! root-ui [:root-ui/log-in nil]))}
            "Log In"]]
          [:div
           [:p (str "Hello, " name "!")]
           [:button
            {:type "button"
             :on-click (fn [_] (put! root-ui [:root-ui/log-out nil]))}
            "Log Out"]])]))))

(defn razer-app [app owner]
  (reify
    om/IInitState
    (init-state [_]
      {:root-ui (chan) :message-text nil})
    om/IWillMount
    (will-mount [_]
      (let [{:keys [ch-chsk ch-send!]} (om/get-shared owner)
            root-ui                    (om/get-state owner :root-ui)
            c                          (async/merge [root-ui ch-chsk])]
        (start-router-loop! handle-event c {:owner owner :cursor app})))
    om/IRenderState
    (render-state [_ {:keys [root-ui message-text] :as state}]
      (html
       [:div
        [:h1 "Om/Sente Chat"]

        (om/build auth (:user app) {:state state})

        (when (get-in app [:user :logged-in?])
          [:div
           [:h2 "Messages"]
           (om/build messages (:messages app))
           [:input {:type "text" :value message-text
                    :on-change #(handle-state-change % :message-text owner)}]
           [:button
            {:type "button"
             :on-click (fn [_]
                         (put! root-ui [:root-ui/send-message message-text]))}
            "Send"]])]))))

;; =============================================================================
;; App Entry

(comment
  {:time nil :sender nil :message nil})

(def app-state
  (atom {:user {:name nil :logged-in? false :uuid js/wsUUID}
         :messages []}))

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
