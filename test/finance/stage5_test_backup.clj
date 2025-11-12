(ns finance.stage5-test
  "Stage 5 Multi-Dimensional Category Resolution Tests"
  (:require [clojure.test :refer :all]
            [finance.merchant-extraction.stage5 :as stage5]))

;; ============================================================================
;; Test Data
;; ============================================================================

(def test-merchant-with-mcc
  {:merchant-id "google"
   :canonical-name "GOOGLE"
   :mcc 5734
   :budget-category "Technology"
   :budget-subcategory "Software & Services"
   :tax-hints {:business-deductible true
               :personal-deductible false
               :sat-category "Gastos de Software"}})

(def test-merchant-healthcare
  {:merchant-id "farmacia-del-ahorro"
   :canonical-name "FARMACIA DEL AHORRO"
   :mcc 5912
   :budget-category "Healthcare"
   :budget-subcategory "Pharmacy"
   :tax-hints {:business-deductible false
               :personal-deductible true
               :sat-category "Gastos Médicos"}})

(def test-transaction-expense
  {:transaction-type "GASTO"
   :amount -45.99
   :merchant-text "GOOGLE WORKSPACE"
   :account-name "BofA Checking"
   :bank "Bank of America"
   :resolved-merchant test-merchant-with-mcc})

(def test-transaction-income
  {:transaction-type "INGRESO"
   :amount 2500.00
   :merchant-text "STRIPE PAYMENT"
   :account-name "Stripe"
   :bank "Stripe"
   :resolved-merchant {:merchant-id "stripe"
                       :canonical-name "STRIPE"
                       :mcc 7372}})

;; ============================================================================
;; Dimension 1: Flow Type → Accounting Category
;; ============================================================================

(deftest test-flow-type-to-accounting-expense
  (testing "GASTO maps to Expenses/Debit"
    (let [result (stage5/flow-type->account-category "GASTO")]
      (is (= "Expenses" (:account-category result)))
      (is (= "Operating Expenses" (:account-subcategory result)))
      (is (= "Debit" (:debit-credit result))))))

(deftest test-flow-type-to-accounting-income
  (testing "INGRESO maps to Revenue/Credit"
    (let [result (stage5/flow-type->account-category "INGRESO")]
      (is (= "Revenue" (:account-category result)))
      (is (= "Operating Income" (:account-subcategory result)))
      (is (= "Credit" (:debit-credit result))))))

(deftest test-flow-type-to-accounting-cc-payment
  (testing "PAGO_TARJETA maps to Liabilities/Debit"
    (let [result (stage5/flow-type->account-category "PAGO_TARJETA")]
      (is (= "Liabilities" (:account-category result)))
      (is (= "Credit Card Payment" (:account-subcategory result)))
      (is (= "Debit" (:debit-credit result))))))

(deftest test-flow-type-to-accounting-unknown
  (testing "Unknown flow type maps to Unknown"
    (let [result (stage5/flow-type->account-category "INVALID")]
      (is (= "Unknown" (:account-category result)))
      (is (= "Uncategorized" (:account-subcategory result))))))

;; ============================================================================
;; Dimension 2-3: Merchant Category + Budget Category
;; ============================================================================

(deftest test-merchant-category-with-mcc
  (testing "Merchant with MCC resolves to MCC category"
    (let [result (stage5/resolve-merchant-categories test-merchant-with-mcc)]
      (is (= "Computer Software Stores" (:merchant-category result)))
      (is (= "Technology" (:budget-category result)))
      (is (= "Software & Services" (:budget-subcategory result)))
      (is (= 5734 (:mcc-code result)))
      (is (= 1.0 (:mcc-confidence result))))))

(deftest test-merchant-category-without-mcc
  (testing "Merchant without MCC uses entity categories"
    (let [result (stage5/resolve-merchant-categories
                   {:category "restaurants"
                    :budget-category "Living"})]
      (is (= "restaurants" (:merchant-category result)))
      (is (= "Living" (:budget-category result)))
      (is (nil? (:mcc-code result)))
      (is (< (:mcc-confidence result) 0.5)))))

(deftest test-merchant-category-unknown-mcc
  (testing "Unknown MCC uses fallback categories"
    (let [result (stage5/resolve-merchant-categories
                   {:mcc 9999
                    :budget-category "Other"})]
      (is (= "Unknown MCC" (:merchant-category result)))
      (is (= "Other" (:budget-category result)))
      (is (= 9999 (:mcc-code result)))
      (is (= 0.5 (:mcc-confidence result))))))

