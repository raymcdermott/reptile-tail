(ns socket-server.socket-repl
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.core.server :as clj-server])
  (:import (java.net Socket)
           (java.io OutputStreamWriter)
           (clojure.lang LineNumberingPushbackReader)))

(defn send-code
  [code-writer clj-code]
  (binding [*out* code-writer]
    (prn clj-code)
    (flush)))

(defn prepl-client
  [{:keys [host port]}]
  (let [client        (Socket. ^String host ^Integer port)
        server-reader (LineNumberingPushbackReader. (io/reader client))
        server-writer (OutputStreamWriter. (io/output-stream client))]
    [server-reader server-writer]))

(def ^:private socket-opts
  {:port          9080
   :server-daemon false
   :accept        'clojure.core.server/io-prepl})

(defn shared-eval
  [repl form]
  (let [_      (send-code (:writer repl) form)
        result (read (:reader repl))]
    ; TODO ... use core.async to prevent blocking in this loop thereby have the chance to provide intermediate results
    ; TODO ... work out the right way to cancel a command after code has been sent to the REPL
    (loop [results [result]]
      (if (= :ret (:tag (last results)))
        {:eval-result results}
        (recur (conj results (read (:reader repl))))))))

(defn stop-shared-prepl
  [prepl]
  ; TODO we do this to clobber all existing servers - could be more finessed ;-)
  (clj-server/stop-servers))

(defn shared-prepl
  [opts]
  ; TODO we do this to clobber all existing servers - could be more finessed ;-)
  (clj-server/stop-servers)
  ; TODO the caller uses an atom to hold the server for future use, we could do that
  (clj-server/start-server (merge socket-opts opts))

  (let [[prepl-reader prepl-writer] (prepl-client socket-opts)]
    {:reader prepl-reader :writer prepl-writer}))

;; hook web sockets in

;; then hook core.async

;; then make things nice


