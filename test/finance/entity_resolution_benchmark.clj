(ns finance.entity-resolution-benchmark
  "Performance benchmarks for entity resolution
   Measures current performance before any optimization"
  (:require [clojure.test :refer :all]
            [finance.merchant-extraction.stage4 :as stage4]
            [finance.entity-registry :as registry]))

;; ============================================================================
;; Test Data
;; ============================================================================

(def sample-transaction
  {:pdf-source "scotiabank_edo_2025-07-14_0372.pdf"
   :bank "Scotiabank"
   :clean-merchant "GOOGLE WORKSPACE"
   :merchant-category "utilities"
   :account-name "Scotiabank Checking"
   :amount 100.0
   :date "2025-07-14"})

(defn generate-test-batch [n]
  "Generate N test transactions"
  (repeatedly n (fn [] sample-transaction)))

;; ============================================================================
;; Benchmark Functions
;; ============================================================================

(defn benchmark-fn
  "Runs function f n times and returns average time in ms"
  [f n description]
  (let [start (System/nanoTime)
        _ (dotimes [i n] (f))
        end (System/nanoTime)
        elapsed-ms (/ (- end start) 1000000.0)
        avg-ms (/ elapsed-ms n)]
    {:description description
     :iterations n
     :total-ms elapsed-ms
     :avg-ms avg-ms}))

;; ============================================================================
;; Individual Entity Benchmarks
;; ============================================================================

(deftest ^:benchmark benchmark-merchant-resolution
  (testing "Merchant entity resolution performance"
    (let [result (benchmark-fn
                  #(stage4/resolve sample-transaction)
                  1000
                  "Merchant resolution (1000 iterations)")]
      (println "\n📊 Merchant Resolution Benchmark:")
      (println "  Total time:" (:total-ms result) "ms")
      (println "  Avg per tx:" (:avg-ms result) "ms")
      (is (< (:avg-ms result) 0.15)
          "Merchant resolution should be < 0.15ms per transaction"))))

(deftest ^:benchmark benchmark-bank-resolution
  (testing "Bank entity resolution performance"
    (let [result (benchmark-fn
                  #(stage4/resolve-bank-entity sample-transaction)
                  1000
                  "Bank resolution (1000 iterations)")]
      (println "\n📊 Bank Resolution Benchmark:")
      (println "  Total time:" (:total-ms result) "ms")
      (println "  Avg per tx:" (:avg-ms result) "ms")
      (is (< (:avg-ms result) 0.10)
          "Bank resolution should be < 0.10ms per transaction"))))

(deftest ^:benchmark benchmark-account-resolution
  (testing "Account entity resolution performance"
    (let [tx-with-bank (stage4/resolve-bank-entity sample-transaction)
          result (benchmark-fn
                  #(stage4/resolve-account-entity tx-with-bank)
                  1000
                  "Account resolution (1000 iterations)")]
      (println "\n📊 Account Resolution Benchmark:")
      (println "  Total time:" (:total-ms result) "ms")
      (println "  Avg per tx:" (:avg-ms result) "ms")
      (is (< (:avg-ms result) 0.10)
          "Account resolution should be < 0.10ms per transaction"))))

(deftest ^:benchmark benchmark-category-resolution
  (testing "Category entity resolution performance"
    (let [result (benchmark-fn
                  #(stage4/resolve-category-entity sample-transaction)
                  1000
                  "Category resolution (1000 iterations)")]
      (println "\n📊 Category Resolution Benchmark:")
      (println "  Total time:" (:total-ms result) "ms")
      (println "  Avg per tx:" (:avg-ms result) "ms")
      (is (< (:avg-ms result) 0.10)
          "Category resolution should be < 0.10ms per transaction"))))

;; ============================================================================
;; Complete Pipeline Benchmark
;; ============================================================================

(deftest ^:benchmark benchmark-all-entities
  (testing "Complete 4-entity resolution performance"
    (let [result (benchmark-fn
                  #(stage4/resolve-all-entities sample-transaction)
                  1000
                  "All entities resolution (1000 iterations)")]
      (println "\n📊 Complete Resolution (4 Entities) Benchmark:")
      (println "  Total time:" (:total-ms result) "ms")
      (println "  Avg per tx:" (:avg-ms result) "ms")
      (is (< (:avg-ms result) 0.40)
          "Complete resolution should be < 0.40ms per transaction"))))

;; ============================================================================
;; Batch Processing Benchmark
;; ============================================================================

