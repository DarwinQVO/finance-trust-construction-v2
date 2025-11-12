(ns finance.merchant-extraction.stage4
  "Stage 4: Entity Resolution (Registry Lookup + Manual Classification)
   Now uses data-driven entity-engine for ALL entities"
  (:require [finance.merchant-extraction.protocols :as proto]
            [finance.entity-registry :as registry]
            [finance.entity-engine :as engine]
            [clojure.string :as str]))

;; ============================================================================
;; Type-Aware Merchant Extraction
;; ============================================================================

(defn- extract-rfc-from-pattern
  "Extracts RFC from matched pattern (e.g., 'RFC/CURP: SAT8410245V8' → 'SAT8410245V8')
   RFC format: 3-4 letters + 6 digits + 3 alphanumeric characters"
  [matched-pattern]
  (when matched-pattern
    (let [rfc-pattern #"([A-Z]{3,4}\d{6}[A-Z0-9]{3})"]
      (when-let [match (re-find rfc-pattern matched-pattern)]
        (if (string? match) match (second match))))))

(defn- get-merchant-field-by-type
  "Returns appropriate merchant field based on transaction type
   Polymorphic merchant: meaning changes by type

   PRIORITY:
   1. If RFC extracted (from counterparty or direct) → use RFC (e.g., SAT8410245V8)
   2. If beneficiary-name exists (SPEI/SWEB) → use beneficiary
   3. If counterparty detected with actual-merchant-hint → use hint
   4. Otherwise → use clean-merchant

   For DOMICILIACION with generic merchant, the RFC IS the merchant identifier"
  [clean-tx]
  (let [tx-type (:type clean-tx)
        clean-merchant (:clean-merchant clean-tx)
        beneficiary (:beneficiary-name clean-tx)
        counterparty-info (:counterparty-info clean-tx)
        counterparty-detected? (:detected? counterparty-info false)

        ;; Extract RFC from various sources
        rfc-extracted (:rfc counterparty-info)  ;; Direct RFC extraction (Stage 2)
        matched-pattern (:matched-pattern counterparty-info)  ;; Pattern that matched
        rfc-from-pattern (extract-rfc-from-pattern matched-pattern)  ;; Extract from pattern

        ;; Priority: use RFC if available (either extracted or from pattern)
        final-rfc (or rfc-extracted rfc-from-pattern)

        actual-merchant-hint (:actual-merchant-hint counterparty-info)]

    ;; PRIORITY 1: If RFC available, use it (this is the actual merchant identifier)
    (if final-rfc
      final-rfc  ;; Use RFC as merchant (e.g., "SAT8410245V8", "CNM980114PI2")

      ;; PRIORITY 2: No RFC, use type-specific logic
      (case tx-type
        ;; SPEI/SWEB transfers → prioritize clean-merchant if available, fallback to beneficiary
        :spei (or clean-merchant beneficiary)
        :sweb (or clean-merchant beneficiary)

        ;; Domiciliación → try actual-merchant-hint or clean-merchant
        :domiciliacion (or actual-merchant-hint clean-merchant)

        ;; Card purchases → use actual-merchant-hint if available (e.g., "YOUTUBEPREMIUM" from "GOOGLE YOUTUBEPREMIUM")
        :card-purchase (or actual-merchant-hint clean-merchant)
        :card-withdrawal clean-merchant
        :pos-purchase clean-merchant

        ;; Default: use clean merchant
        clean-merchant))))

;; ============================================================================
;; Registry Lookup
;; ============================================================================

(defn- lookup-in-registry
  "Looks up merchant in registry, returns enrichment data or nil"
  [merchant-text]
  (when (and merchant-text (not= merchant-text ""))
    (registry/lookup-merchant merchant-text)))

;; ============================================================================
;; Confidence Scoring for Entity Resolution
;; ============================================================================

(defn- calculate-entity-confidence
  "Calculates confidence score for entity resolution
   Factors:
   - Registry match quality (exact vs substring)
   - Previous stage confidence
   - Unknown entity penalty"
  [clean-tx registry-match]
  (let [previous-confidence (:confidence clean-tx 0.5)]
    (if registry-match
      ;; Registry match found
      (let [match-confidence (:confidence registry-match 0.8)
            ;; Combine previous confidence with match confidence
            combined (* previous-confidence match-confidence)]
        combined)

      ;; No registry match - unknown entity
      ;; Reduce confidence significantly
      (* previous-confidence 0.30))))

;; ============================================================================
;; Entity Resolver Implementation
;; ============================================================================

(defrecord EntityResolver [config]
  proto/EntityResolver
  (resolve-entity [this clean-tx]
    ;; Get merchant field (works even without :clean-merchant if RFC exists)
    (let [merchant-text (get-merchant-field-by-type clean-tx)]

      ;; DEBUG LOGGING
      (println (format "🔍 STAGE 4 DEBUG:"))
      (println (format "  clean-merchant: %s" (:clean-merchant clean-tx)))
      (println (format "  beneficiary-name: %s" (:beneficiary-name clean-tx)))
      (println (format "  tx-type: %s" (:type clean-tx)))
      (println (format "  merchant-text (from get-merchant-field-by-type): %s" merchant-text))

      ;; Only skip if NO merchant field could be extracted at all
      (if-not merchant-text
        ;; No merchant extracted AND no RFC, skip entity resolution
        (do
          (println (format "  ❌ SKIPPING Stage 4: No merchant text"))
          (merge clean-tx
                 {:entity-resolved? false
                  :needs-manual-classification false  ; ← No merchant to classify
                  :stage-4 {:status :skipped
                            :reason "No merchant extracted in Stage 3 and no RFC"
                            :timestamp (java.time.Instant/now)}}))

        ;; Merchant text available (from :clean-merchant, beneficiary, or RFC)
        (let [

            ;; Look up in registry
            registry-match (lookup-in-registry merchant-text)

            ;; Calculate confidence
            entity-confidence (calculate-entity-confidence clean-tx registry-match)]

        ;; DEBUG: Show lookup result
        (println (format "  🔎 REGISTRY LOOKUP:"))
        (println (format "     search-text: '%s'" merchant-text))
        (println (format "     match-found?: %s" (boolean registry-match)))
        (when registry-match
          (println (format "     canonical-name: %s" (:canonical-name registry-match)))
          (println (format "     category: %s" (:category registry-match)))
          (println (format "     match-type: %s" (:match-type registry-match)))
          (println (format "     confidence: %.2f" (:confidence registry-match))))
        (when-not registry-match
          (println (format "     ⚠️  NOT FOUND → will add to pending")))

        (if registry-match
          ;; Found in registry → resolve automatically
          (merge clean-tx
                 {:entity-resolved? true
                  :canonical-merchant (:canonical-name registry-match)
                  :merchant-category (:category registry-match)
                  :entity-type (:entity-type registry-match)
                  :confidence entity-confidence
                  :stage-4 {:status :resolved
                            :source :registry
                            :match-type (:match-type registry-match)
                            :merchant-id (:merchant-id registry-match)
                            :original-text merchant-text
                            :timestamp (java.time.Instant/now)}})

          ;; Not found in registry → needs manual classification
          (do
            ;; Add to pending classification queue with FULL transaction context
            (when merchant-text
              (registry/add-pending-classification
               merchant-text
               clean-tx))  ;; Pass FULL transaction for complete provenance

            ;; Return transaction with pending status
            (merge clean-tx
                   {:entity-resolved? false
                    :canonical-merchant nil
                    :merchant-category :unknown
                    :entity-type :unknown
                    :confidence entity-confidence
                    :needs-manual-classification true
                    :stage-4 {:status :pending-classification
                              :source :unknown
                              :original-text merchant-text
                              :timestamp (java.time.Instant/now)}}))))))))

;; ============================================================================
;; Factory Function
;; ============================================================================

(defn create-resolver
  "Creates an EntityResolver instance"
  ([]
   (create-resolver {}))
  ([config]
   (->EntityResolver config)))

;; ============================================================================
;; Bank Entity Resolution
;; ============================================================================

(defn- extract-bank-from-pdf-source
  "Extracts bank name from pdf-source filename
   Example: 'scotiabank_edo_2025-07-14_0372.pdf' → 'scotiabank'"
  [pdf-source]
  (when pdf-source
    (-> pdf-source
        (clojure.string/split #"_")
        first
        clojure.string/lower-case)))

(defn resolve-bank-entity
  "Resolves bank entity from pdf-source or bank field
   Returns enriched transaction with bank entity data"
  [transaction]
  (let [;; Try to get bank from pdf-source first
        pdf-source (:pdf-source transaction)
        extracted-bank (extract-bank-from-pdf-source pdf-source)

        ;; Fallback to explicit bank field
        bank-text (or extracted-bank (:bank transaction))

        ;; Look up in bank registry
        bank-entity (when bank-text
                      (registry/lookup-bank bank-text))]

    (if bank-entity
      (merge transaction
             {:bank-entity bank-entity
              :bank-resolved? true
              :bank-canonical (:canonical-name bank-entity)
              :bank-type (:bank-type bank-entity)
              :bank-country (:country bank-entity)})
      ;; No bank entity found
      (merge transaction
             {:bank-entity nil
              :bank-resolved? false
              :bank-text bank-text}))))

;; ============================================================================
;; Account Entity Resolution
;; ============================================================================

(defn resolve-account-entity
  "Resolves account entity from account-name or derives from bank
   Returns enriched transaction with account entity data"
  [transaction]
  (let [;; Try explicit account-name first
        account-text (:account-name transaction)

        ;; Fallback: derive from bank + account type
        bank-canonical (:bank-canonical transaction)
        derived-account (when (and bank-canonical (not account-text))
                          (str bank-canonical " Checking"))

        final-account-text (or account-text derived-account)

        ;; Look up in account registry
        account-entity (when final-account-text
                        (registry/lookup-account final-account-text))]

    (if account-entity
      (merge transaction
             {:account-entity account-entity
              :account-resolved? true
              :account-canonical (:canonical-name account-entity)
              :account-type (:account-type account-entity)})
      ;; No account entity found, use defaults
      (merge transaction
             {:account-entity nil
              :account-resolved? false
              :account-text final-account-text}))))

;; ============================================================================
;; Category Entity Resolution
;; ============================================================================

(defn resolve-category-entity
  "Resolves category entity from merchant-category
   Returns enriched transaction with category entity data"
  [transaction]
  (let [;; Get category from merchant entity
        category-text (:merchant-category transaction)

        ;; Look up in category registry
        category-entity (when category-text
                         (registry/lookup-category category-text))]

    (if category-entity
      (merge transaction
             {:category-entity category-entity
              :category-resolved? true
              :category-canonical (:canonical-name category-entity)
              :budget-category (:budget-category category-entity)
              :budget-subcategory (:budget-subcategory category-entity)})
      ;; No category entity found
      (merge transaction
             {:category-entity nil
              :category-resolved? false
              :category-text category-text}))))

;; ============================================================================
;; Complete Entity Resolution (All 4)
;; ============================================================================

(defn resolve-all-entities
  "Resolves all 4 entity types: Merchant, Bank, Account, Category
   Now uses data-driven entity-engine for ALL entities
   Entities are defined in resources/config/entity_definitions.json"
  [transaction]
  ;; Delegate to generic entity engine
  (engine/resolve-all-entities transaction))

;; ============================================================================
;; Convenience Functions
;; ============================================================================

(defn resolve
  "Convenience function to resolve entity with default resolver"
  [clean-tx]
  (let [resolver (create-resolver)]
    (proto/resolve-entity resolver clean-tx)))

(defn resolve-batch
  "Resolves ALL 4 entities for batch of clean transactions
   Now uses data-driven entity-engine for batch processing"
  [clean-txs]
  ;; Delegate to generic entity engine
  (engine/resolve-batch clean-txs))

;; ============================================================================
;; Statistics & Analysis
;; ============================================================================

(defn resolution-statistics
  "Returns statistics about entity resolution"
  [resolved-txs]
  (let [with-merchant (filter :clean-merchant resolved-txs)
        resolved (filter :entity-resolved? with-merchant)
        pending (filter :needs-manual-classification with-merchant)
        by-source (group-by #(get-in % [:stage-4 :source]) resolved)
        by-type (group-by :entity-type resolved)]
    {:total-transactions (count resolved-txs)
     :with-merchant (count with-merchant)
     :resolved (count resolved)
     :pending-manual-classification (count pending)
     :resolution-rate (if (pos? (count with-merchant))
                        (format "%.1f%%" (* 100.0 (/ (count resolved) (count with-merchant))))
                        "N/A")
     :avg-confidence (if (seq resolved)
                       (/ (reduce + (map :confidence resolved))
                          (count resolved))
                       0)
     :by-source (into {}
                     (map (fn [[k v]]
                            [k (count v)])
                          by-source))
     :by-entity-type (into {}
                          (map (fn [[k v]]
                                 [k (count v)])
                               by-type))}))

;; ============================================================================
;; Validation
;; ============================================================================

(defn validate-resolved-transaction
  "Validates that a resolved transaction has required fields"
  [resolved-tx]
  (and (contains? resolved-tx :stage-4)
       (or (not (:clean-merchant resolved-tx))  ;; No merchant - stage-4 skipped
           (:entity-resolved? resolved-tx)       ;; Entity resolved
           (:needs-manual-classification resolved-tx))))  ;; Pending classification

(defn validate-batch
  "Validates batch of resolved transactions"
  [resolved-txs]
  {:valid (count (filter validate-resolved-transaction resolved-txs))
   :invalid (count (remove validate-resolved-transaction resolved-txs))
   :validation-errors (remove validate-resolved-transaction resolved-txs)})
