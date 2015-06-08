(ns io.rkn.conformity
  (:require [datomic.api :refer [q db] :as d]
            [clojure.java.io :as io]))

(def default-conformity-attribute :confirmity/conformed-norms)
(def conformity-ensure-norm-tx :conformity/ensure-norm-tx)

(def ensure-norm-tx-txfn
  "Transaction function to ensure each norm tx is executed exactly once"
  (d/function
   '{:lang :clojure
     :params [db norm-attr norm index-attr index tx]
     :code (when-not (seq (q '[:find ?tx
                               :in $ ?na ?nv ?ia ?iv
                               :where [?tx ?na ?nv ?tx] [?tx ?ia ?iv ?tx]]
                             db norm-attr norm index-attr index))
             (cons {:db/id (d/tempid :db.part/tx)
                    norm-attr norm
                    index-attr index}
                   tx))}))

(defn load-schema-rsc
  "Load an edn schema resource file"
  [resource-filename]
  (-> resource-filename
      io/resource
      slurp
      read-string))

(defn index-attr
  "Returns the index-attr corresponding to a conformity-attr"
  [conformity-attr]
  (keyword (namespace conformity-attr)
           (str (name conformity-attr) "-index")))

(defn has-attribute?
  "Returns true if a database has an attribute named attr-name"
  [db attr-name]
  (-> (d/entity db attr-name)
      :db.install/_attribute
      boolean))

(defn has-function?
  "Returns true if a database has a function named fn-name"
  [db fn-name]
  (-> (d/entity db fn-name)
      :db/fn
      boolean))

(defn ensure-conformity-schema
  "Ensure that the two attributes and one transaction function
  required to track conformity via the conformity-attr keyword
  parameter are installed in the database."
  [conn conformity-attr]
  (when-not (has-attribute? (db conn) conformity-attr)
    (d/transact conn [{:db/id (d/tempid :db.part/db)
                       :db/ident conformity-attr
                       :db/valueType :db.type/keyword
                       :db/cardinality :db.cardinality/one
                       :db/doc "Name of this transaction's norm"
                       :db/index true
                       :db.install/_attribute :db.part/db}]))
  (when-not (has-attribute? (db conn) (index-attr conformity-attr))
    (d/transact conn [{:db/id (d/tempid :db.part/db)
                       :db/ident (index-attr conformity-attr)
                       :db/valueType :db.type/long
                       :db/cardinality :db.cardinality/one
                       :db/doc "Index of this transaction within its norm"
                       :db/index true
                       :db.install/_attribute :db.part/db}]))
  (when-not (has-function? (db conn) conformity-ensure-norm-tx)
    (d/transact conn [{:db/id (d/tempid :db.part/user)
                       :db/ident conformity-ensure-norm-tx
                       :db/doc "Ensures each norm tx is executed exactly once"
                       :db/fn ensure-norm-tx-txfn}])))

(defn conforms-to?
  "Does database have a norm installed?

      conformity-attr  (optional) the keyword name of the attribute used to
                       track conformity
      norm             the keyword name of the norm you want to check
      tx-count         the count of transactions for that norm"
  ([db norm tx-count]
   (conforms-to? db default-conformity-attribute norm tx-count))
  ([db conformity-attr norm tx-count]
   (and (has-attribute? db conformity-attr)
        (pos? tx-count)
        (-> (q '[:find ?tx
                 :in $ ?na ?nv
                 :where [?tx ?na ?nv ?tx]]
               db conformity-attr norm)
            count
            (= tx-count)))))

(defn ensure-conforms
  "Ensure that norms represented as datoms are conformed-to (installed), be they
  schema, data or otherwise.

      conformity-attr  (optional) the keyword name of the attribute used to
                       track conformity
      norm-map         a map from norm names to data maps.
                       a data map contains:
                         :txes     - the data to install
                         :requires - (optional) a list of prerequisite norms
                                     in norm-map.
      norm-names       (optional) A collection of names of norms to conform to.
                       Will use keys of norm-map if not provided."
  ([conn norm-map]
   (ensure-conforms conn norm-map (keys norm-map)))
  ([conn norm-map norm-names]
   (ensure-conforms conn default-conformity-attribute norm-map norm-names))
  ([conn conformity-attr norm-map norm-names]
   (ensure-conformity-schema conn conformity-attr)
   (doseq [norm norm-names
           :let [{:keys [txes requires]} (get norm-map norm)
                 tx-count (count txes)]
           :when (not (conforms-to? (db conn) conformity-attr norm tx-count))]
     (when (zero? tx-count)
       (throw (ex-info (str "No data provided for norm " norm)
                       {:schema/missing norm})))
     (when requires
       (ensure-conforms conn conformity-attr norm-map requires))
     (doseq [[index tx] (map-indexed vector txes)]
       (try
         @(d/transact conn [[conformity-ensure-norm-tx
                             conformity-attr norm
                             (index-attr conformity-attr) index
                             tx]])
         (catch Throwable t
           (throw (ex-info (.getMessage t) {:tx tx} t))))))))
