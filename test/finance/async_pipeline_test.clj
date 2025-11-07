(ns finance.async-pipeline-test
  "Tests for async pipeline with core.async + transducers.

   Tests verify:
   - Parallel processing works correctly
   - Transducers maintain context independence in async
   - Backpressure works properly
   - Channel composition works
   - Integration with Process/Perception layers"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :as async :refer [<!! >!! chan close!]]
            [finance.async-pipeline :as ap]
            [finance.transducers :as xf]
            [finance.classification :as classify]))

;; ============================================================================
;; TEST DATA

(def sample-csv-rows
  "Sample CSV rows for testing"
  [{:date "2024-01-15"
    :amount "45.99"
    :merchant "STARBUCKS STORE #123"
    :type "debit"
    :bank "BofA"}
   {:date "2024-01-16"
    :amount "2500.00"
    :merchant "PAYROLL DEPOSIT"
    :type "credit"
    :bank "BofA"}
   {:date "2024-01-17"
    :amount "120.50"
    :merchant "AMAZON.COM"
    :type "debit"
    :bank "AppleCard"}
   {:date "invalid-date"
    :amount "50.00"
    :merchant "TEST"
    :type "debit"
    :bank "BofA"}
   {:date "2024-01-18"
    :amount "not-a-number"
    :merchant "TEST2"
    :type "debit"
    :bank "BofA"}])

(def sample-rules
  "Sample classification rules"
  [{:pattern #"STARBUCKS"
    :category "Cafe"
    :confidence 0.95}
   {:pattern #"AMAZON"
    :category "Shopping"
    :confidence 0.90}
   {:pattern #"PAYROLL"
    :category "Income"
    :confidence 0.99}])

;; ============================================================================
;; HELPER FUNCTIONS

(defn <!!-all
  "Collect all items from a channel into a vector"
  [ch]
  (loop [result []]
    (if-let [item (<!! ch)]
      (recur (conj result item))
      result)))

(defn count-items
  "Count items in a channel (consumes the channel)"
  [ch]
  (count (<!!-all ch)))

;; ============================================================================
;; TEST 1: Parse Transactions Async

