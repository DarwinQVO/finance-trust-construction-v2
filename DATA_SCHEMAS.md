# Trust Construction - Data Schemas

**Date:** 2025-11-07
**Status:** DESIGN
**Purpose:** Canonical data structures before implementation

---

## 1. Core Data Principles

### Rich Hickey's Design Philosophy

```
"It is better to have 100 functions operate on one data structure
 than 10 functions on 10 data structures."
                                        — Alan Perlis (Rich quotes often)

Translation:
- Use maps, vectors, sets (Clojure's core data structures)
- Avoid custom types/classes
- Data should be generic and composable
```

### Our Schema Design Rules

1. **Maps for everything** (not records, not types)
2. **Keywords for keys** (namespaced where needed)
3. **Nil is acceptable** (optional fields can be nil)
4. **Specs for validation** (not structure enforcement)
5. **Extensible** (can add fields without breaking)

---

## 2. Entity Schema (Base for All Entities)

### Purpose
Every entity (Transaction, Bank, Merchant, etc.) has this base structure

### Schema

```clojure
{;; IDENTITY (never changes)
 :id "550e8400-e29b-41d4-a716-446655440000"  ; UUID v4
 :entity-type :transaction  ; or :bank, :merchant, :category, :account

 ;; TIME (temporal dimension)
 :valid-from "2025-11-07T14:30:00.000Z"  ; ISO 8601
 :valid-to nil  ; nil = current, or ISO 8601 if expired

 ;; VERSION
 :version 1  ; Starts at 1, increments on update

 ;; PROVENANCE
 :provenance {:source-file "bofa_march_2024.csv"
              :source-line 23
              :extracted-at "2025-11-07T14:30:00.000Z"
              :extracted-by "darwin"
              :parser-version "1.2.0"
              :transformation-chain ["parse-csv" "normalize-bofa" "classify-merchant"]}

 ;; AUDIT TRAIL
 :created-at "2025-11-07T14:30:00.000Z"
 :created-by "darwin"
 :updated-at "2025-11-07T15:00:00.000Z"  ; nil if never updated
 :updated-by "darwin"  ; nil if never updated

 ;; DATA (entity-specific, see below)
 :data {...}}
```

### Why This Structure?

**Identity (:id):**
- UUID ensures global uniqueness
- Can merge data from multiple sources
- No collisions ever

**Time (:valid-from, :valid-to):**
- Enables time-travel queries
- "What was this transaction's category on March 1?"
- Bi-temporal: valid-time (business) + transaction-time (database)

**Version (:version):**
- Simple integer counter
- Easy to compare (v2 > v1)
- Helps detect conflicts

**Provenance:**
- Debugging: "Where did this value come from?"
- Auditing: "Who classified this?"
- Trust: "Can I trust this data?"

---

## 3. Transaction Schema

### Purpose
Core financial transaction entity

### Schema

```clojure
{:id "tx-550e8400-..."
 :entity-type :transaction
 :valid-from "2025-03-20T00:00:00.000Z"
 :valid-to nil
 :version 1

 :provenance {:source-file "bofa_march_2024.csv"
              :source-line 23
              :extracted-at "2025-03-20T10:30:00.000Z"
              :extracted-by "darwin"
              :parser-version "bofa-parser-v1.2"}

 :created-at "2025-03-20T10:30:00.000Z"
 :created-by "darwin"

 :data {;; CORE FIELDS (required)
        :date "2025-03-20"  ; Date only (no time)
        :amount 45.99  ; Always positive (type indicates direction)
        :merchant "Starbucks"  ; Normalized name
        :bank "BofA"  ; Normalized name
        :type :expense  ; :expense | :income | :credit-payment | :transfer

        ;; CLASSIFICATION (enriched)
        :category :restaurant  ; From rules
        :confidence 0.95  ; 0.0-1.0
        :classification-rule :merchant-pattern-15  ; Which rule matched
        :classification-timestamp "2025-03-20T10:31:00.000Z"

        ;; OPTIONAL FIELDS
        :description "STARBUCKS #5678 SEATTLE WA"  ; Raw from bank
        :account-name "Checking"
        :account-number "****1234"
        :currency "USD"
        :notes nil  ; User notes

        ;; DEDUPLICATION
        :idempotency-hash "a3f8bc..."  ; SHA-256 of core fields
        :duplicate-of nil  ; UUID if this is a duplicate
        :duplicate-reason nil  ; Why marked as duplicate

        ;; RECONCILIATION
        :reconciled false
        :reconciled-at nil
        :reconciled-by nil

        ;; VERIFICATION
        :verified false
        :verified-at nil
        :verified-by nil}}
```

