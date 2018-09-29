(ns reptile.tail.server
  (:require [reptile.server.http :as http]))

(defn -main "For `lein run`, etc."
  [& args]
  (if (not (or (= (count args) 4) (= (count args) 2)))
    (println "need `web port`, `secret` [optionally `socket host` and `socket port` for external processes]")
    (if (= (count args) 4)
      (let [port        (Integer/parseInt (first args))
            secret      (second args)
            socket-host (nth args 2)
            socket-port (Integer/parseInt (last args))]
        (http/start-reptile-server port secret socket-host socket-port))
      (let [port   (Integer/parseInt (first args))
            secret (second args)]
        (http/start-reptile-server port secret)))))