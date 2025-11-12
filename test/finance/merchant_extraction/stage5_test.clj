(ns finance.merchant-extraction.stage5-test
  (:require [clojure.test :refer :all]
            [finance.merchant-extraction.stage5 :as stage5]))

(deftest test-flow-type-to-accounting
  (testing "Flow type maps to correct accounting categories"
    (let [expense (stage5/flow-type->account-category "GASTO")]
      (is (= "Expenses" (:account-category expense)))
      (is (= "Debit" (:debit-credit expense))))

    (let [income (stage5/flow-type->account-category "INGRESO")]
      (is (= "Revenue" (:account-category income)))
      (is (= "Credit" (:debit-credit income))))

    (let [payment (stage5/flow-type->account-category "PAGO_TARJETA")]
      (is (= "Liabilities" (:account-category payment))))))

(deftest test-merchant-category-resolution
  (testing "Merchant categories resolved from MCC"
    ;; With MCC
    (let [result (stage5/resolve-merchant-categories
                   {:mcc 5734
                    :canonical-name "GOOGLE"})]
      (is (= "Computer Software Stores" (:merchant-category result)))
      (is (= "Technology" (:budget-category result)))
      (is (= 1.0 (:mcc-confidence result))))

    ;; Without MCC
    (let [result (stage5/resolve-merchant-categories
                   {:category "utilities"
                    :budget-category "Living"})]
      (is (= "utilities" (:merchant-category result)))
      (is (= "Living" (:budget-category result)))
      (is (< (:mcc-confidence result) 0.5)))))

(deftest test-tax-category-resolution
  (testing "Tax categories resolved correctly"
    ;; Business deductible expense
    (let [result (stage5/resolve-tax-category
                   {:tax-hints {:business-deductible true
                                :sat-category "Gastos de Software"}}
                   {:transaction-type "GASTO"})]
      (is (= "Business Deductible" (:tax-category result)))
      (is (true? (:business-deductible result)))
      (is (= "Gastos de Software" (:sat-category result))))

    ;; Healthcare (personal deductible)
    (let [result (stage5/resolve-tax-category
                   {:budget-category "Healthcare"
                    :mcc 8011}
                   {:transaction-type "GASTO"})]
      (is (= "Medical Deductible" (:tax-category result)))
      (is (true? (:personal-deductible result))))

    ;; Income (always taxable)
    (let [result (stage5/resolve-tax-category
                   {}
                   {:transaction-type "INGRESO"})]
      (is (= "Taxable Income" (:tax-category result)))
      (is (true? (:business-taxable result))))))

(deftest test-payment-method-resolution
  (testing "Payment methods resolved from context"
    ;; Credit card
    (let [result (stage5/resolve-payment-method
                   {:bank "Apple Card"})]
      (is (= "Credit Card" (:payment-method result)))
      (is (= "Apple Card" (:payment-network result))))

    ;; Bank transfer
    (let [result (stage5/resolve-payment-method
                   {:transaction-type "TRASPASO"
                    :bank "BofA"})]
      (is (= "Bank Transfer" (:payment-method result))))

    ;; Online payment
    (let [result (stage5/resolve-payment-method
                   {:bank "Stripe"})]
      (is (= "Online Payment" (:payment-method result)))
      (is (= "Stripe" (:payment-network result))))))

(deftest test-full-category-resolution
  (testing "Complete category resolution with all dimensions"
    (let [transaction {:transaction-type "GASTO"
                       :amount -45.99
                       :account-name "BofA Checking"
                       :bank "Bank of America"
                       :resolved-merchant {:merchant-id "google"
                                          :canonical-name "GOOGLE"
                                          :mcc 5734
                                          :budget-category "Technology"
                                          :tax-hints {:business-deductible true
                                                     :sat-category "Gastos de Software"}}}
          result (stage5/resolve-categories transaction)]

      ;; Dimension 1: Flow Type
      (is (= "GASTO" (:flow-type result)))
      (is (= "Expenses" (:account-category result)))

      ;; Dimension 2: Merchant Category
      (is (= "Computer Software Stores" (:merchant-category result)))
      (is (= 5734 (:mcc-code result)))

      ;; Dimension 3: Budget Category
      (is (= "Technology" (:budget-category result)))

      ;; Dimension 5: Tax Category
      (is (= "Business Deductible" (:tax-category result)))
      (is (true? (:business-deductible result)))

      ;; Dimension 6: Payment Method
      (is (= "Debit/Checking" (:payment-method result)))

      ;; Overall
      (is (= "complete" (:stage5-status result)))
      (is (> (:category-resolution-confidence result) 0.5)))))

