(ns finance.merchant-extraction.stage5
  "Stage 5: Multi-Dimensional Category Resolution

   Resolves 6 independent dimensions:
   1. Flow Type (GASTO, INGRESO, etc.) - from Stage 1
   2. Merchant Category (from MCC)
   3. Budget Category (Living, Technology, etc.)
   4. Accounting Category (Assets, Liabilities, etc.)
   5. Tax Category (Deductible business, Deductible personal, etc.)
   6. Payment Method (Cash, Credit, Transfer, etc.)"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]))

;; ============================================================================
;; MCC Registry Loading
;; ============================================================================

(def mcc-registry
  "Lazy-loaded MCC registry"
  (delay
    (try
      (-> (io/resource "registry/mcc_registry.edn")
          slurp
          edn/read-string
          :mcc-codes)
      (catch Exception e
        (println "⚠️ Failed to load MCC registry:" (.getMessage e))
        {}))))

(defn get-mcc-data
  "Get MCC metadata by code"
  [mcc-code]
  (get @mcc-registry mcc-code))

;; ============================================================================
;; Dimension 1: Flow Type (from Stage 1)
;; ============================================================================

(defn flow-type->account-category
  "Map transaction flow type to accounting category"
  [flow-type]
  (case flow-type
    "GASTO"        {:account-category "Expenses"
                    :account-subcategory "Operating Expenses"
                    :debit-credit "Debit"}
    "INGRESO"      {:account-category "Revenue"
                    :account-subcategory "Operating Income"
                    :debit-credit "Credit"}
    "PAGO_TARJETA" {:account-category "Liabilities"
                    :account-subcategory "Credit Card Payment"
                    :debit-credit "Debit"}
    "TRASPASO"     {:account-category "Equity"
                    :account-subcategory "Owner Transfer"
                    :debit-credit "Both"}
    "RETIRO"       {:account-category "Cash"
                    :account-subcategory "Cash Withdrawal"
                    :debit-credit "Debit"}
    "DEPOSITO"     {:account-category "Cash"
                    :account-subcategory "Cash Deposit"
                    :debit-credit "Credit"}
    "COMISION"     {:account-category "Expenses"
                    :account-subcategory "Bank Fees"
                    :debit-credit "Debit"}
    "INTERES"      {:account-category "Revenue"
                    :account-subcategory "Interest Income"
                    :debit-credit "Credit"}
    "AJUSTE"       {:account-category "Equity"
                    :account-subcategory "Adjustment"
                    :debit-credit "Both"}
    ;; Default
    {:account-category "Unknown"
     :account-subcategory "Uncategorized"
     :debit-credit "Unknown"}))

;; ============================================================================
;; Dimension 2-3: Merchant Category + Budget Category (from MCC)
;; ============================================================================

(defn resolve-merchant-categories
  "Resolve merchant and budget categories from category-entity
   NOW: Uses category-entity from Stage 4 (NO HARDCODED FALLBACKS from merchant)"
  [transaction]
  (let [;; Get category entity from Stage 4
        category-entity (:category-entity transaction)

        ;; Get MCC from merchant (if available)
        merchant-entity (:resolved-merchant transaction)
        mcc (:mcc merchant-entity)]

    (if category-entity
      ;; Priority 1: Use category entity (from Stage 4 resolution)
      {:merchant-category (:canonical-name category-entity)
       :budget-category (:budget-category category-entity)
       :budget-subcategory (:budget-subcategory category-entity)
       :mcc-code mcc
       :mcc-confidence (:confidence category-entity 0.95)}

      ;; Priority 2: Try MCC lookup (if merchant has MCC)
      (if-let [mcc-data (and mcc (get-mcc-data mcc))]
        {:merchant-category (:name mcc-data)
         :budget-category (:budget-category mcc-data)
         :budget-subcategory (:budget-subcategory mcc-data)
         :mcc-code mcc
         :mcc-confidence 0.90}

        ;; Priority 3: Unknown (no category entity, no MCC)
        {:merchant-category "Unknown"
         :budget-category "Uncategorized"
         :budget-subcategory "Other"
         :mcc-code nil
         :mcc-confidence 0.30}))))

;; ============================================================================
;; Dimension 5: Tax Category
;; ============================================================================

(defn resolve-tax-category
  "Resolve tax deductibility and category based on merchant + context"
  [merchant-entity transaction-context]
  (let [tax-hints (get merchant-entity :tax-hints {})
        flow-type (:transaction-type transaction-context)
        amount (get transaction-context :amount 0.0)]

    (cond
      ;; Income always taxable
      (= flow-type "INGRESO")
      {:tax-category "Taxable Income"
       :business-taxable true
       :personal-taxable true
       :sat-category (get tax-hints :sat-category "Ingresos por Servicios")
       :irs-category "Business Income - 1099"
       :deductible false
       :tax-confidence 1.0}

      ;; Check merchant tax hints for expenses
      (and (= flow-type "GASTO")
           (:business-deductible tax-hints))
      {:tax-category "Business Deductible"
       :business-deductible true
       :personal-deductible (get tax-hints :personal-deductible false)
       :sat-category (get tax-hints :sat-category "Gastos Generales")
       :irs-category "Business Expense"
       :deductible true
       :tax-confidence 0.9}

      ;; Healthcare special case (personal deductible in Mexico/USA)
      (and (= flow-type "GASTO")
           (or (= (get merchant-entity :budget-category) "Healthcare")
               (= (get merchant-entity :mcc) 8011)  ; Doctors
               (= (get merchant-entity :mcc) 5912))) ; Pharmacies
      {:tax-category "Medical Deductible"
       :business-deductible false
       :personal-deductible true
       :sat-category "Gastos Médicos"
       :irs-category "Medical Expense (subject to AGI threshold)"
       :deductible true
       :tax-confidence 0.95}

      ;; Bank fees, commissions
      (or (= flow-type "COMISION") (= flow-type "INTERES"))
      {:tax-category "Bank Charges"
       :business-deductible true
       :personal-deductible false
       :sat-category "Gastos Financieros"
       :irs-category "Business Interest/Fees"
       :deductible true
       :tax-confidence 0.85}

      ;; Credit card payments (not deductible, it's a liability payment)
      (= flow-type "PAGO_TARJETA")
      {:tax-category "Non-Deductible"
       :business-deductible false
       :personal-deductible false
       :sat-category "Pago de Pasivos"
       :irs-category "Debt Payment"
       :deductible false
       :tax-confidence 1.0}

      ;; Transfers (not deductible, equity movement)
      (= flow-type "TRASPASO")
      {:tax-category "Non-Deductible"
       :business-deductible false
       :personal-deductible false
       :sat-category "Movimiento de Capital"
       :irs-category "Capital Transfer"
       :deductible false
       :tax-confidence 1.0}

      ;; Default for expenses: not deductible unless proven
      :else
      {:tax-category "Non-Deductible"
       :business-deductible false
       :personal-deductible false
       :sat-category "Gastos No Deducibles"
       :irs-category "Personal Expense"
       :deductible false
       :tax-confidence 0.7})))

;; ============================================================================
;; Dimension 6: Payment Method
;; ============================================================================

(defn resolve-payment-method
  "Resolve payment method from transaction context
   NOW: Uses account-entity and bank-entity from Stage 4 (NO HARDCODED RULES)"
  [transaction-context]
  (let [;; Get entities from Stage 4
        account-entity (:account-entity transaction-context)
        bank-entity (:bank-entity transaction-context)
        flow-type (:transaction-type transaction-context)

        ;; Priority 1: Use account entity (most specific)
        payment-from-account (when account-entity
                               {:payment-method (:payment-method account-entity)
                                :payment-network (:payment-network account-entity)
                                :payment-confidence (:confidence account-entity 0.95)})

        ;; Priority 2: Use bank entity (less specific)
        payment-from-bank (when bank-entity
                            {:payment-method (:default-payment-method bank-entity)
                             :payment-network (:canonical-name bank-entity)
                             :payment-confidence (:confidence bank-entity 0.80)})

        ;; Priority 3: Derive from flow-type (fallback)
        payment-from-flow (case flow-type
                           "RETIRO" {:payment-method "Cash"
                                    :payment-network "ATM"
                                    :payment-confidence 0.70}
                           "TRASPASO" {:payment-method "Bank Transfer"
                                      :payment-network "Internal"
                                      :payment-confidence 0.70}
                           "PAGO_TARJETA" {:payment-method "Bank Transfer"
                                          :payment-network "Credit Card Payment"
                                          :payment-confidence 0.70}
                           ;; Default
                           {:payment-method "Unknown"
                            :payment-network "Unknown"
                            :payment-confidence 0.30})]

    ;; Use first available (account > bank > flow)
    (or payment-from-account
        payment-from-bank
        payment-from-flow)))

;; ============================================================================
;; Main Resolution Function
;; ============================================================================

(defn resolve-categories
  "Resolve all 6 dimensions for a single transaction

   Input: transaction with Stage 4 enrichment
   Output: transaction with Stage 5 multi-dimensional categories"
  [transaction]
  (let [;; Extract Stage 4 data
        merchant-entity (get transaction :resolved-merchant {})
        flow-type (get transaction :transaction-type "DESCONOCIDO")

        ;; Dimension 1: Flow Type → Accounting
        accounting (flow-type->account-category flow-type)

        ;; Dimension 2-3: Merchant + Budget Categories (NOW: from transaction with category-entity)
        merchant-budget (resolve-merchant-categories transaction)

        ;; Dimension 5: Tax Category
        tax (resolve-tax-category merchant-entity transaction)

        ;; Dimension 6: Payment Method (NOW: uses account-entity and bank-entity)
        payment (resolve-payment-method transaction)

        ;; Calculate overall confidence
        overall-confidence (/ (+ (get merchant-budget :mcc-confidence 0.5)
                                 (get tax :tax-confidence 0.7)
                                 (get payment :payment-confidence 0.7))
                              3.0)]

    ;; Merge all dimensions into transaction
    (merge transaction
           {:stage5-status "complete"
            :stage5-timestamp (java.util.Date.)

            ;; Dimension 1: Flow Type
            :flow-type flow-type
            :account-category (get accounting :account-category)
            :account-subcategory (get accounting :account-subcategory)
            :debit-credit (get accounting :debit-credit)

            ;; Dimension 2: Merchant Category
            :merchant-category (get merchant-budget :merchant-category)
            :mcc-code (get merchant-budget :mcc-code)

            ;; Dimension 3: Budget Category
            :budget-category (get merchant-budget :budget-category)
            :budget-subcategory (get merchant-budget :budget-subcategory)

            ;; Dimension 5: Tax Category
            :tax-category (get tax :tax-category)
            :business-deductible (get tax :business-deductible)
            :personal-deductible (get tax :personal-deductible)
            :sat-category (get tax :sat-category)
            :irs-category (get tax :irs-category)

            ;; Dimension 6: Payment Method
            :payment-method (get payment :payment-method)
            :payment-network (get payment :payment-network)

            ;; Overall confidence
            :category-resolution-confidence overall-confidence})))

(defn resolve-batch
  "Resolve categories for a batch of transactions"
  [transactions]
  (map resolve-categories transactions))

;; ============================================================================
;; Statistics & Reporting
;; ============================================================================

(defn category-statistics
  "Generate statistics for resolved categories"
  [transactions]
  (let [resolved (filter :stage5-status transactions)]
    {:total-transactions (count transactions)
     :resolved-count (count resolved)
     :resolution-rate (if (pos? (count transactions))
                        (double (/ (count resolved) (count transactions)))
                        0.0)

     ;; Budget category breakdown
     :by-budget-category
     (frequencies (map :budget-category resolved))

     ;; Tax deductibility breakdown
     :by-tax-category
     (frequencies (map :tax-category resolved))

     ;; Payment method breakdown
     :by-payment-method
     (frequencies (map :payment-method resolved))

     ;; Accounting category breakdown
     :by-account-category
     (frequencies (map :account-category resolved))

     ;; Confidence distribution
     :avg-confidence
     (if (seq resolved)
       (/ (reduce + (map :category-resolution-confidence resolved))
          (count resolved))
       0.0)}))

;; ============================================================================
;; Public API
;; ============================================================================

(def stage5-processor
  "Stage 5 processor record"
  {:name "Stage 5: Multi-Dimensional Category Resolution"
   :version "1.0.0"
   :process-fn resolve-categories
   :batch-fn resolve-batch
   :stats-fn category-statistics})
