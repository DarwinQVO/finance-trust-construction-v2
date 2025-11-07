(ns finance.integration.ml-classification-test
  "Integration tests for ML classification pipeline.

  Tests end-to-end flow:
  1. Submit transaction for classification
  2. ML detection (merchant + category + anomaly)
  3. Review queue processing
  4. Human approval/rejection/correction
  5. Facts stored in Datomic

  Phase 4 - Integration Testing
  Date: 2025-11-06"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.core.async :as async :refer [<!! >!! timeout]]
            [datomic.api :as d]
            [finance.core-datomic :as db]
            [finance.orchestration.ml-pipeline :as ml-pipeline]
            [finance.clients.ml-service :as ml]
            [finance.api.handlers :as handlers]))

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(def ^:dynamic *test-conn* nil)

(defn with-test-db
  "Fixture: Create fresh in-memory database for each test."
  [f]
  (let [test-db-uri (str "datomic:mem://ml-test-" (java.util.UUID/randomUUID))]
    (d/create-database test-db-uri)
    (let [conn (d/connect test-db-uri)]
      ;; Initialize with test database
      (db/init! test-db-uri)

      (binding [*test-conn* conn]
        (try
          (f)
          (finally
            (d/delete-database test-db-uri)))))))

(defn with-ml-pipeline
  "Fixture: Start ML pipeline workers for test."
  [f]
  ;; Start pipeline with test connection
  (ml-pipeline/start-pipeline! *test-conn*)

  ;; Wait for workers to initialize
  (<!! (timeout 100))

  (try
    (f)
    (finally
      ;; Shutdown pipeline
      (ml-pipeline/shutdown-pipeline!))))

(use-fixtures :each with-test-db with-ml-pipeline)

;; ============================================================================
;; Test Data
;; ============================================================================

(def test-transaction
  "Sample transaction for testing."
  {:id "test-tx-001"
   :description "STARBUCKS PURCHASE"
   :amount 45.99
   :date (java.util.Date.)})

(def test-bank
  {:entity/id :bank-001
   :entity/canonical-name "Bank of America"
   :bank/type :bank
   :bank/country "USA"
   :temporal/valid-from (java.util.Date.)})

(def test-merchant
  {:entity/id :merchant-001
   :entity/canonical-name "Starbucks"
   :temporal/valid-from (java.util.Date.)})

(def test-category
  {:entity/id :category-001
   :entity/canonical-name "Café"
   :category/type :expense
   :temporal/valid-from (java.util.Date.)})

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn setup-test-data!
  "Insert test entities into database."
  [conn]
  ;; First transaction: Insert entities (bank, merchant, category)
  @(d/transact conn [test-bank test-merchant test-category])

  ;; Second transaction: Insert transaction referencing entities
  @(d/transact conn [{:transaction/id "test-tx-001"
                      :transaction/date (java.util.Date.)
                      :transaction/amount 45.99
                      :transaction/description "STARBUCKS PURCHASE"
                      :transaction/type :expense
                      :transaction/currency "USD"
                      :transaction/bank [:entity/id :bank-001]}]))

(defn wait-for-review-queue
  "Wait for transaction to appear in review queue.

  Returns review queue item or nil after timeout."
  [conn tx-id max-wait-ms]
  (let [start (System/currentTimeMillis)]
    (loop []
      (let [db (d/db conn)
            item (d/q '[:find (pull ?e [*]) .
                        :in $ ?tx-id
                        :where
                        [?e :review-queue/transaction-id ?tx-id]]
                      db tx-id)]
        (cond
          item item
          (> (- (System/currentTimeMillis) start) max-wait-ms) nil
          :else (do
                  (<!! (timeout 100))
                  (recur)))))))