### Field Details

**:type values:**
```clojure
:expense           ; Money going out (most common)
:income           ; Money coming in (salary, refunds)
:credit-payment   ; Paying credit card bill
:transfer         ; Moving money between own accounts
```

**:category values (examples):**
```clojure
:restaurant
:groceries
:transportation
:utilities
:entertainment
:healthcare
:shopping
:housing
:insurance
:education
:uncategorized  ; Default if can't classify
```

**:confidence ranges:**
```clojure
0.95-1.00  ; High confidence (auto-classified, known merchant)
0.70-0.94  ; Medium confidence (pattern match, some ambiguity)
0.50-0.69  ; Low confidence (guess, needs review)
0.00-0.49  ; Very low confidence (unknown merchant, manual review required)
```

---

## 4. Bank Schema

### Purpose
Bank entity (BofA, AppleCard, etc.)

### Schema

```clojure
{:id "bank-550e8400-..."
 :entity-type :bank
 :valid-from "2025-01-01T00:00:00.000Z"
 :valid-to nil
 :version 1

 :provenance {:source "manual-entry"
              :created-by "system-init"}

 :data {;; IDENTITY
        :name "Bank of America"  ; Official name
        :short-name "BofA"  ; Display name
        :aliases ["bofa" "boa" "bank of america"]  ; For matching

        ;; CLASSIFICATION
        :type :checking  ; :checking | :savings | :credit-card | :investment
        :currency "USD"

        ;; METADATA
        :website "https://www.bankofamerica.com"
        :routing-number "026009593"  ; Optional
        :active true}}
```

---

## 5. Merchant Schema

### Purpose
Merchant entity (Starbucks, Amazon, etc.)

### Schema

```clojure
{:id "merchant-550e8400-..."
 :entity-type :merchant
 :valid-from "2025-01-01T00:00:00.000Z"
 :valid-to nil
 :version 1

 :data {;; IDENTITY
        :name "Starbucks"  ; Canonical name
        :aliases ["STARBUCKS*" "SBUX" "Starbucks Coffee"]  ; Variations

        ;; CLASSIFICATION
        :default-category :restaurant  ; Most common category
        :confidence 0.98  ; How sure are we?

        ;; METADATA
        :website "https://www.starbucks.com"
        :industry "Food & Beverage"
        :active true

        ;; STATISTICS (derived, could be in projection)
        :transaction-count 47
        :total-spent 523.11
        :first-seen "2024-01-15"
        :last-seen "2025-03-20"}}
```

---

## 6. Category Schema

### Purpose
Transaction category (restaurant, groceries, etc.)

### Schema

```clojure
{:id "category-550e8400-..."
 :entity-type :category
 :valid-from "2025-01-01T00:00:00.000Z"
 :valid-to nil
 :version 1

 :data {;; IDENTITY
        :name :restaurant  ; Keyword (for code use)
        :display-name "Restaurants & Dining"  ; Human-readable

        ;; HIERARCHY (optional)
        :parent :food  ; Parent category (for grouping)
        :children [:fast-food :fine-dining :cafe]  ; Sub-categories

        ;; RULES
        :patterns [#"STARBUCKS.*" #"MCDONALD.*" #"RESTAURANT.*"]
        :keywords ["cafe" "restaurant" "dining" "food"]

        ;; METADATA
        :icon "🍽️"
        :color "#FF6B6B"
        :active true
        :budget-monthly 500.00  ; Optional budget}}
```

---

