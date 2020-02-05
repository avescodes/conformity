(ns io.rkn.conformity-test
  (:require [clojure.test :refer :all]
            [io.rkn.conformity :refer :all]
            [datomic.api :refer [q db] :as d]
            [migrations.txes :refer [txes-foo txes-bar]]))

(def uri  "datomic:mem://test")
(defn fresh-conn []
  (d/delete-database uri)
  (d/create-database uri)
  (d/connect uri))

(defn attr
  ([ident]
   (attr ident :db.type/string))
  ([ident value-type]
   [{:db/id (d/tempid :db.part/db)
     :db/ident ident
     :db/valueType value-type
     :db/cardinality :db.cardinality/one
     :db.install/_attribute :db.part/db}]))

(def sample-norms-map1 {:test1/norm1
                        {:txes [(attr :test/attribute1)
                                (attr :test/attribute2)]}
                        :test1/norm2
                        {:txes [(attr :test/attribute3)]}})

(def sample-norms-map2 {:test2/norm1
                        {:txes [(attr :test/attribute1)]}
                        :test2/norm2 ;; Bad data type - should 'splode
                        {:txes [(attr :test/attribute2 :db.type/nosuch)]}})

(def sample-norms-map3 {:test3/norm1
                        {:txes [(attr :test/attribute1)
                                (attr :test/attribute2)]}
                        :test3/norm2
                        {:txes [(attr :test/attribute3)]
                         :requires [:test3/norm1]}})

(def sample-norms-map5 {:test4/norm1
                        {:txes [[{:db/id (d/tempid :db.part/db)
                                  :db/ident :test/unique-attribute
                                  :db/valueType :db.type/string
                                  :db/cardinality :db.cardinality/one
                                  :db.install/_attribute :db.part/db}]
                                [{:db/id :test/unique-attribute
                                  :db/index true
                                  :db.alter/_attribute :db.part/db}]
                                [{:db/id :test/unique-attribute
                                  :db/unique :db.unique/value
                                  :db.alter/_attribute :db.part/db}]]}})

