(ns finance.perception
  "Perception Layer - All READS are derived from the event log.

  Rich Hickey Principle: Separate Process (writes) from Perception (reads).

  Perception Layer Responsibilities:
  - Build current state from events
  - Query derived views
  - Aggregate data
  - NEVER write to the log (that's process's job)

  Key Principle: CURRENT STATE = fold(all-events)
  - All reads are queries over the immutable log
  - State is DERIVED, not stored
  - Can rebuild entire state from events
  - Time-travel is native (query events as-of time T)

  Rich Hickey:
  'The log is the database. Everything else is just a cache.'

  This namespace implements 'just a cache' - derived views that can be
  rebuilt at any time from the event log."
  (:require [datomic.api :as d]
            [trust.events-datomic :as events]))

;; ============================================================================
;; PROJECTION: BUILD STATE FROM EVENTS
;; ============================================================================

(defn- apply-transaction-event
  "Apply a single transaction event to the state.

  This is a REDUCER function for event replay.

  Args:
    state - Current state map {:transactions {...}}
    event - Event map {:event/type :event/data ...}

  Returns updated state."
  [state event]
  (case (:event/type event)
    :transaction-imported
    (let [tx (get-in event [:event/data :transaction])
          tx-id (:id tx)]
      (assoc-in state [:transactions tx-id] tx))

    :transaction-classified
    (let [tx-id (get-in event [:event/data :transaction-id])
          classification (get-in event [:event/data :classification])]
      (update-in state [:transactions tx-id] merge classification))

    :transaction-corrected
    (let [tx-id (get-in event [:event/data :transaction-id])
          correction (get-in event [:event/data :correction])]
      (update-in state [:transactions tx-id] merge correction))

    :transaction-verified
    (let [tx-id (get-in event [:event/data :transaction-id])
          verification (get-in event [:event/data :verification])]
      (update-in state [:transactions tx-id] merge verification))

    :duplicate-detected
    (let [original-id (get-in event [:event/data :original-id])
          duplicate-id (get-in event [:event/data :duplicate-id])
          similarity (get-in event [:event/data :similarity])]
      (update-in state [:duplicates] (fnil conj [])
                 {:original-id original-id
                  :duplicate-id duplicate-id
                  :similarity similarity}))

    :duplicate-resolved
    (let [duplicate-id (get-in event [:event/data :duplicate-id])
          is-duplicate? (get-in event [:event/data :resolution :is-duplicate?])]
      (if is-duplicate?
        (update-in state [:duplicates-confirmed] (fnil conj #{}) duplicate-id)
        (update-in state [:duplicates-rejected] (fnil conj #{}) duplicate-id)))

    :balance-reconciled
    (let [reconciliation (:event/data event)]
      (update-in state [:reconciliations] (fnil conj []) reconciliation))

    :discrepancy-detected
    (let [discrepancy (:event/data event)]
      (update-in state [:discrepancies] (fnil conj []) discrepancy))

    ;; Unknown event type - ignore (forward compatibility)
    state))

(defn project-current-state
  "Project current state from all events.

  This is the MAIN PROJECTION function.
  State = fold(all-events, initial-state, apply-transaction-event)

  Args:
    db - Datomic database value

  Returns state map:
    {:transactions {\"tx-001\" {...} \"tx-002\" {...}}
     :duplicates [{:original-id ... :duplicate-id ...}]
     :duplicates-confirmed #{\"tx-003\" \"tx-004\"}
     :duplicates-rejected #{\"tx-005\"}
     :reconciliations [...]
     :discrepancies [...]}

  Example:
    (project-current-state (d/db conn))
    ; => {:transactions {\"tx-001\" {:id \"tx-001\" :amount 45.99 ...}}
    ;     :duplicates []
    ;     :reconciliations []}"
  [db]
  (events/replay-events db {} apply-transaction-event))

(defn project-state-at-time
  "Project state as it was at a specific time.

  This is TIME-TRAVEL - build state from events up to time T.

  Args:
    conn - Datomic connection
    time - Instant to query

  Returns state map at that point in time.

  Example:
    (project-state-at-time conn #inst \"2024-03-20\")
    ; => {:transactions {\"tx-001\" {...}}  ; Only txs that existed on March 20"
  [conn time]
  (let [past-db (d/as-of (d/db conn) time)]
    (project-current-state past-db)))

;; ============================================================================
;; QUERIES: TRANSACTIONS
;; ============================================================================

(defn get-transaction
  "Get a single transaction by ID.

  This is a DERIVED VIEW from events.

  Args:
    db - Datomic database value
    transaction-id - Transaction ID

  Returns transaction map or nil.

  Example:
    (get-transaction (d/db conn) \"tx-001\")
    ; => {:id \"tx-001\" :amount 45.99 :merchant :starbucks ...}"
  [db transaction-id]
  (let [state (project-current-state db)]
    (get-in state [:transactions transaction-id])))

(defn get-all-transactions
  "Get all transactions.

  This is a DERIVED VIEW from events.

  Args:
    db - Datomic database value

  Returns vector of transaction maps.

  Example:
    (get-all-transactions (d/db conn))
    ; => [{:id \"tx-001\" ...} {:id \"tx-002\" ...}]"
  [db]
  (let [state (project-current-state db)]
    (vec (vals (:transactions state)))))

(defn count-transactions
  "Count total transactions.

  This is a DERIVED VIEW from events.

  Example:
    (count-transactions (d/db conn))
    ; => 156"
  [db]
  (count (get-all-transactions db)))

;; ============================================================================
;; QUERIES: FILTERED VIEWS
;; ============================================================================

(defn transactions-by-bank
  "Get transactions for a specific bank.

  This is a FILTERED VIEW over derived state.

  Args:
    db - Datomic database value
    bank-id - Bank ID keyword

  Returns vector of transactions.

  Example:
    (transactions-by-bank (d/db conn) :bofa)"
  [db bank-id]
  (filter #(= (:bank %) bank-id)
          (get-all-transactions db)))

(defn transactions-by-category
  "Get transactions for a specific category.

  Args:
    db - Datomic database value
    category-id - Category ID keyword

  Example:
    (transactions-by-category (d/db conn) :restaurants)"
  [db category-id]
  (filter #(= (:category-id %) category-id)
          (get-all-transactions db)))

(defn transactions-by-merchant
  "Get transactions for a specific merchant.

  Args:
    db - Datomic database value
    merchant-id - Merchant ID keyword

  Example:
    (transactions-by-merchant (d/db conn) :starbucks)"
  [db merchant-id]
  (filter #(= (:merchant-id %) merchant-id)
          (get-all-transactions db)))

(defn transactions-by-type
  "Get transactions of a specific type.

  Args:
    db - Datomic database value
    tx-type - Transaction type (:expense :income :transfer)

  Example:
    (transactions-by-type (d/db conn) :expense)"
  [db tx-type]
  (filter #(= (:type %) tx-type)
          (get-all-transactions db)))

(defn transactions-in-range
  "Get transactions within a date range.

  Args:
    db - Datomic database value
    start-date - Start date (inclusive)
    end-date - End date (exclusive)

  Example:
    (transactions-in-range (d/db conn)
      #inst \"2024-03-01\"
      #inst \"2024-03-31\")"
  [db start-date end-date]
  (filter (fn [tx]
            (let [date (:date tx)]
              (and (not (.before date start-date))
                   (.before date end-date))))
          (get-all-transactions db)))

;; ============================================================================
;; QUERIES: CONFIDENCE FILTERING
;; ============================================================================

(defn high-confidence-transactions
  "Get transactions with high confidence (>= 0.9).

  This is a QUALITY FILTER over derived state.

  Example:
    (high-confidence-transactions (d/db conn))"
  [db]
  (filter #(>= (or (:confidence %) 0.0) 0.9)
          (get-all-transactions db)))

(defn low-confidence-transactions
  "Get transactions with low confidence (< 0.7).

  These need manual review.

  Example:
    (low-confidence-transactions (d/db conn))"
  [db]
  (filter #(< (or (:confidence %) 0.0) 0.7)
          (get-all-transactions db)))

(defn unverified-transactions
  "Get transactions that haven't been verified by user.

  Example:
    (unverified-transactions (d/db conn))"
  [db]
  (remove :verified? (get-all-transactions db)))

;; ============================================================================
;; QUERIES: DUPLICATES
;; ============================================================================

(defn get-duplicate-candidates
  "Get all duplicate candidates detected by the system.

  This is a DERIVED VIEW from DuplicateDetected events.

  Returns vector of {:original-id :duplicate-id :similarity}

  Example:
    (get-duplicate-candidates (d/db conn))
    ; => [{:original-id \"tx-001\" :duplicate-id \"tx-002\" :similarity 0.95}]"
  [db]
  (let [state (project-current-state db)]
    (:duplicates state [])))

(defn get-confirmed-duplicates
  "Get IDs of transactions confirmed as duplicates.

  This is a DERIVED VIEW from DuplicateResolved events.

  Returns set of transaction IDs.

  Example:
    (get-confirmed-duplicates (d/db conn))
    ; => #{\"tx-003\" \"tx-004\"}"
  [db]
  (let [state (project-current-state db)]
    (:duplicates-confirmed state #{})))

(defn is-duplicate?
  "Check if a transaction is marked as duplicate.

  Args:
    db - Datomic database value
    transaction-id - Transaction ID

  Returns true if confirmed duplicate.

  Example:
    (is-duplicate? (d/db conn) \"tx-003\")
    ; => true"
  [db transaction-id]
  (contains? (get-confirmed-duplicates db) transaction-id))

;; ============================================================================
;; QUERIES: RECONCILIATION
;; ============================================================================

(defn get-reconciliations
  "Get all balance reconciliations.

  This is a DERIVED VIEW from BalanceReconciled events.

  Example:
    (get-reconciliations (d/db conn))"
  [db]
  (let [state (project-current-state db)]
    (:reconciliations state [])))

(defn get-discrepancies
  "Get all detected discrepancies.

  This is a DERIVED VIEW from DiscrepancyDetected events.

  Example:
    (get-discrepancies (d/db conn))"
  [db]
  (let [state (project-current-state db)]
    (:discrepancies state [])))

(defn unresolved-discrepancies
  "Get discrepancies that haven't been resolved.

  Example:
    (unresolved-discrepancies (d/db conn))"
  [db]
  ;; TODO: Track resolution events
  (get-discrepancies db))

;; ============================================================================
;; AGGREGATIONS
;; ============================================================================

(defn transaction-statistics
  "Calculate transaction statistics.

  This is an AGGREGATION over derived state.

  Returns:
    {:total N
     :by-type {:expense N :income M ...}
     :by-bank {:bofa N :apple-card M ...}
     :by-category {:restaurants N :groceries M ...}
     :total-income X
     :total-expenses Y
     :net-cashflow Z
     :high-confidence N
     :low-confidence M
     :unverified K}

  Example:
    (transaction-statistics (d/db conn))"
  [db]
  (let [txs (get-all-transactions db)
        by-type (group-by :type txs)
        by-bank (group-by :bank txs)
        by-category (group-by :category-id txs)
        income-txs (get by-type :income [])
        expense-txs (get by-type :expense [])]
    {:total (count txs)
     :by-type (into {} (map (fn [[k v]] [k (count v)]) by-type))
     :by-bank (into {} (map (fn [[k v]] [k (count v)]) by-bank))
     :by-category (into {} (map (fn [[k v]] [k (count v)]) by-category))
     :total-income (reduce + 0.0 (map :amount income-txs))
     :total-expenses (reduce + 0.0 (map :amount expense-txs))
     :net-cashflow (- (reduce + 0.0 (map :amount income-txs))
                      (reduce + 0.0 (map :amount expense-txs)))
     :high-confidence (count (high-confidence-transactions db))
     :low-confidence (count (low-confidence-transactions db))
     :unverified (count (unverified-transactions db))}))

(defn monthly-summary
  "Get monthly income/expense summary.

  This is a TIME-SERIES AGGREGATION.

  Returns vector of {:month :income :expenses :net}

  Example:
    (monthly-summary (d/db conn))
    ; => [{:month \"2024-03\" :income 5000.0 :expenses 3000.0 :net 2000.0}]"
  [db]
  (let [txs (get-all-transactions db)
        by-month (group-by (fn [tx]
                            (let [date (:date tx)]
                              (format "%tY-%tm" date date)))
                          txs)]
    (mapv (fn [[month txs]]
            (let [income-txs (filter #(= (:type %) :income) txs)
                  expense-txs (filter #(= (:type %) :expense) txs)
                  income (reduce + 0.0 (map :amount income-txs))
                  expenses (reduce + 0.0 (map :amount expense-txs))]
              {:month month
               :income income
               :expenses expenses
               :net (- income expenses)}))
          (sort-by first by-month))))

;; ============================================================================
;; TIME-TRAVEL
;; ============================================================================

(defn transaction-history
  "Get the full history of a transaction.

  Shows all events that affected this transaction.

  Args:
    db - Datomic database value
    transaction-id - Transaction ID

  Returns vector of events affecting this transaction.

  Example:
    (transaction-history (d/db conn) \"tx-001\")
    ; => [{:event/type :transaction-imported :event/data {...}}
    ;     {:event/type :transaction-classified :event/data {...}}
    ;     {:event/type :transaction-corrected :event/data {...}}]"
  [db transaction-id]
  (let [all-events (events/all-events db)]
    (filter (fn [event]
              (or (= transaction-id (get-in event [:event/data :transaction :id]))
                  (= transaction-id (get-in event [:event/data :transaction-id]))))
            all-events)))

;; ============================================================================
;; EXAMPLE USAGE
;; ============================================================================

(comment
  (require '[datomic.api :as d])
  (require '[finance.perception :as perception])

  ;; Get connection (from finance.core-datomic)
  (def conn (d/connect "datomic:mem://finance"))
  (def db (d/db conn))

  ;; 1. Project current state from all events
  (def state (perception/project-current-state db))
  state
  ; => {:transactions {"tx-001" {...} "tx-002" {...}}
  ;     :duplicates []
  ;     :reconciliations []}

  ;; 2. Query single transaction
  (perception/get-transaction db "tx-001")
  ; => {:id "tx-001" :amount 45.99 :merchant :starbucks ...}

  ;; 3. Query all transactions
  (perception/get-all-transactions db)
  ; => [{:id "tx-001" ...} {:id "tx-002" ...}]

  (perception/count-transactions db)
  ; => 156

  ;; 4. Filtered views
  (perception/transactions-by-bank db :bofa)
  (perception/transactions-by-category db :restaurants)
  (perception/transactions-by-type db :expense)

  ;; 5. Date range
  (perception/transactions-in-range db
    #inst "2024-03-01"
    #inst "2024-03-31")

  ;; 6. Quality filters
  (perception/high-confidence-transactions db)
  (perception/low-confidence-transactions db)
  (perception/unverified-transactions db)

  ;; 7. Duplicates
  (perception/get-duplicate-candidates db)
  (perception/get-confirmed-duplicates db)
  (perception/is-duplicate? db "tx-003")

  ;; 8. Reconciliation
  (perception/get-reconciliations db)
  (perception/get-discrepancies db)

  ;; 9. Statistics
  (perception/transaction-statistics db)
  ; => {:total 156
  ;     :by-type {:expense 120 :income 36}
  ;     :total-income 5000.0
  ;     :total-expenses 3500.0
  ;     :net-cashflow 1500.0
  ;     :high-confidence 142
  ;     :low-confidence 14
  ;     :unverified 20}

  ;; 10. Monthly summary
  (perception/monthly-summary db)
  ; => [{:month "2024-03" :income 5000.0 :expenses 3000.0 :net 2000.0}]

  ;; 11. TIME-TRAVEL! Query past state
  (def past-state (perception/project-state-at-time conn #inst "2024-03-20"))
  past-state
  ; => {:transactions {"tx-001" {...}}  ; Only txs that existed on March 20

  ;; 12. Transaction history
  (perception/transaction-history db "tx-001")
  ; => [{:event/type :transaction-imported ...}
  ;     {:event/type :transaction-classified ...}
  ;     {:event/type :transaction-corrected ...}]
  )
