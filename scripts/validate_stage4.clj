(require '[finance.merchant-extraction.stage1 :as stage1])
(require '[finance.merchant-extraction.stage2 :as stage2])
(require '[finance.merchant-extraction.stage3 :as stage3])
(require '[finance.merchant-extraction.stage4 :as stage4])
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

(defn analyze-disambiguation
  "Analyzes merchant disambiguation results"
  [disambiguated-txs]
  (let [with-clean-merchant (filter :clean-merchant disambiguated-txs)
        rule-matched (filter #(= :rule-match (get-in % [:stage-4 :disambiguation-method])) with-clean-merchant)
        fallback (filter #(= :fallback (get-in % [:stage-4 :disambiguation-method])) with-clean-merchant)
        by-category (group-by :merchant-category rule-matched)
        unique-merchants (distinct (map :merchant-id rule-matched))]
    {:total-transactions (count disambiguated-txs)
     :clean-merchants (count with-clean-merchant)
     :rule-matched (count rule-matched)
     :fallback (count fallback)
     :match-rate (if (pos? (count with-clean-merchant))
                   (format "%.1f%%" (* 100.0 (/ (count rule-matched) (count with-clean-merchant))))
                   "0.0%")
     :categories (into (sorted-map)
                       (map (fn [[cat txs]]
                              [cat (count txs)])
                            by-category))
     :unique-merchants (count unique-merchants)
     :top-merchants (take 10 (sort-by (comp - count second)
                                      (group-by :merchant-id rule-matched)))
     :examples {:rule-matched (take 5 rule-matched)
                :fallback (take 5 fallback)}}))

;; ============================================================================
;; Main Execution
;; ============================================================================

(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "Badge ME-7: Stage 4 Validation (Merchant Disambiguation)")
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
(println)

;; Analysis
(def analysis (analyze-disambiguation disambiguated-txs))

;; Print report
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "STAGE 4 RESULTS")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println (format "Total transactions: %d" (:total-transactions analysis)))
(println (format "Clean merchants: %d" (:clean-merchants analysis)))
(println (format "Rule matched: %d" (:rule-matched analysis)))
(println (format "Fallback: %d" (:fallback analysis)))
(println (format "Match rate: %s" (:match-rate analysis)))
(println (format "Unique merchants identified: %d" (:unique-merchants analysis)))
(println)

(when (seq (:categories analysis))
  (println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
  (println "CATEGORIES BREAKDOWN")
  (println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
  (doseq [[category count] (:categories analysis)]
    (println (format "  %-40s %3d" (name category) count)))
  (println))

(when (seq (:top-merchants analysis))
  (println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
  (println "TOP 10 MERCHANTS")
  (println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
  (doseq [[merchant-id txs] (:top-merchants analysis)]
    (let [first-tx (first txs)]
      (println (format "  %-40s %3d" (name merchant-id) (count txs)))))
  (println))

(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "RULE-MATCHED EXAMPLES")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(when (seq (get-in analysis [:examples :rule-matched]))
  (doseq [tx (get-in analysis [:examples :rule-matched])]
    (println)
    (println (format "Date: %s" (:date tx)))
    (println (format "Clean Merchant: %s" (:clean-merchant tx)))
    (println (format "→ Merchant ID: %s" (name (:merchant-id tx))))
    (println (format "→ Canonical: %s" (:merchant-name tx)))
    (println (format "→ Category: %s" (name (:merchant-category tx))))
    (println (format "→ Matched Pattern: %s" (get-in tx [:stage-4 :matched-pattern])))
    (println (format "→ Confidence: %.1f%%" (* 100.0 (:confidence tx))))))

(println)
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "FALLBACK EXAMPLES")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(when (seq (get-in analysis [:examples :fallback]))
  (doseq [tx (get-in analysis [:examples :fallback])]
    (println)
    (println (format "Date: %s" (:date tx)))
    (println (format "Clean Merchant: %s" (:clean-merchant tx)))
    (println (format "→ Merchant ID: %s (generated)" (name (:merchant-id tx))))
    (println (format "→ Canonical: %s (original)" (:merchant-name tx)))
    (println (format "→ Category: %s (fallback)" (name (:merchant-category tx))))
    (println (format "→ Confidence: %.1f%%" (* 100.0 (:confidence tx))))))

(println)
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(if (>= (:rule-matched analysis) (* 0.60 (:clean-merchants analysis)))
  (println (format "✅ STAGE 4 SUCCESS - %.1f%% merchants matched to rules"
                   (* 100.0 (/ (:rule-matched analysis)
                              (:clean-merchants analysis)))))
  (println (format "⚠️ STAGE 4 NEEDS MORE RULES - Only %.1f%% matched"
                   (* 100.0 (/ (:rule-matched analysis)
                              (:clean-merchants analysis))))))
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