(def sample-norms-map-txes-fns {:test-txes-fn/norm1
                                {:txes-fn 'migrations.txes/txes-foo}
                                :test-txes-fn/norm2
                                {:txes-fn 'migrations.txes/txes-bar}})

(deftest test-ensure-conforms
  (testing "installs all expected norms"

    (testing "without explicit norms list"
      (let [conn (fresh-conn)
            result (ensure-conforms conn sample-norms-map1)]
        (is (= (set (map (juxt :norm-name :tx-index) result))
               (set [[:test1/norm1 0] [:test1/norm1 1] [:test1/norm2 0]])))
        (is (has-attribute? (db conn) :test/attribute1))
        (is (has-attribute? (db conn) :test/attribute2))
        (is (has-attribute? (db conn) :test/attribute3))
        (is (empty? (ensure-conforms conn sample-norms-map1)))))

    (testing "can add db/unique after an avet index add"
      (let [conn (fresh-conn)
            result (ensure-conforms conn sample-norms-map5)]
        (is (has-attribute? (db conn) :test/unique-attribute))))

    (testing "with explicit norms list"
      (let [conn (fresh-conn)
            result (ensure-conforms conn sample-norms-map2 [:test2/norm1])]
        (is (= (map (juxt :norm-name :tx-index) result)
               [[:test2/norm1 0]]))
        (is (has-attribute? (db conn) :test/attribute1))
        (is (not (has-attribute? (db conn) :test/attribute2)))
        (is (empty? (ensure-conforms conn sample-norms-map2 [:test2/norm1]))))

      (testing "and requires"
        (let [conn (fresh-conn)
              result (ensure-conforms conn sample-norms-map3 [:test3/norm2])]
          (is (= (map (juxt :norm-name :tx-index) result)
                 [[:test3/norm1 0] [:test3/norm1 1] [:test3/norm2 0]]))
          (is (has-attribute? (db conn) :test/attribute1))
          (is (has-attribute? (db conn) :test/attribute2))
          (is (has-attribute? (db conn) :test/attribute3))
          (is (empty? (ensure-conforms conn sample-norms-map3
                                       [:test3/norm2])))))))

  (testing "throws exception if norm-map lacks transactions for a norm"
    (let [conn (fresh-conn)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No transactions provided for norm :test4/norm1"
                            (ensure-conforms conn {}
                                             [:test4/norm1])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No transactions provided for norm :test4/norm1"
                            (ensure-conforms conn {:test4/norm1 {}}
                                             [:test4/norm1])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No transactions provided for norm :test4/norm1"
                            (ensure-conforms conn {:test4/norm1 {:txes []}}
                                             [:test4/norm1])))))

  (testing "it uses the corrected spelling of the default-conformity-attribute on a new db"
    (let [conn (fresh-conn)]
      (ensure-conforms conn sample-norms-map1)
      (is (= false (has-attribute? (db conn) :confirmity/conformed-norms)))
      (is (= true (has-attribute? (db conn) :conformity/conformed-norms)))))

  (testing "it continues to use the bad spelling of the default-conformity-attribute if already installed"
    (let [conn (fresh-conn)]
      (ensure-conforms conn :confirmity/conformed-norms sample-norms-map1 (keys sample-norms-map1))
      (is (= true (has-attribute? (db conn) :confirmity/conformed-norms)))
      (is (= false (has-attribute? (db conn) :conformity/conformed-norms)))
      (ensure-conforms conn sample-norms-map1)
      (is (= true (has-attribute? (db conn) :confirmity/conformed-norms)))
      (is (= false (has-attribute? (db conn) :conformity/conformed-norms)))))

  (testing "with tx-instant"
    (let [conn (fresh-conn)
          before (last-tx-instant (db conn))]
      (ensure-conforms conn :conformity/conformed-norms sample-norms-map1
        (keys sample-norms-map1) before)
      (is (has-attribute? (db conn) :test/attribute1))
      (is (has-attribute? (db conn) :test/attribute2))
      (is (= before (last-tx-instant (db conn)))))))

(deftest test-with-conforms
  (testing "speculatively installs all expected norms"

    (testing "without explicit norms list"
      (let [{:keys [db result]} (with-conforms (d/db (fresh-conn)) sample-norms-map1)]
        (is (= (set (map (juxt :norm-name :tx-index) result))
               (set [[:test1/norm1 0] [:test1/norm1 1] [:test1/norm2 0]])))
        (is (has-attribute? db :test/attribute1))
        (is (has-attribute? db :test/attribute2))
        (is (has-attribute? db :test/attribute3))
        (is (empty? (:result (with-conforms db sample-norms-map1))))))

    (testing "can add db/unique after an avet index add"
      (let [{:keys [db result]} (with-conforms (d/db (fresh-conn)) sample-norms-map5)]
        (is (has-attribute? db :test/unique-attribute))))

    (testing "with explicit norms list"
      (let [{:keys [db result]} (with-conforms (d/db (fresh-conn)) sample-norms-map2 [:test2/norm1])]
        (is (= (map (juxt :norm-name :tx-index) result)
               [[:test2/norm1 0]]))
        (is (has-attribute? db :test/attribute1))
        (is (not (has-attribute? db :test/attribute2)))
        (is (empty? (:result (with-conforms db sample-norms-map2 [:test2/norm1])))))

      (testing "and requires"
        (let [{:keys [db result]} (with-conforms (d/db (fresh-conn)) sample-norms-map3 [:test3/norm2])]
          (is (= (map (juxt :norm-name :tx-index) result)
                 [[:test3/norm1 0] [:test3/norm1 1] [:test3/norm2 0]]))
          (is (has-attribute? db :test/attribute1))
          (is (has-attribute? db :test/attribute2))
          (is (has-attribute? db :test/attribute3))
          (is (empty? (:result (with-conforms db sample-norms-map3
                                 [:test3/norm2]))))))))

  (testing "throws exception if norm-map lacks transactions for a norm"
    (let [db (d/db (fresh-conn))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No transactions provided for norm :test4/norm1"
                            (with-conforms db {}
                                             [:test4/norm1])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No transactions provided for norm :test4/norm1"
                            (with-conforms db {:test4/norm1 {}}
                                             [:test4/norm1])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No transactions provided for norm :test4/norm1"
                            (with-conforms db {:test4/norm1 {:txes []}}
                                             [:test4/norm1]))))))

(deftest test-conforms-to?
  (let [tx-count (count (:txes (sample-norms-map1 :test1/norm1)))]
    (testing "returns true if a norm is already installed"
      (let [conn (fresh-conn)]
        (ensure-conforms conn sample-norms-map1 [:test1/norm1])
        (is (= true (conforms-to? (db conn) :test1/norm1 tx-count)))))

    (testing "returns false if"
      (testing "a norm has not been installed"
        (let [conn (fresh-conn)]
          (ensure-conformity-schema conn default-conformity-attribute)
          (is (= false (conforms-to? (db conn) :test1/norm1 tx-count)))))

      (testing "conformity-attr does not exist"
        (let [conn (fresh-conn)]
          (is (= false (conforms-to? (db conn) :test1/norm1 tx-count))))))))

(deftest test-ensure-conformity-schema
  (testing "it adds the conformity schema if it is absent"
    (let [conn (fresh-conn)
          _ (ensure-conformity-schema conn :test/conformity)
          db (db conn)]
      (is (has-attribute? db :test/conformity))
      (is (has-attribute? db :test/conformity-index))
      (is (has-function? db conformity-ensure-norm-tx))))

  (testing "it does nothing if the conformity schema exists"
    (let [conn (fresh-conn)
          count-txes (fn [db]
                       (-> (q '[:find ?tx
                                :where [?tx :db/txInstant]]
                              db)
                           count))
          _ (ensure-conformity-schema conn :test/conformity)
          before (count-txes (db conn))
          _ (ensure-conformity-schema conn :test/conformity)
          after (count-txes (db conn))]
      (is (= before after)))))

(deftest test-fails-on-bad-norm
  (testing "It explodes when you pass it a bad norm"
    (let [conn (fresh-conn)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #":db.error/not-an-entity Unable to resolve entity: :db.type/nosuch"
                            (ensure-conforms conn sample-norms-map2 [:test2/norm2]))))))

