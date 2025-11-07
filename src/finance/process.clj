(ns finance.process
  "Process Layer - All WRITES are append-only.

  Rich Hickey Principle: Separate Process (writes) from Perception (reads).

  Process Layer Responsibilities:
  - Append events to the immutable log
  - Validate write requests
  - Emit domain events
  - NEVER query or read state (that's perception's job)

  Key Principle: THE LOG IS THE DATABASE
  - All writes go to event log
  - Events are immutable facts
  - Current state is DERIVED from events (not stored)
  - This namespace never performs reads (no queries)

  Rich Hickey:
  'Information systems conflate process and perception. Database updates are
   process. Queries are perception. Keep them separate.'"
  (:require [datomic.api :as d]
            [trust.events-datomic :as events]
            [finance.transducers :as xf]
            [finance.classification :as classify]))

;; ============================================================================
;; PROCESS: TRANSACTION IMPORTS
;; ============================================================================

(defn append-transaction-imported!
  "Append TransactionImported event to the log.

  This is a WRITE operation (process).
  Does NOT query or validate - just appends the fact.

  Args:
    conn - Datomic connection
    tx - Transaction map

  Returns the event entity ID.

  Example:
    (append-transaction-imported! conn
      {:id \"tx-001\"
       :date #inst \"2024-03-20\"
       :amount 45.99
       :merchant :starbucks
       :bank :bofa})"
  [conn tx]
  (events/append-event! conn :transaction-imported
    {:transaction tx}
    {:source (or (:source-file tx) "unknown")
     :line (or (:source-line tx) 0)}))

(defn append-transactions-batch!
  "Append multiple TransactionImported events.

  This is BATCH WRITE (process).
  Uses transducers for efficient transformation.

  Args:
    conn - Datomic connection
    transactions - Collection of transaction maps

  Returns vector of event entity IDs.

  Example:
    (append-transactions-batch! conn transactions)"
  [conn transactions]
  (into []
        (map (fn [tx] (append-transaction-imported! conn tx)))
        transactions))

;; ============================================================================
;; PROCESS: CLASSIFICATION
;; ============================================================================

(defn append-transaction-classified!
  "Append TransactionClassified event to the log.

  This records the DECISION to classify a transaction.

  Args:
    conn - Datomic connection
    transaction-id - Transaction ID
    classification - Classification result map:
                     {:category-id :restaurants
                      :merchant-id :starbucks
                      :confidence 0.95
                      :rule-id :starbucks-prefix}

  Returns the event entity ID.

  Example:
    (append-transaction-classified! conn \"tx-001\"
      {:category-id :restaurants
       :merchant-id :starbucks
       :confidence 0.95
       :rule-id :starbucks-prefix})"
  [conn transaction-id classification]
  (events/append-event! conn :transaction-classified
    {:transaction-id transaction-id
     :classification classification}
    {:classifier-version "1.0.0"}))

(defn classify-and-append!
  "Classify transactions and append classification events.

  This is a COMPOSITE PROCESS:
  1. Transform data (classify using transducers)
  2. Append events (write to log)

  Args:
    conn - Datomic connection
    transactions - Collection of transactions
    rules - Classification rules

  Returns vector of {:transaction-id :event-id} maps.

  Example:
    (classify-and-append! conn transactions rules)"
  [conn transactions rules]
  (let [;; Step 1: Classify using transducers (transformation)
        classified (classify/classify-batch-v2 transactions rules)

        ;; Step 2: Append events (process)
        events (map (fn [tx]
                     {:transaction-id (:id tx)
                      :event-id (append-transaction-classified! conn
                                  (:id tx)
                                  {:category-id (:category-id tx)
                                   :merchant-id (:merchant-id tx)
                                   :confidence (:confidence tx)
                                   :rule-id (:classification-rule-id tx)})})
                   classified)]
    (vec events)))

;; ============================================================================
;; PROCESS: RECONCILIATION
;; ============================================================================

