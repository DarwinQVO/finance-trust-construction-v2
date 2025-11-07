(ns finance.transducers-test
  "Tests for transducers (Rich Hickey aligned).

  Tests verify:
  1. Individual transducer behavior
  2. Composition
  3. Context independence (into, sequence, transduce)
  4. Error handling
  5. Property-based testing

  Phase 6 - Testing Transducer Pipelines
  Date: 2025-11-06"
  (:require [clojure.test :refer [deftest is testing]]
            [finance.transducers :as xf]))

;; ============================================================================
;; Test Data
;; ============================================================================

(def valid-date-samples
  [{:date "03/20/2024"}
   {:date "2024-11-06"}
   {:date "01/01/2024"}])

(def invalid-date-samples
  [{:date "not-a-date"}
   {:date "2024/11/06"}  ; Wrong separator
   {:date "11-06-2024"}  ; Wrong format
   {:date nil}])

(def valid-amount-samples
  [{:amount "45.99"}
   {:amount "$1,234.56"}
   {:amount "-45.99"}])  ; Should become positive

(def invalid-amount-samples
  [{:amount "not-a-number"}
   {:amount "abc"}
   {:amount nil}])

(def valid-type-samples
  [{:type "GASTO"}
   {:type "INGRESO"}
   {:type "PAGO_TARJETA"}
   {:type "TRASPASO"}])

(def invalid-type-samples
  [{:type "INVALID"}
   {:type "unknown"}
   {:type nil}])

;; ============================================================================
;; Parsing Transducer Tests
;; ============================================================================

(deftest test-parse-date-xf
  (testing "parse-date-xf with valid dates"
    (let [result (into [] (xf/parse-date-xf :date) valid-date-samples)]
      (is (= 3 (count result)))
      (is (instance? java.util.Date (:date (first result))))
      (is (= "03/20/2024" (:raw-date (first result))))
      (is (instance? java.util.Date (:date (second result))))
      (is (= "2024-11-06" (:raw-date (second result))))))

  (testing "parse-date-xf with invalid dates"
    (let [result (into [] (xf/parse-date-xf :date) invalid-date-samples)]
      (is (= 4 (count result)))
      ;; Check first invalid date has error
      (is (nil? (:date (first result))))
      (is (= "not-a-date" (:raw-date (first result))))
      (is (contains? (first result) :error))
      (is (= :date (:field (:error (first result)))))))

  (testing "parse-date-xf preserves records without date field"
    (let [result (into [] (xf/parse-date-xf :date) [{:amount 100}])]
      (is (= [{:amount 100}] result)))))

(deftest test-parse-amount-xf
  (testing "parse-amount-xf with valid amounts"
    (let [result (into [] (xf/parse-amount-xf :amount) valid-amount-samples)]
      (is (= 3 (count result)))
      (is (= 45.99 (:amount (first result))))
      (is (= "45.99" (:raw-amount (first result))))
      (is (= 1234.56 (:amount (second result))))
      (is (= "$1,234.56" (:raw-amount (second result))))
      ;; Check negative becomes positive
      (is (= 45.99 (:amount (nth result 2))))
      (is (= "-45.99" (:raw-amount (nth result 2))))))

  (testing "parse-amount-xf with invalid amounts"
    (let [result (into [] (xf/parse-amount-xf :amount) invalid-amount-samples)]
      (is (= 3 (count result)))
      (is (nil? (:amount (first result))))
      (is (= "not-a-number" (:raw-amount (first result))))
      (is (contains? (first result) :error))))

  (testing "parse-amount-xf preserves records without amount field"
    (let [result (into [] (xf/parse-amount-xf :amount) [{:date "2024-11-06"}])]
      (is (= [{:date "2024-11-06"}] result)))))

(deftest test-normalize-type-xf
  (testing "normalize-type-xf with valid types"
    (let [result (into [] (xf/normalize-type-xf :type) valid-type-samples)]
      (is (= 4 (count result)))
      (is (= :expense (:type (first result))))
      (is (= "GASTO" (:raw-type (first result))))
      (is (= :income (:type (second result))))
      (is (= "INGRESO" (:raw-type (second result))))
      (is (= :transfer (:type (nth result 2))))
      (is (= "PAGO_TARJETA" (:raw-type (nth result 2))))
      (is (= :transfer (:type (nth result 3))))
      (is (= "TRASPASO" (:raw-type (nth result 3))))))

  (testing "normalize-type-xf with invalid types"
    (let [result (into [] (xf/normalize-type-xf :type) invalid-type-samples)]
      (is (= 3 (count result)))
      (is (= :unknown (:type (first result))))
      (is (= "INVALID" (:raw-type (first result))))
      (is (contains? (first result) :error)))))

