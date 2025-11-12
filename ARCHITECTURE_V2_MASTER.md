# Trust Construction System - Architecture V2 (Master Design)

**Created:** 2025-11-07
**Status:** Design Phase (Before Implementation)
**Philosophy:** Rich Hickey principles applied from day 1

---

## 🎯 Vision

**Separate the REUSABLE from the SPECIFIC**

```
trust-construction/        # Generic, reusable for ANY domain
    ├── core/             # Pure data transformations
    ├── persistence/      # Storage abstractions
    ├── validation/       # Generic validation rules
    └── versioning/       # Version control for data

finance/                   # Finance-specific application
    ├── domain/           # Finance concepts (transactions, accounts)
    ├── rules/            # Finance business rules (as data)
    ├── transformations/  # Finance-specific transforms
    └── ui/               # Finance-specific views
```

**Key Insight:** `trust-construction` should work for:
- Finance (our use case)
- Healthcare records
- Legal documents
- Scientific data
- Any domain needing trusted data

---

## 📐 Architecture Principles (Rich Hickey)

### 1. Data > Mechanism

**Before (wrong):**
```clojure
;; Hardcoded logic
(defn classify-transaction [tx]
  (if (str/includes? (:merchant tx) "STARBUCKS")
    (assoc tx :category :cafe)
    tx))
```

**After (correct):**
```clojure
;; Rules are data
(def rules
  [{:pattern #"STARBUCKS" :category :cafe :confidence 0.95}])

;; Generic engine
(defn classify [tx rules]
  (apply-rules tx rules))
```

### 2. Simplicity > Ease

**Simple:** One concern per component
```clojure
;; Simple: just validates
(defn validate-transaction [tx schema])

;; Simple: just transforms
(defn transform-transaction [tx rules])

;; Simple: just persists
(defn persist-transaction [tx store])
```

**Complex (wrong):**
```clojure
;; Does everything!
(defn process-transaction [tx]
  (-> tx
      validate
      transform
      classify
      deduplicate
      persist))
```

### 3. Decomplecting

**Complected (wrong):**
```clojure
;; Schema + selection + validation mixed
{:transaction/amount {:type :number
                      :required true
                      :min 0.01
                      :for-contexts [:ui :audit]}}
```

**Decomplected (correct):**
```clojure
;; Separate concerns
{:attribute {:name :transaction/amount
             :type :number}}

{:validation {:attribute :transaction/amount
              :rules [{:type :required}
                      {:type :min-value :value 0.01}]}}

{:context {:name :ui
           :attributes [:transaction/amount :transaction/date]}}
```

### 4. Process vs Perception

**Process (writes):**
```clojure
;; Append-only, immutable
(defn append-event! [store event]
  (conj! store event))
```

**Perception (reads):**
```clojure
;; Derived, query-time
(defn current-balance [events account-id]
  (reduce + (filter-by-account events account-id)))
```

### 5. Time as Explicit Dimension

**Wrong:**
```clojure
;; Time hidden
{:id "tx-123"
 :amount 100
 :category :shopping}  ; Lost: when classified, by whom
```

**Correct:**
```clojure
;; Time explicit
{:id "tx-123"
 :amount 100
 :events [{:type :classified
           :category :shopping
           :by "system"
           :at #inst "2025-11-07T15:00:00Z"}
          {:type :verified
           :by "darwin"
           :at #inst "2025-11-07T16:00:00Z"}]}
```

---

## 🏗️ Trust Construction (Generic Core)

### Purpose

**Build trust in untrusted data through:**
1. **Provenance** - Know where data came from
2. **Versioning** - Track all changes
3. **Validation** - Enforce quality rules
4. **Audit** - Record all decisions
5. **Confidence** - Quantify certainty

**Domain-agnostic!** Works for finance, healthcare, legal, etc.

---

### Core Modules

#### 1. `trust-construction.core`

**Pure data transformations (transducers)**

