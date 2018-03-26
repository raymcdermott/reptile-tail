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
            [puget.printer :as puget]
            [hickory.core :as hickory]
            [socket-server.socket-repl :as repl]
            [clojure.edn :as edn]))

;; (timbre/set-level! :trace) ; Uncomment for more logging
(reset! sente/debug-mode?_ true)                            ; Uncomment for extra debug info

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

;; The standard Sente approach uses Ring to authenticate but we want to use WS
(def connected-users (atom {:editors     ["eric" "mia" "mike" "ray"]
                            :session-key "apropos"}))


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
           (POST "/login" ring-req (login-handler ring-req))
           (route/not-found "<h1>Page not found</h1>"))

(def main-ring-handler
  "**NB**: Sente requires the Ring `wrap-params` + `wrap-keyword-params`
  middleware to work. These are included with
  `ring.middleware.defaults/wrap-defaults` - but you'll need to ensure
  that they're included yourself if you're not using `wrap-defaults`."
  (ring.middleware.defaults/wrap-defaults
    ring-routes ring.middleware.defaults/site-defaults))

;;;; Some server>user async push examples

(defn test-fast-server>user-pushes
  "Quickly pushes 100 events to all connected users. Note that this'll be
  fast+reliable even over Ajax!"
  []
  (doseq [uid (:any @connected-uids)]
    (doseq [i (range 100)]
      (chsk-send! uid [:fast-push/is-fast (str "hello " i "!!")]))))

(comment (test-fast-server>user-pushes))

(defonce broadcast-enabled?_ (atom true))

(defn start-example-broadcaster!
  "As an example of server>user async pushes, setup a loop to broadcast an
  event to all connected users every 10 seconds"
  []
  (let [broadcast!
        (fn [i]
          (let [uids (:any @connected-uids)]
            (debugf "Broadcasting server>user: %s uids" (count uids))
            (doseq [uid uids]
              (chsk-send! uid
                          [:some/broadcast
                           {:what-is-this "An async broadcast pushed from server"
                            :how-often    "Every 10 seconds"
                            :to-whom      uid
                            :i            i}]))))]

    (go-loop [i 0]
      (<! (async/timeout 10000))
      (when @broadcast-enabled?_ (broadcast! i))
      (recur (inc i)))))

;;;; Sente event handlers

(defmulti -event-msg-handler
          "Multimethod to handle Sente `event-msg`s"
          :id                                               ; Dispatch on event-id
          )

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [{:as ev-msg :keys [id ?data event]}]
  (-event-msg-handler ev-msg)                               ; Handle event-msgs on a single thread
  ;; (future (-event-msg-handler ev-msg)) ; Handle event-msgs on a thread pool
  )

(defmethod -event-msg-handler
  :default                                                  ; Default/fallback case (no other matching handler)
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid session)]
    (debugf "Unhandled event: %s" event)
    (when ?reply-fn
      (?reply-fn {:umatched-event-as-echoed-from-from-server event}))))

(defmethod -event-msg-handler :example/test-rapid-push
  [ev-msg]
  (test-fast-server>user-pushes))

(defmethod -event-msg-handler :example/toggle-broadcast
  [{:as ev-msg :keys [?reply-fn]}]
  (let [loop-enabled? (swap! broadcast-enabled?_ not)]
    (?reply-fn loop-enabled?)))

;; TODO Add your (defmethod -event-msg-handler <event-id> [ev-msg] <body>)s here...

;;;;;;;;;;; REPL

(defmethod -event-msg-handler :reptile/keystrokes
  [{:keys [?data client-id]}]
  (doseq [uid (:any @connected-uids)]
    ; TODO ... need to look into the whole reply-fn stuff
    (chsk-send! uid [:fast-push/keystrokes ?data])))

(def shared-repl (atom nil))


(defn colorize
  [edn-form]
  (puget/cprint-str edn-form {:color-markup :html-inline}))

(defn as-hiccup
  [html-form]
  (map hickory/as-hiccup (hickory/parse-fragment html-form)))

(defn pretty-form
  [edn-form]
  (pr-str (as-hiccup (colorize edn-form))))

(defmethod -event-msg-handler :reptile/repl
  [{:keys [?data]}]
  (let [prepl      (or @shared-repl (reset! shared-repl (repl/shared-prepl {:name "reptile"})))
        input-form (try (edn/read-string (:form ?data))
                        (catch Exception e {:exception (str "Exception: " (.getMessage e) " - check parens!")}))]
    (let [response   (if-not (:exception input-form)
                       (repl/shared-eval prepl input-form)
                       {:tag :ret, :val (:exception input-form), :form (:form ?data)})
          prettified (when-not (:exception input-form)
                       (assoc response :pretty (pretty-form input-form)))]

      (doseq [uid (:any @connected-uids)]
        ; TODO ... need to look into the whole reply-fn stuff
        (chsk-send! uid [:fast-push/eval (or prettified response)])))))

(defn shutdown-repl
  [repl]
  ;; TODO eventually shutdown the incoming REPL, now just shut all

  )

;;;;;;;;;;; LOGIN

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
                   ; TODO ... need to look into the whole reply-fn stuff
                   (chsk-send! uid [:fast-push/editors curr-users]))))))

(defn register-uid [state uid send-fn]
  (assoc-in state [:clients uid :send-fn] (partial send-fn uid)))

(defn register-user [state user client-id]
  (let [kw-user (keyword user)]
    (assoc-in state [:reptile :clients kw-user :client-id] client-id)))

(defn deregister-user [state user]
  (let [kw-user (keyword user)]
    (update-in state [:reptile :clients] dissoc kw-user)))

(defn auth [{:keys [client-id ?data ?reply-fn state]}]
  (if-let [user (:proposed-user ?data)]
    (do
      (swap! state register-user user client-id)
      (?reply-fn :login-ok))
    (?reply-fn :login-failed)))

(defmethod -event-msg-handler :reptile/login
  [ev-msg]
  (async/thread
    (auth (assoc ev-msg :state connected-users))))

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
(defn stop-web-server! [] (when-let [stop-fn @web-server_] (stop-fn)))
(defn start-web-server! [& [port]]
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

(defn stop! []
  (stop-router!)
  (stop-web-server!))

(defn start! []
  (start-router!)
  (start-web-server! 9090)
  ;(start-example-broadcaster!) ; turn this off - instead we will follow other users
  )

(defn -main "For `lein run`, etc." [] (start!))

(comment
  (start!)
  (test-fast-server>user-pushes))
