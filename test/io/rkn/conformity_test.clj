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
  (testing "installs all norm expected with explicit norms list"
    (let [conn (fresh-conn)]
      (ensure-conforms conn sample-norms-map [:test/norm-1])
      (is (= 1 (count (q '[:find ?e :where [?e :db/ident :test/attribute]] (db conn)))))))

  (testing "installs all norm expected with no explicit norms list"
    (let [conn (fresh-conn)]
      (ensure-conforms conn sample-norms-map2)
      (is (= 1 (count (q '[:find ?e :where [?e :db/ident :test/attribute]] (db conn)))))
      (is (= 1 (count (q '[:find ?e :where [?e :db/ident :test/attribute2]] (db conn)))))))

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

(deftest test-fails-on-bad-norm
  (testing "It explodes when you pass it a bad norm"
    (let [conn (fresh-conn)]
      (try
        (ensure-conforms conn sample-norms-map [:test/bad-norm-1])
        (is false "ensure-conforms should have thrown an exception")
        (catch Exception _
          (is true "Blew up like it was supposed to."))))))
