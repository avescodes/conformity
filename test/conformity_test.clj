(ns conformity-test
  (:use clojure.test
        conformity
        [datomic.api :only [db] :as d]))

(def uri  "datomic:mem://test")
(defn fresh-conn []
  (d/delete-database uri)
  (d/create-database uri)
  (d/connect uri))

(def sample-norms-map {:test/norm-1 {:txes [[]]}})
(def sample-norms (keys sample-norms-map))
(deftest test-conforms-to?
  (testing "returns true if a norm is already installed"
    (let [conn (fresh-conn)]
      (ensure-conformity-attribute conn :test/conformity)
      (ensure-conforms conn :test/conformity {}))
    (is ))
  (testing "returns false if a norm has not been installed"
    (is false))
  (testing "returns false if conformity-attr does not exist"
    (is false)))

(deftest test-ensure-conforms
  (testing "installs all norm expected" (is false))
  (testing "throws exception if norm-map lacks transactions for a norm" (is false)))

;; How do you test private functions?
(def has-attribute? (ns-resolve 'conform 'has-attribute?))
(deftest test-has-attribute?
  (testing "is true when attribute exists in db" (is false))
  (testing "is false when attribute isn't in db" (is false)))

(def ensure-conformity-attribute (ns-resolve 'conform 'ensure-conform-attribute))
(deftest test-ensure-conform-attribute
  (testing "it adds the conformity attribute if it is absent")
  (testing "it does nothing if the conformity attribute exists"))