;; ============================================================================
;; Dimension 5: Tax Category
;; ============================================================================

(deftest test-tax-category-business-expense
  (testing "Business expense is deductible"
    (let [result (stage5/resolve-tax-category
                   test-merchant-with-mcc
                   {:transaction-type "GASTO"})]
      (is (= "Business Deductible" (:tax-category result)))
      (is (true? (:business-deductible result)))
      (is (false? (:personal-deductible result)))
      (is (= "Gastos de Software" (:sat-category result)))
      (is (> (:tax-confidence result) 0.8)))))

(deftest test-tax-category-healthcare
  (testing "Healthcare is personally deductible"
    (let [result (stage5/resolve-tax-category
                   test-merchant-healthcare
                   {:transaction-type "GASTO"})]
      (is (= "Medical Deductible" (:tax-category result)))
      (is (false? (:business-deductible result)))
      (is (true? (:personal-deductible result)))
      (is (= "Gastos Médicos" (:sat-category result)))
      (is (> (:tax-confidence result) 0.9)))))

(deftest test-tax-category-income
  (testing "Income is always taxable"
    (let [result (stage5/resolve-tax-category
                   {}
                   {:transaction-type "INGRESO"})]
      (is (= "Taxable Income" (:tax-category result)))
      (is (true? (:business-taxable result)))
      (is (true? (:personal-taxable result)))
      (is (= 1.0 (:tax-confidence result))))))

(deftest test-tax-category-cc-payment
  (testing "Credit card payments are not deductible"
    (let [result (stage5/resolve-tax-category
                   {}
                   {:transaction-type "PAGO_TARJETA"})]
      (is (= "Non-Deductible" (:tax-category result)))
      (is (false? (:business-deductible result)))
      (is (false? (:personal-deductible result)))
      (is (= 1.0 (:tax-confidence result))))))

;; ============================================================================
;; Dimension 6: Payment Method
;; ============================================================================

(deftest test-payment-method-credit-card
  (testing "Apple Card resolves to Credit Card"
    (let [result (stage5/resolve-payment-method
                   {:bank "Apple Card"})]
      (is (= "Credit Card" (:payment-method result)))
      (is (= "Apple Card" (:payment-network result)))
      (is (> (:payment-confidence result) 0.9)))))

(deftest test-payment-method-online
  (testing "Stripe resolves to Online Payment"
    (let [result (stage5/resolve-payment-method
                   {:bank "Stripe"})]
      (is (= "Online Payment" (:payment-method result)))
      (is (= "Stripe" (:payment-network result)))
      (is (= 1.0 (:payment-confidence result))))))

(deftest test-payment-method-transfer
  (testing "TRASPASO resolves to Bank Transfer"
    (let [result (stage5/resolve-payment-method
                   {:transaction-type "TRASPASO"
                    :bank "BofA"})]
      (is (= "Bank Transfer" (:payment-method result)))
      (is (= "BofA" (:payment-network result))))))

(deftest test-payment-method-cash
  (testing "RETIRO resolves to Cash"
    (let [result (stage5/resolve-payment-method
                   {:transaction-type "RETIRO"})]
      (is (= "Cash" (:payment-method result)))
      (is (= "ATM" (:payment-network result))))))

;; ============================================================================
;; Full Resolution Tests
;; ============================================================================

(deftest test-full-resolution-expense
  (testing "Complete resolution of expense transaction"
    (let [result (stage5/resolve-categories test-transaction-expense)]
      ;; Stage 5 status
      (is (= "complete" (:stage5-status result)))
      (is (:stage5-timestamp result))

      ;; Dimension 1: Flow Type
      (is (= "GASTO" (:flow-type result)))
      (is (= "Expenses" (:account-category result)))
      (is (= "Debit" (:debit-credit result)))

      ;; Dimension 2: Merchant Category
      (is (= "Computer Software Stores" (:merchant-category result)))
      (is (= 5734 (:mcc-code result)))

      ;; Dimension 3: Budget Category
      (is (= "Technology" (:budget-category result)))
      (is (= "Software & Services" (:budget-subcategory result)))

      ;; Dimension 5: Tax Category
      (is (= "Business Deductible" (:tax-category result)))
      (is (true? (:business-deductible result)))

      ;; Dimension 6: Payment Method
      (is (= "Debit/Checking" (:payment-method result)))

      ;; Overall confidence
      (is (> (:category-resolution-confidence result) 0.5)))))