(defn append-balance-reconciled!
  "Append BalanceReconciled event to the log.

  This records the FACT that a balance was reconciled.

  Args:
    conn - Datomic connection
    reconciliation - Reconciliation result map:
                     {:account-id :bofa-checking
                      :date #inst \"2024-03-31\"
                      :expected-balance 10000.00
                      :actual-balance 10000.00
                      :reconciled? true}

  Returns the event entity ID.

  Example:
    (append-balance-reconciled! conn
      {:account-id :bofa-checking
       :date #inst \"2024-03-31\"
       :expected-balance 10000.00
       :actual-balance 10000.00
       :reconciled? true})"
  [conn reconciliation]
  (events/append-event! conn :balance-reconciled
    reconciliation
    {:reconciler-version "1.0.0"}))

(defn append-discrepancy-detected!
  "Append DiscrepancyDetected event to the log.

  This records the FACT that a discrepancy was found.

  Args:
    conn - Datomic connection
    discrepancy - Discrepancy map:
                  {:account-id :bofa-checking
                   :date #inst \"2024-03-31\"
                   :expected-balance 10000.00
                   :actual-balance 9950.00
                   :difference -50.00
                   :reason :missing-transaction}

  Returns the event entity ID."
  [conn discrepancy]
  (events/append-event! conn :discrepancy-detected
    discrepancy
    {:severity :high}))

;; ============================================================================
;; PROCESS: MANUAL CORRECTIONS
;; ============================================================================

