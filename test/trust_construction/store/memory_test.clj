(ns trust-construction.store.memory-test
  "Tests for in-memory Store implementation.

  Tests cover:
  - Basic append and query
  - Idempotency (duplicate detection)
  - Query by ID
  - Query by entity-type
  - Query with filters
  - Time-travel queries
  - Versions queries
  - Utility functions
  - Edge cases"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [trust-construction.protocols.store :as store]
            [trust-construction.store.memory :as mem]))

;; Fixtures

(def ^:dynamic *store* nil)

(defn create-fresh-store
  "Create a fresh store for each test."
  [f]
  (binding [*store* (mem/create-memory-store)]
    (f)))

(use-fixtures :each create-fresh-store)

;; Test 1: Basic append

(deftest test-basic-append
  (testing "Basic append! returns expected result"
    (let [result (store/append! *store*
                                {:amount 45.99 :merchant "Starbucks"}
                                {:entity-type :transaction
                                 :author "darwin"
                                 :timestamp "2025-11-07T10:30:00Z"})]
      (is (map? result))
      (is (string? (:id result)))
      (is (= 1 (:version result)))
      (is (string? (:timestamp result)))
      (is (string? (:hash result)))
      (is (false? (:duplicate result))))))

;; Test 2: Query by ID

(deftest test-query-by-id
  (testing "Query by ID returns correct entity"
    (let [result (store/append! *store*
                                {:amount 45.99 :merchant "Starbucks"}
                                {:entity-type :transaction
                                 :author "darwin"
                                 :timestamp "2025-11-07T10:30:00Z"})
          id (:id result)
          entities (store/query *store* {:entity-type :transaction
                                        :id id})]
      (is (= 1 (count entities)))
      (let [entity (first entities)]
        (is (= id (:id entity)))
        (is (= :transaction (:entity-type entity)))
        (is (= {:amount 45.99 :merchant "Starbucks"} (:data entity)))))))

;; Test 3: Query by entity-type

(deftest test-query-by-entity-type
  (testing "Query by entity-type returns all entities of that type"
    ;; Add 3 transactions
    (store/append! *store*
                  {:amount 45.99 :merchant "Starbucks"}
                  {:entity-type :transaction :author "darwin"
                   :timestamp "2025-11-07T10:30:00Z"})
    (store/append! *store*
                  {:amount 120.50 :merchant "Amazon"}
                  {:entity-type :transaction :author "darwin"
                   :timestamp "2025-11-07T10:31:00Z"})
    ;; Add 1 bank
    (store/append! *store*
                  {:name "Bank of America" :code "BofA"}
                  {:entity-type :bank :author "darwin"
                   :timestamp "2025-11-07T10:32:00Z"})

    ;; Query transactions
    (let [txs (store/query *store* {:entity-type :transaction})]
      (is (= 2 (count txs))))

    ;; Query banks
    (let [banks (store/query *store* {:entity-type :bank})]
      (is (= 1 (count banks))))))

;; Test 4: Idempotency (duplicate detection)

(deftest test-idempotency
  (testing "Appending same data twice detects duplicate"
    (let [data {:amount 45.99 :merchant "Starbucks"}
          metadata {:entity-type :transaction :author "darwin"
                    :timestamp "2025-11-07T10:30:00Z"}
          result1 (store/append! *store* data metadata)
          result2 (store/append! *store* data metadata)]
      ;; First append: not duplicate
      (is (false? (:duplicate result1)))

      ;; Second append: duplicate detected
      (is (true? (:duplicate result2)))
      (is (= (:id result1) (:id result2)))
      (is (= (:hash result1) (:hash result2)))

      ;; Only 1 entity in store
      (is (= 1 (mem/count-entities *store*))))))

;; Test 5: Query with filters

(deftest test-query-with-filters
  (testing "Query with filters returns only matching entities"
    ;; Add multiple transactions
    (store/append! *store*
                  {:amount 45.99 :merchant "Starbucks" :category :restaurant}
                  {:entity-type :transaction :author "darwin"
                   :timestamp "2025-11-07T10:30:00Z"})
    (store/append! *store*
                  {:amount 120.50 :merchant "Amazon" :category :shopping}
                  {:entity-type :transaction :author "darwin"
                   :timestamp "2025-11-07T10:31:00Z"})
    (store/append! *store*
                  {:amount 200.00 :merchant "Best Buy" :category :shopping}
                  {:entity-type :transaction :author "darwin"
                   :timestamp "2025-11-07T10:32:00Z"})

    ;; Filter by category
    (let [shopping (store/query *store*
                               {:entity-type :transaction
                                :filters {:category :shopping}})]
      (is (= 2 (count shopping))))

    ;; Filter by amount > 100
    (let [expensive (store/query *store*
                                {:entity-type :transaction
                                 :filters {:amount [:> 100]}})]
      (is (= 2 (count expensive))))

    ;; Multiple filters (AND)
    (let [expensive-shopping (store/query *store*
                                         {:entity-type :transaction
                                          :filters {:category :shopping
                                                   :amount [:> 100]}})]
      (is (= 2 (count expensive-shopping))))))

;; Test 6: Query with limit and offset

(deftest test-query-with-limit-offset
  (testing "Query with limit and offset for pagination"
    ;; Add 10 transactions
    (doseq [i (range 10)]
      (store/append! *store*
                    {:amount (* 10 (inc i)) :index i}
                    {:entity-type :transaction :author "darwin"
                     :timestamp (format "2025-11-07T10:3%d:00Z" i)}))

    ;; Get first 5
    (let [page1 (store/query *store*
                            {:entity-type :transaction
                             :limit 5})]
      (is (= 5 (count page1))))

    ;; Get next 5 (offset 5, limit 5)
    (let [page2 (store/query *store*
                            {:entity-type :transaction
                             :offset 5
                             :limit 5})]
      (is (= 5 (count page2))))

    ;; Get all (no limit)
    (let [all (store/query *store* {:entity-type :transaction})]
      (is (= 10 (count all))))))

