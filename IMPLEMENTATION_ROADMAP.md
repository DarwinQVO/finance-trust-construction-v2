# Trust Construction - Implementation Roadmap

**Date:** 2025-11-07
**Status:** DESIGN
**Purpose:** Step-by-step implementation plan with dependencies

---

## 1. Overview

### What We're Building

```
trust-construction/     (GENERIC - reusable for ANY domain)
├── protocols          (Interfaces)
├── core              (Pure functions)
├── persistence       (Storage implementations)
└── events            (Event sourcing)

finance/              (SPECIFIC - finance application)
├── domain            (Transaction, Bank, etc.)
├── transformations   (Import, classification)
├── persistence       (Finance-specific queries)
└── api               (REST endpoints, if needed)
```

### Implementation Strategy

**Bottom-Up** (protocols → implementations → finance app)

**NOT Top-Down** (avoid premature integration)

---

## 2. Phase Structure

Each phase:
- **Duration:** 3-5 days
- **Deliverables:** Working code + tests
- **Success Criteria:** All tests pass
- **Review Point:** Demo working functionality

---

## 3. PHASE 1: Core Protocols (3 days)

### Goal
Define and implement basic protocols with in-memory implementations

### Tasks

#### Day 1: Store Protocol

**Files to Create:**
```
src/trust_construction/
├── protocols/
│   └── store.clj           (Store protocol definition)
└── store/
    ├── memory.clj          (In-memory implementation)
    └── memory_test.clj     (Tests)
```

**Store Protocol:**
```clojure
(ns trust-construction.protocols.store)

(defprotocol Store
  (append! [store data metadata])
  (query [store spec])
  (get-by-id [store entity-type id])
  (get-versions [store entity-type id])
  (get-at-time [store entity-type id timestamp]))
```

**Memory Store Implementation:**
```clojure
(ns trust-construction.store.memory
  (:require [trust-construction.protocols.store :as store]))

(defrecord MemoryStore [data-atom]  ; atom of {entity-type {id [versions]}}
  store/Store
  (append! [this data metadata]
    ;; Implementation)
  (query [this spec]
    ;; Implementation)
  ...)

(defn create-memory-store []
  (->MemoryStore (atom {})))
```

**Tests (10 tests):**
```clojure
(deftest test-append
  (testing "Append creates new entity"
    (let [store (create-memory-store)
          result (store/append! store
                   {:name "Test"}
                   {:entity-type :test :author "darwin"})]
      (is (uuid? (:id result)))
      (is (= 1 (:version result))))))

(deftest test-query-filters
  (testing "Query with filters"
    ...))

(deftest test-get-by-id
  (testing "Get by ID returns latest version"
    ...))

(deftest test-time-travel
  (testing "Get entity as it was at past time"
    ...))
```

**Success Criteria:**
- ✅ Store protocol defined
- ✅ MemoryStore implements all methods
- ✅ 10 tests pass (100% coverage)
- ✅ Can store/retrieve entities
- ✅ Time-travel works

---

#### Day 2: Parser Protocol

**Files to Create:**
```
src/trust_construction/
├── protocols/
│   └── parser.clj          (Parser protocol definition)
└── parser/
    ├── csv.clj             (CSV parser implementation)
    └── parser_test.clj     (Tests)
```

**Parser Protocol:**
```clojure
(ns trust-construction.protocols.parser)

(defprotocol Parser
  (parse [parser input metadata])
  (detect-format [parser input])
  (supports-format? [parser format]))
```

**CSV Parser Implementation:**
```clojure
(ns trust-construction.parser.csv
  (:require [clojure.data.csv :as csv]
            [trust-construction.protocols.parser :as parser]))

(defrecord CSVParser []
  parser/Parser
  (parse [this input metadata]
    {:status :success
     :data (parse-csv-data input metadata)
     :errors []
     :stats {...}})

  (detect-format [this input]
    {:format :csv
     :confidence 0.95
     :indicators ["Has CSV header" "Comma-separated"]})

  (supports-format? [this format]
    (= format :csv)))
```

