#!/usr/bin/env bb
(require '[clojure.pprint :refer [pprint]])

;; Load Stage 5
(load-file "src/finance/merchant_extraction/stage5.clj")

(println "\n🔍 FASE 3 Manual Test: Stage 5 Multi-Dimensional Category Resolution")
(println "========================================================================\n")

;; Test 1: MCC Registry loaded
(print "1. MCC Registry loaded: ")
(let [registry @finance.merchant-extraction.stage5/mcc-registry]
  (if (seq registry)
    (println (format "✅ %d MCC codes loaded" (count registry)))
    (println "❌ Failed to load")))

;; Test 2: Flow type mapping
(print "2. Flow type → Accounting mapping: ")
(let [result (finance.merchant-extraction.stage5/flow-type->account-category "GASTO")]
  (if (= "Expenses" (:account-category result))
    (do
      (println "✅")
      (println "   Result:" result))
    (println "❌")))

;; Test 3: Merchant category resolution (with MCC)
(print "3. Merchant category resolution (with MCC 5734): ")
(let [result (finance.merchant-extraction.stage5/resolve-merchant-categories {:mcc 5734})]
  (if (and (:merchant-category result) (:budget-category result))
    (do
      (println "✅")
      (println "   Merchant Category:" (:merchant-category result))
      (println "   Budget Category:" (:budget-category result))
      (println "   Confidence:" (:mcc-confidence result)))
    (println "❌")))

;; Test 4: Tax category resolution (business expense)
(print "4. Tax category resolution (business expense): ")
(let [result (finance.merchant-extraction.stage5/resolve-tax-category
               {:tax-hints {:business-deductible true
                           :sat-category "Gastos de Software"}}
               {:transaction-type "GASTO"})]
  (if (:tax-category result)
    (do
      (println "✅")
      (println "   Tax Category:" (:tax-category result))
      (println "   Business Deductible:" (:business-deductible result)))
    (println "❌")))

;; Test 5: Payment method resolution
(print "5. Payment method resolution (Stripe): ")
(let [result (finance.merchant-extraction.stage5/resolve-payment-method {:bank "Stripe"})]
  (if (= "Online Payment" (:payment-method result))
    (do
      (println "✅")
      (println "   Payment Method:" (:payment-method result))
      (println "   Payment Network:" (:payment-network result)))
    (println "❌")))

;; Test 6: Full 6-dimensional resolution
(print "6. Full 6-dimensional resolution: ")
(let [sample-tx {:transaction-type "GASTO"
                 :amount -45.99
                 :bank "Stripe"
                 :resolved-merchant {:mcc 5734
                                    :tax-hints {:business-deductible true
                                               :sat-category "Gastos de Software"}}}
      result (finance.merchant-extraction.stage5/resolve-categories sample-tx)]
  (if (and (:flow-type result)
           (:merchant-category result)
           (:budget-category result)
           (:account-category result)
           (:tax-category result)
           (:payment-method result))
    (do
      (println "✅ All 6 dimensions resolved")
      (println "\n   📊 Sample Transaction After Stage 5:")
      (println "   =====================================")
      (println "   Dimension 1 - Flow Type:" (:flow-type result))
      (println "   Dimension 2 - Merchant Category:" (:merchant-category result))
      (println "   Dimension 3 - Budget Category:" (:budget-category result))
      (println "   Dimension 4 - Account Category:" (:account-category result))
      (println "   Dimension 5 - Tax Category:" (:tax-category result))
      (println "   Dimension 6 - Payment Method:" (:payment-method result))
      (println "   Overall Confidence:" (:category-resolution-confidence result)))
    (println "❌ Missing dimensions")))

;; Test 7: Statistics generation
(print "\n7. Statistics generation: ")
(let [sample-txs [{:stage5-status "complete"
                   :budget-category "Technology"
                   :tax-category "Business Deductible"
                   :payment-method "Online Payment"
                   :account-category "Expenses"
                   :category-resolution-confidence 0.9}
                  {:stage5-status "complete"
                   :budget-category "Living"
                   :tax-category "Non-Deductible"
                   :payment-method "Credit Card"
                   :account-category "Expenses"
                   :category-resolution-confidence 0.85}]
      stats (finance.merchant-extraction.stage5/category-statistics sample-txs)]
  (if (and (:total-transactions stats)
           (:by-budget-category stats))
    (do
      (println "✅")
      (println "\n   📈 Category Statistics:")
      (println "   ======================")
      (println "   Total:" (:total-transactions stats))
      (println "   Resolved:" (:resolved-count stats))
      (println "   Resolution Rate:" (* 100 (:resolution-rate stats)) "%")
      (println "   Avg Confidence:" (:avg-confidence stats))
      (println "   By Budget Category:" (:by-budget-category stats))
      (println "   By Tax Category:" (:by-tax-category stats))
      (println "   By Payment Method:" (:by-payment-method stats)))
    (println "❌")))

(println "\n✅ FASE 3 Manual Test Complete\n")
