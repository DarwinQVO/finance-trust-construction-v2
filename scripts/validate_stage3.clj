(require '[finance.merchant-extraction.stage1 :as stage1])
(require '[finance.merchant-extraction.stage2 :as stage2])
(require '[finance.merchant-extraction.stage3 :as stage3])
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

(defn analyze-extraction
  "Analyzes merchant extraction results"
  [clean-txs]
  (let [with-merchant (filter :merchant? clean-txs)
        extracted (filter :clean-merchant with-merchant)
        failed (remove :clean-merchant with-merchant)

        ;; Group by merchant length
        by-length (group-by #(count (:clean-merchant %)) extracted)

        ;; Noise analysis
        total-noise (reduce + (map #(count (:removed-noise %)) extracted))
        avg-noise (if (seq extracted) (/ total-noise (count extracted)) 0)]

    {:total-transactions (count clean-txs)
     :merchant-transactions (count with-merchant)
     :successfully-extracted (count extracted)
     :extraction-failed (count failed)
     :success-rate (if (pos? (count with-merchant))
                     (format "%.1f%%" (* 100.0 (/ (count extracted) (count with-merchant))))
                     "0.0%")
     :avg-noise-removed avg-noise
     :total-noise-removed total-noise
     :avg-merchant-length (if (seq extracted)
                            (/ (reduce + (map #(count (:clean-merchant %)) extracted))
                               (count extracted))
                            0)
     :shortest-merchant (if (seq extracted)
                          (apply min (map #(count (:clean-merchant %)) extracted))
                          0)
     :longest-merchant (if (seq extracted)
                         (apply max (map #(count (:clean-merchant %)) extracted))
                         0)
     :examples {:short (take 3 (filter #(< (count (:clean-merchant %)) 15) extracted))
                :medium (take 3 (filter #(and (>= (count (:clean-merchant %)) 15)
                                              (< (count (:clean-merchant %)) 30)) extracted))
                :long (take 3 (filter #(>= (count (:clean-merchant %)) 30) extracted))}}))

;; ============================================================================
;; Main Execution
;; ============================================================================

(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "Badge ME-6: Stage 3 Validation (NER Extraction)")
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
(println)

;; Analysis
(def analysis (analyze-extraction clean-txs))

;; Print report
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "STAGE 3 RESULTS")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println (format "Total transactions: %d" (:total-transactions analysis)))
(println (format "Merchant transactions: %d" (:merchant-transactions analysis)))
(println (format "Successfully extracted: %d" (:successfully-extracted analysis)))
(println (format "Extraction failed: %d" (:extraction-failed analysis)))
(println (format "Success rate: %s" (:success-rate analysis)))
(println)

(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "NOISE REMOVAL STATISTICS")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println (format "Total noise patterns removed: %d" (:total-noise-removed analysis)))
(println (format "Avg noise per transaction: %.1f" (double (:avg-noise-removed analysis))))
(println)

(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "MERCHANT NAME STATISTICS")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println (format "Avg merchant length: %.1f chars" (double (:avg-merchant-length analysis))))
(println (format "Shortest merchant: %d chars" (:shortest-merchant analysis)))
(println (format "Longest merchant: %d chars" (:longest-merchant analysis)))
(println)

(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "EXTRACTED MERCHANTS - EXAMPLES")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

(when (seq (get-in analysis [:examples :short]))
  (println "\nShort names (<15 chars):")
  (doseq [tx (get-in analysis [:examples :short])]
    (println (format "  • %s (from: %s)"
                     (:clean-merchant tx)
                     (subs (:description tx) 0 (min 60 (count (:description tx))))))))

(when (seq (get-in analysis [:examples :medium]))
  (println "\nMedium names (15-30 chars):")
  (doseq [tx (get-in analysis [:examples :medium])]
    (println (format "  • %s (from: %s)"
                     (:clean-merchant tx)
                     (subs (:description tx) 0 (min 60 (count (:description tx))))))))

(when (seq (get-in analysis [:examples :long]))
  (println "\nLong names (>30 chars):")
  (doseq [tx (get-in analysis [:examples :long])]
    (println (format "  • %s (from: %s)"
                     (:clean-merchant tx)
                     (subs (:description tx) 0 (min 60 (count (:description tx))))))))

(println)

;; Show some full before/after examples
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "BEFORE/AFTER EXAMPLES")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(let [examples (take 5 (filter :clean-merchant clean-txs))]
  (doseq [tx examples]
    (println)
    (println (format "Date: %s" (:date tx)))
    (println (format "BEFORE: %s" (subs (:description tx) 0 (min 80 (count (:description tx))))))
    (println (format "AFTER:  %s" (:clean-merchant tx)))
    (println (format "Removed: %d noise patterns" (count (:removed-noise tx))))))

(println)
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(if (>= (:successfully-extracted analysis) (* 0.90 (:merchant-transactions analysis)))
  (println (format "✅ STAGE 3 SUCCESS - %.1f%% extraction rate"
                   (* 100.0 (/ (:successfully-extracted analysis)
                              (:merchant-transactions analysis)))))
  (println (format "⚠️ STAGE 3 NEEDS REVIEW - Only %.1f%% extraction rate"
                   (* 100.0 (/ (:successfully-extracted analysis)
                              (:merchant-transactions analysis))))))
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
