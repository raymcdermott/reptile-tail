(ns reptile.tail.socket-repl
  (:require [clojure.java.io :as io]
            [clojure.tools.reader.reader-types :as reader-types]
            [clojure.tools.reader.edn :as edn]
            [clojure.core.server :as clj-server])
  (:import (java.net Socket ServerSocket)
           (java.io OutputStreamWriter)
           (clojure.lang LineNumberingPushbackReader DynamicClassLoader)))

(defn send-code
  [code-writer clj-code]
  (binding [*out* code-writer
            *flush-on-newline* true]
    (prn clj-code)))

;; TODO ... poll server and enable reconnection
(defn prepl-client
  "Attaching the PREPL to a given `host` and `port`"
  [host port]
  (let [host          (if (= :self host) "localhost" host)
        client        (Socket. ^String host ^Integer port)
        server-reader (LineNumberingPushbackReader. (io/reader client))
        server-writer (OutputStreamWriter. (io/output-stream client))]
    [server-reader server-writer]))

; TODO use :eof reader property to indicate EOF issues rather than exception
; TODO use spec to verify the :reader / :writer keys are present on the passed repl
(defn process-form
  "Check the validity of the form and evaluate it using the given `repl`"
  [repl form]
  (try
    (let [eval-ok!     (eval (read-string form))
          prepl-reader (partial read (:reader repl))
          edn-form     (read-string form)]
      (send-code (:writer repl) edn-form)

      (if-let [result (prepl-reader)]
        (loop [results [result]]
          (if (= :ret (:tag (last results)))
            results
            (recur (conj results (prepl-reader)))))
        {:ex (str "Shared-eval - no results. Input form: " form)}))

    (catch Exception e (if (= (.getMessage e) "EOF while reading")
                         [{:form form :ms 0 :ns "user" :tag :ret
                           :val "" :empty-source "shared-multi"}]
                         {:ex (pr-str e)}))))

(defn read-forms
  "Read the string in the REPL buffer to obtain all forms (rather than just the first)"
  [repl-forms]
  (let [pbr      (reader-types/string-push-back-reader repl-forms)
        sentinel ::eof]
    (loop [data-read (clojure.tools.reader/read {:eof sentinel} pbr)
           result    []]
      (if (= data-read sentinel)
        result
        (recur (clojure.tools.reader/read {:eof sentinel} pbr)
               (conj result (pr-str data-read)))))))

(defn shared-eval
  "Evaluate the form(s) provided in the string `forms-str` using the given `repl`"
  [repl forms-str]
  (let [expanded-forms (read-forms forms-str)]
    (if (empty? expanded-forms)
      [{:form forms-str :ms 0 :ns "user" :tag :ret :val "" :empty-source "shared-eval"}]
      (flatten (map (partial process-form repl) expanded-forms)))))

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