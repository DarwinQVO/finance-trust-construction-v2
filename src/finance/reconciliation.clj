(ns finance.reconciliation
  "Balance reconciliation - Verify transaction sums match expected balances.

  Implements:
  - Running balance calculation
  - Balance verification
  - Discrepancy detection
  - Multi-currency support")

;; ============================================================================
;; BALANCE CALCULATION
;; ============================================================================

(defn calculate-running-balance
  "Calculate running balance for a sequence of transactions.

  Args:
    transactions - Sorted transactions (by date)
    starting-balance - Initial balance (default 0.0)

  Returns transactions with :running-balance added.

  Example:
    (calculate-running-balance
      [{:amount 100 :type :income}
       {:amount 50 :type :expense}]
      1000.0)
    ; => [{:amount 100 :type :income :running-balance 1100.0}
    ;     {:amount 50 :type :expense :running-balance 1050.0}]"
  ([transactions]
   (calculate-running-balance transactions 0.0))
  ([transactions starting-balance]
   (let [sorted-txs (sort-by :date transactions)]
     (loop [txs sorted-txs
            balance starting-balance
            result []]
       (if (empty? txs)
         result
         (let [tx (first txs)
               amount (:amount tx 0.0)
               type (:type tx :unknown)

               ;; Calculate new balance
               new-balance (case type
                            :income (+ balance amount)
                            :expense (- balance amount)
                            :transfer balance  ; Transfers don't affect balance
                            balance)

               ;; Add running balance to transaction
               updated-tx (assoc tx :running-balance new-balance)]
           (recur (rest txs)
                  new-balance
                  (conj result updated-tx))))))))

(defn final-balance
  "Get the final balance after all transactions.

  Example:
    (final-balance transactions 1000.0)
    ; => 1050.0"
  ([transactions]
   (final-balance transactions 0.0))
  ([transactions starting-balance]
   (let [with-balance (calculate-running-balance transactions starting-balance)]
     (:running-balance (last with-balance) starting-balance))))

;; ============================================================================
;; BALANCE VERIFICATION
;; ============================================================================

(defn reconcile-balance
  "Reconcile transactions against expected balance.

  Args:
    transactions - Vector of transactions
    opts - Options:
           :starting-balance - Starting balance
           :ending-balance - Expected ending balance
           :tolerance - Tolerance for differences (default 0.01)

  Returns:
    {:reconciled? true/false
     :calculated-balance N
     :expected-balance M
     :difference D
     :within-tolerance? true/false}

  Example:
    (reconcile-balance transactions
      {:starting-balance 1000.0
       :ending-balance 1050.0
       :tolerance 0.01})"
  [transactions {:keys [starting-balance ending-balance tolerance]
                 :or {starting-balance 0.0
                      ending-balance 0.0
                      tolerance 0.01}}]
  (let [calculated (final-balance transactions starting-balance)
        difference (Math/abs (- calculated ending-balance))
        within-tolerance? (<= difference tolerance)]
    {:reconciled? within-tolerance?
     :calculated-balance calculated
     :expected-balance ending-balance
     :difference difference
     :within-tolerance? within-tolerance?
     :transactions-count (count transactions)}))

(defn verify-balance
  "Verify that calculated balance matches expected balance.

  Throws exception if not reconciled.

  Example:
    (verify-balance transactions
      {:starting-balance 1000.0
       :ending-balance 1050.0})"
  [transactions opts]
  (let [result (reconcile-balance transactions opts)]
    (if (:reconciled? result)
      result
      (throw (ex-info "Balance reconciliation failed"
                      result)))))

;; ============================================================================
;; DISCREPANCY DETECTION
;; ============================================================================

(defn find-discrepancies
  "Find transactions that might cause balance discrepancies.

  Looks for:
  - Missing transaction types
  - Unusually large amounts
  - Duplicate amounts on same day

  Returns vector of {:tx ... :issue ...} maps.

  Example:
    (find-discrepancies transactions)"
  [transactions]
  (let [issues (atom [])]

    ;; Check for missing types
    (doseq [tx transactions]
      (when (nil? (:type tx))
        (swap! issues conj
               {:tx tx
                :issue :missing-type
                :description "Transaction type not set"})))

    ;; Check for large amounts (> 10,000)
    (doseq [tx transactions]
      (when (> (:amount tx 0.0) 10000.0)
        (swap! issues conj
               {:tx tx
                :issue :large-amount
                :description "Unusually large amount"})))

    ;; Check for duplicates (same amount, same day)
    (let [by-date-amount (group-by (juxt :date :amount) transactions)]
      (doseq [[[date amount] txs] by-date-amount]
        (when (> (count txs) 1)
          (doseq [tx txs]
            (swap! issues conj
                   {:tx tx
                    :issue :potential-duplicate
                    :description (format "Multiple transactions with amount %s on %s"
                                       amount date)})))))

    @issues))

;; ============================================================================
;; PERIOD RECONCILIATION
;; ============================================================================

(defn reconcile-period
  "Reconcile transactions for a specific time period.

  Args:
    transactions - All transactions
    opts - Options:
           :start-date - Period start
           :end-date - Period end
           :starting-balance - Balance at start
           :ending-balance - Expected balance at end

  Example:
    (reconcile-period transactions
      {:start-date #inst \"2024-03-01\"
       :end-date #inst \"2024-03-31\"
       :starting-balance 1000.0
       :ending-balance 1500.0})"
  [transactions {:keys [start-date end-date] :as opts}]
  (let [period-txs (filter (fn [tx]
                            (let [date (:date tx)]
                              (and (not (.before date start-date))
                                   (not (.after date end-date)))))
                          transactions)]
    (assoc (reconcile-balance period-txs opts)
           :period-start start-date
           :period-end end-date
           :period-transactions (count period-txs))))

;; ============================================================================
;; MULTI-CURRENCY RECONCILIATION
;; ============================================================================

(defn reconcile-by-currency
  "Reconcile transactions grouped by currency.

  Args:
    transactions - Transactions with :currency field
    opts - Options per currency:
           {:USD {:starting-balance 1000.0 :ending-balance 1500.0}
            :EUR {:starting-balance 500.0 :ending-balance 600.0}}

  Returns map of currency -> reconciliation-result.

  Example:
    (reconcile-by-currency transactions
      {:USD {:starting-balance 1000.0 :ending-balance 1500.0}
       :EUR {:starting-balance 500.0 :ending-balance 600.0}})"
  [transactions currency-opts]
  (let [by-currency (group-by :currency transactions)]
    (into {}
          (map (fn [[currency txs]]
                 (let [opts (get currency-opts currency {})]
                   [currency (reconcile-balance txs opts)]))
               by-currency))))

;; ============================================================================
;; ACCOUNT RECONCILIATION
;; ============================================================================

(defn reconcile-account
  "Reconcile transactions for a specific account.

  Args:
    transactions - All transactions
    account-id - Account identifier
    opts - Reconciliation options

  Example:
    (reconcile-account transactions :bofa-checking
      {:starting-balance 1000.0
       :ending-balance 1500.0})"
  [transactions account-id opts]
  (let [account-txs (filter #(= (:account-id %) account-id) transactions)]
    (assoc (reconcile-balance account-txs opts)
           :account-id account-id
           :account-transactions (count account-txs))))

;; ============================================================================
;; RECONCILIATION REPORT
;; ============================================================================

(defn reconciliation-report
  "Generate a comprehensive reconciliation report.

  Returns:
    {:summary {...}
     :by-account {...}
     :by-currency {...}
     :discrepancies [...]}

  Example:
    (reconciliation-report transactions opts)"
  [transactions opts]
  (let [summary (reconcile-balance transactions opts)
        discrepancies (find-discrepancies transactions)
        by-account (when (:accounts opts)
                    (into {}
                          (map (fn [[account-id account-opts]]
                                 [account-id
                                  (reconcile-account transactions account-id account-opts)])
                               (:accounts opts))))
        by-currency (when (:currencies opts)
                     (reconcile-by-currency transactions (:currencies opts)))]
    {:summary summary
     :by-account by-account
     :by-currency by-currency
     :discrepancies discrepancies
     :discrepancy-count (count discrepancies)}))

;; ============================================================================
;; UTILITIES
;; ============================================================================

(defn sum-by-type
  "Sum transaction amounts by type.

  Returns map of type -> total.

  Example:
    (sum-by-type transactions)
    ; => {:income 5000.0 :expense 3500.0 :transfer 0.0}"
  [transactions]
  (reduce (fn [acc tx]
            (let [type (:type tx :unknown)
                  amount (:amount tx 0.0)]
              (update acc type (fnil + 0.0) amount)))
          {}
          transactions))

(defn net-cashflow
  "Calculate net cashflow (income - expenses).

  Example:
    (net-cashflow transactions)
    ; => 1500.0"
  [transactions]
  (let [sums (sum-by-type transactions)]
    (- (get sums :income 0.0)
       (get sums :expense 0.0))))

;; ============================================================================
;; EXAMPLE USAGE (for documentation)
;; ============================================================================

(comment
  ;; Calculate running balance
  (def with-balance
    (calculate-running-balance transactions 1000.0))

  (final-balance transactions 1000.0)
  ; => 1500.0

  ;; Reconcile
  (reconcile-balance transactions
    {:starting-balance 1000.0
     :ending-balance 1500.0
     :tolerance 0.01})
  ; => {:reconciled? true
  ;     :calculated-balance 1500.0
  ;     :expected-balance 1500.0
  ;     :difference 0.0
  ;     :within-tolerance? true}

  ;; Find discrepancies
  (find-discrepancies transactions)
  ; => [{:tx {...} :issue :missing-type :description "..."}
  ;     {:tx {...} :issue :large-amount :description "..."}]

  ;; Period reconciliation
  (reconcile-period transactions
    {:start-date #inst "2024-03-01"
     :end-date #inst "2024-03-31"
     :starting-balance 1000.0
     :ending-balance 1500.0})

  ;; Multi-currency
  (reconcile-by-currency transactions
    {:USD {:starting-balance 1000.0 :ending-balance 1500.0}
     :EUR {:starting-balance 500.0 :ending-balance 600.0}})

  ;; Account reconciliation
  (reconcile-account transactions :bofa-checking
    {:starting-balance 1000.0
     :ending-balance 1500.0})

  ;; Comprehensive report
  (reconciliation-report transactions
    {:starting-balance 1000.0
     :ending-balance 1500.0
     :accounts {:bofa-checking {:starting-balance 500.0
                                 :ending-balance 750.0}}
     :currencies {:USD {:starting-balance 800.0
                        :ending-balance 1200.0}}})

  ;; Utilities
  (sum-by-type transactions)
  ; => {:income 5000.0 :expense 3500.0}

  (net-cashflow transactions)
  ; => 1500.0
  )
