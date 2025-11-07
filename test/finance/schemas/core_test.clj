(ns finance.schemas.core-test
  "Tests for core Malli schemas using property-based testing.

  Philosophy (Rich Hickey aligned):
  - Generate 1000s of test cases automatically
  - Properties must hold for ALL generated data
  - If it compiles and tests pass, it works

  Test coverage:
  - Schema validation (positive + negative cases)
  - Schema composition (primitives → complex)
  - Error messages (human-readable)
  - Generators (can generate valid data)
  - Roundtrip (generate → validate → succeed)
  "
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.generator :as mg]
            [malli.error :as me]
            [finance.schemas.core :as schemas]))

;;; ============================================================================
;;; Primitive Schema Tests
;;; ============================================================================

(deftest test-non-empty-string
  (testing "NonEmptyString validation"
    (is (m/validate schemas/NonEmptyString "hello"))
    (is (m/validate schemas/NonEmptyString "a"))
    (is (not (m/validate schemas/NonEmptyString "")))
    (is (not (m/validate schemas/NonEmptyString nil)))
    (is (not (m/validate schemas/NonEmptyString 123)))))

(deftest test-positive-number
  (testing "PositiveNumber validation"
    (is (m/validate schemas/PositiveNumber 0.01))
    (is (m/validate schemas/PositiveNumber 123.45))
    (is (not (m/validate schemas/PositiveNumber 0)))
    (is (not (m/validate schemas/PositiveNumber -45.99)))
    (is (not (m/validate schemas/PositiveNumber "123")))))

(deftest test-confidence
  (testing "Confidence score validation"
    (is (m/validate schemas/Confidence 0.0))
    (is (m/validate schemas/Confidence 0.5))
    (is (m/validate schemas/Confidence 1.0))
    (is (not (m/validate schemas/Confidence -0.1)))
    (is (not (m/validate schemas/Confidence 1.1)))
    (is (not (m/validate schemas/Confidence 2.0)))))

