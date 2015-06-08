# conformity

A Clojure/Datomic library for idempotently transacting datoms (norms) into your database – be they schema, data, or otherwise.

In the simplest sense, conformity allows you to write schema migrations and ensure that they run once and only once.

In a more general sense, conformity allows you to declare expectations (in the form of norms) about the state of your database, and enforce those idempotently without repeatedly transacting schema, required data, etc.

## Dependency

Conformity is available on clojars, and can be included in your leiningen `project.clj` by adding the following to `:dependencies`:
```clojure
[io.rkn/conformity "0.3.4"]
```


## Usage

The easiest way to use conformity is to store your norms in an edn file that lives in your `resources/` folder.
 
```clojure
;; resources/something.edn
{:my-project/something-schema
  {:txes [[{:db/id #db/id [:db.part/db]
            :db/ident :something/title
            :db/valueType :db.type/string
            :db/cardinality :db.cardinality/one
            :db/index false
            :db.install/_attribute :db.part/db}]]}}
```
Then in your code:
# src/my_project/something.clj
```clojure
(ns my-project.something
  (:require [io.rkn.conformity :as c]
            [datomic.api :as d]))

(def uri "datomic:mem://my-project")
(d/create-database uri)
(def conn (d/connect uri))

(defn load-resource [filename] (read-string (slurp (clojure.java.io/reader (clojure.java.io/resource filename)))))
(def norms-map (load-resource "something.edn"))

(println (str "Has attribute? " (c/has-attribute? (d/db conn) :something/title)))
(c/ensure-conforms conn norms-map [:my-project/something-schema])
(println (str "Has attribute? " (c/has-attribute? (d/db conn) :something/title)))

; ... Code dependant on the presence of attributes in :my-project/something-schema
```
You can see this more directly illustrated in a console…
```clojure
; nREPL 0.1.5

; Setup a in-memory db
(require '[datomic.api :as d])
(def uri "datomic:mem://my-project")
(d/create-database uri)
(def conn (d/connect uri))

; Hook up conformity and your sample datom
(require '[io.rkn.conformity :as c])
(defn load-resource [filename] (read-string (slurp (clojure.java.io/reader (clojure.java.io/resource filename)))))
(def norms-map (load-resource "something.edn"))

(c/has-attribute? (db conn) :something/title)
; -> false

(c/ensure-conforms conn norms-map [:my-project/something-schema])
(c/has-attribute? (db conn) :something/title)
; -> true
```
### Caveat: Norms only get conformed-to once!

Once a norm is conformed to that's it! *It won't be transacted again*. That does mean that **you shouldn't edit a norm and expect it to magically get updated** the next time `ensure-conforms` runs.

In the future you may be able to intelligently version norms themselves, but I had to draw the line somewhere for the initial release.

## License

Copyright © 2012-2014 Ryan Neufeld

Distributed under the Eclipse Public License, the same as Clojure.
