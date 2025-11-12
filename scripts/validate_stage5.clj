(require '[finance.merchant-extraction.stage1 :as stage1])
(require '[finance.merchant-extraction.stage2 :as stage2])
(require '[finance.merchant-extraction.stage3 :as stage3])
(require '[finance.merchant-extraction.stage4 :as stage4])
(require '[finance.merchant-extraction.stage5 :as stage5])
(require '[clojure.java.io :as io])
(require '[clojure.edn :as edn])
(require '[clojure.pprint :as pp])

;; ============================================================================
;; Load Test Data
;; ============================================================================

(defn load-test-data []
  (with-open [r (io/reader "test-data/scotiabank_ALL_transactions.edn")]
    (edn/read (java.io.PushbackReader. r))))

;; ============================================================================
;; Analysis Functions
;; ============================================================================

(defn analyze-resolution
  "Analyzes entity resolution results"
  [resolved-txs]
  (let [with-entities (filter :entity resolved-txs)
        new-entities (filter #(= :new-entity (get-in % [:stage-5 :resolution-method])) with-entities)
        existing-entities (filter #(= :existing-entity (get-in % [:stage-5 :resolution-method])) with-entities)
        needs-review (filter :needs-verification with-entities)
        by-state (group-by :entity-state with-entities)
        unique-entities (distinct (map :entity-id with-entities))]
    {:total-transactions (count resolved-txs)
     :entities-resolved (count with-entities)
     :new-entities (count new-entities)
     :existing-entities (count existing-entities)
     :needs-verification (count needs-review)
     :verification-rate (if (pos? (count with-entities))
                          (format "%.1f%%" (* 100.0 (/ (count needs-review) (count with-entities))))
                          "0.0%")
     :by-state (into (sorted-map)
                     (map (fn [[state txs]]
                            [state (count txs)])
                          by-state))
     :unique-entities (count unique-entities)
     :canonical-entities (count (filter #(= :canonical (:entity-state %)) with-entities))
     :provisional-entities (count (filter #(= :provisional (:entity-state %)) with-entities))
     :examples {:canonical (take 5 (filter #(= :canonical (:entity-state %)) with-entities))
                :provisional (take 5 (filter #(= :provisional (:entity-state %)) with-entities))
                :needs-review (take 5 needs-review)}}))

;; ============================================================================
;; Main Execution
;; ============================================================================

(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "Badge ME-8: Stage 5 Validation (Entity Resolution)")
(println "Testing with 71 Scotiabank transactions")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println)

;; Load data
(println "Loading test data...")
(def raw-txs (load-test-data))
(println (format "✓ Loaded %d transactions" (count raw-txs)))
(println)

;; Process with Stage 1
(println "Running Stage 1 (Type Detection)...")
(def typed-txs (stage1/detect-batch raw-txs))
(println (format "✓ Stage 1: %d transactions" (count typed-txs)))

;; Process with Stage 2
(println "Running Stage 2 (Counterparty Detection)...")
(def counterparty-txs (stage2/detect-batch typed-txs))
(println (format "✓ Stage 2: %d transactions" (count counterparty-txs)))

;; Process with Stage 3
(println "Running Stage 3 (NER Extraction)...")
(def clean-txs (stage3/extract-batch counterparty-txs))
(println (format "✓ Stage 3: %d transactions" (count clean-txs)))

;; Process with Stage 4
(println "Running Stage 4 (Merchant Disambiguation)...")
(def disambiguated-txs (stage4/disambiguate-batch clean-txs))
(println (format "✓ Stage 4: %d transactions" (count disambiguated-txs)))

;; Create entity store
(println "Creating entity store...")
(def entity-store (stage5/create-entity-store))
(println "✓ Entity store created")

;; Process with Stage 5
(println "Running Stage 5 (Entity Resolution)...")
(def resolved-txs (stage5/resolve-batch disambiguated-txs entity-store))
(println (format "✓ Stage 5: %d transactions" (count resolved-txs)))
(println)

;; Analysis
(def analysis (analyze-resolution resolved-txs))

;; Print report
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "STAGE 5 RESULTS")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println (format "Total transactions: %d" (:total-transactions analysis)))
(println (format "Entities resolved: %d" (:entities-resolved analysis)))
(println (format "New entities created: %d" (:new-entities analysis)))
(println (format "Existing entities reused: %d" (:existing-entities analysis)))
(println (format "Unique entities: %d" (:unique-entities analysis)))
(println (format "Needs verification: %d (%s)"
                 (:needs-verification analysis)
                 (:verification-rate analysis)))
(println)

(when (seq (:by-state analysis))
  (println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
  (println "ENTITY STATES")
  (println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
  (println (format "  Canonical:    %3d" (:canonical-entities analysis)))
  (println (format "  Provisional:  %3d" (:provisional-entities analysis)))
  (println))

(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "CANONICAL ENTITIES (Rule-matched)")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(when (seq (get-in analysis [:examples :canonical]))
  (doseq [tx (get-in analysis [:examples :canonical])]
    (println)
    (println (format "Date: %s" (:date tx)))
    (println (format "Entity ID: %s" (name (:entity-id tx))))
    (println (format "Merchant Name: %s" (:merchant-name tx)))
    (println (format "Category: %s" (name (:merchant-category tx))))
    (println (format "State: %s" (name (:entity-state tx))))
    (println (format "Transaction #: %d" (get-in tx [:entity :transaction-count] 1)))
    (println (format "Needs Review: %s" (:needs-verification tx)))))

(println)
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "PROVISIONAL ENTITIES (Fallback)")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(when (seq (get-in analysis [:examples :provisional]))
  (doseq [tx (take 3 (get-in analysis [:examples :provisional]))]
    (println)
    (println (format "Date: %s" (:date tx)))
    (println (format "Entity ID: %s" (name (:entity-id tx))))
    (println (format "Merchant Name: %s" (:merchant-name tx)))
    (println (format "Category: %s" (name (:merchant-category tx))))
    (println (format "State: %s (needs promotion to canonical)" (name (:entity-state tx))))
    (println (format "Transaction #: %d" (get-in tx [:entity :transaction-count] 1)))
    (println (format "Needs Review: %s" (:needs-verification tx)))
    (println (format "Verification Reason: %s" (:verification-reason tx)))))

(println)
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "NEEDS MANUAL REVIEW")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println (format "Total needing review: %d" (:needs-verification analysis)))
(when (seq (get-in analysis [:examples :needs-review]))
  (doseq [tx (take 3 (get-in analysis [:examples :needs-review]))]
    (println)
    (println (format "Date: %s" (:date tx)))
    (println (format "Entity: %s" (name (:entity-id tx))))
    (println (format "Reason: %s" (:verification-reason tx)))
    (println (format "Confidence: %.1f%%" (* 100.0 (:confidence tx))))))

(println)
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(if (>= (:entities-resolved analysis) (:new-entities analysis))
  (println (format "✅ STAGE 5 SUCCESS - %d unique entities created"
                   (:unique-entities analysis)))
  (println "⚠️ STAGE 5 ISSUE - Entity resolution incomplete"))
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
