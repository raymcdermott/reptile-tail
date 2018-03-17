(ns socket-server.socket-repl
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.core.server :as clj-server])
  (:import (java.net Socket)
           (java.io OutputStreamWriter)
           (clojure.lang LineNumberingPushbackReader)))

(defn start
  [opts]
  (clj-server/start-server opts))

(defn send-code
  [code-writer clj-code]
  (binding [*out* code-writer]
    (prn clj-code)
    (flush)))

(defn repl
  [{:keys [host port]}]
  (let [client (Socket. ^String host ^Integer port)
        server-reader (LineNumberingPushbackReader. (io/reader client))
        server-writer (OutputStreamWriter. (io/output-stream client))]
    [server-reader server-writer]))

(defn eval-form
  [opts form]
  (let [_ (start opts)
        [r w] (repl opts)
        _ (send-code w form)
        response (read r)
        _ (clj-server/stop-servers)]
    response))

(def socket-opts
  {:port          9080
   :name          "apropos"
   :server-daemon false
   :accept        'clojure.core.server/io-prepl})

(defn send-form
  [form]
  (clj-server/stop-servers)
  (eval-form socket-opts (edn/read-string form)))


;; hook web sockets in


;; then hook core.async


;; then make things nice


