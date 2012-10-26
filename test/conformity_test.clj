(ns conformity-test
  (:use clojure.test
        conformity
        [datomic.api :only [q db] :as d]))

(def uri  "datomic:mem://test")
(defn fresh-conn []
  (d/delete-database uri)
  (d/create-database uri)
  (d/connect uri))

(def sample-norms-map {:test/norm-1
                       {:txes [[{:db/id #db/id [:db.part/db]
                                 :db/ident :test/attribute
                                 :db/valueType :db.type/string
                                 :db/cardinality :db.cardinality/one
                                 :db/fulltext false
                                 :db/index false
                                 :db.install/_attribute :db.part/db}]]}})

(deftest test-ensure-conforms
  (testing "installs all norm expected"
    (let [conn (fresh-conn)]
      (ensure-conforms conn sample-norms-map [:test/norm-1])
      (is (= 1 (count (q '[:find ?e :where [?e :db/ident :test/attribute]] (db conn)))))))
  
  (testing "throws exception if norm-map lacks transactions for a norm"
    (let [conn (fresh-conn)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No data provided for norm :test/norm-2"
            (ensure-conforms conn {:test/norm-2 {}} [:test/norm-2]))))))

(deftest test-conforms-to?
  (testing "returns true if a norm is already installed"
    (let [conn (fresh-conn)]
      (ensure-conforms conn sample-norms-map [:test/norm-1])
      (is (= true (conforms-to? (db conn) :test/norm-1)))))
  
  (testing "returns false if a norm has not been installed"
    (let [conn (fresh-conn)]
      (ensure-conformity-attribute conn default-conformity-attribute)
      (is (= false (conforms-to? (db conn) :test/norm-1)))))
  
  (testing "returns false if conformity-attr does not exist"
    (let [conn (fresh-conn)]
      (is (= false (conforms-to? (db conn) :test/norm-1))))))

(deftest test-ensure-conform-attribute
  (testing "it adds the conformity attribute if it is absent"
    (let [conn (fresh-conn)]
      (ensure-conformity-attribute conn :test/conformity)
      (is (= true (has-attribute? (db conn) :test/conformity))))) 

  (testing "it does nothing if the conformity attribute exists"
    (defn count-txes [conn] (count (q '[:find ?tx :where [?tx :db/txInstant _]] (db conn))))
    (let [conn (fresh-conn)]
      (ensure-conformity-attribute conn :test/conformity)
      (is (= (count-txes conn) (do (ensure-conformity-attribute conn :test/conformity) (count-txes conn)))))))