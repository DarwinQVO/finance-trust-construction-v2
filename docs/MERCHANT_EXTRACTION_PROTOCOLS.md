# Merchant Extraction Pipeline - Protocols

**Badge:** ME-2
**Status:** In Progress
**Filosofía:** Protocol-oriented design (Clojure multimethods + protocols)

---

## 🎯 Principio Central

> "Polymorphism is about what you can do with something, not what it is."
> — Rich Hickey

**Implicación:** Los protocols definen CAPABILITIES, no tipos concretos.

---

## 📐 Architecture Overview

```
Pipeline = Stage1 → Stage2 → Stage3 → Stage4 → Stage5

Each stage:
- Input: Transaction map
- Output: Enriched transaction map
- Contract: Protocol function
- Rules: EDN data (external)
```

---

## 🔧 Stage 1: Transaction Type Detection

### Protocol

```clojure
(defprotocol TransactionTypeDetector
  "Detects transaction type and determines if merchant extraction is needed"
  (detect-type [this raw-tx rules]
    "Returns map with :type, :direction, :merchant?, :confidence"))
```

### Implementation Contract

**Input:**
```clojure
{:date "11-AGO-25"
 :description "CLIPMX AGREGADOR 00000000101008685717 CLIP MX REST HANAICHI..."
 :retiro 2236.00
 :deposit nil
 :saldo 13489.58}
```

**Output:**
```clojure
{;; Original (preserved)
 :date "11-AGO-25"
 :description "CLIPMX AGREGADOR ..."
 :retiro 2236.00
 :deposit nil
 :saldo 13489.58

 ;; NEW
 :type :card-purchase
 :direction :expense
 :merchant? true
 :confidence 0.95
 :stage-1 {:detected-by :pattern-match
           :matched-rule :card-purchase-pattern
           :timestamp (java.time.Instant/now)}}
```

### Rules Format (EDN)

```clojure
;; rules/stage1_type_detection.edn
{:transaction-types
 {:card-purchase
  {:patterns ["CARG RE" "REF\\." "AUT\\."]
   :direction :expense
   :merchant? true
   :confidence 0.95}

  :spei-transfer-out
  {:patterns ["SPEI" "TRANSF\\.INTERB"]
   :direction :transfer
   :merchant? false
   :confidence 0.98}

  :spei-transfer-in
  {:patterns ["SPEI" "TRANSF\\.INTERB"]
   :field :deposit
   :direction :income
   :merchant? false
   :confidence 0.98}

  :domiciliacion
  {:patterns ["DOMICILIACION"]
   :direction :expense
   :merchant? true  ;; Some domiciliaciones are subscriptions
   :confidence 0.85}

  :reversal
  {:patterns ["REV\\." "REVERSA"]
   :direction :income
   :merchant? true
   :confidence 0.92}}}
```

### Decision Logic

```clojure
(defn detect-type-impl [raw-tx rules]
  (let [description (:description raw-tx)
        has-deposit? (some? (:deposit raw-tx))
        has-retiro? (some? (:retiro raw-tx))]

    ;; Find first matching rule
    (or (find-matching-type description has-deposit? has-retiro? rules)
        {:type :unknown
         :direction :unknown
         :merchant? false
         :confidence 0.0})))
```

**Early termination:** Si `:merchant?` es `false`, pipeline TERMINA aquí.

---

## 🔧 Stage 2: Counterparty Detection

### Protocol

```clojure
(defprotocol CounterpartyDetector
  "Identifies payment aggregators/marketplaces (CLIP, ZETTLE, etc.)"
  (detect-counterparty [this typed-tx rules]
    "Returns map with :counterparty-info or nil"))
```

### Implementation Contract

**Input:** Output de Stage 1 (Typed Transaction)

**Output:**
```clojure
{;; Original + Stage 1 (preserved)
 :type :card-purchase
 :merchant? true

 ;; NEW
 :counterparty-info {:detected? true
                     :counterparty-id :clip
                     :counterparty-type :payment-aggregator
                     :actual-merchant-hint "REST HANAICHI"
                     :extract-after "CLIP MX"}
 :confidence 0.95
 :stage-2 {:detected-by :pattern-match
           :matched-rule :clip-aggregator
           :timestamp (java.time.Instant/now)}}
```

### Rules Format (EDN)