(deftest test-parse-transactions-async
  (testing "Parallel parsing with async/pipeline"
    (let [out-ch (ap/parse-transactions-async! sample-csv-rows {:parallelism 2})
          results (<!!-all out-ch)]

      (testing "Parses valid transactions"
        (is (= 3 (count results))
            "Should parse 3 valid transactions (2 invalid filtered out)"))

      (testing "Parsed dates correctly"
        (is (every? inst? (map :date results))
            "All dates should be parsed to java.util.Date"))

      (testing "Parsed amounts correctly"
        (is (every? number? (map :amount results))
            "All amounts should be parsed to numbers"))

      (testing "Normalized merchants"
        (let [merchants (set (map :merchant results))]
          (is (contains? merchants "STARBUCKS")
              "Merchant should be normalized")
          (is (contains? merchants "AMAZON")
              "Merchant should be normalized")))

      (testing "Filters out errors"
        (is (every? #(nil? (:errors %)) results)
            "No transaction should have errors")))))

;; ============================================================================
;; TEST 2: Classify Transactions Async

(deftest test-classify-transactions-async
  (testing "Parallel classification with async/pipeline"
    (let [;; First parse
          parsed-ch (ap/parse-transactions-async! sample-csv-rows {:parallelism 2})
          parsed (<!!-all parsed-ch)

          ;; Then classify
          classify-ch (ap/classify-transactions-async!
                        parsed
                        sample-rules
                        {:parallelism 2 :min-confidence :any})
          results (<!!-all classify-ch)]

      (testing "Classifies all transactions"
        (is (= 3 (count results))
            "Should classify all 3 parsed transactions"))

      (testing "Categories assigned correctly"
        (let [categories (set (map :category results))]
          (is (contains? categories "Cafe") "Should classify STARBUCKS as Cafe")
          (is (contains? categories "Shopping") "Should classify AMAZON as Shopping")
          (is (contains? categories "Income") "Should classify PAYROLL as Income")))

      (testing "Confidence scores present"
        (is (every? #(contains? % :confidence) results)
            "All transactions should have confidence scores")
        (is (every? #(number? (:confidence %)) results)
            "All confidence scores should be numbers")
        (is (every? #(<= 0.0 (:confidence %) 1.0) results)
            "All confidence scores should be between 0.0 and 1.0")))))

;; ============================================================================
;; TEST 3: Complete Pipeline (Parse + Classify)

(deftest test-process-file-async
  (testing "Complete async pipeline: parse + classify"
    (let [out-ch (ap/process-file-async!
                   sample-csv-rows
                   sample-rules
                   {:parallelism 2 :min-confidence :any})
          results (<!!-all out-ch)]

      (testing "Processes all valid transactions"
        (is (= 3 (count results))
            "Should process 3 valid transactions"))

      (testing "Both parsing and classification applied"
        (is (every? inst? (map :date results))
            "Dates should be parsed")
        (is (every? number? (map :amount results))
            "Amounts should be parsed")
        (is (every? string? (map :category results))
            "Categories should be assigned"))

      (testing "Enrichment metadata present"
        (is (every? #(contains? % :classification-metadata) results)
            "All transactions should have classification metadata")))))

;; ============================================================================
;; TEST 4: Confidence Filtering

(deftest test-confidence-filtering
  (testing "Filter by confidence threshold"
    (testing "Filter :high confidence (>= 0.90)"
      (let [out-ch (ap/process-file-async!
                     sample-csv-rows
                     sample-rules
                     {:parallelism 2 :min-confidence :high})
            results (<!!-all out-ch)]
        (is (every? #(>= (:confidence %) 0.90) results)
            "All results should have confidence >= 0.90")))

    (testing "Filter :medium confidence (>= 0.70)"
      (let [out-ch (ap/process-file-async!
                     sample-csv-rows
                     sample-rules
                     {:parallelism 2 :min-confidence :medium})
            results (<!!-all out-ch)]
        (is (every? #(>= (:confidence %) 0.70) results)
            "All results should have confidence >= 0.70")))

    (testing "Accept :any confidence"
      (let [out-ch (ap/process-file-async!
                     sample-csv-rows
                     sample-rules
                     {:parallelism 2 :min-confidence :any})
            results (<!!-all out-ch)]
        (is (= 3 (count results))
            "Should accept all classified transactions")))))

;; ============================================================================
;; TEST 5: Parallelism

(deftest test-parallelism
  (testing "Different parallelism levels work"
    (testing "Parallelism = 1 (sequential)"
      (let [out-ch (ap/parse-transactions-async!
                     sample-csv-rows
                     {:parallelism 1})
            results (<!!-all out-ch)]
        (is (= 3 (count results))
            "Should process all transactions with parallelism=1")))

    (testing "Parallelism = 4 (default)"
      (let [out-ch (ap/parse-transactions-async!
                     sample-csv-rows
                     {:parallelism 4})
            results (<!!-all out-ch)]
        (is (= 3 (count results))
            "Should process all transactions with parallelism=4")))

    (testing "Parallelism = 8 (high)"
      (let [out-ch (ap/parse-transactions-async!
                     sample-csv-rows
                     {:parallelism 8})
            results (<!!-all out-ch)]
        (is (= 3 (count results))
            "Should process all transactions with parallelism=8")))))

;; ============================================================================
;; TEST 6: Context Independence (Transducers work in async context)

(deftest test-context-independence
  (testing "Same transducers work in different contexts"
    (let [;; Context 1: Synchronous (into)
          sync-pipeline (comp
                          (xf/parse-date-xf :date)
                          (xf/parse-amount-xf :amount)
                          (xf/filter-valid-date-xf :date)
                          (xf/filter-valid-amount-xf :amount))
          sync-results (into [] sync-pipeline sample-csv-rows)

          ;; Context 2: Asynchronous (core.async)
          in-ch (chan 10)
          out-ch (chan 10)]

      ;; Use same transducers in async pipeline
      (async/pipeline 2 out-ch sync-pipeline in-ch)
      (async/onto-chan! in-ch sample-csv-rows)

      (let [async-results (<!!-all out-ch)]
        (testing "Same number of results"
          (is (= (count sync-results) (count async-results))
              "Sync and async should produce same count"))

        (testing "Same transformation applied"
          (is (every? inst? (map :date sync-results)))
          (is (every? inst? (map :date async-results)))
          (is (every? number? (map :amount sync-results)))
          (is (every? number? (map :amount async-results))))))))

;; ============================================================================
;; TEST 7: Error Handling in Async

(deftest test-async-error-handling
  (testing "Errors don't stop pipeline"
    (let [mixed-data [{:date "2024-01-15" :amount "50.00" :merchant "VALID" :type "debit" :bank "BofA"}
                      {:date "invalid" :amount "50.00" :merchant "INVALID-DATE" :type "debit" :bank "BofA"}
                      {:date "2024-01-16" :amount "not-number" :merchant "INVALID-AMOUNT" :type "debit" :bank "BofA"}
                      {:date "2024-01-17" :amount "75.00" :merchant "VALID2" :type "debit" :bank "BofA"}]
          out-ch (ap/parse-transactions-async! mixed-data {:parallelism 2})
          results (<!!-all out-ch)]

      (testing "Valid transactions processed"
        (is (= 2 (count results))
            "Should process 2 valid transactions"))

      (testing "Invalid transactions filtered out"
        (is (every? #(and (inst? (:date %)) (number? (:amount %))) results)
            "All results should have valid dates and amounts")))))

;; ============================================================================
;; TEST 8: Large Batch Performance

(deftest test-large-batch
  (testing "Process large batch of transactions"
    (let [;; Generate 1000 transactions
          large-batch (for [i (range 1000)]
                        {:date "2024-01-15"
                         :amount (str (* 10.0 (inc i)))
                         :merchant (str "MERCHANT-" i)
                         :type "debit"
                         :bank "BofA"})

          out-ch (ap/parse-transactions-async!
                   large-batch
                   {:parallelism 4 :buffer-size 100})
          results (<!!-all out-ch)]

      (testing "Processes all 1000 transactions"
        (is (= 1000 (count results))
            "Should process all 1000 transactions"))

      (testing "No errors in batch processing"
        (is (every? #(and (inst? (:date %)) (number? (:amount %))) results)
            "All transactions should be valid")))))

;; ============================================================================
;; TEST 9: Backpressure

(deftest test-backpressure
  (testing "Backpressure prevents memory overflow"
    (let [;; Small buffer to test backpressure
          large-batch (for [i (range 100)]
                        {:date "2024-01-15"
                         :amount "50.00"
                         :merchant (str "MERCHANT-" i)
                         :type "debit"
                         :bank "BofA"})

          ;; Use small buffer size
          out-ch (ap/parse-transactions-async!
                   large-batch
                   {:parallelism 2 :buffer-size 10})]

      ;; Consume slowly to test backpressure
      (testing "Can consume results incrementally"
        (let [first-10 (doall (repeatedly 10 #(<!! out-ch)))
              remaining (<!!-all out-ch)
              all-results (concat first-10 remaining)]
          (is (= 100 (count all-results))
              "Should get all 100 transactions with backpressure"))))))

;; ============================================================================
;; TEST 10: Property-Based Tests

(deftest test-idempotency-async
  (testing "Processing same data twice yields same results"
    (let [out-ch-1 (ap/process-file-async! sample-csv-rows sample-rules {:parallelism 2})
          results-1 (<!!-all out-ch-1)

          out-ch-2 (ap/process-file-async! sample-csv-rows sample-rules {:parallelism 2})
          results-2 (<!!-all out-ch-2)]

      (testing "Same number of results"
        (is (= (count results-1) (count results-2))
            "Both runs should produce same count"))

      (testing "Same categories assigned"
        (is (= (set (map :category results-1))
               (set (map :category results-2)))
            "Both runs should assign same categories")))))

(deftest test-order-independence
  (testing "Results independent of input order (for unordered operations)"
    (let [shuffled (shuffle sample-csv-rows)
          out-ch-original (ap/parse-transactions-async! sample-csv-rows {:parallelism 2})
          out-ch-shuffled (ap/parse-transactions-async! shuffled {:parallelism 2})

          results-original (<!!-all out-ch-original)
          results-shuffled (<!!-all out-ch-shuffled)]

      (testing "Same number of valid transactions"
        (is (= (count results-original) (count results-shuffled))
            "Shuffling shouldn't affect count of valid transactions"))

      (testing "Same merchants processed"
        (is (= (set (map :merchant results-original))
               (set (map :merchant results-shuffled)))
            "Shuffling shouldn't affect which merchants are processed")))))