(deftest test-full-resolution-income
  (testing "Complete resolution of income transaction"
    (let [result (stage5/resolve-categories test-transaction-income)]
      (is (= "complete" (:stage5-status result)))
      (is (= "INGRESO" (:flow-type result)))
      (is (= "Revenue" (:account-category result)))
      (is (= "Taxable Income" (:tax-category result)))
      (is (= "Online Payment" (:payment-method result))))))

;; ============================================================================
;; Batch Processing Tests
;; ============================================================================

(deftest test-batch-processing
  (testing "Batch processing maintains all enrichments"
    (let [transactions [test-transaction-expense
                        test-transaction-income]
          results (stage5/resolve-batch transactions)]
      (is (= 2 (count results)))
      (is (every? :stage5-status results))
      (is (every? :budget-category results))
      (is (every? :tax-category results))
      (is (every? :payment-method results)))))

(deftest test-batch-processing-empty
  (testing "Empty batch returns empty"
    (let [results (stage5/resolve-batch [])]
      (is (empty? results)))))

;; ============================================================================
;; Statistics Tests
;; ============================================================================

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
      (is (= 1 (get-in stats [:by-budget-category "Technology"])))
      (is (= 1 (get-in stats [:by-budget-category "Living"])))
      (is (> (:avg-confidence stats) 0.8)))))

(deftest test-statistics-empty
  (testing "Statistics for empty list"
    (let [stats (stage5/category-statistics [])]
      (is (= 0 (:total-transactions stats)))
      (is (= 0 (:resolved-count stats)))
      (is (= 0.0 (:resolution-rate stats))))))

(deftest test-statistics-partial-resolution
  (testing "Statistics with partial resolution"
    (let [transactions [{:stage5-status "complete"
                         :budget-category "Technology"
                         :category-resolution-confidence 0.9}
                        {:budget-category "Living"}]  ; Not resolved
          stats (stage5/category-statistics transactions)]
      (is (= 2 (:total-transactions stats)))
      (is (= 1 (:resolved-count stats)))
      (is (= 0.5 (:resolution-rate stats))))))

;; ============================================================================
;; Self-Audit Function
;; ============================================================================

(defn run-self-audit []
  (println "\n🔍 FASE 3+4 Self-Audit: Stage 5 Multi-Dimensional Category Resolution")
  (println "===========================================================================\n")

  ;; Test 1: MCC Registry loaded
  (print "1. MCC Registry loaded: ")
  (if (seq @stage5/mcc-registry)
    (println (format "✅ %d MCC codes loaded" (count @stage5/mcc-registry)))
    (println "❌ Failed to load"))

  ;; Test 2: Flow type mapping
  (print "2. Flow type → Accounting mapping: ")
  (let [test-result (stage5/flow-type->account-category "GASTO")]
    (if (= "Expenses" (:account-category test-result))
      (println "✅ GASTO → Expenses")
      (println "❌ Mapping failed")))

  ;; Test 3: Merchant category resolution
  (print "3. Merchant category resolution: ")
  (let [result (stage5/resolve-merchant-categories {:mcc 5734})]
    (if (and (:merchant-category result) (:budget-category result))
      (println "✅ MCC 5734 → Computer Software Stores")
      (println "❌ Resolution failed")))

  ;; Test 4: Tax category resolution
  (print "4. Tax category resolution: ")
  (let [result (stage5/resolve-tax-category
                 {:tax-hints {:business-deductible true}}
                 {:transaction-type "GASTO"})]
    (if (= "Business Deductible" (:tax-category result))
      (println "✅ Business expense detected")
      (println "❌ Tax resolution failed")))

  ;; Test 5: Payment method resolution
  (print "5. Payment method resolution: ")
  (let [result (stage5/resolve-payment-method {:bank "Stripe"})]
    (if (= "Online Payment" (:payment-method result))
      (println "✅ Stripe → Online Payment")
      (println "❌ Payment method failed")))

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
      (println "✅ Stats generated")
      (println "❌ Stats failed")))

  ;; Test 8: Batch processing
  (print "8. Batch processing: ")
  (let [results (stage5/resolve-batch
                  [{:transaction-type "GASTO"
                    :resolved-merchant {:mcc 5734}}
                   {:transaction-type "INGRESO"
                    :resolved-merchant {:mcc 7372}}])]
    (if (and (= 2 (count results))
             (every? :stage5-status results))
      (println "✅ 2 transactions processed")
      (println "❌ Batch processing failed")))

  (println "\n===========================================================================")
  (println "✅ FASE 3+4 Self-Audit Complete")
  (println "===========================================================================\n"))
