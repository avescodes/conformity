(defproject io.rkn/conformity "0.5.4"
  :description "Idempotent datom transacting for Datomic.\n\nSpecial thanks to Stuart Halloway for the original idea, implementation and permission to take it and run."
  :url "http://github.com/rkneufeld/conformity"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.5.1"]
                                  [com.datomic/datomic-free "0.9.4815.12"]
                                  [org.clojure/tools.namespace "0.2.3"]]
                   :source-paths ["dev"]}})
