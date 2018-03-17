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

            [socket-server.socket-repl :as repl]))

;; (timbre/set-level! :trace) ; Uncomment for more logging
(reset! sente/debug-mode?_ true)                            ; Uncomment for extra debug info

;; We use this to connect uids to socket REPL connections
(def uids->repl (atom {}))

;;;; Define our Sente channel socket (chsk) server

(let [;; Serializtion format, must use same val for client + server:
      packer (sente-transit/get-transit-packer)
      chsk-server (sente/make-channel-socket-server! (get-sch-adapter) {:packer packer})
      {:keys [ch-recv send-fn connected-uids ajax-post-fn ajax-get-or-ws-handshake-fn]} chsk-server]

  (def ring-ajax-post ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk ch-recv)                                     ; ChannelSocket's receive channel
  (def chsk-send! send-fn)                                  ; ChannelSocket's send API fn
  (def connected-uids connected-uids)                       ; Watchable, read-only atom
  )

;; We can watch this atom for changes if we like
(add-watch connected-uids :connected-uids
           (fn [_ _ old new]
             (when (not= old new)
               (infof "Connected uids change: %s" new)
               (infof "We are going to (un)map a REPL connection here"))))

;;;; Ring handlers

(defn landing-pg-handler [ring-req]
  (hiccup/html
    [:textarea#output {:style "width: 100%; height: 200px;"}]
    [:p
     [:input#repl-input-eric {:type :text :placeholder "(eric clojure here)"}]
     [:button#btn-repl-eric {:type "button"} "Eval"]]
    [:p
     [:input#repl-input-mia {:type :text :placeholder "(mia clojure here)"}]
     [:button#btn-repl-mia {:type "button"} "Eval"]]
    [:p
     [:input#repl-input-mike {:type :text :placeholder "(mike clojure here)"}]
     [:button#btn-repl-mike {:type "button"} "Eval"]]
    [:p
     [:input#repl-input-ray {:type :text :placeholder "(ray clojure here)"}]
     [:button#btn-repl-ray {:type "button"} "Eval"]]
    [:p
     [:button#btn5 {:type "button"} "Disconnect"]
     [:button#btn6 {:type "button"} "Reconnect"]]
    ;;
    ;[:hr]
    ;[:h2 "Step 3: try login with a user-id"]
    ;[:p "The server can use this id to send events to *you* specifically."]
    ;[:p
    ; [:input#input-login {:type :text :placeholder "User-id"}]
    ; [:button#btn-login {:type "button"} "Secure login!"]]
    ;;
    [:script {:src "main.js"}]))

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
           (GET "/" ring-req (landing-pg-handler ring-req))
           (GET "/chsk" ring-req (ring-ajax-get-or-ws-handshake ring-req))
           (POST "/chsk" ring-req (ring-ajax-post ring-req))
           (POST "/login" ring-req (login-handler ring-req))
           (route/resources "/")                            ; Static files, notably public/main.js (our cljs target)
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
        uid (:uid session)]
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

(defmethod -event-msg-handler :example/repl
  [{:keys [?reply-fn ?data]}]
  (when-let [response (repl/send-form (:form ?data))]
    (debugf "Response for: %s" (or (:user ?data) "unknown"))
    (?reply-fn response)))

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
  (let [port (or port 0)                                    ; 0 => Choose any available port
        ring-handler (var main-ring-handler)

        [port stop-fn]
        (let [server (aleph/start-server ring-handler {:port port})
              p (promise)]
          (future @p)                                       ; Workaround for Ref. https://goo.gl/kLvced
          [(aleph.netty/port server)
           (fn [] (.close ^java.io.Closeable server) (deliver p nil))])

        uri (format "http://localhost:%s/" port)]

    (infof "Web server is running at `%s`" uri)
    (try (.browse (java.awt.Desktop/getDesktop)
                  (java.net.URI. uri))
         (catch java.awt.HeadlessException _))

    (reset! web-server_ stop-fn)))

(defn start-prepl []

  )

(defn stop! []
  (stop-router!)
  (stop-web-server!))

(defn start! []
  (start-router!)
  (start-web-server!)
  ;(start-example-broadcaster!) ; turn this off - instead we will follow other users
  )

(defn -main "For `lein run`, etc." [] (start!))

(comment
  (start!)
  (test-fast-server>user-pushes))
