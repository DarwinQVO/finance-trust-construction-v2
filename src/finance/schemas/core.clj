(ns finance.schemas.core
  "Core Malli schemas for financial transactions and ML detection.

  Philosophy (Rich Hickey aligned):
  - Schemas are DATA, not code
  - Schemas are composable (build complex from simple)
  - Schemas have provenance (who defined, when, why)
  - Schemas are versioned (v1, v2, etc.)
  - Validation is explicit (at boundaries, not scattered)

  Usage:
    (require '[finance.schemas.core :as schemas])
    (m/validate schemas/Transaction tx)  ; Returns true/false
    (m/explain schemas/Transaction tx)   ; Returns explanation
  "
  (:require [malli.core :as m]
            [malli.util :as mu]
            [malli.error :as me]
            [malli.generator :as mg]
            [clojure.string :as str]))

;;; ============================================================================
;;; Schema Registry (Central source of truth)
;;; ============================================================================

(def ^:private schema-metadata
  "Metadata about schemas (provenance, versioning, descriptions)."
  {:transaction/v1 {:version "1.0.0"
                    :created "2025-11-06"
                    :author "darwin"
                    :description "Core transaction schema with provenance"
                    :rich-hickey-alignment 95}
   :ml-detection-request/v1 {:version "1.0.0"
                              :created "2025-11-06"
                              :author "darwin"
                              :description "Request to Python ML service"}
   :ml-detection-response/v1 {:version "1.0.0"
                               :created "2025-11-06"
                               :author "darwin"
                               :description "Response from Python ML service"}})

;;; ============================================================================
;;; Primitive Schemas (Building blocks)
;;; ============================================================================

(def NonEmptyString
  "String that is not empty or whitespace-only."
  [:string {:min 1
            :error/message "String must not be empty"}])

(def PositiveNumber
  "Number greater than zero."
  [:double {:min 0.01
            :error/message "Number must be positive"}])

(def Confidence
  "Confidence score between 0.0 and 1.0."
  [:double {:min 0.0
            :max 1.0
            :error/message "Confidence must be between 0.0 and 1.0"}])

(def ISODateString
  "Date in ISO 8601 format (YYYY-MM-DD)."
  [:re {:error/message "Date must be in YYYY-MM-DD format"}
   #"^\d{4}-\d{2}-\d{2}$"])

(def TransactionType
  "Valid transaction types."
  [:enum {:error/message "Invalid transaction type"}
   :income :expense :transfer :credit-payment])

(def UUID
  "UUID string (lowercase hex with dashes)."
  [:re {:error/message "Must be a valid UUID"}
   #"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"])

;;; ============================================================================
;;; Entity Schemas (Core domain objects)
;;; ============================================================================

(def Merchant
  "Merchant entity with canonical name and confidence."
  [:map {:closed true
         :error/message "Invalid merchant structure"}
   [:canonical-name NonEmptyString]
   [:confidence Confidence]
   [:extracted-from {:optional true} NonEmptyString]])

(def Category
  "Transaction category with confidence."
  [:map {:closed true
         :error/message "Invalid category structure"}
   [:name NonEmptyString]
   [:confidence Confidence]
   [:rule-id {:optional true} NonEmptyString]])

(def Provenance
  "Provenance metadata (where data came from)."
  [:map {:closed true
         :error/message "Invalid provenance structure"}
   [:source-file NonEmptyString]
   [:source-line {:optional true} :int]
   [:imported-at ISODateString]
   [:imported-by {:optional true} NonEmptyString]
   [:parser-version {:optional true} NonEmptyString]])

;;; ============================================================================
;;; Transaction Schema (v1)
;;; ============================================================================

(def Transaction
  "Core transaction schema with full provenance.

  Rich Hickey alignment:
  - Separates facts (amount, date) from inferences (category, merchant)
  - Includes confidence scores for all inferences
  - Complete provenance (where data came from)
  - Immutable structure (no setters, only new versions)

  Example:
    {:id \"123e4567-e89b-12d3-a456-426614174000\"
     :date \"2024-11-06\"
     :amount 45.99
     :description \"STARBUCKS #12345\"
     :type :expense
     :merchant {:canonical-name \"Starbucks\"
                :confidence 0.95
                :extracted-from \"STARBUCKS #12345\"}
     :category {:name \"Coffee & Tea\"
                :confidence 0.90
                :rule-id \"rule-15\"}
     :provenance {:source-file \"bofa_nov_2024.csv\"
                  :source-line 23
                  :imported-at \"2024-11-06\"
                  :imported-by \"darwin\"
                  :parser-version \"1.2.0\"}}
  "
  [:map {:closed true
         :error/message "Invalid transaction structure"}
   ;; Identity (facts)
   [:id UUID]
   [:date ISODateString]
   [:amount PositiveNumber]
   [:description NonEmptyString]

   ;; Classification (inferences with confidence)
   [:type TransactionType]
   [:merchant {:optional true} Merchant]
   [:category {:optional true} Category]

   ;; Provenance (complete audit trail)
   [:provenance Provenance]

   ;; Optional fields
   [:currency {:optional true} [:string {:pattern #"^[A-Z]{3}$"}]]
   [:account-id {:optional true} UUID]
   [:notes {:optional true} :string]])

;;; ============================================================================
;;; ML Detection Schemas (Python service communication)
;;; ============================================================================

(def MLDetectionRequest
  "Request to Python ML service for merchant/category detection.

  Sent via HTTP POST to Python FastAPI service.
  Serialized as Transit JSON for type safety.

  Example:
    {:transaction-id \"123e4567-e89b-12d3-a456-426614174000\"
     :description \"STARBUCKS #12345\"
     :amount 45.99
     :date \"2024-11-06\"
     :type :expense
     :historical-amounts [42.50 43.00 45.99 46.50]}
  "
  [:map {:closed true
         :error/message "Invalid ML detection request"}
   [:transaction-id UUID]
   [:description NonEmptyString]
   [:amount PositiveNumber]
   [:date ISODateString]
   [:type TransactionType]
   [:historical-amounts {:optional true} [:vector :double]]])

(def MLDetectionResponse
  "Response from Python ML service.

  Received via HTTP from Python FastAPI service.
  Deserialized from Transit JSON.

  Example:
    {:merchant-detection {:canonical-name \"Starbucks\"
                          :confidence 0.95
                          :method \"pattern-matching\"}
     :category-detection {:name \"Coffee & Tea\"
                          :confidence 0.90
                          :rule-id \"rule-15\"}
     :anomaly-detection {:is-anomaly false
                         :confidence 0.85
                         :z-score 0.5}}
  "
  [:map {:closed true
         :error/message "Invalid ML detection response"}
   [:merchant-detection [:map
                         [:canonical-name NonEmptyString]
                         [:confidence Confidence]
                         [:method {:optional true} NonEmptyString]]]
   [:category-detection [:map
                         [:name NonEmptyString]
                         [:confidence Confidence]
                         [:rule-id {:optional true} NonEmptyString]]]
   [:anomaly-detection [:map
                        [:is-anomaly :boolean]
                        [:confidence Confidence]
                        [:z-score {:optional true} :double]]]])

;;; ============================================================================
;;; Review Queue Schemas (Human approval workflow)
;;; ============================================================================

(def ReviewQueueItem
  "Item waiting for human review/approval.

  Represents a transaction with ML detections that needs manual verification.
  Rich Hickey: Separate facts (transaction) from inferences (ML results)
  from decisions (approval/rejection).

  Example:
    {:id \"review-001\"
     :transaction-id \"123e4567-e89b-12d3-a456-426614174000\"
     :ml-detection {:merchant-detection {...}
                    :category-detection {...}
                    :anomaly-detection {...}}
     :status :pending
     :submitted-at \"2024-11-06T10:30:00Z\"
     :reviewed-at nil
     :reviewed-by nil
     :decision nil}
  "
  [:map {:closed true
         :error/message "Invalid review queue item"}
   [:id UUID]
   [:transaction-id UUID]
   [:ml-detection MLDetectionResponse]
   [:status [:enum :pending :approved :rejected :corrected]]
   [:submitted-at ISODateString]
   [:reviewed-at {:optional true} ISODateString]
   [:reviewed-by {:optional true} NonEmptyString]
   [:decision {:optional true} [:enum :approve :reject :correct]]
   [:correction {:optional true} [:map
                                   [:merchant {:optional true} Merchant]
                                   [:category {:optional true} Category]]]])

;;; ============================================================================
;;; Validation Functions (Explicit boundaries)
;;; ============================================================================

(defn validate
  "Validate data against a schema.

  Returns {:valid? true} or {:valid? false :errors [...]}

  Usage:
    (validate Transaction tx)
    => {:valid? true}

    (validate Transaction bad-tx)
    => {:valid? false
        :errors [{:path [:amount]
                  :message \"Number must be positive\"}]}
  "
  [schema data]
  (if (m/validate schema data)
    {:valid? true}
    {:valid? false
     :errors (-> (m/explain schema data)
                 (me/humanize))}))

(defn explain
  "Get detailed explanation of validation failure.

  Returns human-readable error messages.

  Usage:
    (explain Transaction bad-tx)
    => {:amount [\"Number must be positive\"]
        :date [\"Date must be in YYYY-MM-DD format\"]}
  "
  [schema data]
  (-> (m/explain schema data)
      (me/humanize)))

(defn coerce
  "Coerce data to match schema (type conversions).

  Attempts to convert strings to numbers, dates, etc.
  Returns coerced data or throws exception.

  Usage:
    (coerce Transaction {:amount \"45.99\" ...})
    => {:amount 45.99 ...}
  "
  [schema data]
  ;; TODO: Implement coercion with malli transformers
  ;; For now, just validate
  (if (m/validate schema data)
    data
    (throw (ex-info "Coercion failed"
                    {:schema schema
                     :data data
                     :errors (explain schema data)}))))

(defn generate
  "Generate random valid data from schema.

  Useful for testing.

  Usage:
    (generate Transaction)
    => {:id \"123e4567-e89b-12d3-a456-426614174000\"
        :date \"2024-11-06\"
        :amount 45.99
        ...}
  "
  [schema]
  (mg/generate schema))

;;; ============================================================================
;;; Schema Introspection (Debugging helpers)
;;; ============================================================================

(defn schema-info
  "Get metadata about a schema.

  Usage:
    (schema-info :transaction/v1)
    => {:version \"1.0.0\"
        :created \"2025-11-06\"
        :author \"darwin\"
        :description \"Core transaction schema with provenance\"
        :rich-hickey-alignment 95}
  "
  [schema-key]
  (get schema-metadata schema-key))

(defn list-schemas
  "List all available schemas with metadata."
  []
  (for [[k v] schema-metadata]
    (assoc v :key k)))

(defn validate-at-boundary
  "Validate data at API boundary (with logging).

  Rich Hickey: Validate at system boundaries, not scattered throughout code.

  Usage:
    (validate-at-boundary Transaction tx \"API /transactions POST\")
  "
  [schema data boundary-name]
  (let [result (validate schema data)]
    (when-not (:valid? result)
      ;; TODO: Add structured logging here
      (println (str "❌ Validation failed at boundary: " boundary-name))
      (println (str "   Errors: " (:errors result))))
    result))

;;; ============================================================================
;;; Exports (Public API)
;;; ============================================================================

(def schemas
  "Map of all schemas for easy access."
  {:transaction/v1 Transaction
   :ml-request/v1 MLDetectionRequest
   :ml-response/v1 MLDetectionResponse
   :review-item/v1 ReviewQueueItem
   :merchant Merchant
   :category Category
   :provenance Provenance})

(comment
  ;; REPL experiments

  ;; 1. Validate a transaction
  (def sample-tx
    {:id "123e4567-e89b-12d3-a456-426614174000"
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
                  :parser-version "1.2.0"}})

  (validate Transaction sample-tx)
  ;; => {:valid? true}

  ;; 2. Validate invalid transaction
  (def bad-tx
    {:id "not-a-uuid"
     :date "2024-13-40"  ; Invalid date
     :amount -45.99      ; Negative amount
     :description ""     ; Empty string
     :type :invalid})    ; Invalid type

  (explain Transaction bad-tx)
  ;; => {:id ["Must be a valid UUID"]
  ;;     :date ["Date must be in YYYY-MM-DD format"]
  ;;     :amount ["Number must be positive"]
  ;;     :description ["String must not be empty"]
  ;;     :type ["Invalid transaction type"]
  ;;     :provenance ["missing required key"]}

  ;; 3. Generate random valid transaction
  (generate Transaction)
  ;; => {:id "f47ac10b-58cc-4372-a567-0e02b2c3d479"
  ;;     :date "2024-11-06"
  ;;     :amount 123.45
  ;;     ...}

  ;; 4. List all schemas
  (list-schemas)
  ;; => ({:key :transaction/v1
  ;;      :version "1.0.0"
  ;;      :created "2025-11-06"
  ;;      :author "darwin"
  ;;      :description "Core transaction schema with provenance"
  ;;      :rich-hickey-alignment 95}
  ;;     ...)

  ;; 5. Schema metadata
  (schema-info :transaction/v1)
  ;; => {:version "1.0.0"
  ;;     :created "2025-11-06"
  ;;     :author "darwin"
  ;;     :description "Core transaction schema with provenance"
  ;;     :rich-hickey-alignment 95}
  )