```clojure
(ns trust-construction.core)

;; Generic transformations
(defn parse-field-xf [field parser-fn])
(defn validate-field-xf [field validator-fn])
(defn enrich-field-xf [field enricher-fn])
(defn filter-errors-xf [])
(defn add-provenance-xf [metadata])
(defn compute-hash-xf [fields hash-fn])
```

**Key:** No finance concepts here!

---

#### 2. `trust-construction.persistence`

**Storage abstractions**

```clojure
(ns trust-construction.persistence)

;; Protocol (interface)
(defprotocol Store
  (append! [store data])
  (query [store query-spec])
  (get-by-id [store id]))

;; Implementations
(defrecord SQLiteStore [db-path]
  Store
  (append! [_ data] ...)
  (query [_ spec] ...)
  (get-by-id [_ id] ...))

(defrecord FileStore [base-dir]
  Store
  (append! [_ data] ...)
  (query [_ spec] ...)
  (get-by-id [_ id] ...))
```

**Key:** Store is pluggable!

---

#### 3. `trust-construction.validation`

**Generic validation rules**

```clojure
(ns trust-construction.validation)

;; Rule types (data!)
{:id :required-field
 :type :required
 :field :any}

{:id :min-value
 :type :comparison
 :operator :>=
 :field :any
 :value :any}

{:id :pattern-match
 :type :regex
 :field :string
 :pattern :regex}

;; Generic validator
(defn validate [data rules]
  {:valid? boolean
   :errors [{:rule :rule-id
             :field :field-name
             :message "..."}]})
```

**Key:** Rules are data, validator is generic!

---

#### 4. `trust-construction.versioning`

**Generic versioning (already built!)**

```clojure
(ns trust-construction.versioning)

;; Domain-agnostic
(defn save-version! [type data metadata])
(defn load-version [type timestamp])
(defn list-versions [type])
(defn compare-versions [type v1 v2])
(defn rollback! [type timestamp metadata])
```

**Status:** ✅ Already implemented in Badge 30!

---

#### 5. `trust-construction.events`

**Event sourcing foundation**

```clojure
(ns trust-construction.events)

;; Event store (append-only)
(defprotocol EventStore
  (append-event! [store event])
  (get-events [store entity-id])
  (get-events-since [store timestamp]))

;; Generic event structure
{:event/id "evt-123"
 :event/type :entity-updated
 :event/entity-id "tx-456"
 :event/timestamp #inst "2025-11-07"
 :event/actor "darwin"
 :event/reason "Fixed amount"
 :event/data {:before {...} :after {...}}}

;; Projection (events → state)
(defn project [events]
  (reduce apply-event {} events))
```

---

#### 6. `trust-construction.confidence`

**Confidence scoring**

```clojure
(ns trust-construction.confidence)

;; Generic confidence calculation
(defn compute-confidence [data rules]
  {:confidence 0.0-1.0
   :factors [{:rule :rule-id
              :weight 0.0-1.0
              :matched? boolean}]})

;; Thresholds (data!)
{:high   {:min 0.90}
 :medium {:min 0.70 :max 0.89}
 :low    {:min 0.50 :max 0.69}
 :unacceptable {:max 0.49}}
```

---

## 💰 Finance Application (Specific)

### Purpose

**Apply trust-construction to financial data**

---

### Finance Modules

#### 1. `finance.domain`

**Finance concepts**

```clojure
(ns finance.domain.transaction)

;; Domain model (data!)
{:transaction/id "tx-123"
 :transaction/date #inst "2025-11-07"
 :transaction/amount 45.99
 :transaction/type :expense
 :transaction/merchant "Starbucks"
 :transaction/category :restaurants
 :transaction/account "Chase-1234"}

(ns finance.domain.account)

{:account/id "acc-456"
 :account/name "Chase Checking"
 :account/type :checking
 :account/institution :chase
 :account/currency :USD}

(ns finance.domain.bank)

{:bank/id :chase
 :bank/name "JP Morgan Chase"
 :bank/routing "021000021"}
```