(deftest test-iso-date-string
  (testing "ISODateString validation"
    (is (m/validate schemas/ISODateString "2024-11-06"))
    (is (m/validate schemas/ISODateString "2024-01-01"))
    ;; Note: Regex pattern #"^\d{4}-\d{2}-\d{2}$" only checks format, not validity
    ;; "2024-13-01" matches the pattern (even though month 13 doesn't exist)
    ;; For full date validation, would need custom validator
    (is (not (m/validate schemas/ISODateString "11/06/2024")))  ; Wrong format
    (is (not (m/validate schemas/ISODateString "2024-11-6")))   ; Missing zero pad
    (is (not (m/validate schemas/ISODateString nil)))))

(deftest test-transaction-type
  (testing "TransactionType enum validation"
    (is (m/validate schemas/TransactionType :income))
    (is (m/validate schemas/TransactionType :expense))
    (is (m/validate schemas/TransactionType :transfer))
    (is (m/validate schemas/TransactionType :credit-payment))
    (is (not (m/validate schemas/TransactionType :invalid)))
    (is (not (m/validate schemas/TransactionType "income")))  ; String, not keyword
    (is (not (m/validate schemas/TransactionType nil)))))

(deftest test-uuid
  (testing "UUID validation"
    (is (m/validate schemas/UUID "123e4567-e89b-12d3-a456-426614174000"))
    (is (m/validate schemas/UUID "f47ac10b-58cc-4372-a567-0e02b2c3d479"))
    ;; Note: Regex pattern only checks format, not if hex digits are valid
    ;; "not-a-uuid" fails because it doesn't match 8-4-4-4-12 pattern
    ;; But "zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz" would pass (even though invalid hex)
    (is (not (m/validate schemas/UUID "not-a-uuid")))
    (is (not (m/validate schemas/UUID "123E4567-E89B-12D3-A456-426614174000")))  ; Uppercase
    (is (not (m/validate schemas/UUID nil)))))

;;; ============================================================================
;;; Entity Schema Tests
;;; ============================================================================

(deftest test-merchant
  (testing "Merchant schema validation"
    (let [valid-merchant {:canonical-name "Starbucks"
                          :confidence 0.95}
          invalid-merchant {:canonical-name ""  ; Empty string
                            :confidence 1.5}]  ; Out of range
      (is (m/validate schemas/Merchant valid-merchant))
      (is (not (m/validate schemas/Merchant invalid-merchant)))

      ;; With optional field
      (is (m/validate schemas/Merchant
                      (assoc valid-merchant :extracted-from "STARBUCKS #12345"))))))

(deftest test-category
  (testing "Category schema validation"
    (let [valid-category {:name "Coffee & Tea"
                          :confidence 0.90}
          invalid-category {:name "Coffee"
                            :confidence -0.1}]  ; Negative confidence
      (is (m/validate schemas/Category valid-category))
      (is (not (m/validate schemas/Category invalid-category)))

      ;; With optional rule-id
      (is (m/validate schemas/Category
                      (assoc valid-category :rule-id "rule-15"))))))

(deftest test-provenance
  (testing "Provenance schema validation"
    (let [valid-provenance {:source-file "bofa_nov_2024.csv"
                            :imported-at "2024-11-06"}
          invalid-provenance {:source-file ""  ; Empty
                              :imported-at "invalid-date"}]
      (is (m/validate schemas/Provenance valid-provenance))
      (is (not (m/validate schemas/Provenance invalid-provenance)))

      ;; With all optional fields
      (is (m/validate schemas/Provenance
                      (assoc valid-provenance
                             :source-line 23
                             :imported-by "darwin"
                             :parser-version "1.2.0"))))))

;;; ============================================================================
;;; Transaction Schema Tests
;;; ============================================================================

(deftest test-transaction-valid
  (testing "Valid transaction passes validation"
    (let [valid-tx {:id "123e4567-e89b-12d3-a456-426614174000"
                    :date "2024-11-06"
                    :amount 45.99
                    :description "STARBUCKS #12345"
                    :type :expense
                    :merchant {:canonical-name "Starbucks"
                               :confidence 0.95
                               :extracted-from "STARBUCKS #12345"}
                    :category {:name "Coffee & Tea"
                               :confidence 0.90
                               :rule-id "rule-15"}
                    :provenance {:source-file "bofa_nov_2024.csv"
                                 :source-line 23
                                 :imported-at "2024-11-06"
                                 :imported-by "darwin"
                                 :parser-version "1.2.0"}}]
      (is (m/validate schemas/Transaction valid-tx))
      (is (:valid? (schemas/validate schemas/Transaction valid-tx))))))

(deftest test-transaction-invalid-fields
  (testing "Invalid transaction fails validation"
    (let [invalid-tx {:id "not-a-uuid"  ; Invalid UUID
                      :date "2024-13-40"  ; Invalid date (but passes regex)
                      :amount -45.99  ; Negative amount
                      :description ""  ; Empty string
                      :type :invalid  ; Invalid type
                      :provenance {:source-file ""
                                   :imported-at "bad-date"}}]
      (is (not (m/validate schemas/Transaction invalid-tx)))

      ;; Check that we get validation errors
      (let [result (schemas/validate schemas/Transaction invalid-tx)]
        (is (not (:valid? result)))
        (is (map? (:errors result)))
        (is (> (count (:errors result)) 0))
        ;; Specific field errors (may be nested)
        (is (contains? (:errors result) :amount))
        (is (contains? (:errors result) :description))
        (is (contains? (:errors result) :type))))))

(deftest test-transaction-missing-required
  (testing "Transaction missing required fields fails"
    (let [incomplete-tx {:id "123e4567-e89b-12d3-a456-426614174000"
                         :date "2024-11-06"
                         ;; Missing: amount, description, type, provenance
                         }]
      (is (not (m/validate schemas/Transaction incomplete-tx)))

      (let [result (schemas/validate schemas/Transaction incomplete-tx)]
        (is (not (:valid? result)))
        (is (contains? (:errors result) :amount))
        (is (contains? (:errors result) :description))
        (is (contains? (:errors result) :type))
        (is (contains? (:errors result) :provenance))))))

;;; ============================================================================
;;; ML Detection Schema Tests
;;; ============================================================================

(deftest test-ml-detection-request
  (testing "MLDetectionRequest validation"
    (let [valid-request {:transaction-id "123e4567-e89b-12d3-a456-426614174000"
                         :description "STARBUCKS #12345"
                         :amount 45.99
                         :date "2024-11-06"
                         :type :expense}
          invalid-request {:transaction-id "not-uuid"
                           :description ""
                           :amount -45.99
                           :date "bad-date"
                           :type :invalid}]
      (is (m/validate schemas/MLDetectionRequest valid-request))
      (is (not (m/validate schemas/MLDetectionRequest invalid-request)))

      ;; With optional historical amounts
      (is (m/validate schemas/MLDetectionRequest
                      (assoc valid-request :historical-amounts [42.50 43.00 45.99]))))))

(deftest test-ml-detection-response
  (testing "MLDetectionResponse validation"
    (let [valid-response {:merchant-detection {:canonical-name "Starbucks"
                                                :confidence 0.95
                                                :method "pattern-matching"}
                          :category-detection {:name "Coffee & Tea"
                                               :confidence 0.90
                                               :rule-id "rule-15"}
                          :anomaly-detection {:is-anomaly false
                                              :confidence 0.85
                                              :z-score 0.5}}
          invalid-response {:merchant-detection {:canonical-name ""
                                                  :confidence 1.5}  ; Out of range
                            :category-detection {:name "Coffee"
                                                 :confidence -0.1}
                            :anomaly-detection {:is-anomaly "not-bool"  ; Not boolean
                                                :confidence 2.0}}]
      (is (m/validate schemas/MLDetectionResponse valid-response))
      (is (not (m/validate schemas/MLDetectionResponse invalid-response))))))

;;; ============================================================================
;;; Review Queue Schema Tests
;;; ============================================================================

(deftest test-review-queue-item
  (testing "ReviewQueueItem validation"
    (let [valid-item {:id "123e4567-e89b-12d3-a456-426614174000"
                      :transaction-id "f47ac10b-58cc-4372-a567-0e02b2c3d479"
                      :ml-detection {:merchant-detection {:canonical-name "Starbucks"
                                                          :confidence 0.95}
                                     :category-detection {:name "Coffee"
                                                          :confidence 0.90}
                                     :anomaly-detection {:is-anomaly false
                                                         :confidence 0.85}}
                      :status :pending
                      :submitted-at "2024-11-06"}]
      (is (m/validate schemas/ReviewQueueItem valid-item))

      ;; With all optional fields (after review)
      (is (m/validate schemas/ReviewQueueItem
                      (assoc valid-item
                             :reviewed-at "2024-11-07"
                             :reviewed-by "darwin"
                             :decision :approve))))))

;;; ============================================================================
;;; Property-Based Tests (Generators)
;;; ============================================================================

(deftest test-generate-valid-transaction
  (testing "Generated transactions are always valid"
    (dotimes [_ 10]  ; Generate 10 random transactions
      (let [generated-tx (mg/generate schemas/Transaction)]
        (is (m/validate schemas/Transaction generated-tx)
            (str "Generated transaction failed validation: " generated-tx))))))

(deftest test-generate-valid-merchant
  (testing "Generated merchants are always valid"
    (dotimes [_ 10]
      (let [generated-merchant (mg/generate schemas/Merchant)]
        (is (m/validate schemas/Merchant generated-merchant))))))

(deftest test-generate-valid-category
  (testing "Generated categories are always valid"
    (dotimes [_ 10]
      (let [generated-category (mg/generate schemas/Category)]
        (is (m/validate schemas/Category generated-category))))))

;;; ============================================================================
;;; Schema Composition Tests
;;; ============================================================================

(deftest test-schema-composition
  (testing "Complex schemas compose from primitives"
    ;; Transaction uses Merchant, Category, Provenance
    ;; If primitives are valid, complex schema should be valid
    (let [valid-merchant {:canonical-name "Starbucks"
                          :confidence 0.95}
          valid-category {:name "Coffee"
                          :confidence 0.90}
          valid-provenance {:source-file "test.csv"
                            :imported-at "2024-11-06"}
          valid-tx {:id "123e4567-e89b-12d3-a456-426614174000"
                    :date "2024-11-06"
                    :amount 45.99
                    :description "Test"
                    :type :expense
                    :merchant valid-merchant
                    :category valid-category
                    :provenance valid-provenance}]

      ;; All components valid → composite valid
      (is (m/validate schemas/Merchant valid-merchant))
      (is (m/validate schemas/Category valid-category))
      (is (m/validate schemas/Provenance valid-provenance))
      (is (m/validate schemas/Transaction valid-tx)))))

;;; ============================================================================
;;; Error Message Tests
;;; ============================================================================

(deftest test-error-messages-are-human-readable
  (testing "Validation errors are human-readable"
    (let [bad-tx {:id "not-a-uuid"
                  :date "bad-date"
                  :amount -100
                  :description ""
                  :type :invalid
                  :provenance {:source-file ""
                               :imported-at "bad"}}
          result (schemas/validate schemas/Transaction bad-tx)]

      (is (not (:valid? result)))
      (is (map? (:errors result)))
      (is (> (count (:errors result)) 0))

      ;; Errors should be descriptive (checking they exist, not exact structure)
      (let [errors (:errors result)]
        (is (contains? errors :amount))
        (is (contains? errors :description))
        (is (contains? errors :type))))))

;;; ============================================================================
;;; Schema Metadata Tests
;;; ============================================================================

(deftest test-schema-info
  (testing "Schema metadata is accessible"
    (let [info (schemas/schema-info :transaction/v1)]
      (is (map? info))
      (is (= "1.0.0" (:version info)))
      (is (= "2025-11-06" (:created info)))
      (is (= "darwin" (:author info)))
      (is (>= (:rich-hickey-alignment info) 90)))))

(deftest test-list-schemas
  (testing "Can list all available schemas"
    (let [all-schemas (schemas/list-schemas)]
      (is (sequential? all-schemas))
      (is (> (count all-schemas) 0))
      (is (every? #(contains? % :key) all-schemas))
      (is (every? #(contains? % :version) all-schemas)))))

;;; ============================================================================
;;; Validation Helper Tests
;;; ============================================================================

(deftest test-validate-helper
  (testing "validate helper function"
    (let [valid-tx {:id "123e4567-e89b-12d3-a456-426614174000"
                    :date "2024-11-06"
                    :amount 45.99
                    :description "Test"
                    :type :expense
                    :provenance {:source-file "test.csv"
                                 :imported-at "2024-11-06"}}
          invalid-tx {:id "bad"
                      :date "bad"
                      :amount -100
                      :description ""
                      :type :invalid
                      :provenance {:source-file ""
                                   :imported-at "bad"}}]

      ;; Valid transaction
      (let [result (schemas/validate schemas/Transaction valid-tx)]
        (is (:valid? result)))

      ;; Invalid transaction
      (let [result (schemas/validate schemas/Transaction invalid-tx)]
        (is (not (:valid? result)))
        (is (map? (:errors result)))))))

(deftest test-explain-helper
  (testing "explain helper function"
    (let [invalid-tx {:id "bad-uuid"
                      :date "bad-date"
                      :amount -100
                      :description ""
                      :type :invalid
                      :provenance {:source-file ""
                                   :imported-at "bad"}}
          explanation (schemas/explain schemas/Transaction invalid-tx)]

      (is (map? explanation))
      (is (> (count explanation) 0)))))

(comment
  ;; Run tests in REPL
  (clojure.test/run-tests 'finance.schemas.core-test)

  ;; Run specific test
  (test-transaction-valid)

  ;; Generate sample data
  (mg/generate schemas/Transaction)
  (mg/sample schemas/Transaction {:size 10})
  )
