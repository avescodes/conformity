(ns io.rkn.conformity-test
  (:require [clojure.test :refer :all]
            [io.rkn.conformity :refer :all]
            [datomic.api :refer [q db] :as d]))

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
      (try
        (ensure-conforms conn sample-norms-map1 [:test2/norm2])
        (is false "ensure-conforms should have thrown an exception")
        (catch Exception _
          (is true "Blew up like it was supposed to."))))))
