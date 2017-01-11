(ns io.rkn.conformity
  (:require [datomic.api :refer [q db] :as d]
            [clojure.java.io :as io]))

(def default-conformity-attribute :conformity/conformed-norms)
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

(defn read-resource
  "Reads and returns data from a resource containing edn text. An
  optional argument allows specifying opts for clojure.edn/read"
  ([resource-name]
   (read-resource {:readers *data-readers*} resource-name))
  ([opts resource-name]
   (->> (io/resource resource-name)
        (io/reader)
        (java.io.PushbackReader.)
        (clojure.edn/read opts))))

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

(defn maybe-timeout-synch-schema [conn maybe-timeout]
  (if maybe-timeout
    (let [result (deref (d/sync-schema conn (d/basis-t (d/db conn))) maybe-timeout ::timed-out)]
      (if (= result ::timed-out)
        (throw (ex-info "Timed out calling synch-schema between conformity transactions" {:timeout maybe-timeout}))
        result))
    @(d/sync-schema conn (d/basis-t (d/db conn)))))

(defn reduce-txes
  "Reduces the seq of transactions for a norm into a transaction
  result accumulator"
  [acc conn norm-attr norm-name txes sync-schema-timeout]
  (reduce
   (fn [acc [tx-index tx]]
     (try
       (let [safe-tx [conformity-ensure-norm-tx
                      norm-attr norm-name
                      (index-attr norm-attr) tx-index
                      tx]
             _ (maybe-timeout-synch-schema conn sync-schema-timeout)
             tx-result @(d/transact conn [safe-tx])]
         (if (next (:tx-data tx-result))
           (conj acc {:norm-name norm-name
                      :tx-index tx-index
                      :tx-result tx-result})
           acc))
       (catch Throwable t
         (let [reason (.getMessage t)
               data {:succeeded acc
                     :failed {:norm-name norm-name
                              :tx-index tx-index
                              :reason reason}}]
           (throw (ex-info reason data t))))))
   acc (map-indexed vector txes)))

(defn reduce-norms
  "Reduces norms from a norm-map specified by a seq of norm-names into
  a transaction result accumulator"
  [acc conn norm-attr norm-map norm-names]
  (let [sync-schema-timeout (:conformity.setting/sync-schema-timeout norm-map)]
    (reduce
      (fn [acc norm-name]
        (let [{:keys [txes requires]} (get norm-map norm-name)]
          (cond (conforms-to? (db conn) norm-attr norm-name (count txes))
                acc
                (empty? txes)
                (let [reason (str "No transactions provided for norm " norm-name)
                      data {:succeeded acc
                            :failed {:norm-name norm-name
                                     :reason reason}}]
                  (throw (ex-info reason data)))
                :else
                (-> acc
                  (reduce-norms conn norm-attr norm-map requires)
                  (reduce-txes conn norm-attr norm-name txes sync-schema-timeout)))))
      acc norm-names)))

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
                       Will use keys of norm-map if not provided.

  On success, returns a vector of maps with values for :norm-name, :tx-index,
  and :tx-result for each transaction that improved the db's conformity.

  On failure, throws an ex-info with a reason and data about any partial
  success before the failure."
  ([conn norm-map]
   (ensure-conforms conn norm-map (keys norm-map)))
  ([conn norm-map norm-names]
   (ensure-conforms conn default-conformity-attribute norm-map norm-names))
  ([conn conformity-attr norm-map norm-names]
   (ensure-conformity-schema conn conformity-attr)
   (reduce-norms [] conn conformity-attr norm-map norm-names)))

(defn- speculative-conn
  "Creates a mock datomic.Connection that speculatively applies transactions using datomic.api/with"
  [db]
  (let [state (atom {:db-after db})
        wrap-listenable-future (fn [value]
                                 (reify datomic.ListenableFuture
                                   (get [this] value)
                                   (get [this timeout time-unit] value)
                                   (toString [this] (prn-str value))))]
    (reify datomic.Connection
      (db [_] (:db-after @state))
      (transact [_ tx-data]
        (let [tx-result-after (swap! state #(d/with (:db-after %) tx-data))]
          (wrap-listenable-future tx-result-after)))
      (sync [_] (wrap-listenable-future (:db-after @state)))
      (sync [_ t] (wrap-listenable-future (:db-after @state)))
      (syncSchema [_ t] (wrap-listenable-future (:db-after @state))))))

(defn with-conforms
  "Variation of ensure-conforms that speculatively ensures norm are conformed to

   On success, returns a map with:
     :db     the resulting database that conforms the the provided norms
     :result a vector of maps with values for :norm-name, :tx-index,
             and :tx-result for each transaction that improved the db's conformity.

   On failure, throws an ex-info with a reason and data about any partial
   success before the failure."
  ([db norm-map]
   (with-conforms db norm-map (keys norm-map)))
  ([db norm-map norm-names]
   (with-conforms db default-conformity-attribute norm-map norm-names))
  ([db conformity-attr norm-map norm-names]
   (let [conn (speculative-conn db)]
     (ensure-conformity-schema conn conformity-attr)
     (let [result (reduce-norms [] conn conformity-attr norm-map norm-names)]
       {:db (d/db conn)
        :result result}))))
