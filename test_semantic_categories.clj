(ns test-semantic-categories
  "Test that semantic categories are being propagated from Stage 2"
  (:require [finance.merchant-extraction.stage1 :as stage1]
            [finance.merchant-extraction.stage2 :as stage2]
            [clojure.pprint :as pp]))

(def test-txs
  [{:date "01-JUN"
    :description "GOOGLE YOUTUBEPREMIUM"
    :amount 159.00
    :retiro 159.00
    :context-lines ["CARG RE 159.00" "GOOGLE YOUTUBEPREMIUM" "REF. 987654321"]}

   {:date "02-JUN"
    :description "DOMICILIACION SAT"
    :amount 3139.82
    :retiro 3139.82
    :context-lines ["COBRANZA DOMICILIADA" "RFC/CURP: SAT8410245V8" "FECHA: 02 JUN"]}])

(println "\n🎯 TESTING SEMANTIC CATEGORY PROPAGATION")
(println "=========================================\n")

(doseq [[idx tx] (map-indexed vector test-txs)]
  (let [stage1-result (stage1/detect tx)
        stage2-result (stage2/detect stage1-result)]

    (println (format "\nTest %d: %s" (inc idx) (:description tx)))
    (println "─────────────────────────────────────")
    (println "Stage 1 Result:")
    (println (format "  :type = %s" (:type stage1-result)))
    (println (format "  :merchant? = %s" (:merchant? stage1-result)))

    (println "\nStage 2 Result:")
    (println (format "  :counterparty-info = %s"
                     (if-let [ci (:counterparty-info stage2-result)]
                       (format "{:detected? %s, :counterparty-id %s}"
                               (:detected? ci)
                               (:counterparty-id ci))
                       "nil")))
    (println (format "  :semantic-category = %s" (:semantic-category stage2-result)))

    (when-not (:semantic-category stage2-result)
      (println "\n⚠️  WARNING: semantic-category is NIL!")
      (println "Full counterparty-info:")
      (pp/pprint (:counterparty-info stage2-result)))))

(println "\n✅ Done!")
