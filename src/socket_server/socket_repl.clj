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
  [repl source form]
  (send-code (:writer repl) form)

  (when-let [result (read (:reader repl))]
    ; TODO ... use core.async to prevent blocking in this loop thereby have the chance to provide intermediate results
    ; TODO ... work out the right way to cancel a command after code has been sent to the REPL
    (loop [results [result]]
      (println "repl read" result)
      (if (= :ret (:tag (last results)))
        {:eval-result results :source source}
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




#_(defn main
    "A generic runner for ClojureScript. repl-env must satisfy
    cljs.repl/IReplEnvOptions and cljs.repl/IJavaScriptEnv protocols. args is a
    sequence of command line flags."
    [repl-env & args]
    (try
      (let [cl (.getContextClassLoader (Thread/currentThread))]
        (.setContextClassLoader (Thread/currentThread) (clojure.lang.DynamicClassLoader. cl)))
      (let [commands (merged-commands repl-env)]
        (if args
          (loop [[opt arg & more :as args] (normalize commands args) inits []]
            (if (dispatch? commands :init opt)
              (recur more (conj inits [opt arg]))
              ((get-dispatch commands :main opt script-opt)
                repl-env args (initialize inits commands))))
          (repl-opt repl-env nil nil)))
      (finally
        (flush))))