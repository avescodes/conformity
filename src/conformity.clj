(ns conformity
  (:use [datomic.api :only [q db] :as d]))

(defn- has-attribute?
    "Does database have an attribute named attr-name?"
    [db attr-name]
    (-> (d/entity db attr-name)
        :db.install/_attribute
        boolean))

(defn- ensure-conformity-attribute
  "Ensure that conformity-attr, a keyword-valued attribute used
   as a value on transactions to track named norms, is
   installed in database."
  [conn conformity-attr]
  (when-not (has-attribute? (db conn) conformity-attr)
    (d/transact conn [{:db/id #db/id [:db.part/db]
                     :db/ident conformity-attr
                     :db/valueType :db.type/keyword
                     :db/cardinality :db.cardinality/one
                     :db/doc "Name of schema installed by this transaction"
                     :db/index true
                     :db.install/_attribute :db.part/db}])))

(defn conforms-to?
  "Does database have a schema named norm installed?
   Uses conformity-attr (an attribute added to transactions!) to track
   which schema names are installed."
  [db conformity-attr norm]
  (and (has-attribute? db conformity-attr)
       (-> (q '[:find ?e
                :in $ ?sa ?sn
                :where [?e ?sa ?sn ?e]]
              db conformity-attr norm)
           seq boolean)))

(defn ensure-conforms
  "Ensure that norms represented as datoms are conformed-to (installed), be they
   schema, data or otherwise.

      conformity-attr  the keyword-valued attribute in which
                       enacted norms will be recorded
      norm-map         a map from norm names to data maps.
                       the data map contains two keys:
                         :txes     - the data to install
                         :requires - a list of other norms to conform to
      norms            the names of norms to conform to"
  [conn conformity-attr norm-map & names]
  (ensure-conformity-attribute conn conformity-attr)
  (doseq [norm names]
    (when-not (conforms-to? (db conn) conformity-attr norm)
      (let [{:keys [requires txes]} (get norm-map norm)]
        (apply ensure-conforms conn conformity-attr norm-map requires)
        (if txes
          (doseq [tx txes]
            ;; hrm, could mark the last tx specially
            (d/transact conn (cons {:db/id (d/tempid :db.part/tx)
                                  conformity-attr norm}
                                 tx)))
          (throw (ex-info (str "No data provided for norm" norm)
                          {:schema/missing norm})))))))
