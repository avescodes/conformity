# conformity

A Clojure/Datomic library for idempotently\* transacting datoms (norms) into your database – be they schema, data, or otherwise.

In the simplest sense, conformity allows you to write schema migrations and ensure that they run once and only  once.

In a more general sense, conformity allows you to declare expectations (in the form of norms) about the state of your database, and enforce those idempotently without repeatedly transacting schema, required data, etc.


>\* I say idempotent in the sense that running `ensure-conforms` repeatedly in serial will not transact your norms more than once. If you do so in parallel I make no guarantees about behavior.

## Dependency

Conformity is available on clojars, and can be included in your leiningen `project.clj` by adding the following to `:dependencies`:

    [conformity "0.1.0"]
    
    

## Usage

The easiest way to use conformity is to store your norms in a datom that lives in your `resources/` folder.

    # resources/something.dtm
    
    {:my-project/something-schema
     {:txes [[{:db/id #db/id [:db.part/db]
                :db/ident :something/title
                :db/valueType :db.type/string
                :db/cardinality :db.cardinality/one
                :db/index false
                :db.install/_attribute :db.part/db}]]}}
    
Then in your code:

    # src/my_project/something.clj
    
    (ns my-project.something
      (:use [conformity :as c]))
      
    (defn load-resource [filename] (read-string (slurp (clojure.java.io/reader (clojure.java.io/resource filename)))))
    (def norms (load-resource "something.dtm"))
    
    (c/ensure-conforms conn norms [:my-project/something-schema]))
    
    # ... Code dependant on the presence of attributes in :my-project/something-schema

You can see this more directly illustrated in a console…
    
    ; nREPL 0.1.5
    
    ; Setup a in-memory db
    user> (use '[datomic.api :as d])
    user> (def uri "datomic:mem//my-project")
    user> (d/create-database uri)
    user> (def conn (d/connect uri))
    
    ; Hook up conformity and your sample datom
    user> (use '[conformity :as c])
    user> (defn load-resource [filename] (read-string (slurp (clojure.java.io/reader (clojure.java.io/resource filename)))))
    user> (def norms-map (load-resource "something.dtm"))
    
    user> (c/has-attribute? (db conn) :something/title)
    false
    
    user> (c/ensure-conforms conn norms-map [:my-project/something-schema])
    user> (c/has-attribute? (db conn) :something/title)
    true
    
### Caveat: Norms only get conformed-to once!

Once a norm is conformed to that's it! *It won't be transacted again*. That does mean that **you shouldn't edit a norm and expect it to magically get updated** the next time `ensure-conforms` runs.

In the future you may be able to intelligently version norms themselves, but I had to draw the line somewhere for the initial release. 

## But I use datomic-pro!

Awesome, I love you!

Unfortunately there isn't an easy way to rely on either pro or free, so I decided to choose datomic-free for the least friction.

If you're using the pro version of Datomic you'll need to exclude the datomic-free dependency introduced by depending on conformity like so:

    # project.clj, inside your :dependencies map…
    [conformity "0.1.0" :exclusions [com.datomic/datomic-free]]
    
## License

Copyright © 2012 Ryan Neufeld

Distributed under the Eclipse Public License, the same as Clojure.
