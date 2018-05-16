(ns socket-server.server
  (:require [clojure.string :as str]
            [ring.middleware.defaults]
            [compojure.core :as comp :refer (defroutes GET POST)]
            [compojure.route :as route]
            [hiccup.core :as hiccup]
            [clojure.core.async :as async :refer (<! <!! >! >!! put! chan go go-loop)]
            [taoensso.encore :as encore :refer (have have?)]
            [taoensso.timbre :as timbre :refer (tracef debugf infof warnf errorf)]
            [taoensso.sente :as sente]
            [aleph.http :as aleph]
            [taoensso.sente.server-adapters.aleph :refer (get-sch-adapter)]
            [taoensso.sente.packers.transit :as sente-transit]
            [glow.core :as glow :refer [highlight-html]]
            [hickory.core :as hickory]
            [socket-server.socket-repl :as repl]
            [clojure.edn :as edn]))

;; (timbre/set-level! :trace) ; Uncomment for more logging
(reset! sente/debug-mode?_ false)                            ; Uncomment for extra debug info

;;;; Define our Sente channel socket (chsk) server

(let [;; Serializtion format, must use same val for client + server:
      packer      (sente-transit/get-transit-packer)
      chsk-server (sente/make-channel-socket-server! (get-sch-adapter) {:packer packer})
      {:keys [ch-recv send-fn connected-uids ajax-post-fn ajax-get-or-ws-handshake-fn]} chsk-server]

  (def ring-ajax-post ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk ch-recv)                                     ; ChannelSocket's receive channel
  (def chsk-send! send-fn)                                  ; ChannelSocket's send API fn
  (def connected-uids connected-uids)                       ; Watchable, read-only atom
  )

;;;; Ring handlers

(defn login-handler
  "Here's where you'll add your server-side login/auth procedure (Friend, etc.).
  In our simplified example we'll just always successfully authenticate the user
  with whatever user-id they provided in the auth request."
  [ring-req]
  (let [{:keys [session params]} ring-req
        {:keys [user-id]} params]
    (debugf "Login request: %s" params)
    {:status 200 :session (assoc session :uid user-id)}))

(defroutes ring-routes
           (GET "/chsk" ring-req (ring-ajax-get-or-ws-handshake ring-req))
           (POST "/chsk" ring-req (ring-ajax-post ring-req))
           (route/not-found "<h1>Page not found</h1>"))

(def main-ring-handler
  (ring.middleware.defaults/wrap-defaults
    ring-routes ring.middleware.defaults/site-defaults))

(defonce broadcast-enabled?_ (atom true))

;;;; Sente event handlers
(defmulti -event-msg-handler
          "Multimethod to handle Sente `event-msg`s"
          :id)                                              ; Dispatch on event-id

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [ev-msg]
  (-event-msg-handler ev-msg))                              ; Handle event-msgs on a single thread

(defmethod -event-msg-handler :default                      ; Default/fallback case (no other matching handler)
  [{:keys [event ?reply-fn]}]
  (debugf "Unhandled event: %s" event)
  (when ?reply-fn
    (?reply-fn {:unmatched-event-as-echoed-from-from-server event})))

(defmethod -event-msg-handler :example/toggle-broadcast
  [{:keys [?reply-fn]}]
  (let [loop-enabled? (swap! broadcast-enabled?_ not)]
    (?reply-fn loop-enabled?)))

;; TODO Add your (defmethod -event-msg-handler <event-id> [ev-msg] <body>)s here...

;;;;;;;;;;; REPL

(defmethod -event-msg-handler :reptile/keystrokes
  ;; Send the keystrokes to one and all
  [{:keys [?data]}]
  (let [shared-data {:form (:form ?data) :user (:user-name ?data)}]
    (doseq [uid (:any @connected-uids)]
      (chsk-send! uid [:fast-push/keystrokes shared-data]))))

(def shared-repl (atom nil))

(defn pretty-form
  "Syntax format and colouring"
  [edn-form]
  (pr-str (map hickory/as-hiccup
               (hickory/parse-fragment
                 (highlight-html edn-form)))))

