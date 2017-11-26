(ns migrations.requiring
  (:require [datomic.api :as d]))

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