**Key:** These are FINANCE concepts, not generic!

---

#### 2. `finance.rules`

**Finance business rules (as data!)**

```clojure
;; Merchant classification
(def merchant-rules
  [{:id :starbucks
    :pattern #"STARBUCKS.*"
    :category :restaurants
    :confidence 0.95}
   {:id :amazon
    :pattern #"AMAZON.*"
    :category :shopping
    :confidence 0.90}])

;; Deduplication
(def deduplication-rules
  [{:id :exact-match
    :strategy :exact
    :fields [:date :amount :merchant]
    :confidence 1.0}
   {:id :fuzzy-merchant
    :strategy :fuzzy
    :fields [:date :amount]
    :fuzzy {:merchant {:tolerance 3}}
    :confidence 0.85}])

;; Validation
(def transaction-validation-rules
  [{:id :positive-amount
    :field :amount
    :type :min-value
    :value 0.01}
   {:id :valid-date
    :field :date
    :type :date-range
    :min #inst "2020-01-01"
    :max :now}])
```

**Key:** Rules are DATA in EDN files!

---

#### 3. `finance.transformations`

**Finance-specific transformations**

```clojure
(ns finance.transformations.import)

;; Uses trust-construction.core transducers
(defn csv-import-pipeline [source-file]
  (comp
    ;; Generic (from trust-construction)
    (tc/parse-field-xf :date parse-date)
    (tc/parse-field-xf :amount parse-amount)
    (tc/validate-field-xf :amount positive?)
    (tc/add-provenance-xf {:source source-file})
    (tc/compute-hash-xf [:date :amount :merchant] sha256)

    ;; Finance-specific
    (normalize-merchant-xf)
    (classify-xf merchant-rules)
    (detect-duplicates-xf deduplication-rules)))
```

---

#### 4. `finance.persistence`

**Finance storage (uses trust-construction)**

```clojure
(ns finance.persistence.store)

;; Use generic store
(def transaction-store
  (tc.persistence/->SQLiteStore "transactions.db"))

;; Finance-specific queries
(defn get-transactions-by-category [category]
  (tc.persistence/query transaction-store
    {:where {:category category}
     :order-by [:date :desc]}))

(defn get-balance [account-id]
  (let [txs (tc.persistence/query transaction-store
              {:where {:account account-id}})]
    (reduce + (map :amount txs))))
```

---

## 📂 Directory Structure

```
finance-clj/
├── src/
│   ├── trust_construction/          # GENERIC (reusable)
│   │   ├── core.clj                # Transducers
│   │   ├── persistence.clj         # Storage protocols
│   │   ├── validation.clj          # Generic validation
│   │   ├── versioning.clj          # Version control ✅
│   │   ├── events.clj              # Event sourcing
│   │   └── confidence.clj          # Confidence scoring
│   │
│   └── finance/                     # SPECIFIC (finance)
│       ├── domain/
│       │   ├── transaction.clj
│       │   ├── account.clj
│       │   ├── bank.clj
│       │   └── merchant.clj
│       │
│       ├── transformations/
│       │   ├── import.clj          # CSV/JSON import
│       │   ├── classification.clj  # Classify transactions
│       │   └── reconciliation.clj  # Balance reconciliation
│       │
│       ├── persistence/
│       │   └── store.clj           # Finance queries
│       │
│       └── api/
│           └── handlers.clj        # REST API
│
├── resources/
│   ├── trust_construction/          # Generic configs
│   │   └── schemas/
│   │       └── base.cue
│   │
│   └── finance/                     # Finance rules
│       └── rules/
│           ├── merchant-rules.edn
│           ├── deduplication-rules.edn
│           └── validation-rules.edn
│
└── test/
    ├── trust_construction/
    └── finance/
```

---

## 🔄 Data Flow

### Import Pipeline

