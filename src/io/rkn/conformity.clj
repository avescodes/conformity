(ns io.rkn.conformity
  (:require [datomic.api :refer [q db] :as d]
            [clojure.java.io :as io]))

(def default-conformity-attribute :confirmity/conformed-norms)
(def conformity-ensure-norm-tx :conformity/ensure-norm-tx)

(def ensure-norm-tx-txfn
  "transaction function to ensure each norm tx is executed exactly once"
  (d/function
   '{:lang :clojure
     :params [db norm-attr norm index-attr index tx]
     :code (when-not (seq (q '[:find ?tx
                               :in $ ?na ?nv ?ia ?iv
                               :where [?tx ?na ?nv ?tx] [?tx ?ia ?iv ?tx]]
                             db norm-attr norm index-attr index))
             tx)}))

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
    "Check if a database has an attribute named attr-name"
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
                       :db/doc "Name of schema installed by this transaction"
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

      conformity-attr  (optional) the keyword name of the attribute used to track
                       conformity
      norm             the keyword name of the norm you want to check"
  ([db norm] (conforms-to? db default-conformity-attribute norm))
  ([db conformity-attr norm]
     (and (has-attribute? db conformity-attr)
          (-> (q '[:find ?e
                   :in $ ?sa ?sn
                   :where [?e ?sa ?sn ?e]]
                 db conformity-attr norm)
              seq boolean))))

(defn ensure-conforms
  "Ensure that norms represented as datoms are conformed-to (installed), be they
   schema, data or otherwise.

      conformity-attr  (optional) the keyword-valued attribute where conformity
                       tracks enacted norms.
      norm-map         a map from norm names to data maps.
                       the data map contains:
                         :txes     - the data to install
                         :requires - (optional) The name of a prerequisite norm
                                     in norm-map.
      norm-names       (optional) A collection of names of norms to conform to.
                       Will use keys of norm-map if not provided."
  ([conn norm-map] (ensure-conforms conn norm-map (keys norm-map)))
  ([conn norm-map norm-names] (ensure-conforms conn default-conformity-attribute norm-map norm-names))
  ([conn conformity-attr norm-map norm-names]
     (doseq [norm norm-names]
       (when-not (conforms-to? (db conn) conformity-attr norm)
         (let [{:keys [txes requires]} (get norm-map norm)]
           (when requires
             (ensure-conforms conn conformity-attr norm-map requires))
           (if txes
             (doseq [tx txes]
               (try
                 ;; hrm, could mark the last tx specially
                 @(d/transact conn (cons {:db/id (d/tempid :db.part/tx)
                                          conformity-attr norm}
                                         tx))
                 (catch Throwable t
                   (throw (ex-info (.getMessage t) {:tx tx} t)))))
             (throw (ex-info (str "No data provided for norm " norm)
                             {:schema/missing norm}))))))))

   (ensure-conformity-schema conn conformity-attr)
