# Trust Construction - Event Catalog

**Date:** 2025-11-07
**Status:** DESIGN
**Purpose:** Complete catalog of all events before implementation

---

## 1. Event Sourcing Principles

### Rich Hickey on Events

```
"Facts don't change. Interpretations do."

Events = Facts (immutable)
State = Interpretation (derived from events)

Process = Append events
Perception = Query projections
```

### Our Event Design

1. **Events are facts** - Never change once written
2. **Events are primary** - State is derived from events
3. **Events have semantics** - Not just "data changed"
4. **Events enable time-travel** - Replay to any point
5. **Events support audit** - Complete history

---

## 2. Base Event Structure

### All Events Have

```clojure
{;; EVENT IDENTITY
 :event-id "evt-550e8400-e29b-41d4-a716-446655440000"  ; UUID
 :event-type :transaction-imported  ; Keyword
 :sequence-number 12345  ; Auto-increment (ordering)

 ;; WHAT ENTITY THIS AFFECTS
 :aggregate-id "tx-550e8400-..."  ; Entity UUID
 :aggregate-type :transaction  ; :transaction | :bank | :merchant | etc.

 ;; WHEN
 :timestamp "2025-03-20T10:30:00.000Z"  ; ISO 8601

 ;; WHO
 :author "darwin"  ; Username
 :source "import-script"  ; What triggered this

 ;; EVENT-SPECIFIC DATA
 :data {...}  ; Varies by event-type

 ;; TRACING
 :metadata {:correlation-id "batch-abc123"  ; Group related events
            :causation-id "evt-previous-..."  ; What caused this event
            :source-file "bofa_march_2024.csv"}}
```

---

## 3. Transaction Events (8 events)

### 3.1 TransactionImported

**When:** New transaction added to system

**Purpose:** Record that we received a transaction from external source

```clojure
{:event-id "evt-001"
 :event-type :transaction-imported
 :sequence-number 1
 :aggregate-id "tx-550e8400-..."
 :aggregate-type :transaction
 :timestamp "2025-03-20T10:30:00.000Z"
 :author "darwin"
 :source "csv-import"

 :data {;; CORE TRANSACTION DATA
        :date "2025-03-20"
        :amount 45.99
        :merchant "STARBUCKS #5678"  ; Raw, not normalized yet
        :bank "BofA"
        :type :expense
        :description "STARBUCKS #5678 SEATTLE WA"

        ;; IMPORT METADATA
        :raw-data {:col1 "03/20/2025"
                   :col2 "STARBUCKS..."
                   :col3 "45.99"}  ; Original CSV row
        :parser-version "bofa-parser-v1.2"
        :confidence 1.0}  ; High confidence in parsing

 :metadata {:source-file "bofa_march_2024.csv"
            :source-line 23
            :correlation-id "batch-abc123"}}
```

**Projection Impact:**
- Creates new transaction in current-transactions
- Status = :imported (not classified yet)

---

### 3.2 TransactionClassified

**When:** Transaction categorized (merchant + category assigned)

**Purpose:** Record classification decision (human or automatic)

```clojure
{:event-id "evt-002"
 :event-type :transaction-classified
 :sequence-number 2
 :aggregate-id "tx-550e8400-..."
 :aggregate-type :transaction
 :timestamp "2025-03-20T10:31:00.000Z"
 :author "classification-engine"
 :source "rule-engine"

 :data {;; CLASSIFICATION RESULT
        :merchant-normalized "Starbucks"  ; Cleaned up
        :category :restaurant
        :confidence 0.95

        ;; HOW IT WAS CLASSIFIED
        :classification-method :automatic  ; or :manual
        :rule-id :merchant-pattern-starbucks
        :rule-version "1.0.0"

        ;; PREVIOUS STATE (for comparison)
        :previous-merchant "STARBUCKS #5678"
        :previous-category nil}

 :metadata {:causation-id "evt-001"  ; Caused by import
            :correlation-id "batch-abc123"}}
```

**Projection Impact:**
- Updates transaction with merchant + category
- Status = :classified

---

### 3.3 TransactionVerified

**When:** User manually verifies transaction is correct

**Purpose:** Record human approval (builds trust)