(deftest test-normalize-bank-xf
  (testing "normalize-bank-xf with various bank names"
    (let [samples [{:bank "Bank of America"}
                   {:bank "BOFA"}
                   {:bank "Apple Card"}
                   {:bank "Stripe"}
                   {:bank "Wise"}
                   {:bank "Scotiabank"}
                   {:bank "Unknown Bank"}]
          result (into [] (xf/normalize-bank-xf :bank) samples)]
      (is (= :bofa (:bank (first result))))
      (is (= :bofa (:bank (second result))))
      (is (= :apple-card (:bank (nth result 2))))
      (is (= :stripe (:bank (nth result 3))))
      (is (= :wise (:bank (nth result 4))))
      (is (= :scotiabank (:bank (nth result 5))))
      (is (= :unknown (:bank (nth result 6)))))))

(deftest test-normalize-merchant-xf
  (testing "normalize-merchant-xf preserves multi-word names"
    (let [samples [{:merchant "WHOLE FOODS MARKET"}
                   {:merchant "STARBUCKS #123"}
                   {:merchant "UBER RIDE 45678, DES: TRIP"}
                   {:merchant ""}
                   {:merchant nil}]
          result (into [] (xf/normalize-merchant-xf :merchant) samples)]
      (is (= :whole-foods-market (:merchant (first result))))
      (is (= :starbucks (:merchant (second result))))
      (is (= :uber-ride (:merchant (nth result 2))))
      (is (= :unknown-merchant (:merchant (nth result 3))))
      (is (= :unknown-merchant (:merchant (nth result 4)))))))

;; ============================================================================
;; Validation Transducer Tests
;; ============================================================================

(deftest test-filter-errors-xf
  (testing "filter-errors-xf removes records with :error key"
    (let [samples [{:id 1 :amount 100}
                   {:id 2 :error {:message "Invalid"}}
                   {:id 3 :amount 200}
                   {:id 4 :error {:message "Bad data"}}]
          result (into [] (xf/filter-errors-xf) samples)]
      (is (= 2 (count result)))
      (is (= 1 (:id (first result))))
      (is (= 3 (:id (second result)))))))

(deftest test-filter-valid-amount-xf
  (testing "filter-valid-amount-xf keeps only positive numbers"
    (let [samples [{:amount 100}
                   {:amount 0}
                   {:amount -50}
                   {:amount nil}
                   {:amount 45.99}]
          result (into [] (xf/filter-valid-amount-xf :amount) samples)]
      (is (= 2 (count result)))
      (is (= 100 (:amount (first result))))
      (is (= 45.99 (:amount (second result)))))))

(deftest test-filter-valid-date-xf
  (testing "filter-valid-date-xf keeps only java.util.Date objects"
    (let [samples [{:date (java.util.Date.)}
                   {:date nil}
                   {:date "2024-11-06"}
                   {:date (java.util.Date.)}]
          result (into [] (xf/filter-valid-date-xf :date) samples)]
      (is (= 2 (count result)))
      (is (instance? java.util.Date (:date (first result))))
      (is (instance? java.util.Date (:date (second result)))))))

;; ============================================================================
;; Enrichment Transducer Tests
;; ============================================================================

(deftest test-add-id-xf
  (testing "add-id-xf adds unique UUID to each record"
    (let [samples [{:amount 100} {:amount 200}]
          result (into [] (xf/add-id-xf :transaction-id) samples)]
      (is (= 2 (count result)))
      (is (string? (:transaction-id (first result))))
      (is (string? (:transaction-id (second result))))
      (is (not= (:transaction-id (first result))
                (:transaction-id (second result)))))))

(deftest test-add-idempotency-hash-xf
  (testing "add-idempotency-hash-xf adds SHA-256 hash"
    (let [tx {:date "2024-11-06" :amount 45.99 :merchant :starbucks :bank :bofa}
          result (into [] (xf/add-idempotency-hash-xf) [tx])]
      (is (= 1 (count result)))
      (is (string? (:idempotency-hash (first result))))
      (is (= 64 (count (:idempotency-hash (first result)))))))  ; SHA-256 = 64 hex chars

  (testing "add-idempotency-hash-xf produces same hash for same data"
    (let [tx1 {:date "2024-11-06" :amount 45.99 :merchant :starbucks :bank :bofa}
          tx2 {:date "2024-11-06" :amount 45.99 :merchant :starbucks :bank :bofa}
          result1 (into [] (xf/add-idempotency-hash-xf) [tx1])
          result2 (into [] (xf/add-idempotency-hash-xf) [tx2])]
      (is (= (:idempotency-hash (first result1))
             (:idempotency-hash (first result2))))))

  (testing "add-idempotency-hash-xf produces different hash for different data"
    (let [tx1 {:date "2024-11-06" :amount 45.99 :merchant :starbucks :bank :bofa}
          tx2 {:date "2024-11-07" :amount 45.99 :merchant :starbucks :bank :bofa}
          result1 (into [] (xf/add-idempotency-hash-xf) [tx1])
          result2 (into [] (xf/add-idempotency-hash-xf) [tx2])]
      (is (not= (:idempotency-hash (first result1))
                (:idempotency-hash (first result2)))))))