**Tests (8 tests):**
```clojure
(deftest test-parse-csv
  (testing "Parse valid CSV"
    (let [parser (->CSVParser)
          input "date,amount,merchant\n2025-03-20,45.99,Starbucks"
          result (parser/parse parser input {})]
      (is (= :success (:status result)))
      (is (= 1 (count (:data result)))))))

(deftest test-detect-csv-format
  (testing "Detect CSV format"
    ...))

(deftest test-parse-with-errors
  (testing "Parse CSV with errors returns partial results"
    ...))
```

**Success Criteria:**
- ✅ Parser protocol defined
- ✅ CSV parser works
- ✅ 8 tests pass
- ✅ Returns errors as data (no exceptions)
- ✅ Format detection works

---

#### Day 3: Validator Protocol

**Files to Create:**
```
src/trust_construction/
├── protocols/
│   └── validator.clj       (Validator protocol definition)
└── validator/
    ├── rules.clj           (Rule-based validator)
    └── validator_test.clj  (Tests)
```

**Validator Protocol:**
```clojure
(ns trust-construction.protocols.validator)

(defprotocol Validator
  (validate [validator data rules])
  (explain [validator result])
  (compose [validator & validators]))
```

**Rules Validator:**
```clojure
(ns trust-construction.validator.rules
  (:require [trust-construction.protocols.validator :as validator]))

(defrecord RulesValidator []
  validator/Validator
  (validate [this data rules]
    {:valid? true
     :score 0.95
     :results (map #(apply-rule % data) rules)
     :stats {:total-rules (count rules)
             :passed ...
             :failed ...}})

  (explain [this result]
    (format-validation-result result))

  (compose [this & validators]
    (->ComposedValidator validators)))

(defn- apply-rule [rule data]
  (let [field (get data (:field rule))
        constraint (:constraint rule)
        passed? (check-constraint field constraint)]
    {:rule-id (:id rule)
     :status (if passed? :pass :fail)
     :severity (:severity rule)
     :message (:message rule)
     :actual field
     :expected constraint}))
```

**Tests (7 tests):**
```clojure
(deftest test-validate-pass
  (testing "Validation passes when all rules pass"
    ...))

(deftest test-validate-fail
  (testing "Validation fails when any rule fails"
    ...))

(deftest test-explain
  (testing "Explain generates human-readable output"
    ...))

(deftest test-compose-validators
  (testing "Compose multiple validators"
    ...))
```

**Success Criteria:**
- ✅ Validator protocol defined
- ✅ RulesValidator works
- ✅ 7 tests pass
- ✅ Can compose validators
- ✅ Explain generates readable output

---

### Phase 1 Review

**Deliverables:**
- 3 protocols defined
- 3 implementations working
- 25 tests passing
- Documentation with examples

**Demo:**
```clojure
;; Create store
(def store (create-memory-store))

;; Parse CSV
(def parser (->CSVParser))
(def parsed (parser/parse parser csv-data {}))

;; Validate
(def validator (->RulesValidator))
(def result (validator/validate validator
              (first (:data parsed))
              [{:field :amount :constraint [:> 0] ...}]))

;; Store
(store/append! store (first (:data parsed)) {...})
```

---

## 4. PHASE 2: Event Sourcing (4 days)

### Goal
Implement event store and projections

### Tasks

#### Day 1-2: EventStore Protocol + SQLite Implementation

**Files to Create:**
```
src/trust_construction/
├── protocols/
│   └── event_store.clj       (EventStore protocol)
└── event_store/
    ├── sqlite.clj            (SQLite implementation)
    ├── migration.sql         (Schema)
    └── event_store_test.clj  (Tests)
```

**EventStore Protocol:**
```clojure
(ns trust-construction.protocols.event-store)

(defprotocol EventStore
  (append-event! [store event])
  (get-events [store aggregate-type aggregate-id])
  (get-events-since [store sequence-number])
  (subscribe [store event-type handler-fn]))
```

**SQLite Implementation:**
```clojure
(ns trust-construction.event-store.sqlite
  (:require [next.jdbc :as jdbc]
            [trust-construction.protocols.event-store :as es]))

(defrecord SQLiteEventStore [datasource]
  es/EventStore
  (append-event! [this event]
    (jdbc/execute! datasource
      ["INSERT INTO events (event_id, event_type, ...) VALUES (?, ?, ...)"
       (:event-id event) (:event-type event) ...]))

  (get-events [this aggregate-type aggregate-id]
    (jdbc/execute! datasource
      ["SELECT * FROM events WHERE aggregate_type = ? AND aggregate_id = ?
        ORDER BY sequence_number"
       aggregate-type aggregate-id]))
  ...)
```

