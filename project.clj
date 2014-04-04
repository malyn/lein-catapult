(defproject lein-catapult "0.0.1"
  :description "TCP/IP -> Drawbridge nREPL proxy."
  :url "https://github.com/malyn/lein-catapult"
  :license {:name "BSD"
            :url "http://www.opensource.org/licenses/BSD-3-Clause"
            :distribution :repo }

  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/core.async "0.1.267.0-0d7780-alpha"]
                 [org.clojure/tools.nrepl "0.2.3"]
                 [com.cemerick/drawbridge "0.0.6"]
                 [me.raynes/fs "1.4.5"]]

  :eval-in-leiningen true)
