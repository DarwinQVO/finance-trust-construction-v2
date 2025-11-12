(ns test-stage1-context-search
  "Test that Stage 1 now searches in context-lines (fixing pattern matching)"
  (:require [finance.merchant-extraction.stage1 :as stage1]
            [finance.merchant-extraction.stage2 :as stage2]
            [clojure.pprint :as pp]))

;; Test transactions with context-lines
(def test-txs
  [{:date "01-JUN"
    :description "GOOGLE YOUTUBEPREMIUM"  ;; Description alone won't match
    :amount 159.00
    :retiro 159.00
    :context-lines ["CARG RE 159.00"  ;; ← This contains "CARG RE" for card-purchase
                    "GOOGLE YOUTUBEPREMIUM"
                    "REF. 987654321"]}

   {:date "02-JUN"
    :description "DOMICILIACION SAT"  ;; Description matches
    :amount 3139.82
    :retiro 3139.82
    :context-lines ["COBRANZA DOMICILIADA"  ;; ← Confirms domiciliacion
                    "RFC/CURP: SAT8410245V8"
                    "FECHA: 02 JUN"]}

   {:date "03-JUN"
    :description "SPEI"  ;; Description matches
    :amount 74940.97
    :deposit 74940.97
    :beneficiary-name "WISE PAYMENTS LIMITED"
    :context-lines ["ABONO POR TRANSFERENCIA INTERBANCARIA SPEI"
                    "WISE PAYMENTS LIMITED"
                    "FECHA LIQUIDACION: 03 JUN"]}])

(println "\n🧪 TESTING STAGE 1 CONTEXT-LINES SEARCH FIX")
(println "==========================================\n")

(doseq [[idx tx] (map-indexed vector test-txs)]
  (println (format "\nTest %d: %s" (inc idx) (:description tx)))
  (println "Context:" (pr-str (:context-lines tx)))

  ;; Stage 1 detection
  (let [stage1-result (stage1/detect tx)]
    (println (format "  ✓ Stage 1 Type: %s (confidence: %.0f%%)"
                     (:type stage1-result)
                     (* 100 (:confidence stage1-result 0.0))))

    ;; Stage 2 detection
    (let [stage2-result (stage2/detect stage1-result)]
      (when-let [semantic-cat (:semantic-category stage2-result)]
        (println (format "  ✓ Stage 2 Semantic Category: %s" semantic-cat)))

      (when-let [counterparty-info (:counterparty-info stage2-result)]
        (when (:detected? counterparty-info)
          (println (format "  ✓ Counterparty: %s" (:counterparty-id counterparty-info))))))))

(println "\n✅ If you see types detected above (not :unknown), the fix is working!")