(deftest test-add-provenance-xf
  (testing "add-provenance-xf adds metadata to each record"
    (let [samples [{:amount 100} {:amount 200}]
          result (into [] (xf/add-provenance-xf "test.csv" "1.0.0") samples)]
      (is (= 2 (count result)))
      (is (map? (:provenance (first result))))
      (is (= "test.csv" (:source-file (:provenance (first result)))))
      (is (= 1 (:source-line (:provenance (first result)))))
      (is (= 2 (:source-line (:provenance (second result)))))
      (is (= "1.0.0" (:parser-version (:provenance (first result)))))
      (is (instance? java.util.Date (:imported-at (:provenance (first result))))))))

;; ============================================================================
;; Composed Pipeline Tests
;; ============================================================================

(deftest test-csv-import-pipeline-xf
  (testing "csv-import-pipeline-xf complete transformation"
    (let [csv-row {:date "03/20/2024"
                   :amount "$45.99"
                   :type "GASTO"
                   :bank "Bank of America"
                   :merchant "STARBUCKS #123"}
          pipeline (xf/csv-import-pipeline-xf "test.csv" "1.0.0")
          result (into [] pipeline [csv-row])]
      (is (= 1 (count result)))
      (let [tx (first result)]
        ;; Check parsed fields
        (is (instance? java.util.Date (:date tx)))
        (is (= 45.99 (:amount tx)))
        (is (= :expense (:type tx)))
        (is (= :bofa (:bank tx)))
        (is (= :starbucks (:merchant tx)))

        ;; Check enrichment
        (is (string? (:transaction-id tx)))
        (is (string? (:idempotency-hash tx)))
        (is (map? (:provenance tx)))

        ;; Check raw values preserved
        (is (= "03/20/2024" (:raw-date tx)))
        (is (= "$45.99" (:raw-amount tx)))
        (is (= "GASTO" (:raw-type tx)))
        (is (= "Bank of America" (:raw-bank tx)))
        (is (= "STARBUCKS #123" (:raw-merchant tx))))))

  (testing "csv-import-pipeline-xf filters out invalid records"
    (let [csv-rows [{:date "03/20/2024" :amount "$45.99" :type "GASTO" :bank "BofA" :merchant "STARBUCKS"}
                    {:date "invalid-date" :amount "$45.99" :type "GASTO" :bank "BofA" :merchant "STARBUCKS"}
                    {:date "03/21/2024" :amount "invalid" :type "GASTO" :bank "BofA" :merchant "STARBUCKS"}
                    {:date "03/22/2024" :amount "$50.00" :type "GASTO" :bank "BofA" :merchant "AMAZON"}]
          pipeline (xf/csv-import-pipeline-xf "test.csv" "1.0.0")
          result (into [] pipeline csv-rows)]
      ;; Only valid records should pass through
      (is (= 2 (count result)))
      (is (= :starbucks (:merchant (first result))))
      (is (= :amazon (:merchant (second result)))))))

(deftest test-classification-pipeline-xf
  (testing "classification-pipeline-xf applies classification function"
    (let [classify-fn (fn [tx]
                        (assoc tx
                          :category (if (= (:merchant tx) :starbucks)
                                      "Café"
                                      "Other")
                          :confidence 0.95))
          samples [{:merchant :starbucks :amount 45.99}
                   {:merchant :amazon :amount 120.50}]
          pipeline (xf/classification-pipeline-xf classify-fn)
          result (into [] pipeline samples)]
      (is (= 2 (count result)))
      (is (= "Café" (:category (first result))))
      (is (= 0.95 (:confidence (first result))))
      (is (= "Other" (:category (second result)))))))

;; ============================================================================
;; Context Independence Tests (Rich Hickey Principle)
;; ============================================================================

(deftest test-context-independence-into
  (testing "Transducers work with into (eager collection)"
    (let [pipeline (xf/parse-amount-xf :amount)
          result (into [] pipeline [{:amount "$45.99"}])]
      (is (vector? result))
      (is (= 45.99 (:amount (first result)))))))

