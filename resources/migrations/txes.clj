(ns migrations.txes
  (:require [datomic.api :as d]))

(defn attr
  [prefix ident]
  [{:db/id (d/tempid :db.part/db)
    :db/ident (keyword prefix ident)
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db.install/_attribute :db.part/db}])

(defn txes-foo [conn]
  (vector (vec
           (mapcat (partial attr "txes-fn")
                   ["foo-1" "foo-2"]))))

(defn txes-bar [conn]
  (vector (vec
           (mapcat (partial attr "txes-fn")
                   ["bar-1" "bar-2"]))) )
