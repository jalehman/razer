(ns razer.core
  (:gen-class)
  (:require [razer.index :refer [index-page]]
            [compojure.handler :as handler]
            [compojure.route :as route]
            [compojure.core :refer [GET POST defroutes]]
            [org.httpkit.server :refer [run-server]]
            [clojure.core.match :as match :refer (match)]
            [clojure.core.async :as async :refer [<! >! put! chan go go-loop]]
            [taoensso.sente :as sente]
            [taoensso.timbre :as timbre :refer [infof debugf info debug]]
            [ring.util.response :as resp]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [ring.middleware.reload :as reload]))

(declare chsk-send!)

;; =============================================================================
;; Util

(defn uuid [] (str (java.util.UUID/randomUUID)))

(def connections (atom {}))

;; =============================================================================
;; Handlers

(defn handle-tx
  [{:keys [ring-req event ?reply-fn]} {:keys [tag] :as data}]
  (if (= :mock/err (:type tag))
    (?reply-fn {:chsk/error "Received mock error."})
    (?reply-fn {:message "Cursor update processed."})))

;; =============================================================================
;; Event Middleware

(defn wrap-debug-data
  [ev-msg]
  (do (clojure.pprint/pprint ev-msg)
      ev-msg))

;; =============================================================================
;; Event Router

(defn- type-router
  [{:keys [tag uuid new-state] :as data}]
  (let [name (get-in new-state [:user :name])]
    (case (:type tag)
      :log-in (do (println name)
                  (swap! connections assoc uuid name))
      :send-message (doseq [[k v] @connections]
                      (chsk-send! k [:chat/broadcast
                                     {:message (get-in tag [:params :message])
                                      :sender v :time "just now"}]))
      (info "Unmatched type event."))))

(defn- event-msg-handler
  [{:as ev-msg :keys [?reply-fn]} _]
  (let [session          (:session (:ring-req ev-msg))
        uid              (:uid session)
        [id data :as ev] (:event ev-msg)]
    (match [id data]
      [:cursor/tx _] (type-router data)
      [:chsk/send _] (type-router data)
      [:chsk/ping _] (debug "Ping received.")

      ;; Catch-all
      :else (do (println "Unmatched event " ev)))))

;; =============================================================================
;; Sente init

(let [{:keys [ch-recv send-fn ajax-post-fn ajax-get-or-ws-handshake-fn]}
      (sente/make-channel-socket! {})]
  (def ring-ajax-post                ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk                       ch-recv)
  (def chsk-send!                    send-fn)
  (sente/start-chsk-router-loop! event-msg-handler ch-chsk))

;; =============================================================================
;; Server stuff

(defn make-index-page
  [req]
  (let [u (uuid)]
    (infof "Sending index w/ uuid: %s" u)
    (swap! connections assoc u nil)
    (index-page req u)))

(defroutes app-routes
  (GET "/" req (make-index-page req))

  (GET  "/chsk" req (#'ring-ajax-get-or-ws-handshake req))
  (POST "/chsk" req (#'ring-ajax-post                req))

  (route/resources "/")
  (route/not-found "Page not found"))

(def app
  (-> #'app-routes
      (handler/site)
      (reload/wrap-reload)))

(defn -main [& args]
  (println ";; Server starting on port 8085")
  (run-server app {:port 8085}))
