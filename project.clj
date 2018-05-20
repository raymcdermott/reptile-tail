(defproject reptile-server "0.1.0-SNAPSHOT"
  :description "Clojure server to enable a shared REPL"
  :url "https://github.com/raymcdermott/reptile"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}

  :min-lein-version "2.8.1"

  :plugins [[lein-tools-deps "0.3.0-SNAPSHOT"]
            [lein-ancient "0.6.14"]]

  :tools/deps [:system :home :project]

  :main socket-server.server)


