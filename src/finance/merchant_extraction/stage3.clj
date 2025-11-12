(ns finance.merchant-extraction.stage3
  "Stage 3: NER Extraction (Clean Merchant Names)"
  (:require [finance.merchant-extraction.protocols :as proto]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]))

;; ============================================================================
;; Text Extraction
;; ============================================================================

(defn- extract-merchant-text
  "Extracts merchant text from description, accounting for counterparty and beneficiary"
  [counterparty-tx]
  (let [description (:description counterparty-tx)
        beneficiary-name (:beneficiary-name counterparty-tx)  ;; From PDF parser
        counterparty-info (:counterparty-info counterparty-tx)
        merchant-hint (:actual-merchant-hint counterparty-info)]
    ;; Priority order:
    ;; 1. actual-merchant-hint (from counterparty extraction)
    ;; 2. beneficiary-name (from PDF parser - for SPEI/SWEB)
    ;; 3. description (last resort)
    (or (and merchant-hint (not= merchant-hint "") merchant-hint)
        beneficiary-name
        description)))

;; ============================================================================
;; Noise Removal
;; ============================================================================

(defn- apply-noise-pattern
  "Applies a single noise pattern, returns [cleaned-text removed-parts]"
  [text pattern-def]
  (let [regex (re-pattern (:regex pattern-def))
        matches (re-seq regex text)
        cleaned (str/replace text regex " ")]
    [cleaned (vec matches)]))

(defn- remove-noise
  "Removes all noise patterns from text"
  [text noise-patterns]
  (let [sorted-patterns (sort-by (fn [[_ def]] (:priority def 99)) noise-patterns)
        initial-state {:text text :removed [] :context []}]
    (reduce
     (fn [state [pattern-key pattern-def]]
       (let [[new-text removed] (apply-noise-pattern (:text state) pattern-def)]
         (if (:keep-as-context pattern-def)
           ;; Keep as context, not noise
           {:text new-text
            :removed (:removed state)
            :context (into (:context state) (filter some? removed))}
           ;; Add to removed noise
           {:text new-text
            :removed (into (:removed state) (filter some? removed))
            :context (:context state)})))
     initial-state
     sorted-patterns)))

;; ============================================================================
;; Post-processing
;; ============================================================================

