(defproject conformity "0.0.1"
  :description "(Mostly) idempotent datom transacting for Datomic.\n\nSpecial thanks to Stuart Halloway for the original idea, implementation and permission to take it and run."
  :url "http://github.com/rkneufeld/conformity"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [com.datomic/datomic-free "0.8.3551"]]
  :profiles {:dev {:dependencies [[org.clojure/tools.namespace "0.2.0"]]
                   :source-paths ["dev"]}})
