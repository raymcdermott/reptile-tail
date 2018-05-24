(ns reptile.tail.client
  "Official Sente reference example: client"
  {:author "Peter Taoussanis (@ptaoussanis)"}

  (:require
    [clojure.string :as str]
    [cljs.core.async :as async :refer (<! >! put! chan)]
    [taoensso.encore :as encore :refer-macros (have have?)]
    [taoensso.timbre :as timbre :refer-macros (tracef debugf infof warnf errorf)]
    [taoensso.sente :as sente :refer (cb-success?)]

    ;; Optional, for Transit encoding:
    [taoensso.sente.packers.transit :as sente-transit])

  (:require-macros
    [cljs.core.async.macros :as asyncm :refer (go go-loop)]))

;; (timbre/set-level! :trace) ; Uncomment for more logging

;;;; Util for logging output to on-screen console

(def output-el (.getElementById js/document "output"))
(defn ->output! [fmt & args]
  (let [msg (apply encore/format fmt args)]
    (timbre/debug msg)
    (aset output-el "value" (str "• " (.-value output-el) "\n" msg))
    (aset output-el "scrollTop" (.-scrollHeight output-el))))

(->output! "ClojureScript appears to have loaded correctly.")

;;;; Define our Sente channel socket (chsk) client

(let [;; Serialization format, must use same val for client + server:
      packer (sente-transit/get-transit-packer)             ; Needs Transit dep

      {:keys [chsk ch-recv send-fn state]}
      (sente/make-channel-socket-client!
        "/chsk"                                             ; Must match server Ring routing URL
        {:type   :auto
         :packer packer})]

  (def chsk chsk)

  ; ChannelSocket's receive channel
  (def ch-chsk ch-recv)

  ; ChannelSocket's send API fn
  (def chsk-send! send-fn)

  ; Watchable, read-only atom
  (def chsk-state state))

;;;; Sente event handlers

(defmulti -event-msg-handler "Multimethod to handle Sente `event-msg`s"
          ; Dispatch on event-id
          :id)

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [{:as ev-msg :keys [id ?data event]}]
  (-event-msg-handler ev-msg))

(defmethod -event-msg-handler
  :default                                                  ; Default/fallback case (no other matching handler)
  [{:as ev-msg :keys [event]}]
  (->output! "C Unhandled event: %s" event))

(defmethod -event-msg-handler :chsk/state
  [{:as ev-msg :keys [?data]}]
  (let [[old-state-map new-state-map] (have vector? ?data)]
    (if (:first-open? new-state-map)
      (->output! "C Channel socket successfully established!: %s" new-state-map)
      (->output! "C Channel socket state change: %s" new-state-map))))

(defmethod -event-msg-handler :chsk/recv
  [{:as ev-msg :keys [?data]}]
  (->output! "C Push event from server: %s" ?data))

(defmethod -event-msg-handler :chsk/handshake
  [{:as ev-msg :keys [?data]}]
  (let [[?uid ?csrf-token ?handshake-data] ?data]
    (->output! "C Handshake: %s" ?data)))

;; TODO Add your (defmethod -event-msg-handler <event-id> [ev-msg] <body>)s here...

;;;; Sente event router (our `event-msg-handler` loop)

(defonce router_ (atom nil))
(defn stop-router! [] (when-let [stop-f @router_] (stop-f)))
(defn start-router! []
  (stop-router!)
  (reset! router_
          (sente/start-client-chsk-router!
            ch-chsk event-msg-handler)))

;;;; UI events

(when-let [target-el (.getElementById js/document "btn5")]
  (.addEventListener target-el "click"
                     (fn [ev]
                       (->output! "C Disconnecting")
                       (sente/chsk-disconnect! chsk))))

(when-let [target-el (.getElementById js/document "btn6")]
  (.addEventListener target-el "click"
                     (fn [ev]
                       (->output! "C Reconnecting")
                       (sente/chsk-reconnect! chsk))))

(defn form->eval
  [elem-name form]
  (when-not (str/blank? form)
    (chsk-send! [:example/repl {:user elem-name :form form}] 5000
                (fn [cb-reply]
                  (->output! "Callback reply: %s" cb-reply)))))

(when-let [target-el (.getElementById js/document "btn-repl-eric")]
  (->output! "Listening on btn-repl-eric")
  (.addEventListener
    target-el "click"
    (let [form (.-value (.getElementById js/document "repl-input-eric"))]
      (form->eval "repl-input-eric" form))))

(when-let [target-el (.getElementById js/document "btn-repl-mia")]
  (.addEventListener
    target-el "click"
    (let [form (.-value (.getElementById js/document "repl-input-mia"))]
      (form->eval "repl-input-mia" form))))

(when-let [target-el (.getElementById js/document "btn-repl-mike")]
  (.addEventListener
    target-el "click"
    (let [form (.-value (.getElementById js/document "repl-input-mike"))]
      (form->eval "repl-input-mike" form))))

(when-let [target-el (.getElementById js/document "btn-repl-ray")]
  (.addEventListener
    target-el "click"
    (let [form (.-value (.getElementById js/document "repl-input-ray"))]
      (form->eval "repl-input-ray" form))))

;;;; Init stuff

(defn start! [] (start-router!))

(defonce _start-once (start!))