```clojure
;; rules/stage2_counterparty_detection.edn
{:counterparties
 {:clip
  {:type :payment-aggregator
   :patterns ["CLIPMX" "CLIP MX"]
   :extract-after "CLIP MX"
   :confidence 0.95}

  :zettle
  {:type :payment-aggregator
   :patterns ["ZETTLE"]
   :extract-after "ZETTLE"
   :confidence 0.95}

  :payu
  {:type :payment-aggregator
   :patterns ["PAYU"]
   :extract-after "PAYU"
   :confidence 0.90}

  :stripe
  {:type :payment-aggregator
   :patterns ["STRIPE"]
   :extract-after "STRIPE"
   :confidence 0.95}}}
```

### Decision Logic

```clojure
(defn detect-counterparty-impl [typed-tx rules]
  (if-not (:merchant? typed-tx)
    nil  ;; No counterparty if no merchant expected
    (let [description (:description typed-tx)]
      (or (find-matching-counterparty description rules)
          {:detected? false}))))
```

---

## 🔧 Stage 3: NER Extraction (Clean Merchant)

### Protocol

```clojure
(defprotocol NERExtractor
  "Extracts clean merchant name by removing noise"
  (extract-merchant [this counterparty-tx rules]
    "Returns map with :clean-merchant, :removed-noise, :kept-context"))
```

### Implementation Contract

**Input:** Output de Stage 2 (Counterparty Transaction)

**Output:**
```clojure
{;; Original + Stages 1-2 (preserved)
 :counterparty-info {:detected? true
                     :counterparty-id :clip
                     :extract-after "CLIP MX"}

 ;; NEW
 :clean-merchant "REST HANAICHI"
 :removed-noise ["REF. 0013732041"
                 "AUT. 742785"
                 "RFC BLI 120726UF6"
                 "00000000101008685717"]
 :kept-context ["CLIP MX"]
 :confidence 0.92
 :stage-3 {:extraction-method :post-counterparty
           :noise-patterns-applied 4
           :timestamp (java.time.Instant/now)}}
```

### Rules Format (EDN)

```clojure
;; rules/stage3_ner_extraction.edn
{:noise-patterns
 {:transaction-ids
  {:regex "\\d{14,20}"
   :description "Long numeric IDs"}

  :reference-numbers
  {:regex "REF\\. \\d+"
   :description "Reference numbers"}

  :authorization-codes
  {:regex "AUT\\. \\d+"
   :description "Authorization codes"}

  :rfc-codes
  {:regex "RFC [A-Z0-9]{12,13}"
   :description "Mexican tax IDs"}

  :monto-origen
  {:regex "MONTO ORIGEN [\\d\\.]+ [A-Z]{3}"
   :description "Original amount in foreign currency"}

  :exchange-rate
  {:regex "T/C [\\d\\.]+"
   :description "Exchange rate"}

  :location-codes
  {:regex "[A-Z]{2,3} \\d{4,5}"
   :description "Location postal codes"}

  :country-codes
  {:regex "\\b[A-Z]{3}\\b"
   :description "3-letter country codes"}}

 :context-patterns
 {:useful-prefixes ["CARG RE" "CLIP MX" "ZETTLE" "STRIPE" "PAYU"]}}
```

### Extraction Strategy

```clojure
(defn extract-merchant-impl [counterparty-tx rules]
  (let [description (:description counterparty-tx)
        counterparty-info (:counterparty-info counterparty-tx)

        ;; Strategy depends on counterparty presence
        extraction-point (if (:detected? counterparty-info)
                          (:extract-after counterparty-info)
                          nil)

        ;; Extract from proper position
        raw-text (if extraction-point
                   (extract-after description extraction-point)
                   description)

        ;; Remove noise
        [clean-text removed] (remove-noise raw-text (:noise-patterns rules))]

    {:clean-merchant clean-text
     :removed-noise removed
     :kept-context (extract-context description (:context-patterns rules))}))
```

---

## 🔧 Stage 4: Merchant Disambiguation

### Protocol

```clojure
(defprotocol MerchantDisambiguator
  "Disambiguates merchant variants (UBER vs UBER EATS)"
  (disambiguate-merchant [this clean-tx rules]
    "Returns map with :merchant-id, :merchant-name, :category"))
```

### Implementation Contract

**Input:** Output de Stage 3 (Clean Transaction)

