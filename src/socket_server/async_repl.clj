;(ns socket-server.async-repl
;  "Provides a channel interface to socket repl input and output."
;  (:require
;    [clojure.java.io :as io]
;    [clojure.core.async :as async]
;    [socket-server.log :refer [log-start log-stop]])
;  (:import (java.io PrintStream BufferedReader)
;           (java.net Socket)))
;
;(defn- write-code
;  "Writes a string of code to the socket repl connection."
;  [{:keys [connection]} code-string]
;  (let [{:keys [print-stream]} @connection]
;    (.println print-stream code-string)
;    (.flush print-stream)))
;
;(defn subscribe-output
;  "Pipes the socket repl output to `chan`"
;  [{:keys [output-channel]} chan]
;  (async/pipe output-channel chan))
;
;(defn connect
;  "Create a connection to a socket repl."
;  [{:keys [connection output-channel]} host port]
;  (let [socket (Socket. ^String host (Integer/parseInt port))
;        reader ^BufferedReader (io/reader socket)]
;    (reset! connection {:host         host
;                        :port         port
;                        :socket       socket
;                        :print-stream (-> socket io/output-stream PrintStream.)
;                        :reader       reader})
;    (future
;      (loop []
;        (when-let [line (.readLine reader)]
;          (async/>!! output-channel line)
;          (recur))))))
;
;(defn connected?
;  [{:keys [connection]}]
;  (:host @connection))
;
;(defn start
;  [{:keys [input-channel] :as socket-repl}]
;  (log-start
;    "socket-repl"
;    (async/thread
;      (loop []
;        (when-let [input (async/<!! input-channel)]
;          (when (connected? socket-repl)
;            (write-code socket-repl input))
;          (recur))))
;    socket-repl))
;
;(defn stop
;  [{:keys [connection output-channel input-channel] :as socket-repl}]
;  (log-stop
;    "socket-repl"
;    (let [{:keys [socket]} @connection]
;      (when socket
;        (.shutdownInput socket)
;        (.shutdownOutput socket)))
;    (async/close! output-channel)
;    (async/close! input-channel)
;    socket-repl))
;
;(defn new
;  []
;  {:input-channel (async/chan 1024)
;   :output-channel (async/chan 1024)
;   :connection (atom {:host nil
;                      :port nil
;                      :socket nil
;                      :reader nil
;                      :print-stream nil})})