(deftest test-batch-processing
  (testing "Batch processing maintains all enrichments"
    (let [transactions [{:transaction-type "GASTO"
                         :resolved-merchant {:mcc 5734}}
                        {:transaction-type "INGRESO"
                         :resolved-merchant {:mcc 7372}}]
          results (stage5/resolve-batch transactions)]
      (is (= 2 (count results)))
      (is (every? :stage5-status results))
      (is (every? :budget-category results)))))

(deftest test-category-statistics
  (testing "Category statistics generation"
    (let [transactions [{:stage5-status "complete"
                         :budget-category "Technology"
                         :tax-category "Business Deductible"
                         :payment-method "Credit Card"
                         :account-category "Expenses"
                         :category-resolution-confidence 0.95}
                        {:stage5-status "complete"
                         :budget-category "Living"
                         :tax-category "Non-Deductible"
                         :payment-method "Debit/Checking"
                         :account-category "Expenses"
                         :category-resolution-confidence 0.85}]
          stats (stage5/category-statistics transactions)]

      (is (= 2 (:total-transactions stats)))
      (is (= 2 (:resolved-count stats)))
      (is (= 1.0 (:resolution-rate stats)))
      (is (map? (:by-budget-category stats)))
      (is (> (:avg-confidence stats) 0.8)))))

;; ============================================================================
;; Self-Audit Function
;; ============================================================================

(defn run-self-audit []
  (println "\n🔍 FASE 3 Self-Audit: Stage 5 Multi-Dimensional Category Resolution")
  (println "========================================================================\n")

  ;; Test 1: MCC Registry loaded
  (print "1. MCC Registry loaded: ")
  (if (seq @stage5/mcc-registry)
    (println (format "✅ %d MCC codes loaded" (count @stage5/mcc-registry)))
    (println "❌ Failed to load"))

  ;; Test 2: Flow type mapping
  (print "2. Flow type → Accounting mapping: ")
  (let [test-result (stage5/flow-type->account-category "GASTO")]
    (if (= "Expenses" (:account-category test-result))
      (println "✅")
      (println "❌")))

  ;; Test 3: Merchant category resolution
  (print "3. Merchant category resolution: ")
  (let [result (stage5/resolve-merchant-categories {:mcc 5734})]
    (if (and (:merchant-category result) (:budget-category result))
      (println "✅")
      (println "❌")))

  ;; Test 4: Tax category resolution
  (print "4. Tax category resolution: ")
  (let [result (stage5/resolve-tax-category {} {:transaction-type "GASTO"})]
    (if (:tax-category result)
      (println "✅")
      (println "❌")))

  ;; Test 5: Payment method resolution
  (print "5. Payment method resolution: ")
  (let [result (stage5/resolve-payment-method {:bank "Stripe"})]
    (if (= "Online Payment" (:payment-method result))
      (println "✅")
      (println "❌")))

  ;; Test 6: Full resolution
  (print "6. Full 6-dimensional resolution: ")
  (let [result (stage5/resolve-categories
                 {:transaction-type "GASTO"
                  :bank "Stripe"
                  :resolved-merchant {:mcc 5734
                                     :tax-hints {:business-deductible true}}})]
    (if (and (:flow-type result)
             (:merchant-category result)
             (:budget-category result)
             (:account-category result)
             (:tax-category result)
             (:payment-method result))
      (println "✅ All 6 dimensions resolved")
      (println "❌ Missing dimensions")))

  ;; Test 7: Statistics generation
  (print "7. Statistics generation: ")
  (let [stats (stage5/category-statistics
                [{:stage5-status "complete"
                  :budget-category "Technology"
                  :category-resolution-confidence 0.9}])]
    (if (and (:total-transactions stats)
             (:by-budget-category stats))
      (println "✅")
      (println "❌")))

  (println "\n✅ FASE 3 Self-Audit Complete\n"))