## 7. Account Schema

### Purpose
Bank account (checking, savings, credit card)

### Schema

```clojure
{:id "account-550e8400-..."
 :entity-type :account
 :valid-from "2025-01-01T00:00:00.000Z"
 :valid-to nil
 :version 1

 :data {;; IDENTITY
        :name "BofA Checking"
        :account-number "****1234"  ; Last 4 digits only
        :bank-id "bank-550e8400-..."  ; References bank entity

        ;; TYPE
        :type :checking  ; :checking | :savings | :credit-card
        :currency "USD"

        ;; BALANCE (snapshot, not definitive)
        :balance 5432.10
        :balance-as-of "2025-03-20"

        ;; METADATA
        :opened-date "2020-01-15"
        :closed-date nil  ; nil = active
        :active true}}
```

---

## 8. Event Schema

### Purpose
Events for event sourcing (primary data representation)

### Schema

```clojure
{;; EVENT IDENTITY
 :event-id "evt-550e8400-..."  ; UUID
 :event-type :transaction-imported  ; See event types below
 :sequence-number 12345  ; Auto-increment

 ;; AGGREGATE (what entity this affects)
 :aggregate-id "tx-550e8400-..."
 :aggregate-type :transaction

 ;; TIME
 :timestamp "2025-03-20T10:30:00.000Z"

 ;; AUTHOR
 :author "darwin"
 :source "import-script"

 ;; EVENT DATA (event-specific)
 :data {:amount 45.99
        :merchant "Starbucks"
        :bank "BofA"
        ;; ... event-specific fields}

 ;; METADATA
 :metadata {:source-file "bofa_march_2024.csv"
            :correlation-id "batch-abc123"  ; Links related events
            :causation-id "evt-previous-..."}}  ; What caused this event
```

### Event Types

**Transaction Events:**
```clojure
:transaction-imported       ; New transaction added
:transaction-classified     ; Category assigned
:transaction-verified       ; User verified
:transaction-flagged        ; Marked for review
:transaction-reconciled     ; Matched with bank statement
:transaction-note-added     ; User added note
:transaction-amount-corrected  ; Amount fixed
:transaction-merchant-corrected  ; Merchant name fixed
```

**Entity Events:**
```clojure
:bank-created
:bank-updated
:bank-deleted  ; Soft delete (mark inactive)

:merchant-created
:merchant-updated
:merchant-merged  ; Two merchants merged into one

:category-created
:category-updated

:account-created
:account-updated
:account-closed
```

**Deduplication Events:**
```clojure
:duplicate-detected     ; System detected potential duplicate
:duplicate-confirmed    ; User confirmed it's a duplicate
:duplicate-rejected     ; User said it's NOT a duplicate
```

**Reconciliation Events:**
```clojure
:reconciliation-started    ; Begin reconciling a period
:reconciliation-completed  ; All transactions matched
:discrepancy-found        ; Amount doesn't match
```

---

## 9. Rule Schema

### Purpose
Classification/deduplication rules (stored in EDN files)

### Schema

```clojure
{;; RULE IDENTITY
 :id :merchant-pattern-starbucks  ; Unique keyword
 :version "1.0.0"  ; Semantic version
 :enabled true  ; Can disable without deleting

 ;; RULE TYPE
 :type :classification  ; or :deduplication, :validation

 ;; CLASSIFICATION RULE FIELDS
 :pattern #"STARBUCKS.*"  ; Regex or string
 :merchant :starbucks  ; Target merchant
 :category :restaurant  ; Target category
 :confidence 0.95  ; How confident is this rule?

 ;; PRIORITY (if multiple rules match)
 :priority 100  ; Higher = applied first

 ;; METADATA
 :description "Match Starbucks transactions"
 :created-by "darwin"
 :created-at "2025-01-15"
 :tested true  ; Has this been tested?
 :test-count 47  ; How many txs does it match?}
```

### Deduplication Rule Schema