```clojure
{:event-id "evt-003"
 :event-type :transaction-verified
 :sequence-number 3
 :aggregate-id "tx-550e8400-..."
 :aggregate-type :transaction
 :timestamp "2025-03-20T15:00:00.000Z"
 :author "darwin"
 :source "web-ui"

 :data {;; VERIFICATION
        :verified true
        :verification-notes "Looks correct"

        ;; CONTEXT
        :reviewed-fields [:merchant :category :amount]
        :all-fields-correct true}

 :metadata {:causation-id "evt-002"
            :user-agent "Mozilla/5.0..."}}
```

**Projection Impact:**
- Transaction.verified = true
- Transaction.verified-by = "darwin"
- Transaction.verified-at = timestamp

---

### 3.4 TransactionFlagged

**When:** Transaction marked for review (suspicious or incorrect)

**Purpose:** Record that something needs attention

```clojure
{:event-id "evt-004"
 :event-type :transaction-flagged
 :sequence-number 4
 :aggregate-id "tx-550e8400-..."
 :aggregate-type :transaction
 :timestamp "2025-03-20T15:05:00.000Z"
 :author "data-quality-checker"
 :source "validation-engine"

 :data {;; FLAG
        :flagged true
        :flag-reason :amount-too-high  ; or :merchant-unknown, :duplicate-suspected
        :flag-severity :warning  ; :info | :warning | :error
        :flag-notes "Amount is 10x higher than usual for this merchant"

        ;; EXPECTED vs ACTUAL
        :expected-amount 4.99
        :actual-amount 49.99

        ;; ACTION NEEDED
        :requires-manual-review true}

 :metadata {:rule-id :amount-outlier-detection}}
```

**Projection Impact:**
- Transaction.flagged = true
- Transaction.flag-reason = :amount-too-high
- Appears in "needs review" list

---

### 3.5 TransactionReconciled

**When:** Transaction matched with bank statement

**Purpose:** Record that amount matches expected

```clojure
{:event-id "evt-005"
 :event-type :transaction-reconciled
 :sequence-number 5
 :aggregate-id "tx-550e8400-..."
 :aggregate-type :transaction
 :timestamp "2025-03-20T16:00:00.000Z"
 :author "darwin"
 :source "reconciliation-ui"

 :data {;; RECONCILIATION
        :reconciled true
        :expected-amount 45.99
        :actual-amount 45.99
        :discrepancy 0.0

        ;; STATEMENT INFO
        :statement-file "bofa_statement_march.pdf"
        :statement-date "2025-03-31"
        :statement-line 15}

 :metadata {:reconciliation-session-id "recon-xyz"}}
```

**Projection Impact:**
- Transaction.reconciled = true
- Transaction.reconciled-at = timestamp

---

### 3.6 TransactionNoteAdded

**When:** User adds note/comment to transaction

**Purpose:** Record additional context

```clojure
{:event-id "evt-006"
 :event-type :transaction-note-added
 :sequence-number 6
 :aggregate-id "tx-550e8400-..."
 :aggregate-type :transaction
 :timestamp "2025-03-20T17:00:00.000Z"
 :author "darwin"
 :source "web-ui"

 :data {;; NOTE
        :note "This was for a work meeting, should be reimbursed"
        :note-type :reimbursement  ; or :personal, :business, :tax-deductible

        ;; TAGS (optional)
        :tags [:reimbursable :work :meeting]}}
```

**Projection Impact:**
- Transaction.notes = "..."
- Transaction.tags = [...]

---

### 3.7 TransactionAmountCorrected

**When:** Amount was wrong, user corrects it

**Purpose:** Record correction with reason

```clojure
{:event-id "evt-007"
 :event-type :transaction-amount-corrected
 :sequence-number 7
 :aggregate-id "tx-550e8400-..."
 :aggregate-type :transaction
 :timestamp "2025-03-20T18:00:00.000Z"
 :author "darwin"
 :source "web-ui"

 :data {;; CORRECTION
        :old-amount 45.99
        :new-amount 4.99
        :correction-reason "Decimal point in wrong place"

        ;; VERIFICATION
        :corrected-by "darwin"
        :correction-verified true}}
```

**Projection Impact:**
- Transaction.amount = 4.99
- Transaction.amount-corrected = true
- Transaction.amount-correction-history = [...]

---

