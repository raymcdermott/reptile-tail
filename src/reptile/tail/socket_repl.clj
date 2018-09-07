(ns reptile.tail.socket-repl
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.core.server :as clj-server])
  (:import (java.net Socket ServerSocket)
           (java.io OutputStreamWriter)
           (clojure.lang LineNumberingPushbackReader DynamicClassLoader)))

(defn send-code
  [code-writer clj-code]
  (binding [*out* code-writer]
    (prn clj-code)
    (flush)))

;; TODO ... poll server and enable reconnection
(defn prepl-client
  "Attaching the PREPL to a given `host` and `port`"
  [host port]
  (let [client        (Socket. ^String host ^Integer port)
        server-reader (LineNumberingPushbackReader. (io/reader client))
        server-writer (OutputStreamWriter. (io/output-stream client))]
    [server-reader server-writer]))

(defn shared-eval
  [repl form]
  (send-code (:writer repl) form)

  (when-let [result (read (:reader repl))]
    ; TODO ... use core.async to prevent blocking in this loop thereby have the chance to provide intermediate results
    ; TODO ... work out the right way to cancel a command after code has been sent to the REPL
    (loop [results [result]]
      (if (= :ret (:tag (last results)))
        results
        (recur (conj results (read (:reader repl))))))))

(defn shared-prepl-server
  [opts]
  (let [socket-opts {:port          0
                     :server-daemon false                   ; Keep the app running
                     :accept        'clojure.core.server/io-prepl}]

    ;; A clojure.lang.DynamicClassLoader is needed to enable interactive library addition
    (try (let [current-thread (Thread/currentThread)
               cl             (.getContextClassLoader current-thread)]
           (.setContextClassLoader current-thread (DynamicClassLoader. cl))

           (let [server (clj-server/start-server (merge socket-opts opts))]
             (println "REPL server port" (.getLocalPort ^ServerSocket server))))

         (catch Exception e (str "ClassLoader issue - caught exception: " (.getMessage e))))))


(defn shared-prepl
  [host port]
  (let [local-opts {:host "localhost" :port 9080 :name "tail-prepl"}]

    (if (= host :self)
      (shared-prepl-server local-opts))

    (let [prepl-host (if (= host :self) (:host local-opts) host)
          prepl-port (if (= port :self) (:port local-opts) port)
          [prepl-reader prepl-writer] (prepl-client prepl-host prepl-port)]
      {:reader prepl-reader :writer prepl-writer})))


;; hook web sockets in

;; then hook core.async

;; then make things nice