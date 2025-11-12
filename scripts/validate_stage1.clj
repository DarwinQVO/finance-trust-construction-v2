(require '[finance.merchant-extraction.stage1 :as stage1])
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

(defn analyze-by-type
  "Groups transactions by detected type"
  [typed-txs]
  (let [by-type (group-by :type typed-txs)]
    (into (sorted-map)
          (map (fn [[type txs]]
                 [type {:count (count txs)
                        :merchant-needed (count (filter :merchant? txs))
                        :avg-confidence (/ (reduce + (map :confidence txs)) (count txs))
                        :examples (take 2 (map #(select-keys % [:date :description :type :merchant? :confidence]) txs))}])
               by-type))))

(defn analyze-pipeline
  "Analyzes which transactions will continue to Stage 2"
  [typed-txs]
  (let [needs-merchant (filter :merchant? typed-txs)
        no-merchant (remove :merchant? typed-txs)]
    {:total-transactions (count typed-txs)
     :continue-to-stage-2 (count needs-merchant)
     :pipeline-terminates (count no-merchant)
     :termination-rate (format "%.1f%%" (* 100.0 (/ (count no-merchant) (count typed-txs))))
     :by-direction (frequencies (map :direction typed-txs))}))

(defn analyze-confidence
  "Analyzes confidence score distribution"
  [typed-txs]
  (let [confidences (map :confidence typed-txs)
        high (count (filter #(>= % 0.90) confidences))
        medium (count (filter #(and (>= % 0.70) (< % 0.90)) confidences))
        low (count (filter #(< % 0.70) confidences))]
    {:total (count confidences)
     :high-confidence high
     :medium-confidence medium
     :low-confidence low
     :avg-confidence (/ (reduce + confidences) (count confidences))
     :min-confidence (apply min confidences)
     :max-confidence (apply max confidences)}))

;; ============================================================================
;; Main Execution
;; ============================================================================

(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "Badge ME-4: Stage 1 Validation")
(println "Testing with 71 Scotiabank transactions")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println)

;; Load data
(println "Loading test data...")
(def raw-txs (load-test-data))
(println (format "✓ Loaded %d transactions" (count raw-txs)))
(println)

;; Process with Stage 1
(println "Running Stage 1 detection...")
(def typed-txs (stage1/detect-batch raw-txs))
(println (format "✓ Processed %d transactions" (count typed-txs)))
(println)

;; Analysis
(def by-type-analysis (analyze-by-type typed-txs))
(def pipeline-analysis (analyze-pipeline typed-txs))
(def confidence-analysis (analyze-confidence typed-txs))
(def unknown-txs (filter #(= :unknown (:type %)) typed-txs))

;; Print report
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "SUMMARY")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println (format "Total transactions: %d" (count typed-txs)))
(println (format "Types detected: %d" (count (keys by-type-analysis))))
(println (format "Avg confidence: %.2f" (:avg-confidence confidence-analysis)))
(println (format "Unknown count: %d" (count unknown-txs)))
(println)

(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "BY TYPE")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(doseq [[type stats] by-type-analysis]
  (println (format "\n%s:" (name type)))
  (println (format "  Count: %d" (:count stats)))
  (println (format "  Merchant needed: %d" (:merchant-needed stats)))
  (println (format "  Avg confidence: %.2f" (:avg-confidence stats))))
(println)

(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "PIPELINE ANALYSIS")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(pp/pprint pipeline-analysis)
(println)

(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "CONFIDENCE DISTRIBUTION")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(pp/pprint confidence-analysis)
(println)

(when (seq unknown-txs)
  (println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
  (println "⚠️ UNKNOWN TRANSACTIONS")
  (println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
  (doseq [tx unknown-txs]
    (println (format "\nDate: %s" (:date tx)))
    (println (format "Description: %s" (subs (:description tx) 0 (min 80 (count (:description tx))))))))

(println)
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(if (empty? unknown-txs)
  (println "✅ ALL TRANSACTIONS CLASSIFIED")
  (println (format "⚠️ %d UNKNOWN TRANSACTIONS NEED REVIEW" (count unknown-txs))))
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