**Schema:**
```sql
CREATE TABLE events (
  event_id TEXT PRIMARY KEY,
  event_type TEXT NOT NULL,
  sequence_number INTEGER UNIQUE NOT NULL,
  aggregate_id TEXT NOT NULL,
  aggregate_type TEXT NOT NULL,
  timestamp TEXT NOT NULL,
  author TEXT NOT NULL,
  source TEXT NOT NULL,
  data TEXT NOT NULL,  -- JSON
  metadata TEXT
);

CREATE INDEX idx_aggregate ON events(aggregate_type, aggregate_id);
CREATE INDEX idx_type ON events(event_type);
CREATE INDEX idx_timestamp ON events(timestamp);
```

**Tests (10 tests):**
```clojure
(deftest test-append-event
  (testing "Append event to store"
    ...))

(deftest test-get-events-by-aggregate
  (testing "Get all events for aggregate"
    ...))

(deftest test-events-ordered
  (testing "Events returned in sequence order"
    ...))
```

**Success Criteria:**
- ✅ EventStore protocol defined
- ✅ SQLite implementation works
- ✅ 10 tests pass
- ✅ Events are append-only
- ✅ Sequence numbers correct

---

#### Day 3-4: Projection Protocol + Transaction Projection

**Files to Create:**
```
src/trust_construction/
├── protocols/
│   └── projection.clj           (Projection protocol)
└── projection/
    ├── core.clj                 (Projection helpers)
    └── projection_test.clj      (Tests)
```

**Projection Protocol:**
```clojure
(ns trust-construction.protocols.projection)

(defprotocol Projection
  (project [projection events initial-state])
  (rebuild! [projection event-store])
  (update! [projection event]))
```

**Generic Projection Implementation:**
```clojure
(ns trust-construction.projection.core
  (:require [trust-construction.protocols.projection :as proj]))

(defrecord GenericProjection [apply-event-fn]
  proj/Projection
  (project [this events initial-state]
    (reduce apply-event-fn initial-state events))

  (rebuild! [this event-store]
    (let [all-events (es/get-all-events event-store)]
      (proj/project this all-events {})))

  (update! [this event]
    ;; Incremental update
    ))

(defn create-projection [event-handlers]
  (->GenericProjection
    (fn [state event]
      (if-let [handler (get event-handlers (:event-type event))]
        (handler state event)
        state))))
```

**Example: Transaction Projection:**
```clojure
(def transaction-projection
  (create-projection
    {:transaction-imported
     (fn [state event]
       (assoc state (:aggregate-id event)
         (merge (:data event)
                {:id (:aggregate-id event)
                 :version 1
                 :status :imported})))

     :transaction-classified
     (fn [state event]
       (update state (:aggregate-id event)
         (fn [tx]
           (merge tx (:data event)
                  {:version (inc (:version tx))
                   :status :classified}))))

     ...}))
```

**Tests (8 tests):**
```clojure
(deftest test-project-single-event
  (testing "Project single event"
    ...))

(deftest test-project-multiple-events
  (testing "Project multiple events builds state"
    ...))

(deftest test-rebuild
  (testing "Rebuild from event store"
    ...))
```

**Success Criteria:**
- ✅ Projection protocol defined
- ✅ Generic projection works
- ✅ Transaction projection example works
- ✅ 8 tests pass
- ✅ Can rebuild state from events

---

### Phase 2 Review

**Deliverables:**
- EventStore protocol + SQLite implementation
- Projection protocol + generic implementation
- 18 tests passing
- Working event sourcing demo

**Demo:**
```clojure
;; Create event store
(def event-store (create-sqlite-event-store))

;; Append events
(es/append-event! event-store
  {:event-type :transaction-imported
   :aggregate-id "tx-001"
   :data {:amount 45.99 ...}})

(es/append-event! event-store
  {:event-type :transaction-classified
   :aggregate-id "tx-001"
   :data {:category :restaurant ...}})

;; Project to current state
(def state (proj/project transaction-projection
             (es/get-events event-store :transaction "tx-001")
             {}))

;; => {:tx-001 {:amount 45.99 :category :restaurant :version 2}}
```