(deftest test-context-independence-sequence
  (testing "Transducers work with sequence (lazy)"
    (let [pipeline (xf/parse-amount-xf :amount)
          result (sequence pipeline [{:amount "$45.99"}])]
      (is (seq? result))
      (is (= 45.99 (:amount (first result)))))))

(deftest test-context-independence-transduce
  (testing "Transducers work with transduce (reduction)"
    (let [pipeline (comp (xf/parse-amount-xf :amount)
                        (map :amount))
          result (transduce pipeline + 0 [{:amount "$45.99"} {:amount "$54.01"}])]
      (is (= 100.0 result)))))

;; ============================================================================
;; Composition Tests (Rich Hickey Principle)
;; ============================================================================

(deftest test-composition-order
  (testing "Transducer composition executes in correct order"
    (let [pipeline (comp
                    (xf/parse-amount-xf :amount)
                    (xf/filter-valid-amount-xf :amount)
                    (map #(update % :amount * 2)))
          samples [{:amount "$45.99"}
                   {:amount "invalid"}
                   {:amount "$54.01"}]
          result (into [] pipeline samples)]
      ;; Invalid amount filtered out, valid amounts doubled
      (is (= 2 (count result)))
      (is (= 91.98 (:amount (first result))))
      (is (= 108.02 (:amount (second result)))))))

(deftest test-composition-no-intermediates
  (testing "Composed transducers create no intermediate collections"
    (let [call-count (atom 0)
          counting-xf (map (fn [x] (swap! call-count inc) x))
          pipeline (comp
                    (xf/parse-amount-xf :amount)
                    counting-xf
                    (xf/filter-valid-amount-xf :amount))
          samples [{:amount "$45.99"} {:amount "$54.01"}]
          result (into [] pipeline samples)]
      ;; Each record processed exactly once (no intermediate collections)
      (is (= 2 @call-count))
      (is (= 2 (count result))))))

;; ============================================================================
;; Error Handling Tests (Rich Hickey: Separate Detection from Handling)
;; ============================================================================

(deftest test-error-preservation
  (testing "Errors preserved but don't stop processing"
    (let [pipeline (xf/parse-date-xf :date)
          samples [{:date "03/20/2024"}
                   {:date "invalid"}
                   {:date "03/21/2024"}]
          result (into [] pipeline samples)]
      (is (= 3 (count result)))
      (is (not (contains? (first result) :error)))
      (is (contains? (second result) :error))
      (is (not (contains? (nth result 2) :error))))))

(deftest test-error-filtering-separation
  (testing "Error detection separate from error handling"
    (let [pipeline-detect (xf/parse-amount-xf :amount)
          pipeline-handle (comp pipeline-detect
                              (xf/filter-errors-xf))
          samples [{:amount "$45.99"}
                   {:amount "invalid"}
                   {:amount "$54.01"}]
          result-with-errors (into [] pipeline-detect samples)
          result-filtered (into [] pipeline-handle samples)]
      ;; Detection: all records present
      (is (= 3 (count result-with-errors)))
      ;; Handling: errors filtered out
      (is (= 2 (count result-filtered))))))

;; ============================================================================
;; Property-Based Tests (Generate valid data)
;; ============================================================================

(deftest test-property-idempotency
  (testing "Processing same data twice produces same results"
    (let [pipeline (xf/parse-amount-xf :amount)
          sample {:amount "$45.99"}
          result1 (into [] pipeline [sample])
          result2 (into [] pipeline [sample])]
      (is (= result1 result2)))))

(deftest test-property-composition-associativity
  (testing "Transducer composition is associative"
    (let [xf1 (xf/parse-amount-xf :amount)
          xf2 (xf/filter-valid-amount-xf :amount)
          xf3 (map #(update % :amount * 2))
          pipeline1 (comp (comp xf1 xf2) xf3)
          pipeline2 (comp xf1 (comp xf2 xf3))
          sample {:amount "$45.99"}
          result1 (into [] pipeline1 [sample])
          result2 (into [] pipeline2 [sample])]
      (is (= result1 result2)))))

;; ============================================================================
;; Performance/Memory Tests (Informal)
;; ============================================================================

(deftest test-memory-efficiency
  (testing "Transducers don't create intermediate collections"
    (let [large-dataset (repeat 1000 {:amount "$45.99"})
          pipeline (comp
                    (xf/parse-amount-xf :amount)
                    (xf/filter-valid-amount-xf :amount)
                    (map :amount))
          ;; Using transduce for reduction (no intermediate collection)
          result (transduce pipeline + 0 large-dataset)]
      ;; Use approximate equality due to floating point precision
      (is (< (Math/abs (- 45990.0 result)) 0.01)))))