(defn append-transaction-corrected!
  "Append TransactionCorrected event to the log.

  This records MANUAL corrections to transactions.
  Note: We don't UPDATE the original transaction - we append a correction event.

  Args:
    conn - Datomic connection
    transaction-id - Original transaction ID
    correction - Correction map (fields to correct):
                 {:amount 45.99  ; Was 46.00
                  :merchant :starbucks  ; Was :unknown
                  :reason \"Manual verification\"}

  Returns the event entity ID.

  Example:
    (append-transaction-corrected! conn \"tx-001\"
      {:amount 45.99
       :merchant :starbucks
       :reason \"Manual verification\"})"
  [conn transaction-id correction]
  (events/append-event! conn :transaction-corrected
    {:transaction-id transaction-id
     :correction correction}
    {:corrected-by "user"
     :corrected-at (java.util.Date.)}))

(defn append-transaction-verified!
  "Append TransactionVerified event to the log.

  This records USER APPROVAL of a transaction.

  Args:
    conn - Datomic connection
    transaction-id - Transaction ID
    verification - Verification result:
                   {:verified? true
                    :notes \"Verified against receipt\"}

  Returns the event entity ID."
  [conn transaction-id verification]
  (events/append-event! conn :transaction-verified
    {:transaction-id transaction-id
     :verification verification}
    {:verified-by "user"
     :verified-at (java.util.Date.)}))

;; ============================================================================
;; PROCESS: DEDUPLICATION
;; ============================================================================

(defn append-duplicate-detected!
  "Append DuplicateDetected event to the log.

  This records the FACT that two transactions are duplicates.
  Note: We don't DELETE duplicates - we append a fact about duplication.

  Args:
    conn - Datomic connection
    original-id - Original transaction ID
    duplicate-id - Duplicate transaction ID
    similarity - Similarity score (0.0 to 1.0)

  Returns the event entity ID.

  Example:
    (append-duplicate-detected! conn \"tx-001\" \"tx-002\" 0.95)"
  [conn original-id duplicate-id similarity]
  (events/append-event! conn :duplicate-detected
    {:original-id original-id
     :duplicate-id duplicate-id
     :similarity similarity}
    {:detector-version "1.0.0"}))

(defn append-duplicate-resolved!
  "Append DuplicateResolved event to the log.

  This records MANUAL resolution of duplicate detection.

  Args:
    conn - Datomic connection
    duplicate-id - Which transaction to mark as duplicate
    resolution - Resolution map:
                 {:is-duplicate? true/false
                  :reason \"Manual review\"}

  Returns the event entity ID."
  [conn duplicate-id resolution]
  (events/append-event! conn :duplicate-resolved
    {:duplicate-id duplicate-id
     :resolution resolution}
    {:resolved-by "user"
     :resolved-at (java.util.Date.)}))

;; ============================================================================
;; UTILITIES
;; ============================================================================

(defn append-system-event!
  "Append a system event to the log.

  For administrative events like:
  - System initialization
  - Configuration changes
  - Batch imports completed

  Args:
    conn - Datomic connection
    event-type - Event type keyword
    data - Event data map

  Returns the event entity ID.

  Example:
    (append-system-event! conn :system-initialized
      {:version \"2.0\"
       :timestamp (java.util.Date.)})"
  [conn event-type data]
  (events/append-event! conn event-type data {}))

;; ============================================================================
;; BATCH OPERATIONS
;; ============================================================================

(defn import-file-pipeline!
  "Complete import pipeline for a file.

  This is a HIGH-LEVEL PROCESS that composes multiple writes:
  1. Parse file (transformation)
  2. Classify transactions (transformation)
  3. Append import event (write)
  4. Append transaction events (writes)
  5. Append classification events (writes)

  Args:
    conn - Datomic connection
    file-path - Path to file
    source-type - Source type (:bofa :apple-card :stripe :wise)
    rules - Classification rules

  Returns summary map:
    {:imported N
     :classified M
     :events [event-ids]}

  Example:
    (import-file-pipeline! conn
      \"/data/bofa_march.csv\"
      :bofa
      (classify/get-default-rules))"
  [conn file-path source-type rules]
  (let [;; 1. Log start of import (write)
        _ (append-system-event! conn :import-started
            {:file file-path
             :source source-type
             :timestamp (java.util.Date.)})

        ;; 2. Parse and transform (NO writes yet)
        ;; TODO: Implement actual file parsing
        raw-txs []  ; Placeholder

        ;; 3. Classify (transformation, no writes)
        classified-txs (classify/classify-batch-v2 raw-txs rules)

        ;; 4. Append all events (writes)
        tx-events (append-transactions-batch! conn classified-txs)
        class-events (classify-and-append! conn classified-txs rules)

        ;; 5. Log completion (write)
        _ (append-system-event! conn :import-completed
            {:file file-path
             :count (count classified-txs)
             :timestamp (java.util.Date.)})]

    {:imported (count tx-events)
     :classified (count class-events)
     :events (concat tx-events (map :event-id class-events))}))

;; ============================================================================
;; EXAMPLE USAGE
;; ============================================================================

(comment
  (require '[datomic.api :as d])
  (require '[finance.process :as process])

  ;; Get connection (from finance.core-datomic)
  (def conn (d/connect "datomic:mem://finance"))

  ;; 1. Append transaction import
  (process/append-transaction-imported! conn
    {:id "tx-001"
     :date #inst "2024-03-20"
     :amount 45.99
     :merchant :starbucks
     :bank :bofa
     :source-file "bofa_march.csv"
     :source-line 23})

  ;; 2. Append classification
  (process/append-transaction-classified! conn "tx-001"
    {:category-id :restaurants
     :merchant-id :starbucks
     :confidence 0.95
     :rule-id :starbucks-prefix})

  ;; 3. Batch import
  (def transactions
    [{:id "tx-001" :amount 45.99 :merchant :starbucks}
     {:id "tx-002" :amount 120.50 :merchant :amazon}])

  (process/append-transactions-batch! conn transactions)

  ;; 4. Classify and append
  (process/classify-and-append! conn transactions
    (classify/get-default-rules))

  ;; 5. Manual correction (append fact, don't mutate)
  (process/append-transaction-corrected! conn "tx-001"
    {:amount 46.00  ; Corrected from 45.99
     :reason "Receipt verification"})

  ;; 6. Verification
  (process/append-transaction-verified! conn "tx-001"
    {:verified? true
     :notes "Verified against receipt"})

  ;; 7. Duplicate detection
  (process/append-duplicate-detected! conn "tx-001" "tx-002" 0.95)

  ;; 8. Complete pipeline
  (process/import-file-pipeline! conn
    "/data/bofa_march.csv"
    :bofa
    (classify/get-default-rules))
  )