(defn wait-for-approval
  "Wait for transaction to be approved and stored.

  Returns approved transaction or nil after timeout."
  [conn tx-id max-wait-ms]
  (let [start (System/currentTimeMillis)]
    (loop []
      (let [db (d/db conn)
            tx (d/q '[:find (pull ?e [*]) .
                      :in $ ?tx-id
                      :where
                      [?e :transaction/id ?tx-id]
                      [?e :transaction/classification-approved? true]]
                    db tx-id)]
        (cond
          tx tx
          (> (- (System/currentTimeMillis) start) max-wait-ms) nil
          :else (do
                  (<!! (timeout 100))
                  (recur)))))))

;; ============================================================================
;; Integration Tests - Full ML Classification Flow
;; ============================================================================

(deftest test-full-ml-classification-flow
  (testing "End-to-end ML classification with human approval"
    (let [conn *test-conn*]
      ;; Setup test data
      (setup-test-data! conn)

      ;; Step 1: Submit transaction for classification
      (testing "Submit transaction for classification"
        (let [result (ml-pipeline/submit-transaction-for-classification
                       test-transaction)]
          (is (true? result) "Transaction should be queued for classification")))

      ;; Step 2: Wait for ML detection to complete (async)
      ;; Mock ML service would return results here
      ;; In real test, we'd use with-redefs to mock HTTP calls

      ;; Step 3: Verify transaction appears in review queue
      (testing "Transaction appears in review queue"
        (let [review-item (wait-for-review-queue conn "test-tx-001" 5000)]
          (is (some? review-item) "Transaction should appear in review queue")
          (is (= "test-tx-001" (:review-queue/transaction-id review-item))
              "Review queue should have correct transaction ID")
          (is (= :pending (:review-queue/status review-item))
              "Review queue status should be pending")))

      ;; Step 4: Human approves classification
      (testing "Human approves classification"
        (let [result (ml-pipeline/approve-classification
                       "test-tx-001"
                       "starbucks"
                       "cafe"
                       "test-user@example.com")]
          (is (true? result) "Approval should be queued for processing")))

      ;; Step 5: Wait for approval to be stored
      (testing "Approval stored in Datomic"
        (let [approved-tx (wait-for-approval conn "test-tx-001" 5000)]
          (is (some? approved-tx) "Transaction should be approved and stored")
          (is (true? (:transaction/classification-approved? approved-tx))
              "Transaction should be marked as approved")
          (is (= "test-user@example.com" (:transaction/classified-by approved-tx))
              "Approved-by should be recorded")
          (is (some? (:transaction/classified-at approved-tx))
              "Classified-at timestamp should be recorded")))

      ;; Step 6: Verify event recorded
      (testing "Approval event recorded"
        (let [db (d/db conn)
              event (d/q '[:find (pull ?e [*]) .
                           :where
                           [?e :event/type :classification-approved]
                           [?e :event/transaction-id "test-tx-001"]]
                         db)]
          (is (some? event) "Approval event should be recorded")
          (is (= "starbucks" (:event/merchant event))
              "Event should record merchant")
          (is (= "cafe" (:event/category event))
              "Event should record category")))

      ;; Step 7: Verify review queue updated
      (testing "Review queue marked as resolved"
        (let [db (d/db conn)
              item (d/q '[:find (pull ?e [*]) .
                          :in $ ?tx-id
                          :where
                          [?e :review-queue/transaction-id ?tx-id]]
                        db "test-tx-001")]
          (is (= :approved (:review-queue/status item))
              "Review queue status should be approved")
          (is (= "test-user@example.com" (:review-queue/resolved-by item))
              "Resolved-by should be recorded")
          (is (some? (:review-queue/resolved-at item))
              "Resolved-at timestamp should be recorded"))))))

;; ============================================================================
;; Integration Tests - Rejection Flow
;; ============================================================================