### 3.8 TransactionMerchantCorrected

**When:** Merchant name was wrong, user fixes it

**Purpose:** Record merchant correction

```clojure
{:event-id "evt-008"
 :event-type :transaction-merchant-corrected
 :sequence-number 8
 :aggregate-id "tx-550e8400-..."
 :aggregate-type :transaction
 :timestamp "2025-03-20T19:00:00.000Z"
 :author "darwin"
 :source "web-ui"

 :data {;; CORRECTION
        :old-merchant "Unknown Cafe"
        :new-merchant "Local Coffee Shop"
        :correction-reason "Merchant was mis-identified"

        ;; PROPAGATION (should this update rule?)
        :update-classification-rule false
        :add-merchant-alias true}}
```

**Projection Impact:**
- Transaction.merchant = "Local Coffee Shop"
- Transaction.merchant-corrected = true
- Optionally: Creates MerchantAliasAdded event

---

## 4. Bank Events (3 events)

### 4.1 BankCreated

```clojure
{:event-id "evt-100"
 :event-type :bank-created
 :aggregate-id "bank-550e8400-..."
 :aggregate-type :bank
 :timestamp "2025-01-01T00:00:00.000Z"
 :author "system"
 :source "initialization"

 :data {;; BANK DATA
        :name "Bank of America"
        :short-name "BofA"
        :aliases ["bofa" "boa"]
        :type :checking
        :currency "USD"
        :website "https://bankofamerica.com"
        :active true}}
```

---

### 4.2 BankUpdated

```clojure
{:event-id "evt-101"
 :event-type :bank-updated
 :aggregate-id "bank-550e8400-..."
 :aggregate-type :bank
 :timestamp "2025-03-01T00:00:00.000Z"
 :author "darwin"
 :source "web-ui"

 :data {;; CHANGES
        :changes {:website {:old "http://..."
                           :new "https://..."}
                 :aliases {:old ["bofa"]
                          :new ["bofa" "boa" "bank of america"]}}

        ;; REASON
        :update-reason "Added aliases for better matching"}}
```

---

### 4.3 BankDeactivated

```clojure
{:event-id "evt-102"
 :event-type :bank-deactivated
 :aggregate-id "bank-550e8400-..."
 :aggregate-type :bank
 :timestamp "2025-06-01T00:00:00.000Z"
 :author "darwin"
 :source "web-ui"

 :data {;; DEACTIVATION
        :deactivation-reason "Account closed"
        :deactivation-date "2025-05-31"

        ;; MIGRATION (if transactions moved)
        :transactions-migrated-to "bank-new-..."}}
```

---

## 5. Merchant Events (4 events)

### 5.1 MerchantCreated

```clojure
{:event-id "evt-200"
 :event-type :merchant-created
 :aggregate-id "merchant-550e8400-..."
 :aggregate-type :merchant
 :timestamp "2025-01-01T00:00:00.000Z"
 :author "system"
 :source "initialization"

 :data {;; MERCHANT DATA
        :name "Starbucks"
        :aliases ["STARBUCKS*" "SBUX"]
        :default-category :restaurant
        :confidence 0.98
        :active true}}
```

---

### 5.2 MerchantUpdated

```clojure
{:event-id "evt-201"
 :event-type :merchant-updated
 :aggregate-id "merchant-550e8400-..."
 :aggregate-type :merchant
 :timestamp "2025-02-01T00:00:00.000Z"
 :author "darwin"
 :source "web-ui"

 :data {;; CHANGES
        :changes {:default-category {:old :cafe
                                    :new :restaurant}
                 :confidence {:old 0.95
                             :new 0.98}}

        :update-reason "More transactions confirmed category"}}
```

---

### 5.3 MerchantMerged

**When:** Two merchants are actually the same, merge them

```clojure
{:event-id "evt-202"
 :event-type :merchant-merged
 :aggregate-id "merchant-550e8400-..."  ; Target (keep this one)
 :aggregate-type :merchant
 :timestamp "2025-03-01T00:00:00.000Z"
 :author "darwin"
 :source "web-ui"

 :data {;; MERGE
        :source-merchant-id "merchant-duplicate-..."  ; Delete this one
        :source-merchant-name "Starbuck"  ; Typo
        :target-merchant-name "Starbucks"  ; Correct

        ;; MIGRATION
        :transactions-migrated 47  ; All txs moved to target

        ;; ALIASES
        :aliases-added ["Starbuck"]  ; Add typo as alias

        :merge-reason "Duplicate entry, spelling error"}}
```