(defn- clean-whitespace
  "Collapses multiple spaces and trims"
  [text]
  (-> text
      (str/replace #"\s+" " ")
      str/trim))

(defn- remove-leading-numbers
  "Removes leading numbers from merchant name"
  [text]
  (str/replace text #"^\d+\s+" ""))

(defn- apply-min-max-length
  "Ensures merchant name is within length bounds"
  [text min-len max-len]
  (let [trimmed (if (> (count text) max-len)
                  (subs text 0 max-len)
                  text)]
    (if (< (count trimmed) min-len)
      nil  ;; Too short, invalid
      trimmed)))

(defn- post-process
  "Applies post-processing cleanup"
  [text config]
  (when (and text (pos? (count text)))
    (let [cleaned (cond-> text
                    (:trim-whitespace config) clean-whitespace
                    (:remove-leading-numbers config) remove-leading-numbers)]
      (apply-min-max-length cleaned
                           (:min-merchant-length config 3)
                           (:max-merchant-length config 100)))))

;; ============================================================================
;; NER Extractor Implementation
;; ============================================================================

(defrecord NERExtractor [config]
  proto/NERExtractor
  (extract-merchant [this counterparty-tx rules]
    ;; Only extract if merchant is expected
    (if-not (:merchant? counterparty-tx)
      ;; No merchant expected, skip
      counterparty-tx

      ;; Merchant expected, extract
      (let [merchant-text (extract-merchant-text counterparty-tx)
            noise-patterns (:noise-patterns rules)
            extraction-config (:extraction-config rules)

            ;; Remove noise
            {:keys [text removed context]} (remove-noise merchant-text noise-patterns)

            ;; Post-process
            clean-merchant (post-process text extraction-config)]

        (if clean-merchant
          ;; Valid merchant extracted
          (merge counterparty-tx
                 {:clean-merchant clean-merchant
                  :merchant-name-raw clean-merchant  ;; For UI display
                  :is-clean true
                  :removed-noise removed
                  :kept-context context
                  :confidence (if (seq removed)
                                (* (:confidence counterparty-tx 1.0) 0.97)  ;; Slight penalty for noise
                                (:confidence counterparty-tx 1.0))
                  :stage-3 {:extraction-method (if (get-in counterparty-tx [:counterparty-info :detected?])
                                                 :post-counterparty
                                                 :full-description)
                            :noise-patterns-applied (count removed)
                            :timestamp (java.time.Instant/now)}})

          ;; Could not extract valid merchant - USE FALLBACK (original text)
          ;; NEVER return nil - ensures 100% coverage (pending review)
          (let [fallback-merchant (or merchant-text "UNKNOWN")]
            (merge counterparty-tx
                   {:clean-merchant fallback-merchant  ;; ✅ ALWAYS has value
                    :merchant-name-raw fallback-merchant
                    :is-clean false
                    :removed-noise removed
                    :kept-context context
                    :confidence 0.10  ;; Very low confidence → pending review
                    :stage-3 {:extraction-method :fallback
                              :fallback-reason "Merchant name too short after cleaning, using original text"
                              :timestamp (java.time.Instant/now)}})))))))

;; ============================================================================
;; Factory Function
;; ============================================================================

(defn create-extractor
  "Creates a NERExtractor instance"
  ([]
   (create-extractor {}))
  ([config]
   (->NERExtractor config)))

;; ============================================================================
;; Rules Loading
;; ============================================================================

(defn load-rules
  "Loads Stage 3 rules from EDN file"
  ([]
   (load-rules "rules/stage3_ner_extraction.edn"))
  ([resource-path]
   (with-open [r (io/reader (io/resource resource-path))]
     (edn/read (java.io.PushbackReader. r)))))

;; ============================================================================
;; Convenience Functions
;; ============================================================================

(defn extract
  "Convenience function to extract merchant with default rules"
  [counterparty-tx]
  (let [extractor (create-extractor)
        rules (load-rules)]
    (proto/extract-merchant extractor counterparty-tx rules)))

(defn extract-batch
  "Extracts merchants for batch of counterparty transactions"
  [counterparty-txs]
  (let [extractor (create-extractor)
        rules (load-rules)]
    (map #(proto/extract-merchant extractor % rules) counterparty-txs)))

;; ============================================================================
;; Statistics & Analysis
;; ============================================================================

(defn extraction-statistics
  "Returns statistics about merchant extraction"
  [clean-txs]
  (let [with-merchant (filter :merchant? clean-txs)
        extracted (filter :clean-merchant with-merchant)
        failed (remove :clean-merchant with-merchant)]
    {:total-transactions (count clean-txs)
     :merchant-transactions (count with-merchant)
     :successfully-extracted (count extracted)
     :extraction-failed (count failed)
     :success-rate (if (pos? (count with-merchant))
                     (format "%.1f%%" (* 100.0 (/ (count extracted) (count with-merchant))))
                     "N/A")
     :avg-noise-removed (if (seq extracted)
                          (/ (reduce + (map #(count (:removed-noise %)) extracted))
                             (count extracted))
                          0)
     :avg-merchant-length (if (seq extracted)
                            (/ (reduce + (map #(count (:clean-merchant %)) extracted))
                               (count extracted))
                            0)}))

;; ============================================================================
;; Validation
;; ============================================================================

(defn validate-clean-transaction
  "Validates that a clean transaction has required fields"
  [clean-tx]
  (or (not (:merchant? clean-tx))  ;; No merchant expected - no stage-3 needed
      (and (contains? clean-tx :stage-3)  ;; Merchant expected - must have stage-3
           (or (contains? clean-tx :clean-merchant)  ;; Successfully extracted
               (= :failed (get-in clean-tx [:stage-3 :extraction-method]))))))

(defn validate-batch
  "Validates batch of clean transactions"
  [clean-txs]
  {:valid (count (filter validate-clean-transaction clean-txs))
   :invalid (count (remove validate-clean-transaction clean-txs))
   :validation-errors (remove validate-clean-transaction clean-txs)})
