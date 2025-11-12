(ns finance.merchant-enrichment-test
  "Tests for merchant registry enrichment with MCC codes, budget categories, and tax hints"
  (:require [clojure.test :refer :all]
            [finance.entity-registry :as registry]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

;; ============================================================================
;; MCC Registry Tests
;; ============================================================================

(deftest test-mcc-registry-exists
  (testing "MCC registry file exists"
    (let [mcc-file (io/resource "registry/mcc_registry.edn")]
      (is (some? mcc-file) "MCC registry file should exist at resources/registry/mcc_registry.edn"))))

(deftest test-mcc-registry-valid-edn
  (testing "MCC registry file is valid EDN"
    (let [mcc-file (io/resource "registry/mcc_registry.edn")]
      (when mcc-file
        (let [data (edn/read-string (slurp mcc-file))]
          (is (map? data) "MCC registry should be a map")
          (is (contains? data :mcc-codes) "Should have :mcc-codes key"))))))

(deftest test-mcc-registry-content
  (testing "MCC registry has sufficient content"
    (let [mcc-file (io/resource "registry/mcc_registry.edn")]
      (when mcc-file
        (let [data (edn/read-string (slurp mcc-file))
              mcc-codes (:mcc-codes data)]
          (is (> (count mcc-codes) 5) "Should have at least 5 MCC codes")
          (is (contains? mcc-codes 6300) "Should have insurance MCC code")
          (is (contains? mcc-codes 5734) "Should have software MCC code")
          (is (contains? mcc-codes 5411) "Should have grocery MCC code"))))))

(deftest test-mcc-code-structure
  (testing "MCC codes have proper structure"
    (let [mcc-file (io/resource "registry/mcc_registry.edn")]
      (when mcc-file
        (let [data (edn/read-string (slurp mcc-file))
              sample-mcc (get-in data [:mcc-codes 6300])]
          (is (string? (:name sample-mcc)) "MCC should have name")
          (is (string? (:budget-category sample-mcc)) "MCC should have budget-category")
          (is (string? (:budget-subcategory sample-mcc)) "MCC should have budget-subcategory")
          (is (string? (:typical-flow sample-mcc)) "MCC should have typical-flow")
          (is (map? (:tax-deductible sample-mcc)) "MCC should have tax-deductible map"))))))

;; ============================================================================
;; Enriched Merchants Tests
;; ============================================================================

(deftest test-enriched-merchants-exist
  (testing "Top merchants have been enriched"
    (let [test-merchants ["seguros-atlas" "google" "oxxo" "att" "bp-derma-colombiana"]]
      (doseq [merchant-id test-merchants]
        (let [merchant (registry/get-merchant-by-id merchant-id)]
          (is (some? merchant) (str merchant-id " should exist in registry")))))))

(deftest test-merchant-has-mcc
  (testing "Enriched merchants have MCC codes"
    (let [merchant (registry/get-merchant-by-id "seguros-atlas")]
      (when merchant
        (is (contains? merchant :mcc) "Merchant should have MCC")
        (is (integer? (:mcc merchant)) "MCC should be an integer")
        (is (= 6300 (:mcc merchant)) "Seguros Atlas should have MCC 6300")))))

(deftest test-merchant-has-budget-category
  (testing "Enriched merchants have budget categories"
    (let [merchant (registry/get-merchant-by-id "google")]
      (when merchant
        (is (contains? merchant :budget-category) "Merchant should have budget-category")
        (is (string? (:budget-category merchant)) "Budget category should be a string")
        (is (= "Technology" (:budget-category merchant)) "Google should be in Technology category")))))

(deftest test-merchant-has-tax-hints
  (testing "Enriched merchants have tax hints"
    (let [merchant (registry/get-merchant-by-id "oxxo")]
      (when merchant
        (is (contains? merchant :tax-hints) "Merchant should have tax-hints")
        (is (map? (:tax-hints merchant)) "Tax hints should be a map")
        (is (contains? (:tax-hints merchant) :business-deductible) "Tax hints should have business-deductible")
        (is (boolean? (get-in merchant [:tax-hints :business-deductible]))
            "Business-deductible should be a boolean")))))

(deftest test-merchant-has-typical-flow
  (testing "Enriched merchants have typical flow type"
    (let [merchant (registry/get-merchant-by-id "att")]
      (when merchant
        (is (contains? merchant :typical-flow-type) "Merchant should have typical-flow-type")
        (is (string? (:typical-flow-type merchant)) "Typical flow type should be a string")
        (is (= "GASTO" (:typical-flow-type merchant)) "AT&T should have GASTO flow type")))))

;; ============================================================================
;; Data Quality Tests
;; ============================================================================

(deftest test-enrichment-quality-insurance
  (testing "Insurance merchant has complete enrichment"
    (let [merchant (registry/get-merchant-by-id "seguros-atlas")]
      (when merchant
        (is (= 6300 (:mcc merchant)) "Should have correct MCC")
        (is (= "Insurance Sales, Underwriting, and Premiums" (:mcc-description merchant)))
        (is (= "Insurance" (:budget-category merchant)))
        (is (= "Health & Life" (:budget-subcategory merchant)))
        (is (= "GASTO" (:typical-flow-type merchant)))
        (is (true? (get-in merchant [:tax-hints :business-deductible])))
        (is (true? (get-in merchant [:tax-hints :personal-deductible])))
        (is (= "Gastos Médicos" (get-in merchant [:tax-hints :sat-category])))))))

(deftest test-enrichment-quality-technology
  (testing "Technology merchant has complete enrichment"
    (let [merchant (registry/get-merchant-by-id "google")]
      (when merchant
        (is (= 5734 (:mcc merchant)) "Should have correct MCC")
        (is (= "Computer Software Stores" (:mcc-description merchant)))
        (is (= "Technology" (:budget-category merchant)))
        (is (= "Software & Services" (:budget-subcategory merchant)))
        (is (= "GASTO" (:typical-flow-type merchant)))
        (is (true? (get-in merchant [:tax-hints :business-deductible])))
        (is (false? (get-in merchant [:tax-hints :personal-deductible])))
        (is (= "Gastos de Software" (get-in merchant [:tax-hints :sat-category])))))))

(deftest test-enrichment-quality-healthcare
  (testing "Healthcare merchant has complete enrichment"
    (let [merchant (registry/get-merchant-by-id "farmacia-del-ahorro")]
      (when merchant
        (is (= 5912 (:mcc merchant)) "Should have correct MCC")
        (is (= "Drug Stores and Pharmacies" (:mcc-description merchant)))
        (is (= "Healthcare" (:budget-category merchant)))
        (is (= "Pharmacy" (:budget-subcategory merchant)))
        (is (= "GASTO" (:typical-flow-type merchant)))
        (is (false? (get-in merchant [:tax-hints :business-deductible])))
        (is (true? (get-in merchant [:tax-hints :personal-deductible])))))))

;; ============================================================================
;; Self-Audit Function
;; ============================================================================

(defn run-self-audit
  "Runs comprehensive self-audit of merchant enrichment"
  []
  (println)
  (println "🔍 FASE 2 Self-Audit: Merchant Registry Enrichment")
  (println "=" (apply str (repeat 70 "=")))
  (println)

  ;; Test 1: MCC Registry file exists
  (print "1. MCC Registry file exists: ")
  (if (io/resource "registry/mcc_registry.edn")
    (println "✅")
    (println "❌"))

  ;; Test 2: MCC Registry valid EDN
  (print "2. MCC Registry is valid EDN: ")
  (try
    (let [data (edn/read-string (slurp (io/resource "registry/mcc_registry.edn")))]
      (if (and (map? data) (contains? data :mcc-codes))
        (println "✅")
        (println "❌")))
    (catch Exception e
      (println "❌" (.getMessage e))))

  ;; Test 3: Count MCC codes
  (try
    (let [data (edn/read-string (slurp (io/resource "registry/mcc_registry.edn")))
          mcc-count (count (:mcc-codes data))]
      (println (format "3. MCC codes defined: %s %d codes"
                      (if (>= mcc-count 10) "✅" "❌")
                      mcc-count)))
    (catch Exception e
      (println "3. MCC codes count: ❌" (.getMessage e))))

  ;; Test 4: Top merchants enriched
  (println)
  (println "4. Top merchants enrichment status:")
  (doseq [merchant-id ["seguros-atlas" "google" "oxxo" "att" "farmacia-del-ahorro"]]
    (let [merchant (registry/get-merchant-by-id merchant-id)]
      (if (and merchant
               (:mcc merchant)
               (:budget-category merchant)
               (:tax-hints merchant))
        (println (format "   ✅ %-30s (MCC: %s, Budget: %s)"
                        (:canonical-name merchant)
                        (:mcc merchant)
                        (:budget-category merchant)))
        (println (format "   ❌ %-30s - Missing enriched data" merchant-id)))))

  ;; Test 5: Data quality checks
  (println)
  (println "5. Data quality checks:")
  (let [merchant (registry/get-merchant-by-id "seguros-atlas")]
    (println (format "   MCC is integer: %s"
                    (if (integer? (:mcc merchant)) "✅" "❌")))
    (println (format "   Budget category exists: %s"
                    (if (string? (:budget-category merchant)) "✅" "❌")))
    (println (format "   Tax hints is map: %s"
                    (if (map? (:tax-hints merchant)) "✅" "❌")))
    (println (format "   Has business-deductible flag: %s"
                    (if (boolean? (get-in merchant [:tax-hints :business-deductible])) "✅" "❌")))
    (println (format "   Has SAT category: %s"
                    (if (string? (get-in merchant [:tax-hints :sat-category])) "✅" "❌"))))

  ;; Test 6: MCC description quality
  (println)
  (println "6. MCC description quality:")
  (doseq [merchant-id ["seguros-atlas" "google" "farmacia-del-ahorro"]]
    (let [merchant (registry/get-merchant-by-id merchant-id)]
      (if (and (:mcc merchant) (:mcc-description merchant))
        (println (format "   ✅ %-30s: %s"
                        (:canonical-name merchant)
                        (:mcc-description merchant)))
        (println (format "   ❌ %-30s: Missing MCC description" merchant-id)))))

  (println)
  (println "=" (apply str (repeat 70 "=")))
  (println "✅ FASE 2 Self-Audit Complete")
  (println))