```
CSV File
  ↓
[trust-construction.core]
  → parse-field-xf (generic)
  → validate-field-xf (generic)
  → add-provenance-xf (generic)
  ↓
[finance.transformations]
  → normalize-merchant-xf (finance)
  → classify-xf (finance rules)
  → detect-duplicates-xf (finance rules)
  ↓
[trust-construction.events]
  → append-event! (generic)
  ↓
[trust-construction.persistence]
  → append! (generic store)
  ↓
[finance.persistence]
  → query transactions (finance query)
```

**Key:** Generic layers WRAP finance-specific logic!

---

## 🎨 Interfaces (Protocols)

### 1. Store Protocol

```clojure
(defprotocol Store
  "Generic storage interface"
  (append! [store data] "Add new data")
  (query [store spec] "Query data")
  (get-by-id [store id] "Get by ID")
  (get-versions [store entity-id] "Get all versions"))
```

**Implementations:**
- `SQLiteStore` - SQLite backend
- `FileStore` - File-based storage
- `MemoryStore` - In-memory (testing)

---

### 2. Parser Protocol

```clojure
(defprotocol Parser
  "Parse external data"
  (parse [parser input] "Parse input to domain model")
  (detect-format [parser input] "Auto-detect format"))
```

**Implementations:**
- `CSVParser` - Parse CSV files
- `JSONParser` - Parse JSON files
- `PDFParser` - Parse PDF statements

---

### 3. Validator Protocol

```clojure
(defprotocol Validator
  "Validate data against rules"
  (validate [validator data rules] "Check rules")
  (explain [validator result] "Explain validation"))
```

**Implementations:**
- `SchemaValidator` - Validate against schema
- `RuleValidator` - Validate against rules
- `ConfidenceValidator` - Check confidence thresholds

---

## 🧪 Testing Strategy

### Trust Construction Tests

```clojure
;; Generic tests (no finance)
(deftest test-parse-field-xf
  (testing "Parse any field with any parser"
    (let [parser (fn [s] (Integer/parseInt s))
          xf (tc/parse-field-xf :age parser)
          data [{:age "30"} {:age "invalid"}]]
      (is (= [{:age 30}]
             (into [] xf data))))))

(deftest test-store-protocol
  (testing "Store protocol works with any implementation"
    (doseq [store [(SQLiteStore. "test.db")
                   (FileStore. "/tmp")
                   (MemoryStore.)]]
      (tc.persistence/append! store {:id 1})
      (is (= {:id 1}
             (tc.persistence/get-by-id store 1))))))
```

### Finance Tests

```clojure
;; Finance-specific tests
(deftest test-merchant-classification
  (testing "Classify Starbucks transaction"
    (let [tx {:merchant "STARBUCKS #1234"}]
      (is (= :restaurants
             (:category (classify tx merchant-rules)))))))

(deftest test-balance-calculation
  (testing "Calculate account balance"
    (is (= 1000.00
           (get-balance "checking-001")))))
```

---

## 📊 Configuration

### Trust Construction Config

```clojure
;; trust-construction/config.edn
{:store {:type :sqlite
         :path "data/store.db"}

 :versioning {:enabled true
              :storage "data/versions/"}

 :events {:enabled true
          :store :sqlite}

 :validation {:strict-mode false
              :fail-on-error false}}
```

### Finance Config

```clojure
;; finance/config.edn
{:rules {:merchant "rules/merchant-rules.edn"
         :deduplication "rules/deduplication-rules.edn"
         :validation "rules/validation-rules.edn"}

 :import {:default-currency :USD
          :date-format "yyyy-MM-dd"
          :amount-precision 2}

 :api {:port 3000
       :host "localhost"}}
```

---

## 🎯 Implementation Order

### Phase 1: Trust Construction Core (2-3 weeks)

**Week 1:**
1. `trust-construction.core` - Transducers ✅ (DONE)
2. `trust-construction.validation` - Generic validation
3. `trust-construction.confidence` - Confidence scoring

**Week 2:**
4. `trust-construction.persistence` - Store protocols
5. `trust-construction.events` - Event sourcing
6. Tests for all above

