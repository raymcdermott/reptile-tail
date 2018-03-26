(defproject socket-server "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}

  :min-lein-version "2.5.3"

  :global-vars {*warn-on-reflection* true
                *assert*             true}

  :dependencies [[org.clojure/clojure "1.10.0-alpha4"]
                 [org.clojure/clojurescript "1.9.946"]
                 [org.clojure/core.async "0.4.474"]
                 [org.clojure/tools.logging "0.4.0"]
                 [aleph "0.4.4"]
                 [gloss "0.2.6"]
                 [compojure "1.6.0"]
                 [com.taoensso/sente "1.12.0"]
                 [com.taoensso/timbre "4.10.0"]
                 [ring "1.6.3"]
                 [ring/ring-defaults "0.3.1"]               ; Includes `ring-anti-forgery`, etc.
                 [hiccup "1.0.5"]                           ; Optional, just for HTML

                 [mvxcvi/puget "1.0.2"]
                 [hickory "0.7.1"]

                 ;;; Transit deps optional; may be used to aid perf. of larger data payloads
                 ;;; (see reference example for details):
                 [com.cognitect/transit-clj "0.8.300"]
                 [com.cognitect/transit-cljs "0.8.243"]]

  :plugins [[lein-pprint "1.2.0"]
            [lein-ancient "0.6.14"]
            [lein-cljsbuild "1.1.7"]]

  :cljsbuild {:builds [{:id           :cljs-client
                        :source-paths ["src"]
                        :compiler     {:output-to     "target/main.js"
                                       :optimizations :whitespace #_:advanced
                                       :pretty-print  true}}]}

  :main socket-server.server

  ;; Call `lein start-repl` to get a (headless) development repl that you can
  ;; connect to with Cider+emacs or your IDE of choice:
  :aliases {"start-repl" ["do" "clean," "cljsbuild" "once," "repl" ":headless"]
            "start"      ["do" "clean," "cljsbuild" "once," "run"]}

  :repositories {"sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"})