(defmethod -event-msg-handler :reptile/repl
  ;; Send the results to one and all
  [{:keys [?data]}]
  (let [prepl      (or @shared-repl (reset! shared-repl (repl/shared-prepl {:name "reptile"})))
        input-form (try (read-string (:form ?data))
                        (catch Exception e {:read-exception
                                            (str "Exception: " (.getMessage e) " - check parens!")}))]
    (let [response   (if-not (:read-exception input-form)
                       (repl/shared-eval prepl input-form)
                       {:tag :ret, :val (:read-exception input-form), :form (:form ?data)})
          prettified (when-not (:read-exception input-form)
                       (assoc response :pretty (pretty-form (:form ?data))
                                       :original-form (:form ?data)))]

      (doseq [uid (:any @connected-uids)]
        (println "Sending out to uid" uid "eval from form" (:form response))
        (chsk-send! uid [:fast-push/eval (or prettified response)])))))

(defn shutdown-repl
  [repl]
  ;; TODO eventually shutdown the incoming REPL, now just shut all

  )

;;;;;;;;;;; LOGIN

;; The standard Sente approach uses Ring to authenticate but we want to use WS
(def connected-users (atom {:editors     ["eric" "mia" "mike" "ray"]
                            :session-key "apropos"}))

;; We can watch this atom for changes if we like
(add-watch connected-uids :connected-uids
           (fn [_ _ old new]
             (when (not= old new)
               (infof "Connected uids change: %s" new)
               (infof "We are going to (un)map a REPL connection here"))))

(add-watch connected-users :connected-users
           (fn [_ _ old new]
             (when (not= old new)
               (let [curr-users (get-in new [:reptile :clients])
                     prev-users (get-in old [:reptile :clients])]
                 (println "Current users" curr-users)
                 (println "Previous users" prev-users)
                 (doseq [uid (:any @connected-uids)]
                   (chsk-send! uid [:fast-push/editors curr-users]))))))

(defn register-uid [state uid send-fn]
  (assoc-in state [:clients uid :send-fn] (partial send-fn uid)))

(defn register-user [state user client-id]
  (let [kw-user (keyword user)]
    (assoc-in state [:reptile :clients kw-user :client-id] client-id)))

(defn deregister-user [state user]
  (let [kw-user (keyword user)]
    (update-in state [:reptile :clients] dissoc kw-user)))

(def shared-secret (atom nil))

(defn auth [{:keys [client-id ?data ?reply-fn state]}]
  (let [{:keys [user secret]} ?data]
    (if (= secret @shared-secret)
      (do
        (swap! state register-user user client-id)
        (?reply-fn :login-ok))
      (?reply-fn :login-failed))))

(defmethod -event-msg-handler :reptile/login
  [ev-msg]
  (auth (assoc ev-msg :state connected-users)))

;;;; Sente event router (our `event-msg-handler` loop)

(defonce router_ (atom nil))

(defn stop-router! []
  (when-let [stop-fn @router_] (stop-fn)))

(defn start-router! []
  (stop-router!)
  (reset! router_
          (sente/start-server-chsk-router!
            ch-chsk event-msg-handler)))

;;;; Init stuff

(defonce web-server_ (atom nil))                            ; (fn stop [])

(defn stop-web-server!
  []
  (when-let [stop-fn @web-server_] (stop-fn)))

(defn start-web-server!
  [& [port]]
  (stop-web-server!)
  (let [port         (or port 0)                            ; 0 => Choose any available port
        ring-handler (var main-ring-handler)

        [port stop-fn]
        (let [server (aleph/start-server ring-handler {:port port})
              p      (promise)]
          (future @p)                                       ; Workaround for Ref. https://goo.gl/kLvced
          [(aleph.netty/port server)
           (fn [] (.close ^java.io.Closeable server) (deliver p nil))])

        uri          (format "http://localhost:%s/" port)]

    (infof "Web server is running at `%s`" uri)

    (reset! web-server_ stop-fn)))

(defn stop!
  []
  (stop-router!)
  (stop-web-server!))

(defn start!
  [port]
  (start-router!)
  (start-web-server! port))

(defn -main "For `lein run`, etc."
  [& args]
  (if (not= (count args) 2)
    (println "need `port` and `secret`")
    (let [port   (Integer/parseInt (first args))
          secret (last args)]
      (reset! shared-secret secret)
      (start! port))))
