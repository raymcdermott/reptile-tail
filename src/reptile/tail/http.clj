(ns reptile.tail.http
  (:require [compojure.core :as compojure :refer [GET POST]]
            [ring.middleware.params :as params]
            [ring.middleware.keyword-params :as keyword-params]
            [compojure.route :as route]
            [aleph.http :as http]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [taoensso.sente :as sente]
            [taoensso.sente.server-adapters.aleph :refer (get-sch-adapter)]))

(let [{:keys [ch-recv send-fn connected-uids
              ajax-post-fn ajax-get-or-ws-handshake-fn]}
      (sente/make-channel-socket! (get-sch-adapter) {})]

  (def ring-ajax-post ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  ; ChannelSocket's receive channel
  (def ch-chsk ch-recv)
  ; ChannelSocket's send API fn
  (def chsk-send! send-fn)
  ; Watchable, read-only atom
  (def connected-uids connected-uids))

(def non-websocket-request
  {:status  400
   :headers {"content-type" "application/text"}
   :body    "Expected a websocket request."})


(defn echo-handler
  [req]
  (if-let [socket (try @(http/websocket-connection req)
                       (catch Exception _ nil))]
    (s/connect socket socket)
    non-websocket-request))

;; Async
(defn echo-handler-a
  [req]
  (-> (http/websocket-connection req)
      (d/chain
        (fn [socket]
          (s/connect socket socket)))
      (d/catch
        (fn [_]
          non-websocket-request))))

;; let-flow
(defn echo-handler-lf
  [req]
  (-> (d/let-flow [socket (http/websocket-connection req)]
                  (s/connect socket socket))
      (d/catch
        (fn [_]
          non-websocket-request))))


(def handler
  (->
    (compojure/routes
      (GET "/echo" [] echo-handler)
      (GET "/echo-a" [] echo-handler-a)
      (GET "/echo-lf" [] echo-handler-lf)
      (POST "/chsk" request (ring-ajax-post request))
      (GET "/chsk" request (ring-ajax-get-or-ws-handshake request))
      (route/not-found "No such page."))
    (keyword-params/wrap-keyword-params)
    (params/wrap-params)))

(def s (http/start-server handler {:port 10000}))

(let [conn @(http/websocket-client "ws://localhost:10000/echo-lf")]

  (s/put-all! conn
              (->> 10 range (map str)))

  (->> conn
       (s/transform (take 10))
       s/stream->seq
       doall))
;=> ("0" "1" "2" "3" "4" "5" "6" "7" "8" "9")