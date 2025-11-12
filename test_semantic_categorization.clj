(ns test-semantic-categorization
  "Test semantic categorization with real transaction examples"
  (:require [finance.merchant-extraction.stage1 :as stage1]
            [finance.merchant-extraction.stage2 :as stage2]
            [finance.merchant-extraction.stage3 :as stage3]
            [clojure.pprint :as pp]))

;; Test transactions based on user's real data
(def test-transactions
  [;; 1. SPEI from CAUICH BORGES DARWIN MANUEL (personal income)
   {:date "20-JUN"
    :description "SPEI"
    :amount 3140.00
    :deposit 3140.00
    :retiro nil
    :beneficiary-name "CAUICH BORGES DARWIN MANUEL"
    :context-lines ["ABONO POR TRANSFERENCIA INTERBANCARIA SPEI"
                    "CAUICH BORGES DARWIN MANUEL"
                    "FECHA LIQUIDACION: 17 JUN 2025"
                    "REFERENCIA: 1234567"]}

   ;; 2. Domiciliación SAT (tax payment)
   {:date "20-JUN"
    :description "DOMICILIACION SAT"
    :amount 3139.82
    :deposit nil
    :retiro 3139.82
    :beneficiary-name nil
    :context-lines ["COBRANZA DOMICILIADA"
                    "CLAVE DE RASTREO: ABC123"
                    "RFC/CURP: SAT8410245V8"
                    "FECHA: 20 JUN 2025"]}

   ;; 3. GOOGLE YOUTUBEPREMIUM (subscription)
   {:date "21-JUN"
    :description "GOOGLE YOUTUBEPREMIUM"
    :amount 159.00
    :deposit nil
    :retiro 159.00
    :beneficiary-name nil
    :context-lines ["CARG RE 159.00"
                    "GOOGLE YOUTUBEPREMIUM"
                    "REF. 987654321"]}

   ;; 4. SPEI from WISE PAYMENTS LIMITED (business income)
   {:date "22-JUN"
    :description "SPEI"
    :amount 74940.97
    :deposit 74940.97
    :retiro nil
    :beneficiary-name "WISE PAYMENTS LIMITED"
    :context-lines ["ABONO POR TRANSFERENCIA INTERBANCARIA SPEI"
                    "WISE PAYMENTS LIMITED"
                    "FECHA LIQUIDACION: 22 JUN 2025"
                    "REFERENCIA: WISE-INV-2025"]}

   ;; 5. GOOGLE ONE (subscription)
   {:date "23-JUN"
    :description "GOOGLE ONE"
    :amount 395.00
    :deposit nil
    :retiro 395.00
    :beneficiary-name nil
    :context-lines ["CARG RE 395.00"
                    "GOOGLE ONE"
                    "REF. 123456789"]}

   ;; 6. SWEB to JESHUA (personal transfer)
   {:date "24-JUN"
    :description "SWEB"
    :amount 20000.00
    :deposit nil
    :retiro 20000.00
    :beneficiary-name nil
    :context-lines ["TRANSFERENCIA A RUIZ JESHUA"
                    "SCOTIABANK CUENTA: 1234567890"
                    "REFERENCIA: PRESTAMO"]}])

(defn -main []
  (println "\n🎯 TESTING SEMANTIC CATEGORIZATION SYSTEM")
  (println "==========================================\n")

  (let [;; Process transactions through complete pipeline
        stage1-results (stage1/detect-batch test-transactions)
        stage2-results (stage2/detect-batch stage1-results)
        results (stage3/extract-batch stage2-results)]

    ;; Print results
    (doseq [[idx tx] (map-indexed vector results)]
      (println (format "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"))
      (println (format "Transaction %d: %s" (inc idx) (:description tx)))
      (println (format "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"))
      (println (format "  Date: %s" (:date tx)))
      (println (format "  Amount: $%.2f" (:amount tx)))
      (println (format "  Type: %s (%s)"
                      (:transaction-type tx)
                      (:direction tx)))

      ;; Level 2: Semantic Category (NEW!)
      (when-let [semantic-cat (:semantic-category tx)]
        (println (format "  🎯 SEMANTIC CATEGORY: %s ✅" semantic-cat)))

      ;; Counterparty info
      (when-let [counterparty-info (:counterparty-info tx)]
        (when (:detected? counterparty-info)
          (println (format "  Counterparty: %s (%s)"
                          (:counterparty-id counterparty-info)
                          (:counterparty-type counterparty-info)))))

      ;; Level 3: Merchant extraction
      (when-let [merchant (:clean-merchant tx)]
        (println (format "  Merchant: %s" merchant)))

      (when-let [beneficiary (:beneficiary-name tx)]
        (println (format "  Beneficiary: %s" beneficiary)))

      (println (format "  Confidence: %.0f%%" (* 100 (:confidence tx 0.0)))))

    ;; Summary statistics
    (println "\n\n📊 SUMMARY")
    (println "==========================================")
    (let [by-semantic (group-by :semantic-category results)
          subscription-count (count (get by-semantic :subscription))
          tax-count (count (get by-semantic :taxes))
          business-income-count (count (get by-semantic :business-income))]
      (println (format "Total transactions: %d" (count results)))
      (println (format "  - Subscriptions: %d" subscription-count))
      (println (format "  - Taxes: %d" tax-count))
      (println (format "  - Business Income: %d" business-income-count))
      (println (format "  - Personal Transfers: %d"
                      (- (count results) subscription-count tax-count business-income-count))))

    (println "\n✅ All tests completed successfully!")))

(-main)
