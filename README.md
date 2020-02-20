# conformity

A Clojure/Datomic library for idempotently transacting datoms (norms) into your database – be they schema, data, or otherwise.

In the simplest sense, conformity allows you to write migrations and ensure that they run once and only once.

In a more general sense, conformity allows you to declare expectations (in the form of norms) about the state of your database, and enforce those idempotently without repeatedly transacting schema, required data, etc.

## Dependency

Conformity is available on clojars, and can be included in your leiningen `project.clj` by adding the following to `:dependencies`:

[![Clojars Project](http://clojars.org/io.rkn/conformity/latest-version.svg)](http://clojars.org/io.rkn/conformity)


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

(def norms-map (c/read-resource "something.edn"))

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
(def norms-map (c/read-resource "something.edn"))

(c/has-attribute? (d/db conn) :something/title)
; -> false

(c/ensure-conforms conn norms-map [:my-project/something-schema])
(c/has-attribute? (d/db conn) :something/title)
; -> true
```

### Migrations as code

Instead of using the `:txes` key to point to an inline transaction, you can also use a `:txes-fn` key pointing to a symbol reference to a function, as follows...

```clojure
;; resources/something.edn
{:my-project/something-else-schema
  {:txes-fn my-project.migrations.txes/everyone-likes-orange-instead}}
```

`everyone-likes-orange-instead` will be passed the Datomic connection and should return transaction data, allowing transactions to be driven by full-fledged inspection of the database.

For example...

```clojure
(ns my-project.migrations.txes
  (:require [datomic.api :as d])

(def attr-q
  '[:find ?e
    :in $ ?attr ?v
    :where
    [?e ?attr ?v]])

(defn find-eids-with-val-for-attr
  [db attr val]
  (map first
       (d/q attr-q db attr val)))

(defn everyone-likes-orange-instead
  "Everybody who liked green now likes orange instead."
  [conn]
  (let [green-eids (find-eids-with-val-for-attr
                    (d/db conn)
                    :preferences/color
                    "green")]
    [(for [eid green-eids]
       [:db/add eid
        :preferences/color "orange"])]))
```


### Schema dependencies

Norms can also carry a `:requires` attribute, which points to the keyword/ident of some other such map which it depends on having been already transacted before it can be. This is declarative; Once specified in the map passed to `ensure-conforms`, confirmity handles the rest.

### Norms versioning

Most of the time your norms are supposed to be ran only once.
Once a map gets conformed, a trace of that event will be left in Datomic.
Then every trial of conforming the same norm will be ignored.

However keep in mind that:

1. Conformity upon every `ensure-conforms` call will count how many `txes`
(meaning number of collections of new facts, not a number of facts in one collection)
you're trying to do. If that number is equal as it was before, nothing will happen.

   If it's not, then the norm will be considered not fully transacted and each element from `txes` will
get transacted again. **WARNING:** you shouldn't use it to alter migrations effect. It's meant to make up
for conforms not being atomic. If you need to _fix_ your past migrations then write a new migration for it.

2. To be capable of the above, Conformity has to evaluate `:txes-fn` every time `ensure-conforms` is ran.
If you're sure you don't want to update your norms,
add `:first-time-only` additional parameter to the norm map, as follows:

```clojure
{:my/migration
 {:txes [[{:db/id [:foo/id "foo"]
           :foo/bool true}]]
  :first-time-only true}}
```

### Maintaining txInstant

In Datomic it is possible to do an [initial import of existing data that has its own timestamps](https://docs.datomic.com/on-prem/best-practices.html#set-txinstant-on-imports), where the timestamps are used as the `:db/txInstant` value. To enable the use of Conformity *before* the initial import, a custom `:db/txInstant` value (rather than the transactor's clock time) is necessary. This is because `:db/txInstant` cannot be set to a value that is older than any existing transaction.

This can be done by passing the `tx-instant` argument:

```clojure
(def conformity-attr (c/default-conformity-attribute-for-db (d/db conn)))
(def tx-instant (c/last-tx-instant (d/db conn)))  
(c/ensure-conforms conn conformity-attr norms-map (keys norms-map) tx-instant)
```

## License

Copyright © 2012-2014 Ryan Neufeld

Distributed under the Eclipse Public License, the same as Clojure.