---

## 5. PHASE 3: Finance Domain (5 days)

### Goal
Build finance-specific application using protocols

### Tasks

#### Day 1: Finance Domain Models

**Files to Create:**
```
src/finance/
├── domain/
│   ├── transaction.clj      (Transaction schema + validation)
│   ├── bank.clj             (Bank schema)
│   ├── merchant.clj         (Merchant schema)
│   └── category.clj         (Category schema)
```

**Transaction Domain:**
```clojure
(ns finance.domain.transaction
  (:require [clojure.spec.alpha :as s]))

(s/def ::id uuid?)
(s/def ::date string?)
(s/def ::amount pos?)
(s/def ::merchant string?)
(s/def ::bank string?)
(s/def ::type #{:expense :income :credit-payment :transfer})
(s/def ::category keyword?)
(s/def ::confidence (s/and number? #(<= 0 % 1)))

(s/def ::transaction
  (s/keys :req-un [::id ::date ::amount ::merchant ::bank ::type]
          :opt-un [::category ::confidence]))

(defn create-transaction [data]
  (when (s/valid? ::transaction data)
    data))

(defn validate-transaction [tx]
  (s/explain-data ::transaction tx))
```

**Success Criteria:**
- ✅ All domain models defined
- ✅ Specs for validation
- ✅ Constructor functions
- ✅ 5 tests per model (20 total)

---

#### Day 2-3: Finance Parsers (BofA, AppleCard)

**Files to Create:**
```
src/finance/
├── parsers/
│   ├── bofa.clj             (BofA-specific parser)
│   ├── apple.clj            (AppleCard parser)
│   └── parsers_test.clj     (Tests)
```

**BofA Parser:**
```clojure
(ns finance.parsers.bofa
  (:require [trust-construction.protocols.parser :as parser]
            [finance.domain.transaction :as tx]))

(defrecord BofAParser []
  parser/Parser
  (parse [this input metadata]
    (let [csv-rows (parse-csv input)
          transactions (map bofa-row->transaction csv-rows)]
      {:status :success
       :data transactions
       :errors []
       :stats {:total (count csv-rows)
               :parsed (count transactions)}}))

  (detect-format [this input]
    (if (bofa-format? input)
      {:format :bofa-csv
       :confidence 0.98
       :indicators ["Has BofA header" "3 columns"]}
      {:format :unknown :confidence 0.0}))

  (supports-format? [this format]
    (= format :bofa-csv)))

(defn- bofa-row->transaction [row]
  (tx/create-transaction
    {:date (parse-date (nth row 0))
     :amount (parse-amount (nth row 2))
     :merchant (extract-merchant (nth row 1))
     :bank "BofA"
     :type (classify-type row)
     :raw-data row}))
```

**Tests (15 tests):**
```clojure
(deftest test-parse-bofa-csv
  (testing "Parse BofA CSV with 3 rows"
    ...))

(deftest test-detect-bofa-format
  (testing "Detect BofA CSV format"
    ...))
```

**Success Criteria:**
- ✅ BofA parser works
- ✅ AppleCard parser works
- ✅ 15 tests pass
- ✅ Parsers use Store protocol to save

---

#### Day 4-5: Classification Rules + Engine

**Files to Create:**
```
src/finance/
├── classification/
│   ├── engine.clj           (Classification engine)
│   ├── rules.clj            (Rule loading)
│   └── classification_test.clj (Tests)
├── resources/
│   └── rules/
│       └── classification-rules.edn  (Rules data)
```

**Classification Engine:**
```clojure
(ns finance.classification.engine
  (:require [finance.classification.rules :as rules]))

(defn classify-transaction [tx rules]
  (let [matching-rules (filter #(matches-rule? tx %) rules)
        best-rule (first (sort-by :priority > matching-rules))]
    (if best-rule
      {:category (:category best-rule)
       :merchant (:merchant best-rule)
       :confidence (:confidence best-rule)
       :rule-id (:id best-rule)}
      {:category :uncategorized
       :confidence 0.0})))

(defn- matches-rule? [tx rule]
  (re-matches (:pattern rule) (:merchant tx)))
```