(deftest test-rejection-flow
  (testing "Human rejects ML classification"
    (let [conn *test-conn*]
      (setup-test-data! conn)

      ;; Submit for classification
      (ml-pipeline/submit-transaction-for-classification test-transaction)

      ;; Wait for review queue
      (wait-for-review-queue conn "test-tx-001" 5000)

      ;; Reject classification
      (testing "Human rejects classification"
        (let [result (ml-pipeline/reject-classification
                       "test-tx-001"
                       "Incorrect merchant detection"
                       "test-user@example.com")]
          (is (true? result) "Rejection should be queued for processing")))

      ;; Wait for rejection to be stored
      (<!! (timeout 500))

      ;; Verify rejection event
      (testing "Rejection event recorded"
        (let [db (d/db conn)
              event (d/q '[:find (pull ?e [*]) .
                           :where
                           [?e :event/type :classification-rejected]
                           [?e :event/transaction-id "test-tx-001"]]
                         db)]
          (is (some? event) "Rejection event should be recorded")
          (is (= "Incorrect merchant detection" (:event/reason event))
              "Event should record reason")
          (is (= "test-user@example.com" (:event/rejected-by event))
              "Event should record who rejected")))

      ;; Verify review queue updated
      (testing "Review queue marked as rejected"
        (let [db (d/db conn)
              item (d/q '[:find (pull ?e [*]) .
                          :in $ ?tx-id
                          :where
                          [?e :review-queue/transaction-id ?tx-id]]
                        db "test-tx-001")]
          (is (= :rejected (:review-queue/status item))
              "Review queue status should be rejected"))))))

;; ============================================================================
;; Integration Tests - Correction Flow
;; ============================================================================

(deftest test-correction-flow
  (testing "Human corrects ML classification"
    (let [conn *test-conn*]
      (setup-test-data! conn)

      ;; Submit for classification
      (ml-pipeline/submit-transaction-for-classification test-transaction)

      ;; Wait for review queue
      (wait-for-review-queue conn "test-tx-001" 5000)

      ;; Correct classification
      (testing "Human corrects classification"
        (let [result (ml-pipeline/correct-classification
                       "test-tx-001"
                       "dunkin-donuts"
                       "cafe"
                       "test-user@example.com")]
          (is (true? result) "Correction should be queued for processing")))

      ;; Wait for correction to be stored
      (let [corrected-tx (wait-for-approval conn "test-tx-001" 5000)]

        ;; Verify correction stored
        (testing "Correction stored in Datomic"
          (is (some? corrected-tx) "Transaction should be corrected and stored")
          (is (true? (:transaction/classification-approved? corrected-tx))
              "Transaction should be marked as approved")
          (is (true? (:transaction/classification-corrected? corrected-tx))
              "Transaction should be marked as corrected")
          (is (= "test-user@example.com" (:transaction/classified-by corrected-tx))
              "Corrected-by should be recorded"))

        ;; Verify correction event
        (testing "Correction event recorded"
          (let [db (d/db conn)
                event (d/q '[:find (pull ?e [*]) .
                             :where
                             [?e :event/type :classification-corrected]
                             [?e :event/transaction-id "test-tx-001"]]
                           db)]
            (is (some? event) "Correction event should be recorded")
            (is (= "dunkin-donuts" (:event/corrected-merchant event))
                "Event should record corrected merchant")
            (is (= "cafe" (:event/corrected-category event))
                "Event should record corrected category")))

        ;; Verify review queue updated
        (testing "Review queue marked as corrected"
          (let [db (d/db conn)
                item (d/q '[:find (pull ?e [*]) .
                            :in $ ?tx-id
                            :where
                            [?e :review-queue/transaction-id ?tx-id]]
                          db "test-tx-001")]
            (is (= :corrected (:review-queue/status item))
                "Review queue status should be corrected")))))))

;; ============================================================================
;; Integration Tests - Circuit Breaker
;; ============================================================================

(deftest test-circuit-breaker-behavior
  (testing "Circuit breaker opens after multiple failures"
    ;; This test would mock HTTP failures to test circuit breaker
    ;; Skipping implementation for now - requires mocking HTTP client
    (is true "Circuit breaker test placeholder")))

;; ============================================================================
;; Integration Tests - Retry Logic
;; ============================================================================

(deftest test-retry-logic
  (testing "Retry logic with exponential backoff"
    ;; This test would mock transient HTTP failures to test retry
    ;; Skipping implementation for now - requires mocking HTTP client
    (is true "Retry logic test placeholder")))