**Output:**
```clojure
{;; Original + Stages 1-3 (preserved)
 :clean-merchant "REST HANAICHI"

 ;; NEW
 :merchant-id :restaurante-hanaichi
 :merchant-name "Restaurant Hanaichi"
 :category :restaurants
 :disambiguation-reason "Pattern 'REST' indicates restaurant"
 :confidence 0.90
 :stage-4 {:matched-rule :restaurant-pattern
           :alternatives-considered []
           :timestamp (java.time.Instant/now)}}
```

### Rules Format (EDN)

```clojure
;; rules/stage4_disambiguation.edn
{:disambiguation-rules
 {:google
  {:patterns
   [{:match "GOOGLE YOUTUBEPREMIUM"
     :merchant-id :google-youtube-premium
     :category :entertainment-subscriptions}

    {:match "GOOGLE ONE"
     :merchant-id :google-one
     :category :cloud-storage-subscriptions}

    {:match "GOOGLE CLOUD"
     :merchant-id :google-cloud
     :category :cloud-services}]}

  :uber
  {:patterns
   [{:match "UBER EATS"
     :merchant-id :uber-eats
     :category :restaurants}

    {:match "UBER ONE"
     :merchant-id :uber-one
     :category :subscriptions}

    {:match "UBER TRIP"
     :merchant-id :uber-rides
     :category :transportation}]}

  :restaurant-prefixes
  {:patterns
   [{:prefix "REST "
     :category :restaurants
     :confidence 0.90}

    {:prefix "RESTAURANT "
     :category :restaurants
     :confidence 0.95}]}}}
```

### Disambiguation Strategy

```clojure
(defn disambiguate-merchant-impl [clean-tx rules]
  (let [clean-name (:clean-merchant clean-tx)

        ;; Try exact match first
        exact-match (find-exact-match clean-name rules)

        ;; Then try pattern match
        pattern-match (if-not exact-match
                       (find-pattern-match clean-name rules)
                       nil)

        ;; Finally, use clean name as-is
        result (or exact-match
                   pattern-match
                   {:merchant-id (keyword (string/lower-case clean-name))
                    :merchant-name clean-name
                    :category :uncategorized
                    :confidence 0.60})]

    result))
```

---

## 🔧 Stage 5: Entity Resolution

### Protocol

```clojure
(defprotocol EntityResolver
  "Resolves merchant to entity (canonical/provisional/merged)"
  (resolve-entity [this disambiguated-tx entity-store rules]
    "Returns map with :entity, :entity-state, :needs-verification"))
```

### Implementation Contract

**Input:** Output de Stage 4 (Disambiguated Transaction)

**Output:**
```clojure
{;; Original + Stages 1-4 (preserved)
 :merchant-id :restaurante-hanaichi
 :merchant-name "Restaurant Hanaichi"
 :category :restaurants

 ;; NEW
 :entity {:entity-id :restaurante-hanaichi
          :canonical-name "Restaurant Hanaichi"
          :state :provisional
          :rfc "BLI 120726UF6"
          :country "MX"
          :aliases ["REST HANAICHI"]
          :transaction-count 1
          :first-seen "2025-08-11"
          :last-seen "2025-08-11"
          :confidence 0.85}
 :entity-state :provisional
 :needs-verification true
 :stage-5 {:resolution-method :auto-create-provisional
           :existing-entity? false
           :timestamp (java.time.Instant/now)}}
```

### Entity Store Operations

```clojure
(defprotocol EntityStore
  "Entity storage and retrieval"
  (get-entity [this entity-id]
    "Returns entity or nil")

  (find-by-alias [this alias]
    "Returns entities matching alias")

  (find-by-rfc [this rfc]
    "Returns entity with matching RFC")

  (create-entity [this entity]
    "Creates new entity, returns entity-id")

  (update-entity [this entity-id updates]
    "Updates entity (creates new version)")

  (merge-entities [this source-id target-id reason]
    "Marks source as merged into target"))
```

### Resolution Strategy