```clojure
{:id :exact-match-all-fields
 :version "1.0.0"
 :enabled true

 :type :deduplication
 :strategy :exact-match  ; :exact-match | :fuzzy-match | :time-window

 ;; MATCHING FIELDS
 :fields [:date :amount :merchant :bank]

 ;; TIME WINDOW (if :time-window strategy)
 :time-window {:amount 3 :unit :days}

 ;; CONFIDENCE
 :confidence 1.0  ; Exact match = 100% sure

 :priority 100
 :description "Exact match on all core fields"}
```

---

## 10. Query Schema

### Purpose
Standardized query format for Store protocol

### Schema

```clojure
{;; WHAT TO QUERY
 :entity-type :transaction  ; Required

 ;; FILTERS
 :filters {:bank "BofA"  ; Exact match
           :amount [:> 100]  ; Comparison [:op value]
           :date [:between "2025-01-01" "2025-03-31"]
           :merchant [:in ["Starbucks" "Amazon"]]
           :category [:not :uncategorized]}

 ;; TIME TRAVEL
 :as-of-time "2025-03-01T00:00:00.000Z"  ; Optional

 ;; PAGINATION
 :limit 100
 :offset 0

 ;; SORTING
 :order-by [:timestamp :desc]  ; or [:amount :asc]

 ;; WHAT TO RETURN
 :fields [:id :date :amount :merchant]  ; Default = all fields
 :include-expired false}  ; Include old versions?
```

### Query Operators

```clojure
;; COMPARISON
[:= value]
[:> value]
[:>= value]
[:< value]
[:<= value]
[:!= value]

;; RANGE
[:between low high]

;; SET
[:in [value1 value2 ...]]
[:not-in [value1 value2 ...]]

;; NEGATION
[:not value]

;; NULL CHECK
[:nil]
[:not-nil]

;; STRING
[:contains "substring"]
[:starts-with "prefix"]
[:ends-with "suffix"]
[:matches #"regex"]
```

---

## 11. Validation Result Schema

### Purpose
Result from Validator protocol

### Schema

```clojure
{;; OVERALL
 :valid? true  ; or false
 :score 0.95  ; 0.0-1.0

 ;; INDIVIDUAL RESULTS
 :results [{:rule-id :amount-positive
            :status :pass  ; :pass | :fail | :warning
            :severity :error  ; :info | :warning | :error
            :field :amount
            :message "Amount must be positive"
            :actual 45.99
            :expected [:> 0]
            :timestamp "2025-03-20T10:30:00.000Z"}]

 ;; SUMMARY
 :stats {:total-rules 10
         :passed 9
         :failed 0
         :warnings 1}

 ;; METADATA
 :validated-at "2025-03-20T10:30:00.000Z"
 :validator-version "1.0.0"}
```

---

## 12. Transformation Result Schema

### Purpose
Result from Transformer protocol

### Schema

```clojure
{;; STATUS
 :status :success  ; :success | :partial | :failure

 ;; DATA
 :input {:raw-merchant "STARBUCKS #5678"}  ; Original
 :output {:merchant "Starbucks"}  ; Transformed

 ;; STEPS
 :transformations [{:step :normalize-merchant
                    :before "STARBUCKS #5678"
                    :after "Starbucks"
                    :confidence 0.98
                    :duration-ms 2}]

 ;; METADATA
 :metadata {:transformer-version "1.0.0"
            :total-duration-ms 5
            :rules-applied [:merchant-normalization]
            :timestamp "2025-03-20T10:30:00.000Z"}}
```

---

## 13. Schema Validation (Using clojure.spec)

### Example Specs