---

### 5.4 MerchantAliasAdded

```clojure
{:event-id "evt-203"
 :event-type :merchant-alias-added
 :aggregate-id "merchant-550e8400-..."
 :aggregate-type :merchant
 :timestamp "2025-03-15T00:00:00.000Z"
 :author "darwin"
 :source "merchant-correction"

 :data {;; NEW ALIAS
        :alias "STARBUCKS STORE #5678"
        :added-because "Found in transaction, should match to Starbucks"

        ;; CONFIDENCE
        :confidence 0.95}}
```

---

## 6. Category Events (3 events)

### 6.1 CategoryCreated

```clojure
{:event-id "evt-300"
 :event-type :category-created
 :aggregate-id "category-550e8400-..."
 :aggregate-type :category
 :timestamp "2025-01-01T00:00:00.000Z"
 :author "system"
 :source "initialization"

 :data {;; CATEGORY DATA
        :name :restaurant
        :display-name "Restaurants & Dining"
        :parent :food
        :icon "🍽️"
        :color "#FF6B6B"
        :active true}}
```

---

### 6.2 CategoryUpdated

```clojure
{:event-id "evt-301"
 :event-type :category-updated
 :aggregate-id "category-550e8400-..."
 :aggregate-type :category
 :timestamp "2025-02-01T00:00:00.000Z"
 :author "darwin"
 :source "web-ui"

 :data {;; CHANGES
        :changes {:display-name {:old "Restaurant"
                                :new "Restaurants & Dining"}
                 :parent {:old nil
                         :new :food}}

        :update-reason "Added parent category for grouping"}}
```

---

### 6.3 CategoryBudgetSet

```clojure
{:event-id "evt-302"
 :event-type :category-budget-set
 :aggregate-id "category-550e8400-..."
 :aggregate-type :category
 :timestamp "2025-01-01T00:00:00.000Z"
 :author "darwin"
 :source "budgeting-tool"

 :data {;; BUDGET
        :budget-amount 500.00
        :budget-period :monthly  ; :daily | :weekly | :monthly | :yearly
        :budget-start-date "2025-01-01"

        ;; PREVIOUS
        :previous-budget 450.00}}
```

---

## 7. Deduplication Events (3 events)

### 7.1 DuplicateDetected

**When:** System finds potential duplicate

```clojure
{:event-id "evt-400"
 :event-type :duplicate-detected
 :aggregate-id "tx-duplicate-candidate-..."
 :aggregate-type :duplicate-candidate
 :timestamp "2025-03-20T12:00:00.000Z"
 :author "deduplication-engine"
 :source "batch-analysis"

 :data {;; CANDIDATE PAIR
        :transaction-1-id "tx-001"
        :transaction-2-id "tx-002"

        ;; SIMILARITY
        :similarity-score 0.92  ; 0.0-1.0
        :matching-fields [:date :amount :merchant]
        :differing-fields [:bank]

        ;; RULE THAT DETECTED IT
        :detection-rule :exact-match-most-fields
        :detection-strategy :exact-match
        :confidence 0.92

        ;; STATUS
        :status :pending-review}}  ; :pending-review | :confirmed | :rejected
```

**Projection Impact:**
- Creates entry in duplicate-candidates table
- Appears in "needs review" list

---

### 7.2 DuplicateConfirmed

**When:** User confirms it IS a duplicate

```clojure
{:event-id "evt-401"
 :event-type :duplicate-confirmed
 :aggregate-id "tx-duplicate-candidate-..."
 :aggregate-type :duplicate-candidate
 :timestamp "2025-03-20T15:00:00.000Z"
 :author "darwin"
 :source "web-ui"

 :data {;; CONFIRMATION
        :transaction-to-keep "tx-001"  ; Primary
        :transaction-to-mark "tx-002"  ; Duplicate

        ;; REASON
        :confirmation-reason "Same transaction, different bank exports"
        :confidence 1.0  ; User confirmed = 100%

        ;; ACTION
        :action :mark-duplicate}}  ; Don't delete, just mark
```

