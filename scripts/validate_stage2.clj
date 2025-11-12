(require '[finance.merchant-extraction.stage1 :as stage1])
(require '[finance.merchant-extraction.stage2 :as stage2])
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

(defn analyze-counterparties
  "Groups transactions by counterparty detected"
  [counterparty-txs]
  (let [with-merchant (filter :merchant? counterparty-txs)
        detected (filter #(get-in % [:counterparty-info :detected?]) with-merchant)
        direct (remove #(get-in % [:counterparty-info :detected?]) with-merchant)
        by-counterparty (group-by #(get-in % [:counterparty-info :counterparty-id]) detected)]
    {:total-transactions (count counterparty-txs)
     :merchant-transactions (count with-merchant)
     :counterparty-detected (count detected)
     :direct-merchant (count direct)
     :detection-rate (if (pos? (count with-merchant))
                       (format "%.1f%%" (* 100.0 (/ (count detected) (count with-merchant))))
                       "0.0%")
     :by-counterparty (into (sorted-map)
                            (map (fn [[k v]]
                                   [k {:count (count v)
                                       :avg-confidence (/ (reduce + (map #(get-in % [:counterparty-info :confidence]) v))
                                                         (count v))
                                       :examples (take 2 (map #(select-keys % [:date :description]) v))}])
                                 (dissoc by-counterparty nil)))
     :direct-examples (take 5 (map #(select-keys % [:date :description]) direct))}))

;; ============================================================================
;; Main Execution
;; ============================================================================

(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "Badge ME-5: Stage 2 Validation")
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
(println (format "✓ Stage 1 processed: %d transactions" (count typed-txs)))
(def stage1-stats (stage1/type-statistics typed-txs))
(println (format "  - Merchant needed: %d" (:merchant-extraction-needed stage1-stats)))
(println (format "  - No merchant: %d" (:no-merchant-expected stage1-stats)))
(println)

;; Process with Stage 2
(println "Running Stage 2 (Counterparty Detection)...")
(def counterparty-txs (stage2/detect-batch typed-txs))
(println (format "✓ Stage 2 processed: %d transactions" (count counterparty-txs)))
(println)

;; Analysis
(def analysis (analyze-counterparties counterparty-txs))

;; Print report
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "STAGE 2 RESULTS")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println (format "Total transactions: %d" (:total-transactions analysis)))
(println (format "Merchant transactions: %d" (:merchant-transactions analysis)))
(println (format "Counterparty detected: %d" (:counterparty-detected analysis)))
(println (format "Direct merchant: %d" (:direct-merchant analysis)))
(println (format "Detection rate: %s" (:detection-rate analysis)))
(println)

(when (seq (:by-counterparty analysis))
  (println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
  (println "COUNTERPARTIES DETECTED")
  (println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
  (doseq [[counterparty stats] (:by-counterparty analysis)]
    (println (format "\n%s:" (name counterparty)))
    (println (format "  Count: %d" (:count stats)))
    (println (format "  Avg confidence: %.2f" (:avg-confidence stats)))
    (when (seq (:examples stats))
      (println "  Examples:")
      (doseq [ex (:examples stats)]
        (println (format "    - %s: %s" (:date ex) (subs (:description ex) 0 (min 70 (count (:description ex)))))))))
  (println))

(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "DIRECT MERCHANTS (No Counterparty)")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println (format "Count: %d" (:direct-merchant analysis)))
(when (seq (:direct-examples analysis))
  (println "\nExamples:")
  (doseq [ex (:direct-examples analysis)]
    (println (format "  - %s: %s" (:date ex) (subs (:description ex) 0 (min 70 (count (:description ex))))))))
(println)

(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(if (pos? (:counterparty-detected analysis))
  (println (format "✅ STAGE 2 WORKING - %d counterparties detected" (:counterparty-detected analysis)))
  (println "⚠️ NO COUNTERPARTIES DETECTED - May need more patterns"))
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