```clojure
(require '[clojure.spec.alpha :as s])

;; Transaction spec
(s/def ::id uuid?)
(s/def ::entity-type keyword?)
(s/def ::date string?)  ; ISO date
(s/def ::amount pos?)  ; Positive number
(s/def ::merchant string?)
(s/def ::bank string?)
(s/def ::type #{:expense :income :credit-payment :transfer})
(s/def ::confidence (s/and number? #(<= 0 % 1)))

(s/def ::transaction-data
  (s/keys :req-un [::date ::amount ::merchant ::bank ::type]
          :opt-un [::category ::confidence ::description]))

(s/def ::transaction
  (s/keys :req-un [::id ::entity-type ::data]
          :opt-un [::valid-from ::valid-to ::version]))

;; Usage
(s/valid? ::transaction
  {:id #uuid "550e8400-e29b-41d4-a716-446655440000"
   :entity-type :transaction
   :data {:date "2025-03-20"
          :amount 45.99
          :merchant "Starbucks"
          :bank "BofA"
          :type :expense}})
;; => true

(s/explain ::transaction
  {:id "not-a-uuid"  ; Wrong!
   :entity-type :transaction
   :data {:date "2025-03-20"
          :amount -45.99  ; Wrong! (negative)
          :merchant "Starbucks"
          :bank "BofA"
          :type :expense}})
;; => Shows validation errors
```

---

## 14. Schema Migration Strategy

### When Schema Changes

**Example:** Add new field `:subcategory` to transactions

**Process:**

1. **Add field as optional** (backwards compatible)
```clojure
(s/def ::transaction-data
  (s/keys :req-un [::date ::amount ::merchant]
          :opt-un [::category ::subcategory]))  ; New field optional
```

2. **Write migration function**
```clojure
(defn migrate-add-subcategory [tx]
  (if (nil? (get-in tx [:data :subcategory]))
    (assoc-in tx [:data :subcategory] :none)  ; Default value
    tx))
```

3. **Run migration** (can be lazy)
```clojure
(def transactions (load-all-transactions))
(def migrated (map migrate-add-subcategory transactions))
(save-all-transactions! migrated)
```

4. **Eventually make required** (breaking change, plan carefully)

---

## 15. Schema Documentation Template

### For Each Schema

```markdown
## [Schema Name]

**Purpose:** One-sentence description

**Required Fields:**
- `:field1` - Description
- `:field2` - Description

**Optional Fields:**
- `:field3` - Description

**Example:**
```clojure
{:field1 "value"
 :field2 42}
```

**Validation:**
```clojure
(s/def ::schema-name ...)
```

**Used By:**
- Function X
- Protocol Y

**Related Schemas:**
- Link to related schema
```

---

## 16. Key Design Decisions

### Decision 1: Maps vs Records?

**Records:**
```clojure
(defrecord Transaction [id date amount merchant])
```
- Pros: Type safety, faster
- Cons: Less flexible, harder to extend

**Maps:**
```clojure
{:id "..." :date "..." :amount 45.99 :merchant "..."}
```
- Pros: Flexible, extensible, works everywhere
- Cons: No compile-time checks

**Choice:** Maps (Rich Hickey: "It is better to have 100 functions operate on one data structure")

---

### Decision 2: Strings vs Keywords for IDs?

**String IDs:**
```clojure
{:id "550e8400-e29b-41d4-a716-446655440000"}
```

**Keyword IDs:**
```clojure
{:id :550e8400-e29b-41d4-a716-446655440000}  ; Not valid!
```

**UUID Type:**
```clojure
{:id #uuid "550e8400-e29b-41d4-a716-446655440000"}
```

**Choice:** Strings (interoperable, easy to serialize, works in EDN/JSON)

---

### Decision 3: Timestamps: Strings vs Instants?

**String (ISO 8601):**
```clojure
{:timestamp "2025-03-20T10:30:00.000Z"}
```
- Pros: Human-readable, JSON-friendly, no timezone confusion (always UTC)
- Cons: Parsing overhead

**Instant:**
```clojure
{:timestamp #inst "2025-03-20T10:30:00.000Z"}
```
- Pros: Native Clojure type, can use comparisons
- Cons: Not JSON-serializable directly

**Choice:** Strings (interoperability > convenience, can parse on read)

---

## 17. Schema Evolution Principles

1. **Always backwards compatible** (old code reads new data)
2. **Optional by default** (required fields rarely added)
3. **Defaults exist** (missing fields have sensible defaults)
4. **Migration functions** (upgrade old data to new schema)
5. **Version everything** (know what schema version you have)

---

**Next:** Create event catalog specification