**Projection Impact:**
- tx-002.duplicate-of = tx-001
- tx-002 hidden in default queries
- Duplicate candidate status = :confirmed

---

### 7.3 DuplicateRejected

**When:** User says it's NOT a duplicate

```clojure
{:event-id "evt-402"
 :event-type :duplicate-rejected
 :aggregate-id "tx-duplicate-candidate-..."
 :aggregate-type :duplicate-candidate
 :timestamp "2025-03-20T15:05:00.000Z"
 :author "darwin"
 :source "web-ui"

 :data {;; REJECTION
        :rejection-reason "Different merchants despite similar names"
        :confidence 1.0  ; User confirmed = 100%

        ;; LEARN FROM THIS
        :create-negative-rule true  ; Don't suggest this pair again
        :update-similarity-threshold false}}
```

**Projection Impact:**
- Duplicate candidate removed
- Optionally: Creates rule to prevent false positives

---

## 8. Reconciliation Events (3 events)

### 8.1 ReconciliationStarted

```clojure
{:event-id "evt-500"
 :event-type :reconciliation-started
 :aggregate-id "recon-550e8400-..."
 :aggregate-type :reconciliation-session
 :timestamp "2025-03-31T10:00:00.000Z"
 :author "darwin"
 :source "reconciliation-ui"

 :data {;; SESSION
        :bank "BofA"
        :account "****1234"
        :period-start "2025-03-01"
        :period-end "2025-03-31"

        ;; EXPECTED
        :expected-balance 5432.10
        :statement-file "bofa_march_statement.pdf"}}
```

---

### 8.2 ReconciliationCompleted

```clojure
{:event-id "evt-501"
 :event-type :reconciliation-completed
 :aggregate-id "recon-550e8400-..."
 :aggregate-type :reconciliation-session
 :timestamp "2025-03-31T11:00:00.000Z"
 :author "darwin"
 :source "reconciliation-ui"

 :data {;; RESULT
        :status :success  ; :success | :discrepancy | :failed
        :calculated-balance 5432.10
        :expected-balance 5432.10
        :discrepancy 0.0

        ;; STATS
        :transactions-reconciled 47
        :transactions-missing 0
        :transactions-extra 0}}
```

---

### 8.3 DiscrepancyFound

```clojure
{:event-id "evt-502"
 :event-type :discrepancy-found
 :aggregate-id "recon-550e8400-..."
 :aggregate-type :reconciliation-session
 :timestamp "2025-03-31T10:30:00.000Z"
 :author "reconciliation-engine"
 :source "automatic-check"

 :data {;; DISCREPANCY
        :discrepancy-type :amount-mismatch  ; or :missing-transaction, :extra-transaction
        :expected 5432.10
        :actual 5430.00
        :difference -2.10

        ;; DETAILS
        :affected-transaction-ids ["tx-001" "tx-002"]
        :requires-investigation true

        ;; HYPOTHESIS
        :possible-causes ["Transaction #001 has wrong amount"
                         "Bank fee not captured"]}}
```

---

## 9. Rule Events (3 events)

### 9.1 RuleCreated

```clojure
{:event-id "evt-600"
 :event-type :rule-created
 :aggregate-id "rule-550e8400-..."
 :aggregate-type :classification-rule
 :timestamp "2025-03-01T00:00:00.000Z"
 :author "darwin"
 :source "rule-editor"

 :data {;; RULE
        :rule-id :merchant-pattern-new-cafe
        :type :classification
        :pattern #"NEW CAFE.*"
        :merchant :new-cafe
        :category :restaurant
        :confidence 0.95
        :priority 100
        :enabled true
        :version "1.0.0"

        ;; METADATA
        :description "Match New Cafe transactions"
        :test-count 0}}  ; Will test on existing txs
```

---

### 9.2 RuleUpdated

```clojure
{:event-id "evt-601"
 :event-type :rule-updated
 :aggregate-id "rule-550e8400-..."
 :aggregate-type :classification-rule
 :timestamp "2025-03-15T00:00:00.000Z"
 :author "darwin"
 :source "rule-editor"

 :data {;; CHANGES
        :changes {:confidence {:old 0.95
                             :new 0.98}
                 :priority {:old 100
                           :new 110}}

        :update-reason "Increased confidence after testing"
        :version "1.1.0"  ; Version bumped

        ;; TESTING
        :tested-on-transactions 25
        :test-accuracy 0.98}}
```