**Week 3:**
7. `trust-construction.versioning` - ✅ (DONE in Badge 30)
8. Documentation
9. Example (non-finance) to prove generality

---

### Phase 2: Finance Application (2-3 weeks)

**Week 4:**
1. `finance.domain` - Domain models
2. `finance.rules` - Rules as data
3. `finance.transformations` - Finance transforms

**Week 5:**
4. `finance.persistence` - Finance queries
5. `finance.api` - REST API
6. Tests

**Week 6:**
7. Integration
8. E2E tests
9. Documentation

---

## 🚀 Migration Plan

### From Current Code

**What to keep:**
- ✅ Badge 30: Versioning system (already generic!)
- ✅ Badge 26: Event sourcing foundation
- ✅ Phase 1: Transducers namespace

**What to refactor:**
- ❌ `finance.classification` → Split into:
  - `trust-construction.validation` (generic)
  - `finance.rules.merchant` (finance)

- ❌ `finance.transducers` → Move to:
  - `trust-construction.core` (generic transforms)
  - `finance.transformations` (finance transforms)

- ❌ `finance.async-pipeline` → Move to:
  - `trust-construction.core` (generic async)

**New to build:**
- `trust-construction.persistence` (Store protocol)
- `trust-construction.confidence` (Confidence scoring)
- `finance.domain.*` (Domain models)

---

## 🎓 Design Decisions

### 1. Why Protocols instead of Multimethods?

**Protocols:**
- ✅ Faster (JVM optimized)
- ✅ Type-based dispatch
- ✅ Better for interfaces

**Multimethods:**
- For value-based dispatch
- More flexible, but slower

**Decision:** Protocols for Store, Parser, Validator

---

### 2. Why EDN instead of JSON for rules?

**EDN:**
- ✅ Native Clojure
- ✅ Supports regex `#"..."`
- ✅ Keywords `:keyword`
- ✅ Comments

**JSON:**
- Human-readable
- Language-agnostic

**Decision:** EDN for rules (internal), JSON for API

---

### 3. Why SQLite instead of Datomic?

**SQLite:**
- ✅ Simple, embedded
- ✅ No server needed
- ✅ ACID guarantees
- ✅ WAL mode for concurrency

**Datomic:**
- Better for distributed
- Time-travel built-in
- Complex setup

**Decision:** SQLite for Phase 1, Datomic later if needed

---

## 📈 Success Metrics

### Trust Construction

- ✅ Works with 3+ domains (finance, healthcare, legal)
- ✅ 0 domain-specific code in core
- ✅ 90%+ test coverage
- ✅ Pluggable storage (SQLite, File, Memory)

### Finance

- ✅ 100% rules as data (0 hardcoded logic)
- ✅ Import from 5+ sources (BofA, Chase, Stripe, etc.)
- ✅ 95%+ classification confidence
- ✅ < 1% duplicate transactions

---

## 🎉 Rich Hickey Alignment Goals

**Current:** 85%
**Target:** 95%

**Remaining gaps:**
1. ❌ Classification logic still some code (→ 100% data)
2. ❌ Validation partially hardcoded (→ validation DSL)
3. ❌ Confidence scoring manual (→ rule-based)

**After V2:**
1. ✅ Classification 100% data-driven
2. ✅ Validation 100% rule-based
3. ✅ Confidence from rule weights
4. ✅ Clear separation: generic vs finance

---

## 📝 Next Steps

### Immediate (Design Phase)

1. ✅ Read and approve this document
2. ⏳ Review with Rich Hickey principles
3. ⏳ Identify missing pieces
4. ⏳ Create detailed specs for each module

### Implementation Phase

1. Start with `trust-construction.persistence`
2. Build `trust-construction.validation`
3. Build `trust-construction.confidence`
4. Then start `finance.*` modules

---

**Created:** 2025-11-07
**Status:** DESIGN (Ready for review)
**Next:** Approve & start Phase 1

🎯 **"Design is about pulling things apart" - Rich Hickey**