```clojure
(defn resolve-entity-impl [disambiguated-tx entity-store rules]
  (let [merchant-id (:merchant-id disambiguated-tx)
        merchant-name (:merchant-name disambiguated-tx)

        ;; Try to find existing entity
        existing (or
                  ;; 1. Exact ID match
                  (get-entity entity-store merchant-id)

                  ;; 2. RFC match (if present)
                  (when-let [rfc (extract-rfc (:description disambiguated-tx))]
                    (find-by-rfc entity-store rfc))

                  ;; 3. Alias match
                  (find-by-alias entity-store merchant-name)

                  ;; 4. Fuzzy match (Levenshtein < 3)
                  (fuzzy-match entity-store merchant-name rules))]

    (if existing
      ;; Update existing entity
      (update-entity-stats entity-store existing disambiguated-tx)

      ;; Create new provisional entity
      (create-provisional-entity entity-store disambiguated-tx))))
```

### Entity Lifecycle Rules

```clojure
;; rules/stage5_entity_resolution.edn
{:resolution-rules
 {:fuzzy-match
  {:levenshtein-threshold 3
   :confidence-penalty 0.10}

  :auto-graduation
  {:transaction-threshold 10
   :confidence-threshold 0.95
   :time-span-days 30}

  :merge-detection
  {:check-typos true
   :check-abbreviations true
   :require-manual-approval true}}}
```

---

## 🔄 Pipeline Orchestration

### Main Pipeline Function

```clojure
(defn process-transaction
  "Orchestrates all 5 stages"
  [raw-tx stage-impls rules entity-store]
  (let [stage1 (:stage1 stage-impls)
        stage2 (:stage2 stage-impls)
        stage3 (:stage3 stage-impls)
        stage4 (:stage4 stage-impls)
        stage5 (:stage5 stage-impls)

        ;; Stage 1: Type Detection
        typed-tx (detect-type stage1 raw-tx (:stage1 rules))

        ;; Early termination if no merchant expected
        _ (when-not (:merchant? typed-tx)
            (return typed-tx))

        ;; Stage 2: Counterparty Detection
        counterparty-tx (detect-counterparty stage2 typed-tx (:stage2 rules))

        ;; Stage 3: NER Extraction
        clean-tx (extract-merchant stage3 counterparty-tx (:stage3 rules))

        ;; Stage 4: Disambiguation
        disambiguated-tx (disambiguate-merchant stage4 clean-tx (:stage4 rules))

        ;; Stage 5: Entity Resolution
        resolved-tx (resolve-entity stage5 disambiguated-tx entity-store (:stage5 rules))]

    ;; Add pipeline metadata
    (assoc resolved-tx
           :pipeline {:stages-completed 5
                      :total-time-ms (compute-time)
                      :final-confidence (:confidence resolved-tx)})))
```

### Batch Processing

```clojure
(defn process-batch
  "Processes multiple transactions"
  [raw-txs stage-impls rules entity-store]
  (let [results (atom [])
        errors (atom [])]

    (doseq [raw-tx raw-txs]
      (try
        (let [processed (process-transaction raw-tx stage-impls rules entity-store)]
          (swap! results conj processed))
        (catch Exception e
          (swap! errors conj {:tx raw-tx
                              :error (.getMessage e)}))))

    {:processed @results
     :errors @errors
     :stats {:total (count raw-txs)
             :success (count @results)
             :failed (count @errors)}}))
```

---

## 📦 Implementation Example (Stub)

```clojure
(ns finance.merchant-extraction.stage1
  (:require [finance.merchant-extraction.protocols :as proto]))

(defrecord TypeDetector [config]
  proto/TransactionTypeDetector
  (detect-type [this raw-tx rules]
    ;; Implementation here
    (let [description (:description raw-tx)
          matched-type (match-patterns description (:transaction-types rules))]
      (assoc raw-tx
             :type (:type matched-type)
             :direction (:direction matched-type)
             :merchant? (:merchant? matched-type)
             :confidence (:confidence matched-type)
             :stage-1 {:detected-by :pattern-match
                       :matched-rule (:rule-id matched-type)
                       :timestamp (java.time.Instant/now)}))))

(defn create-detector [config]
  (->TypeDetector config))
```

---

## ✅ Badge ME-2 Success Criteria

**DONE when:**
1. ✅ All 5 stage protocols defined
2. ✅ EntityStore protocol defined
3. ✅ Pipeline orchestration function designed
4. ✅ Rules format specified for each stage (EDN)
5. ✅ Implementation contracts clear (input → output)
6. ✅ Early termination logic documented
7. ✅ Error handling strategy defined

---

**Status:** ✅ COMPLETE
**Next:** Badge ME-3 (Stage 1 Implementation)
