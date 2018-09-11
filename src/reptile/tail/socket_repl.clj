(ns reptile.tail.socket-repl
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.core.server :as clj-server])
  (:import (java.net Socket ServerSocket)
           (java.io OutputStreamWriter)
           (clojure.lang LineNumberingPushbackReader DynamicClassLoader)))

(defn send-code
  [code-writer clj-code]
  (binding [*out* code-writer *flush-on-newline* true]
    (prn clj-code)))

;; TODO ... poll server and enable reconnection
(defn prepl-client
  "Attaching the PREPL to a given `host` and `port`"
  [host port]
  (println "prepl-client host" host "port" port)
  (let [host          (if (= :self host) "localhost" host)
        client        (Socket. ^String host ^Integer port)
        server-reader (LineNumberingPushbackReader. (io/reader client))
        server-writer (OutputStreamWriter. (io/output-stream client))]
    [server-reader server-writer]))

;; TODO - account for strings

(defn shared-eval
  [repl form]
  (let [prepl-reader (partial read (:reader repl))]
    (send-code (:writer repl) (edn/read-string form))

    (if-let [result (prepl-reader)]
      (do
        (println "shared-eval result" result)
        ; TODO ... use core.async to prevent blocking in this loop thereby have the chance to provide intermediate results
        ; TODO ... work out the right way to cancel a command after code has been sent to the REPL
        (loop [results [result]]
          (if (= :ret (:tag (last results)))
            results
            (recur (conj results (prepl-reader))))))
      (println "shared-eval - no results. Input form: " form))))

(defn reptile-valf
  "The prepl default for :valf is `pr-str`, instead here we return values"
  [& xs]
  (first xs))

(defn shared-prepl-server
  [opts]
  (let [socket-opts {:port          0
                     :name          "Reptile server"
                     :server-daemon false
                     :accept        'clojure.core.server/io-prepl
                     :args          [:valf 'reptile.tail.socket-repl/reptile-valf]}]

    ;; A clojure.lang.DynamicClassLoader is needed to enable interactive library addition
    (try
      (let [server-opts    (merge socket-opts opts)
            current-thread (Thread/currentThread)
            cl             (.getContextClassLoader current-thread)
            _              (.setContextClassLoader current-thread (DynamicClassLoader. cl))
            server         (clj-server/start-server server-opts)]

        (.getLocalPort ^ServerSocket server))

      (catch Exception e (println (str "shared-prepl-server - exception: " (.getMessage e)))))))


(defn shared-prepl
  [{:keys [host port] :as prepl-opts}]

  (let [port (if (= host :self) (shared-prepl-server prepl-opts) port)
        host (if (= host :self) "localhost" host)

        [prepl-reader prepl-writer] (prepl-client host port)]
    {:reader prepl-reader :writer prepl-writer}))


;; hook web sockets in

;; then hook core.async

;; then make things nice