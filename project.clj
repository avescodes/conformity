(defproject io.rkn/conformity "0.3.1-SNAPSHOT"
  :description "(Mostly) idempotent datom transacting for Datomic.\n\nSpecial thanks to Stuart Halloway for the original idea, implementation and permission to take it and run."
  :url "http://github.com/rkneufeld/conformity"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [com.datomic/datomic-free "0.8.4007"]]
  :profiles {:dev {:dependencies [[org.clojure/tools.namespace "0.2.3"]]
                   :source-paths ["dev"]}})
