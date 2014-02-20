(ns io.rkn.conformity
  (:use [datomic.api :only [q db] :as d]))

(def default-conformity-attribute :confirmity/conformed-norms)

(defn has-attribute?
    "Check if a database has an attribute named attr-name"
    [db attr-name]
    (-> (d/entity db attr-name)
        :db.install/_attribute
        boolean))

(defn ensure-conformity-attribute
  "Ensure that conformity-attr, a keyword-valued attribute,
   is installed in the database."
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
                       the data map contains one keys:
                         :txes     - the data to install
      norm-names       A collection of names of norms to conform to"
  ([conn norm-map norm-names] (ensure-conforms conn default-conformity-attribute norm-map norm-names))
  ([conn conformity-attr norm-map norm-names]
     (ensure-conformity-attribute conn conformity-attr)
     (doseq [norm norm-names]
       (when-not (conforms-to? (db conn) conformity-attr norm)
         (let [{:keys [txes requires]} (get norm-map norm)]
           (when requires
             (ensure-conforms conn conformity-attr norm-map requires))
           (if txes
             (doseq [tx txes]
               ;; hrm, could mark the last tx specially
               @(d/transact conn (cons {:db/id (d/tempid :db.part/tx)
                                        conformity-attr norm}
                                       tx)))
             (throw (ex-info (str "No data provided for norm " norm)
                             {:schema/missing norm}))))))))