(deftest ^:benchmark benchmark-batch-processing-small
  (testing "Batch processing (100 transactions)"
    (let [batch (generate-test-batch 100)
          result (benchmark-fn
                  #(doall (stage4/resolve-batch batch))
                  10
                  "Batch 100 txs (10 iterations)")]
      (println "\n📊 Batch Processing (100 txs) Benchmark:")
      (println "  Total time:" (:total-ms result) "ms")
      (println "  Avg per batch:" (:avg-ms result) "ms")
      (println "  Avg per tx:" (/ (:avg-ms result) 100) "ms")
      (is (< (:avg-ms result) 50.0)
          "Batch of 100 should process in < 50ms"))))

(deftest ^:benchmark benchmark-batch-processing-large
  (testing "Batch processing (1000 transactions)"
    (let [batch (generate-test-batch 1000)
          result (benchmark-fn
                  #(doall (stage4/resolve-batch batch))
                  5
                  "Batch 1000 txs (5 iterations)")]
      (println "\n📊 Batch Processing (1000 txs) Benchmark:")
      (println "  Total time:" (:total-ms result) "ms")
      (println "  Avg per batch:" (:avg-ms result) "ms")
      (println "  Avg per tx:" (/ (:avg-ms result) 1000) "ms")
      (is (< (:avg-ms result) 400.0)
          "Batch of 1000 should process in < 400ms"))))

;; ============================================================================
;; Registry Lookup Benchmarks
;; ============================================================================

(deftest ^:benchmark benchmark-merchant-lookup
  (testing "Merchant registry lookup performance"
    (let [result (benchmark-fn
                  #(registry/lookup-merchant "GOOGLE WORKSPACE")
                  10000
                  "Merchant lookup (10000 iterations)")]
      (println "\n📊 Merchant Registry Lookup Benchmark:")
      (println "  Total time:" (:total-ms result) "ms")
      (println "  Avg per lookup:" (:avg-ms result) "ms")
      (is (< (:avg-ms result) 0.10)
          "Merchant lookup should be < 0.10ms"))))

(deftest ^:benchmark benchmark-bank-lookup
  (testing "Bank registry lookup performance"
    (let [result (benchmark-fn
                  #(registry/lookup-bank "scotiabank")
                  10000
                  "Bank lookup (10000 iterations)")]
      (println "\n📊 Bank Registry Lookup Benchmark:")
      (println "  Total time:" (:total-ms result) "ms")
      (println "  Avg per lookup:" (:avg-ms result) "ms")
      (is (< (:avg-ms result) 0.05)
          "Bank lookup should be < 0.05ms"))))

(deftest ^:benchmark benchmark-account-lookup
  (testing "Account registry lookup performance"
    (let [result (benchmark-fn
                  #(registry/lookup-account "Scotiabank Checking")
                  10000
                  "Account lookup (10000 iterations)")]
      (println "\n📊 Account Registry Lookup Benchmark:")
      (println "  Total time:" (:total-ms result) "ms")
      (println "  Avg per lookup:" (:avg-ms result) "ms")
      (is (< (:avg-ms result) 0.05)
          "Account lookup should be < 0.05ms"))))

(deftest ^:benchmark benchmark-category-lookup
  (testing "Category registry lookup performance"
    (let [result (benchmark-fn
                  #(registry/lookup-category "utilities")
                  10000
                  "Category lookup (10000 iterations)")]
      (println "\n📊 Category Registry Lookup Benchmark:")
      (println "  Total time:" (:total-ms result) "ms")
      (println "  Avg per lookup:" (:avg-ms result) "ms")
      (is (< (:avg-ms result) 0.05)
          "Category lookup should be < 0.05ms"))))

;; ============================================================================
;; Run All Benchmarks
;; ============================================================================

(defn run-all-benchmarks []
  "Run all benchmarks and print summary"
  (println "\n" (apply str (repeat 80 "=")))
  (println "🏃 ENTITY RESOLUTION PERFORMANCE BENCHMARKS")
  (println (apply str (repeat 80 "=")))

  ;; Individual entities
  (benchmark-merchant-resolution)
  (benchmark-bank-resolution)
  (benchmark-account-resolution)
  (benchmark-category-resolution)

  ;; Complete pipeline
  (benchmark-all-entities)

  ;; Batch processing
  (benchmark-batch-processing-small)
  (benchmark-batch-processing-large)

  ;; Registry lookups
  (benchmark-merchant-lookup)
  (benchmark-bank-lookup)
  (benchmark-account-lookup)
  (benchmark-category-lookup)

  (println "\n" (apply str (repeat 80 "=")))
  (println "✅ All benchmarks complete!")
  (println (apply str (repeat 80 "="))))

(comment
  ;; Run benchmarks manually
  (run-all-benchmarks)

  ;; Run from command line:
  ;; clojure -M:test -n finance.entity-resolution-benchmark
  )