---

### 9.3 RuleDeactivated

```clojure
{:event-id "evt-602"
 :event-type :rule-deactivated
 :aggregate-id "rule-550e8400-..."
 :aggregate-type :classification-rule
 :timestamp "2025-06-01T00:00:00.000Z"
 :author "darwin"
 :source "rule-editor"

 :data {;; DEACTIVATION
        :deactivation-reason "Merchant closed"
        :enabled false

        ;; IMPACT
        :affected-transactions 47  ; How many matched this rule
        :replacement-rule :merchant-pattern-new-owner}}  ; Use this instead
```

---

## 10. Event Projections

### How Events Build State

```clojure
;; Starting with empty state
(def initial-state {})

;; Event 1: TransactionImported
(def state-1
  (apply-event initial-state
    {:event-type :transaction-imported
     :aggregate-id "tx-001"
     :data {:date "2025-03-20"
            :amount 45.99
            :merchant "STARBUCKS #5678"}}))

;; => {:tx-001 {:date "2025-03-20"
;;              :amount 45.99
;;              :merchant "STARBUCKS #5678"
;;              :status :imported
;;              :version 1}}

;; Event 2: TransactionClassified
(def state-2
  (apply-event state-1
    {:event-type :transaction-classified
     :aggregate-id "tx-001"
     :data {:merchant-normalized "Starbucks"
            :category :restaurant
            :confidence 0.95}}))

;; => {:tx-001 {:date "2025-03-20"
;;              :amount 45.99
;;              :merchant "Starbucks"  ; Updated!
;;              :category :restaurant  ; Added!
;;              :confidence 0.95       ; Added!
;;              :status :classified    ; Updated!
;;              :version 2}}            ; Incremented!

;; Event 3: TransactionVerified
(def state-3
  (apply-event state-2
    {:event-type :transaction-verified
     :aggregate-id "tx-001"
     :data {:verified true
            :verification-notes "Correct"}}))

;; => {:tx-001 {:date "2025-03-20"
;;              :amount 45.99
;;              :merchant "Starbucks"
;;              :category :restaurant
;;              :confidence 0.95
;;              :verified true         ; Added!
;;              :verification-notes "Correct"  ; Added!
;;              :status :verified      ; Updated!
;;              :version 3}}           ; Incremented!
```

### Projection Function (Generic)

```clojure
(defn apply-event
  "Apply single event to state, return new state."
  [state event]
  (case (:event-type event)
    :transaction-imported
    (assoc state (:aggregate-id event)
      (merge (:data event)
             {:id (:aggregate-id event)
              :status :imported
              :version 1}))

    :transaction-classified
    (update state (:aggregate-id event)
      (fn [tx]
        (merge tx (:data event)
               {:status :classified
                :version (inc (:version tx))})))

    :transaction-verified
    (update state (:aggregate-id event)
      (fn [tx]
        (merge tx (:data event)
               {:status :verified
                :version (inc (:version tx))})))

    ;; ... handle all other event types

    ;; Default: unknown event, return state unchanged
    state))

(defn project-events
  "Project all events to final state."
  [events]
  (reduce apply-event {} events))
```

---

## 11. Event Store Schema

### SQLite Table

```sql
CREATE TABLE events (
  -- EVENT IDENTITY
  event_id TEXT PRIMARY KEY,
  event_type TEXT NOT NULL,
  sequence_number INTEGER UNIQUE NOT NULL AUTOINCREMENT,

  -- AGGREGATE
  aggregate_id TEXT NOT NULL,
  aggregate_type TEXT NOT NULL,

  -- TIME
  timestamp TEXT NOT NULL,

  -- AUTHOR
  author TEXT NOT NULL,
  source TEXT NOT NULL,

  -- DATA (JSON)
  data TEXT NOT NULL,

  -- METADATA (JSON)
  metadata TEXT,

  -- INDEXES
  INDEX idx_aggregate (aggregate_type, aggregate_id),
  INDEX idx_type (event_type),
  INDEX idx_timestamp (timestamp)
);
```

---

## 12. Event Versioning