**Classification Rules (EDN):**
```clojure
[{:id :merchant-starbucks
  :pattern #"STARBUCKS.*"
  :merchant "Starbucks"
  :category :restaurant
  :confidence 0.95
  :priority 100}

 {:id :merchant-amazon
  :pattern #"AMAZON.*"
  :merchant "Amazon"
  :category :shopping
  :confidence 0.90
  :priority 100}

 ...]
```

**Tests (12 tests):**
```clojure
(deftest test-classify-known-merchant
  (testing "Classify Starbucks transaction"
    ...))

(deftest test-classify-unknown-merchant
  (testing "Unknown merchant gets :uncategorized"
    ...))
```

**Success Criteria:**
- ✅ Classification engine works
- ✅ Rules loaded from EDN
- ✅ 12 tests pass
- ✅ Emits :transaction-classified events

---

### Phase 3 Review

**Deliverables:**
- Finance domain models (Transaction, Bank, Merchant, Category)
- BofA + AppleCard parsers
- Classification engine with rules
- 47 tests passing

**Demo:**
```clojure
;; Parse CSV
(def parser (->BofAParser))
(def parsed (parser/parse parser bofa-csv-data {}))

;; Classify
(def classified (map #(classify-transaction % rules) (:data parsed)))

;; Store as events
(doseq [tx classified]
  (es/append-event! event-store
    {:event-type :transaction-imported
     :aggregate-id (:id tx)
     :data tx}))

;; Project to see results
(def state (proj/rebuild! transaction-projection event-store))
```

---

## 6. PHASE 4: Migration from Current System (3 days)

### Goal
Migrate existing data to new event-sourced system

### Tasks

#### Day 1: Migration Plan

**Analyze current system:**
- Current data format
- Current schemas
- Current functionality

**Map to new system:**
- CSV → Parser → Events
- Rules → EDN files
- Tests → New test framework

#### Day 2-3: Execute Migration

**Scripts to Write:**
```
scripts/
├── migrate_transactions.clj     (Import existing CSV)
├── migrate_rules.clj            (Convert rules to EDN)
└── verify_migration.clj         (Compare old vs new)
```

**Migration Script:**
```clojure
(ns scripts.migrate-transactions
  (:require [trust-construction.event-store.sqlite :as es]
            [finance.parsers.bofa :as bofa]))

(defn migrate []
  (let [event-store (es/create-event-store "data/events.db")
        parser (bofa/->BofAParser)
        csv-data (slurp "data/old/transactions.csv")
        parsed (parser/parse parser csv-data {})]

    ;; Import all transactions as events
    (doseq [tx (:data parsed)]
      (es/append-event! event-store
        {:event-type :transaction-imported
         :aggregate-id (:id tx)
         :aggregate-type :transaction
         :timestamp (java.time.Instant/now)
         :author "migration-script"
         :source "csv-import"
         :data tx
         :metadata {:source-file "transactions.csv"}}))

    (println "Migrated" (count (:data parsed)) "transactions")))
```

**Success Criteria:**
- ✅ All existing data migrated
- ✅ Event store populated
- ✅ Projections match old system
- ✅ No data loss

---

## 7. PHASE 5: Testing & Documentation (2 days)

### Goal
Comprehensive testing and documentation

### Tasks

#### Day 1: Integration Tests

**Files to Create:**
```
test/finance/
└── integration/
    ├── end_to_end_test.clj      (Full workflow)
    ├── performance_test.clj     (Load testing)
    └── regression_test.clj      (Compare with old)
```

**End-to-End Test:**
```clojure
(deftest test-full-workflow
  (testing "Import → Classify → Verify → Query"
    (let [event-store (create-event-store)
          parser (->BofAParser)

          ;; Import
          parsed (parser/parse parser test-csv {})
          _ (import-transactions! event-store (:data parsed))

          ;; Classify
          classified (classify-all event-store rules)

          ;; Verify
          verified (verify-transaction event-store "tx-001")

          ;; Query
          state (project-current-state event-store)
          result (query-transactions state {:category :restaurant})]

      (is (= 1 (count result)))
      (is (= :restaurant (get-in result [0 :category])))
      (is (true? (get-in result [0 :verified]))))))
```

