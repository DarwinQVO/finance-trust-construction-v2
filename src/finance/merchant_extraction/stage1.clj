(ns finance.merchant-extraction.stage1
  "Stage 1: Transaction Type Detection"
  (:require [finance.merchant-extraction.protocols :as proto]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]))

;; ============================================================================
;; Pattern Matching
;; ============================================================================

(defn- pattern-matches?
  "Returns true if pattern matches description"
  [pattern description case-sensitive?]
  (let [desc (if case-sensitive? description (str/upper-case description))
        pat (if case-sensitive? pattern (str/upper-case pattern))
        regex (re-pattern pat)]
    (some? (re-find regex desc))))

(defn- check-field-requirement
  "Returns true if field requirement is met"
  [rule-def raw-tx]
  (if-let [required-field (:field rule-def)]
    (some? (get raw-tx required-field))
    true))  ;; No field requirement

(defn- match-single-type
  "Returns match info if transaction matches this type, nil otherwise"
  [type-key type-def description raw-tx case-sensitive?]
  (let [patterns (:patterns type-def)
        requires-all? (:requires-all? type-def false)

        ;; Check patterns
        pattern-results (map #(pattern-matches? % description case-sensitive?) patterns)
        patterns-match? (if requires-all?
                          (every? true? pattern-results)
                          (some true? pattern-results))

        ;; Check field requirement
        field-ok? (check-field-requirement type-def raw-tx)]

    (when (and patterns-match? field-ok?)
      {:type type-key
       :direction (:direction type-def)
       :merchant? (:merchant? type-def)
       :confidence (:confidence type-def)
       :matched-rule type-key
       :description (:description type-def)})))

(defn- find-matching-type
  "Returns first matching transaction type (respects priority order)"
  [raw-tx rules]
  (let [;; Use FULL text: description + context-lines for matching (like Stage 2)
        full-text (str (:description raw-tx "")
                       " "
                       (str/join " " (:context-lines raw-tx [])))
        type-defs (:transaction-types rules)
        config (:matching-config rules)
        case-sensitive? (:case-sensitive? config false)
        priority-order (:priority-order config (keys type-defs))]

    ;; DEBUG: Log what we're searching in
    (println (format "🔍 Stage 1 searching in: '%s'"
                     (subs full-text 0 (min 80 (count full-text)))))

    ;; Try each type in priority order
    (some
     (fn [type-key]
       (when-let [type-def (get type-defs type-key)]
         (match-single-type type-key type-def full-text raw-tx case-sensitive?)))
     priority-order)))

;; ============================================================================
;; Type Detector Implementation
;; ============================================================================

(defrecord TypeDetector [config]
  proto/TransactionTypeDetector
  (detect-type [this raw-tx rules]
    (let [match-result (find-matching-type raw-tx rules)]

      (if match-result
        ;; Match found
        (merge raw-tx
               {:type (:type match-result)
                :direction (:direction match-result)
                :merchant? (:merchant? match-result)
                :confidence (:confidence match-result)
                :stage-1 {:detected-by :pattern-match
                          :matched-rule (:matched-rule match-result)
                          :rule-description (:description match-result)
                          :timestamp (java.time.Instant/now)}})

        ;; No match - unknown (but still attempt merchant extraction)
        (merge raw-tx
               {:type :unknown
                :direction :unknown
                :merchant? true   ;; Changed: Always attempt extraction for unknown types
                :confidence 0.30  ;; Changed: Give some confidence (low but not zero)
                :stage-1 {:detected-by :no-match
                          :matched-rule nil
                          :timestamp (java.time.Instant/now)}})))))

;; ============================================================================
;; Factory Function
;; ============================================================================

(defn create-detector
  "Creates a TypeDetector instance"
  ([]
   (create-detector {}))
  ([config]
   (->TypeDetector config)))

;; ============================================================================
;; Rules Loading
;; ============================================================================

(defn load-rules
  "Loads Stage 1 rules from EDN file"
  ([]
   (load-rules "rules/stage1_type_detection.edn"))
  ([resource-path]
   (with-open [r (io/reader (io/resource resource-path))]
     (edn/read (java.io.PushbackReader. r)))))

;; ============================================================================
;; Convenience Functions
;; ============================================================================

(defn detect
  "Convenience function to detect type with default rules"
  [raw-tx]
  (let [detector (create-detector)
        rules (load-rules)]
    (proto/detect-type detector raw-tx rules)))

(defn detect-batch
  "Detects types for batch of transactions"
  [raw-txs]
  (let [detector (create-detector)
        rules (load-rules)]
    (map #(proto/detect-type detector % rules) raw-txs)))

;; ============================================================================
;; Statistics & Analysis
;; ============================================================================

(defn type-statistics
  "Returns statistics about detected types"
  [typed-txs]
  (let [by-type (group-by :type typed-txs)
        by-merchant? (group-by :merchant? typed-txs)]
    {:total-count (count typed-txs)
     :by-type (into {} (map (fn [[k v]] [k (count v)]) by-type))
     :merchant-extraction-needed (count (get by-merchant? true))
     :no-merchant-expected (count (get by-merchant? false))
     :unknown-count (count (get by-type :unknown))}))

;; ============================================================================
;; Validation
;; ============================================================================

(defn validate-typed-transaction
  "Validates that a typed transaction has required fields"
  [typed-tx]
  (let [required-fields [:type :direction :merchant? :confidence :stage-1]]
    (every? #(contains? typed-tx %) required-fields)))

(defn validate-batch
  "Validates batch of typed transactions"
  [typed-txs]
  {:valid (count (filter validate-typed-transaction typed-txs))
   :invalid (count (remove validate-typed-transaction typed-txs))
   :validation-errors (remove validate-typed-transaction typed-txs)})