### When Event Schema Changes

**Example:** Add new field to TransactionImported

**Version 1:**
```clojure
{:event-type :transaction-imported
 :data {:date "..." :amount 45.99 :merchant "..."}}
```

**Version 2:**
```clojure
{:event-type :transaction-imported
 :event-version 2  ; New field!
 :data {:date "..."
        :amount 45.99
        :merchant "..."
        :currency "USD"}}  ; New field!
```

**Handling old events:**
```clojure
(defn upgrade-event-v1-to-v2 [event]
  (if (nil? (:event-version event))
    ;; v1 event, upgrade
    (assoc event
      :event-version 2
      :data (assoc (:data event)
              :currency "USD"))  ; Default value
    ;; Already v2+, return as-is
    event))

(defn apply-event [state event]
  (let [event' (upgrade-event-v1-to-v2 event)]
    ;; Now process upgraded event
    ...))
```

---

## 13. Event Sourcing Commands

### Commands vs Events

**Command** = Intent (may fail)
**Event** = Fact (already happened)

```clojure
;; COMMAND (may fail)
{:command-type :import-transaction
 :data {:date "2025-03-20"
        :amount -45.99  ; NEGATIVE! Invalid!
        :merchant "Starbucks"}}

;; Result: Validation error, NO event emitted

;; EVENT (validated, happened)
{:event-type :transaction-imported
 :data {:date "2025-03-20"
        :amount 45.99  ; Corrected to positive
        :merchant "Starbucks"}}
```

### Command Handler Pattern

```clojure
(defn handle-import-transaction-command
  [command]
  (let [data (:data command)]
    ;; VALIDATION
    (cond
      (not (valid-date? (:date data)))
      {:status :error :reason "Invalid date"}

      (not (pos? (:amount data)))
      {:status :error :reason "Amount must be positive"}

      :else
      ;; VALID: Emit event
      {:status :success
       :event {:event-type :transaction-imported
               :data (normalize-data data)}})))
```

---

## 14. Event Replay & Time Travel

### Replay All Events

```clojure
(defn rebuild-state
  "Rebuild entire state from events."
  [event-store]
  (let [all-events (get-all-events event-store)]
    (project-events all-events)))
```

### Time Travel to Specific Date

```clojure
(defn state-at-time
  "Get state as it was at specific timestamp."
  [event-store timestamp]
  (let [events-up-to (filter #(<= (:timestamp %) timestamp)
                            (get-all-events event-store))]
    (project-events events-up-to)))

;; Example: What was transaction tx-001 on March 1?
(def state-march-1
  (state-at-time event-store "2025-03-01T00:00:00.000Z"))

(get state-march-1 "tx-001")
;; => {:date "2025-03-20"
;;     :amount 45.99
;;     :merchant "STARBUCKS #5678"  ; Not normalized yet! (classification happened later)
;;     :status :imported}
```

---

## 15. Key Design Decisions

### Decision 1: Granular vs Coarse Events?

**Coarse (fewer events):**
```clojure
{:event-type :transaction-updated
 :changes {:merchant {:old "..." :new "..."}
           :category {:old "..." :new "..."}}}
```

**Granular (more events):**
```clojure
{:event-type :transaction-merchant-corrected ...}
{:event-type :transaction-classified ...}
```

**Choice:** Granular (semantic, easier to understand history)

---

### Decision 2: Store Entire Entity vs Delta?

**Entire Entity:**
```clojure
{:event-type :bank-updated
 :data {:name "Bank of America"  ; Entire state
        :website "https://..."
        :aliases [...]}}
```

**Delta Only:**
```clojure
{:event-type :bank-updated
 :data {:website {:old "http://..." :new "https://..."}}}  ; Only changed field
```

**Choice:** Delta (saves space, shows what changed, but need to track full state in projections)

---

### Decision 3: Event IDs: Sequential vs UUID?

**Sequential:**
```clojure
{:event-id 12345}  ; Simple int
```

**UUID:**
```clojure
{:event-id "evt-550e8400-..."}
```

**Choice:** UUID (distributed-friendly, can generate offline, globally unique)
But ALSO sequence-number for ordering guarantees.

---

**Total Events Defined:** 26 events across 8 aggregate types

**Next:** Create implementation guide