#### Day 2: Documentation

**Documents to Create:**
```
docs/
├── README.md                    (Getting started)
├── ARCHITECTURE.md              (System design)
├── API.md                       (Protocol reference)
├── MIGRATION_GUIDE.md           (How to migrate)
└── EXAMPLES.md                  (Code examples)
```

**Success Criteria:**
- ✅ 100+ total tests passing
- ✅ Coverage > 90%
- ✅ Documentation complete
- ✅ Examples work

---

## 8. Phase Dependencies

```
Phase 1: Core Protocols
   ↓
Phase 2: Event Sourcing
   ↓
Phase 3: Finance Domain
   ↓
Phase 4: Migration
   ↓
Phase 5: Testing & Docs
```

**Can be parallelized:**
- Phase 1 (Day 1, 2, 3) - All independent
- Phase 3 (Day 2-3 parsers and Day 4-5 classification) - Can overlap

**Cannot be parallelized:**
- Phase 2 depends on Phase 1
- Phase 3 depends on Phase 2
- Phase 4 depends on Phase 3

---

## 9. Success Metrics

### Technical Metrics

- ✅ 100+ tests passing
- ✅ Test coverage > 90%
- ✅ 0 warnings, 0 errors
- ✅ All protocols implemented
- ✅ Event sourcing working

### Functional Metrics

- ✅ Import CSV → events
- ✅ Classify transactions
- ✅ Query current state
- ✅ Time-travel queries work
- ✅ Same results as old system

### Quality Metrics

- ✅ Rich Hickey alignment > 90%
- ✅ Data > Mechanism (rules are data)
- ✅ Process/Perception separated
- ✅ Immutability everywhere
- ✅ Complete audit trail

---

## 10. Risk Management

### Risk 1: Performance

**Risk:** Event sourcing might be slow with many events

**Mitigation:**
- Use snapshots (cache projections)
- Index events by aggregate
- Batch event processing

**Contingency:**
- If > 100K events, implement snapshots
- If queries slow, add read models (separate tables)

---

### Risk 2: Schema Evolution

**Risk:** Event schemas might change, breaking old events

**Mitigation:**
- Version all events (:event-version field)
- Write upgrade functions
- Test with real old events

**Contingency:**
- Keep upgrade functions forever
- Document all schema changes
- Test migration scripts regularly

---

### Risk 3: Migration Fails

**Risk:** Data loss or corruption during migration