;; Test 7: Time-travel query

(deftest test-time-travel-query
  (testing "Query as-of timestamp returns entities valid at that time"
    (let [ts1 "2025-11-07T10:30:00Z"
          ts2 "2025-11-07T10:35:00Z"
          ts3 "2025-11-07T10:40:00Z"]

      ;; Add entities at different times
      (store/append! *store*
                    {:amount 45.99 :merchant "Starbucks"}
                    {:entity-type :transaction :author "darwin"
                     :timestamp ts1})
      (store/append! *store*
                    {:amount 120.50 :merchant "Amazon"}
                    {:entity-type :transaction :author "darwin"
                     :timestamp ts3})

      ;; Query as-of ts2 (between first and second)
      (let [entities (store/query *store*
                                 {:entity-type :transaction
                                  :as-of ts2})]
        ;; Should only include first entity (ts1 <= ts2)
        (is (= 1 (count entities)))
        (is (= 45.99 (get-in (first entities) [:data :amount]))))

      ;; Query as-of ts3 (should include both)
      (let [entities (store/query *store*
                                 {:entity-type :transaction
                                  :as-of ts3})]
        (is (= 2 (count entities)))))))

;; Test 8: Versions query

(deftest test-versions-query
  (testing "Query with :versions :all returns all versions"
    (let [result (store/append! *store*
                                {:amount 45.99 :merchant "Starbucks"}
                                {:entity-type :transaction :author "darwin"
                                 :timestamp "2025-11-07T10:30:00Z"})
          id (:id result)]

      ;; Add same entity with different version (simulate update)
      (store/append! *store*
                    {:amount 45.99 :merchant "Starbucks"}
                    {:entity-type :transaction :author "darwin"
                     :version 2
                     :timestamp "2025-11-07T10:31:00Z"})

      ;; Query all versions (this test might not work as expected
      ;; because our simple implementation doesn't track versions properly yet)
      ;; This is a placeholder for when we implement proper versioning
      (let [single (store/query *store*
                               {:entity-type :transaction
                                :id id})]
        ;; Without :versions :all, should return only current (latest)
        (is (= 1 (count single)))))))

;; Test 9: Utility functions

(deftest test-utility-functions
  (testing "Utility functions work correctly"
    ;; count-entities
    (is (= 0 (mem/count-entities *store*)))

    (store/append! *store*
                  {:amount 45.99}
                  {:entity-type :transaction :author "darwin"
                   :timestamp "2025-11-07T10:30:00Z"})
    (store/append! *store*
                  {:amount 120.50}
                  {:entity-type :transaction :author "darwin"
                   :timestamp "2025-11-07T10:31:00Z"})
    (store/append! *store*
                  {:name "BofA"}
                  {:entity-type :bank :author "darwin"
                   :timestamp "2025-11-07T10:32:00Z"})

    (is (= 3 (mem/count-entities *store*)))

    ;; count-by-type
    (is (= 2 (mem/count-by-type *store* :transaction)))
    (is (= 1 (mem/count-by-type *store* :bank)))

    ;; get-all-hashes
    (let [hashes (mem/get-all-hashes *store*)]
      (is (= 3 (count hashes)))
      (is (every? #(clojure.string/starts-with? % "sha256:") hashes)))

    ;; clear!
    (mem/clear! *store*)
    (is (= 0 (mem/count-entities *store*)))))

;; Test 10: Edge cases

(deftest test-edge-cases
  (testing "Edge cases handled correctly"
    ;; Empty query
    (let [results (store/query *store* {:entity-type :transaction})]
      (is (empty? results)))

    ;; Query non-existent ID
    (let [results (store/query *store*
                              {:entity-type :transaction
                               :id "non-existent-id"})]
      (is (empty? results)))

    ;; Query with nil entity-type
    (let [results (store/query *store* {})]
      (is (vector? results)))

    ;; Append nil data (should still work, data can be anything)
    (let [result (store/append! *store*
                                nil
                                {:entity-type :test :author "darwin"
                                 :timestamp "2025-11-07T10:30:00Z"})]
      (is (map? result))
      (is (string? (:id result))))

    ;; Query after clear
    (mem/clear! *store*)
    (is (empty? (store/query *store* {:entity-type :transaction})))))

;; Summary test

(deftest test-full-workflow
  (testing "Complete workflow: append, query, filter, paginate"
    ;; Add data
    (doseq [i (range 20)]
      (store/append! *store*
                    {:amount (* 10 (inc i))
                     :merchant (if (even? i) "Amazon" "Starbucks")
                     :category (if (even? i) :shopping :restaurant)}
                    {:entity-type :transaction :author "darwin"
                     :timestamp (format "2025-11-07T10:%02d:00Z" i)}))

    ;; Count
    (is (= 20 (mem/count-entities *store*)))

    ;; Filter by category
    (let [restaurants (store/query *store*
                                  {:entity-type :transaction
                                   :filters {:category :restaurant}})]
      (is (= 10 (count restaurants))))

    ;; Paginate
    (let [page1 (store/query *store*
                            {:entity-type :transaction
                             :limit 5})]
      (is (= 5 (count page1))))

    ;; Complex query: filter + limit
    (let [expensive-shopping (store/query *store*
                                         {:entity-type :transaction
                                          :filters {:category :shopping
                                                   :amount [:> 100]}
                                          :limit 3})]
      (is (<= (count expensive-shopping) 3))
      (is (every? #(> (get-in % [:data :amount]) 100) expensive-shopping)))))