(deftest test-loads-norms-from-a-resource
  (testing "loads a datomic schema from edn in a resource"
    (let [sample-norms-map4 (read-resource "sample4.edn")
          tx-count (count (:txes (sample-norms-map4 :test4/norm1)))
          conn (fresh-conn)]
      (is (ensure-conforms conn sample-norms-map4))
      (is (conforms-to? (db conn) :test4/norm1 tx-count))
      (let [tx-meta {:test/user "bob"}
            tx-result @(d/transact conn
                                   [[:test/transaction-metadata tx-meta]
                                    {:db/id (d/tempid :db.part/user)
                                     :test/attribute1 "forty two"}])
            rel (d/q '[:find ?user ?val
                       :where
                       [_ :test/attribute1 ?val ?tx]
                       [?tx :test/user ?user]]
                     (db conn))
            [user val] (first rel)]
        (is (= "bob" user))
        (is (= "forty two" val)))))
  (testing "loads txes from from txes-fn reference in a resource"
    (let [sample-norms-map4 (read-resource "sample4.edn")
          conn (fresh-conn)]
      (is (ensure-conforms conn sample-norms-map4))
      (let [ret (d/q '[:find ?e
                       :where
                       [?e :db/ident :txes-fn/foo-1]]
                     (db conn))]
        (is (= 1 (count ret)))))))

(defn txes= [a b]
  (letfn [(drop-id [xs]
            (mapv #(dissoc % :db/id)
                  xs))]
    (= (map drop-id a)
       (map drop-id b))))

(deftest test-get-norm-gets-norms
  (testing "get-norm loads :txes key from norms-map"
    (is (= (:test1/norm1 sample-norms-map1)
           (get-norm (fresh-conn) sample-norms-map1 :test1/norm1))))
  (testing "get-norm loads :txes-fn key from norms-map"
    (let [conn (fresh-conn)]
      (is (txes= (txes-foo conn)
                 (:txes (get-norm conn sample-norms-map-txes-fns :test-txes-fn/norm1))))
      (is (txes= (txes-bar conn)
                 (:txes (get-norm conn sample-norms-map-txes-fns :test-txes-fn/norm2)))))))

(deftest test-requires-transacted-eagerly
  (testing "required norms transacted prior to evaluation of requiring `txes-fn` norms"
    (let [conn  (fresh-conn)
          norms (read-resource "requiring-sample.edn")]
      (ensure-conforms conn norms [:requiring/dependent])
      (let [ret (d/q '[:find ?v :with ?e :where [?e :preferences/color ?v]] (d/db conn))]
        (is (= [["orange"]] ret))))))

(deftest test-unchangeable-norms
  (let [norms-map {:test-unchangeable/prep
                   {:txes [[{:db/id (d/tempid :db.part/db)
                             :db/ident :foo/id
                             :db/valueType :db.type/string
                             :db/unique :db.unique/value
                             :db/cardinality :db.cardinality/one
                             :db.install/_attribute :db.part/db}
                            {:db/id (d/tempid :db.part/db)
                             :db/ident :foo/bool
                             :db/valueType :db.type/boolean
                             :db/cardinality :db.cardinality/one
                             :db.install/_attribute :db.part/db}
                            {:db/id (d/tempid :db.part/db)
                             :db/ident :foo/color
                             :db/valueType :db.type/string
                             :db/cardinality :db.cardinality/one
                             :db.install/_attribute :db.part/db}]]}
                   :test-unchangeable/populate
                   {:txes [[{:db/id (d/tempid :db.part/user)
                             :foo/id "bar"}]]
                    :requires [:test-unchangeable/prep]}

                   :test-unchangeable/migration
                   {:txes [[{:db/id [:foo/id "bar"]
                             :foo/bool true}]]
                    :requires [:test-unchangeable/populate]}}
        map-w-repeated-norm {:test-unchangeable/migration
                             {:txes [[{:db/id [:foo/id "bar"]
                                       :foo/bool true}]
                                     [{:db/id [:foo/id "bar"]
                                       :foo/color "green"}]]}}]
    (testing "conforming a norm which name's known to had been conformed once should be skipped"

      (testing "if norm isn't marked unchangeable, new element in txes will conform the norm again"
        (let [conn (fresh-conn)]
          (ensure-conforms conn norms-map)
          (is (= (:foo/bool
                   (d/entity (db conn) [:foo/id "bar"]))
                 true))
          (ensure-conforms conn map-w-repeated-norm)
          (is (= (:foo/color
                   (d/entity (db conn) [:foo/id "bar"]))
                 "green"))))

      (testing "if norm is marked unchangeable, new element in txes will not conform"
        (let [conn (fresh-conn)]
          (ensure-conforms conn norms-map)
          (is (= (:foo/bool
                   (d/entity (db conn) [:foo/id "bar"]))
                 true))
          (ensure-conforms conn
                           (assoc-in map-w-repeated-norm
                                     [:test-unchangeable/migration :first-time-only]
                                     true))
          (is (nil? (:foo/color
                      (d/entity (db conn) [:foo/id "bar"])))))))))