**Mitigation:**
- Backup everything first
- Dry-run migration (don't commit)
- Verify results (compare counts, sums)
- Keep old system running in parallel

**Contingency:**
- Rollback to old system
- Fix migration script
- Re-run migration

---

## 11. Tools & Dependencies

### Required

```clojure
;; deps.edn
{:deps
 {org.clojure/clojure {:mvn/version "1.11.1"}
  org.clojure/data.csv {:mvn/version "1.0.1"}
  org.clojure/core.async {:mvn/version "1.6.681"}
  org.clojure/data.json {:mvn/version "2.4.0"}

  ;; Database
  com.github.seancorfield/next.jdbc {:mvn/version "1.3.909"}
  org.xerial/sqlite-jdbc {:mvn/version "3.43.0.0"}

  ;; Testing
  org.clojure/test.check {:mvn/version "1.1.1"}}

 :paths ["src" "resources"]

 :aliases
 {:test {:extra-paths ["test"]
         :extra-deps {lambdaisland/kaocha {:mvn/version "1.87.1366"}}}}}
```

---

## 12. Checklist

### Before Starting

- [ ] Read all design docs (PROTOCOL_SPECS, DATA_SCHEMAS, EVENT_CATALOG)
- [ ] Understand Rich Hickey principles
- [ ] Review versioning system (already implemented)
- [ ] Set up development environment
- [ ] Create project structure

### During Implementation

- [ ] Follow TDD (write tests first)
- [ ] Commit after each task
- [ ] Review code after each day
- [ ] Update documentation as you go
- [ ] Run tests frequently

### After Each Phase

- [ ] All tests pass
- [ ] Code reviewed
- [ ] Documentation updated
- [ ] Demo working
- [ ] Commit with tag (phase-1-complete, etc.)

---

## 13. Timeline

**Total Duration:** 17 days

```
Week 1:
  Mon-Wed: Phase 1 (Core Protocols)
  Thu-Fri: Phase 2 (Event Sourcing) - Days 1-2

Week 2:
  Mon-Tue: Phase 2 (Event Sourcing) - Days 3-4
  Wed-Fri: Phase 3 (Finance Domain) - Days 1-3

Week 3:
  Mon-Tue: Phase 3 (Finance Domain) - Days 4-5
  Wed-Fri: Phase 4 (Migration) - 3 days

Week 4:
  Mon-Tue: Phase 5 (Testing & Docs) - 2 days
  Wed: Buffer day
```

---

## 14. What Success Looks Like

### Week 1 End

```clojure
;; You can do this:
(def store (create-memory-store))
(def parser (->CSVParser))
(def validator (->RulesValidator))

(let [parsed (parser/parse parser csv-data {})
      valid (validator/validate validator (first (:data parsed)) rules)]
  (when (:valid? valid)
    (store/append! store (first (:data parsed)) {})))
```

### Week 2 End

```clojure
;; You can do this:
(def event-store (create-sqlite-event-store))

(es/append-event! event-store
  {:event-type :transaction-imported ...})

(def state (proj/rebuild! transaction-projection event-store))
;; => {:tx-001 {...} :tx-002 {...}}
```

### Week 3 End

```clojure
;; You can do this:
(def parser (->BofAParser))
(def parsed (parser/parse parser bofa-csv {}))
(def classified (map #(classify-transaction % rules) (:data parsed)))

;; All data in event store, can query
(def state (proj/rebuild! transaction-projection event-store))
(def restaurants (query state {:category :restaurant}))
```

### Week 4 End

```clojure
;; Complete system:
;; - All existing data migrated
;; - 100+ tests passing
;; - Documentation complete
;; - Same functionality as old system
;; - Plus: event sourcing, time-travel, complete audit trail
```

---

## 15. Next Steps After Implementation

### Optional Enhancements (Badge 28, 29, 30)

**Badge 28: Value/Index Separation**
- Separate values (immutable files) from index (SQLite)
- Can rebuild index from values anytime

**Badge 29: Schema Refinement**
- Separate Shape/Selection/Qualification in schemas
- Multi-classification support

**Badge 30: More Rules as Data**
- Deduplication rules in CUE ✅ (already done)
- Validation rules in CUE
- Transformation rules in CUE

---

## 16. Key Principles to Remember

### During Implementation

1. **Bottom-Up** - Build protocols first, then implementations
2. **Test-Driven** - Write tests before code
3. **Small Steps** - Commit after each task
4. **Data First** - Prefer data structures over code
5. **Immutability** - Never mutate, always create new

### When Stuck

1. **Read the specs** - Check PROTOCOL_SPECS.md
2. **Look at existing code** - Versioning system is good example
3. **Simplify** - Can you use a simpler data structure?
4. **Ask for help** - Review design docs
5. **Take a break** - Walk away, come back fresh

---

## 17. Definition of Done

### For Each Task

- [ ] Code written
- [ ] Tests written (and passing)
- [ ] Documentation updated
- [ ] Code reviewed (self or peer)
- [ ] Committed with clear message

### For Each Phase

- [ ] All tasks complete
- [ ] All tests pass
- [ ] Integration tested
- [ ] Demo works
- [ ] Tagged in git

### For Project

- [ ] All 5 phases complete
- [ ] 100+ tests passing
- [ ] Documentation complete
- [ ] Migration successful
- [ ] Old system deprecated

---

**Ready to start?**

**First command:**
```bash
cd /Users/darwinborges/finance-clj
mkdir -p src/trust_construction/protocols
touch src/trust_construction/protocols/store.clj

# You're off! Start with Phase 1, Day 1, Task 1: Store Protocol
```

---

**Remember Rich Hickey:**

> "Design is about pulling things apart."
>
> "Simplicity is not easy, but it's worth it."
>
> "Make the data structures explicit and observable."

You've done the design. Now build it, one protocol at a time.

**Good luck! 🚀**
