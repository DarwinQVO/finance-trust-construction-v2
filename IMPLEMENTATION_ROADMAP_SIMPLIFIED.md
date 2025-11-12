# Trust Construction - Implementation Roadmap (SIMPLIFIED)

**Date:** 2025-11-07
**Status:** READY TO IMPLEMENT
**Version:** 2.0 (Simplified: 17 days → 15 days)

---

## 🎯 What Changed

### Before (Original Plan)
```
7 Protocols to implement
17 days total
Phase 1: 3 days (Store, Parser, Validator)
Phase 2: 4 days (EventStore, Projection)
```

### After (Simplified Plan)
```
2 Protocols + 4 Function Namespaces
15 days total (2 days saved!)
Phase 1: 2 days (Store, Parser)
Phase 2: 3 days (validation.clj, events.clj, projection.clj)
```

---

## PHASE 1: Core Protocols (2 days) ⬇️ Down from 3 days

### Day 1: Store Protocol

**Files to Create:**
```
src/trust_construction/
├── protocols/
│   └── store.clj           (Store protocol - SIMPLIFIED)
└── store/
    ├── memory.clj          (In-memory implementation)
    └── memory_test.clj     (Tests)
```

**Store Protocol (Simplified):**
```clojure
(ns trust-construction.protocols.store)

(defprotocol Store
  (append! [store data metadata]
    "Append data, return {:id ... :version ...}")

  (query [store spec]
    "Query with spec. ALL queries go through this:
     {:id \"...\"} - get by id
     {:id \"...\" :versions :all} - get versions
     {:as-of \"2025-01-01\"} - time travel
     {:filters {...}} - complex query"))
```

**Key Change:** 5 methods → 2 methods

**Why:**
- get-by-id → query with {:id "..."}
- get-versions → query with {:versions :all}
- get-at-time → query with {:as-of "..."}
- Simpler protocol, all power in query spec

**Memory Store Implementation:**
```clojure
(ns trust-construction.store.memory
  (:require [trust-construction.protocols.store :as store]))

(defrecord MemoryStore [data-atom]  ; {entity-type {id [versions]}}
  store/Store
  (append! [this data metadata]
    (let [id (uuid)
          version {:id id
                   :data data
                   :metadata metadata
                   :timestamp (now)
                   :version 1}]
      (swap! data-atom
        update-in [(:entity-type metadata) id]
        (fnil conj []) version)
      {:id id
       :version 1
       :timestamp (:timestamp version)}))

  (query [this spec]
    (let [entity-type (:entity-type spec)
          all-entities (get @data-atom entity-type {})]
      (cond
        ;; Get by ID
        (:id spec)
        (let [versions (get all-entities (:id spec))]
          (if (:versions spec)
            versions  ; Return all versions
            [(last versions)]))  ; Return latest

        ;; Time travel
        (:as-of spec)
        (time-travel-query all-entities (:as-of spec))

        ;; Complex filter
        (:filters spec)
        (filter-entities all-entities (:filters spec))

        ;; All entities
        :else
        (map last (vals all-entities))))))  ; Latest version of each

(defn create-memory-store []
  (->MemoryStore (atom {})))
```

**Tests (10 tests):**
```clojure
(ns trust-construction.store.memory-test
  (:require [clojure.test :refer [deftest is testing]]
            [trust-construction.protocols.store :as store]
            [trust-construction.store.memory :as mem]))

(deftest test-append
  (testing "Append creates new entity"
    (let [s (mem/create-memory-store)
          result (store/append! s
                   {:name "Test"}
                   {:entity-type :test :author "darwin"})]
      (is (uuid? (:id result)))
      (is (= 1 (:version result))))))

(deftest test-query-by-id
  (testing "Query by ID returns entity"
    (let [s (mem/create-memory-store)
          result (store/append! s {:name "Test"} {:entity-type :test})
          found (store/query s {:entity-type :test :id (:id result)})]
      (is (= 1 (count found)))
      (is (= "Test" (:name (:data (first found))))))))

(deftest test-query-versions
  (testing "Query all versions"
    (let [s (mem/create-memory-store)
          id (:id (store/append! s {:name "v1"} {:entity-type :test}))
          _ (store/append! s {:name "v2"} {:entity-type :test :id id})
          versions (store/query s {:entity-type :test :id id :versions :all})]
      (is (= 2 (count versions))))))

;; ... 7 more tests
```

**Success Criteria:**
- ✅ Store protocol defined (2 methods)
- ✅ MemoryStore implements all methods
- ✅ 10 tests pass (100% coverage)
- ✅ Can append and query entities
- ✅ Time-travel works
- ✅ Version tracking works

**Time:** 1 day (8 hours)

---

### Day 2: Parser Protocol

**Files to Create:**
```
src/trust_construction/
├── protocols/
│   └── parser.clj          (Parser protocol - NO CHANGE)
└── parser/
    ├── csv.clj             (CSV parser implementation)
    └── parser_test.clj     (Tests)
```

**Parser Protocol (Kept as-is):**
```clojure
(ns trust-construction.protocols.parser)

(defprotocol Parser
  (parse [parser input metadata])
  (detect-format [parser input]))

;; supports-format? → helper function, not in protocol
(defn supports-format? [parser input]
  (>= (:confidence (detect-format parser input)) 0.8))
```

**CSV Parser:**
```clojure
(ns trust-construction.parser.csv
  (:require [clojure.data.csv :as csv]
            [trust-construction.protocols.parser :as parser]))

(defrecord CSVParser []
  parser/Parser
  (parse [this input metadata]
    (let [rows (csv/read-csv input)
          header (first rows)
          data-rows (rest rows)]
      {:status :success
       :data (map #(parse-csv-row header %) data-rows)
       :errors []
       :stats {:total (count data-rows)
               :parsed (count data-rows)
               :failed 0}}))

  (detect-format [this input]
    (if (csv-format? input)
      {:format :csv
       :confidence 0.95
       :indicators ["Has commas" "Rows look like CSV"]}
      {:format :unknown :confidence 0.0})))

(defn create-csv-parser []
  (->CSVParser))
```

**Tests (8 tests):**
```clojure
(deftest test-parse-csv
  (testing "Parse valid CSV"
    (let [parser (create-csv-parser)
          input "date,amount\n2025-03-20,45.99"
          result (parser/parse parser input {})]
      (is (= :success (:status result)))
      (is (= 1 (count (:data result)))))))

;; ... 7 more tests
```

**Success Criteria:**
- ✅ Parser protocol defined
- ✅ CSV parser works
- ✅ 8 tests pass
- ✅ Returns errors as data
- ✅ Format detection works

**Time:** 1 day (8 hours)

---

## PHASE 2: Function Namespaces (3 days) ⬇️ Down from 4 days

### Day 1: validation.clj

**Files to Create:**
```
src/trust_construction/
├── validation.clj          (Validation functions)
└── validation_test.clj     (Tests)
```

**Functions (No Protocol):**
```clojure
(ns trust-construction.validation
  "Validation functions (no protocol needed)")

(defn validate
  "Validate data against rules.

  Rules are DATA (loaded from EDN):
  [{:field :amount :constraint [:> 0] ...}]"
  [data rules]
  {:valid? (every? #(check-rule data %) rules)
   :score (calculate-score rules data)
   :results (map #(apply-rule data %) rules)
   :stats {:total-rules (count rules)
           :passed (count-passed rules data)
           :failed (count-failed rules data)}})

(defn explain [result]
  "Human-readable explanation"
  (format-result result))

(defn compose [& rule-sets]
  "Combine rule sets"
  (vec (apply concat rule-sets)))
```

**Tests (7 tests):**
```clojure
(deftest test-validate-pass
  (testing "All rules pass"
    (let [data {:amount 45.99}
          rules [{:field :amount :constraint [:> 0]}]
          result (validate data rules)]
      (is (:valid? result))
      (is (= 1.0 (:score result))))))

;; ... 6 more tests
```

**Success Criteria:**
- ✅ validate function works
- ✅ explain generates readable output
- ✅ compose combines rule sets
- ✅ 7 tests pass
- ✅ Rules are data (can load from EDN)

**Time:** 1 day (8 hours)

---

### Day 2: events.clj

**Files to Create:**
```
src/trust_construction/
├── events.clj              (Event functions)
└── events_test.clj         (Tests)
```

**Functions (Uses Store Protocol):**
```clojure
(ns trust-construction.events
  "Event functions over Store protocol"
  (:require [trust-construction.protocols.store :as store]
            [clojure.core.async :as async]))

(defn append-event! [store event]
  "Append event (just calls store/append!)"
  (store/append! store
    (assoc event :event-id (uuid))
    {:entity-type :event
     :author (:author event)
     :timestamp (:timestamp event)}))

(defn get-events [store aggregate-type aggregate-id]
  "Get all events for aggregate"
  (store/query store
    {:entity-type :event
     :filters {:aggregate-type aggregate-type
               :aggregate-id aggregate-id}
     :order-by [:sequence-number :asc]}))

(defn get-events-since [store sequence-number]
  "Get events after sequence-number"
  (store/query store
    {:entity-type :event
     :filters {:sequence-number [:> sequence-number]}
     :order-by [:sequence-number :asc]}))

(defn subscribe [store event-type]
  "Subscribe to events (returns core.async channel)"
  (let [ch (async/chan 100)]
    ;; Poll for new events, put on channel
    (async/go-loop [last-seq 0]
      (async/<! (async/timeout 1000))
      (let [new-events (get-events-since store last-seq)]
        (doseq [event new-events]
          (when (or (= event-type :all)
                   (= event-type (:event-type event)))
            (async/>! ch event)))
        (recur (or (:sequence-number (last new-events)) last-seq))))
    ch))
```

**Tests (8 tests):**
```clojure
(deftest test-append-event
  (testing "Append event to store"
    (let [s (create-memory-store)
          event {:event-type :test-event
                 :aggregate-id "agg-001"
                 :data {:foo "bar"}}
          result (append-event! s event)]
      (is (:id result)))))

;; ... 7 more tests
```

**Success Criteria:**
- ✅ append-event! works
- ✅ get-events works
- ✅ get-events-since works
- ✅ subscribe works (core.async)
- ✅ 8 tests pass
- ✅ All use Store protocol underneath

**Time:** 1 day (8 hours)

---

### Day 3: projection.clj

**Files to Create:**
```
src/trust_construction/
├── projection.clj          (Projection functions)
└── projection_test.clj     (Tests)
```

**Functions (Handler Maps, No Protocol):**
```clojure
(ns trust-construction.projection
  "Projection functions with handler maps"
  (:require [trust-construction.events :as events]))

(defn project
  "Project events using handler map.

  handler-map = {:event-type (fn [state event] new-state)}"
  [events initial-state handler-map]
  (reduce
    (fn [state event]
      (if-let [handler (get handler-map (:event-type event))]
        (handler state event)
        state))
    initial-state
    events))

(defn rebuild [store handler-map]
  "Rebuild from event store"
  (let [all-events (events/get-events-by-type store :all)]
    (project all-events {} handler-map)))

(defn update-projection [current-state event handler-map]
  "Update with single event"
  (if-let [handler (get handler-map (:event-type event))]
    (handler current-state event)
    current-state))

(defn project-at-time [store timestamp handler-map]
  "Project up to timestamp"
  (let [events-up-to (store/query store
                       {:entity-type :event
                        :filters {:timestamp [:<= timestamp]}
                        :order-by [:sequence-number :asc]})]
    (project events-up-to {} handler-map)))

;; Example handler map
(def transaction-handlers
  {:transaction-imported
   (fn [state event]
     (assoc state (:aggregate-id event) (:data event)))

   :transaction-classified
   (fn [state event]
     (update state (:aggregate-id event)
       merge (:data event)))})
```

**Tests (8 tests):**
```clojure
(deftest test-project
  (testing "Project single event"
    (let [events [{:event-type :test-event
                   :aggregate-id "agg-001"
                   :data {:foo "bar"}}]
          handlers {:test-event
                    (fn [state event]
                      (assoc state (:aggregate-id event) (:data event)))}
          result (project events {} handlers)]
      (is (= {"agg-001" {:foo "bar"}} result)))))

;; ... 7 more tests
```

**Success Criteria:**
- ✅ project function works
- ✅ rebuild works
- ✅ update-projection works
- ✅ project-at-time works (time-travel)
- ✅ 8 tests pass
- ✅ Handler maps are data (extensible)

**Time:** 1 day (8 hours)

---

## PHASE 3: Finance Domain (5 days) - NO CHANGE

Same as original plan. Uses protocols and functions defined in Phase 1-2.

### Day 1: Finance Domain Models

**Files:**
```
src/finance/
├── domain/
│   ├── transaction.clj
│   ├── bank.clj
│   ├── merchant.clj
│   └── category.clj
```

**No protocol changes, just domain models**

**Time:** 1 day

---

### Day 2-3: Finance Parsers (BofA, AppleCard)

**Files:**
```
src/finance/
├── parsers/
│   ├── bofa.clj             (Implements Parser protocol)
│   ├── apple.clj            (Implements Parser protocol)
│   └── parsers_test.clj
```

**Uses Parser protocol from Phase 1**

**Time:** 2 days

---

### Day 4-5: Classification Engine

**Files:**
```
src/finance/
├── classification/
│   ├── engine.clj           (Uses validation functions)
│   ├── rules.clj            (Load rules from EDN)
│   └── classification_test.clj
```

**Uses:**
- validation.clj from Phase 2
- transducers.clj from Phase 1 (already exists!)
- Rules from resources/rules/*.edn (Badge 30)

**Time:** 2 days

---

## PHASE 4: Migration (3 days) - NO CHANGE

Same as original plan.

**Time:** 3 days

---

## PHASE 5: Testing & Documentation (2 days) - NO CHANGE

Same as original plan.

**Time:** 2 days

---

## 📊 Comparison: Original vs Simplified

| Phase | Original | Simplified | Time Saved |
|-------|----------|------------|------------|
| Phase 1 | 3 days | 2 days | 1 day |
| Phase 2 | 4 days | 3 days | 1 day |
| Phase 3 | 5 days | 5 days | 0 days |
| Phase 4 | 3 days | 3 days | 0 days |
| Phase 5 | 2 days | 2 days | 0 days |
| **Total** | **17 days** | **15 days** | **2 days** |

---

## ✅ Implementation Checklist

### Before Starting

- [x] Read RICH_HICKEY_CRITIQUE.md
- [x] Read PROTOCOL_SPECS_SIMPLIFIED.md
- [x] Understand simplifications
- [x] Decision made: Opción A ✅

### Phase 1 (2 days)

**Day 1:**
- [ ] Create trust_construction/protocols/store.clj
- [ ] Create trust_construction/store/memory.clj
- [ ] Write 10 tests
- [ ] All tests pass

**Day 2:**
- [ ] Create trust_construction/protocols/parser.clj
- [ ] Create trust_construction/parser/csv.clj
- [ ] Write 8 tests
- [ ] All tests pass

### Phase 2 (3 days)

**Day 1:**
- [ ] Create trust_construction/validation.clj
- [ ] Write 7 tests
- [ ] All tests pass

**Day 2:**
- [ ] Create trust_construction/events.clj
- [ ] Write 8 tests
- [ ] All tests pass

**Day 3:**
- [ ] Create trust_construction/projection.clj
- [ ] Write 8 tests
- [ ] All tests pass

### Phase 3-5 (10 days)
- [ ] Same as original plan

---

## 🎯 Success Metrics

**Technical:**
- ✅ 2 protocols (down from 7)
- ✅ 4 function namespaces
- ✅ 41 tests in Phase 1-2 (10+8+7+8+8)
- ✅ All tests pass

**Alignment:**
- ✅ 95% Rich Hickey aligned (up from 85%)

**Time:**
- ✅ 15 days total (down from 17 days)
- ✅ 2 days saved

**Code Quality:**
- ✅ Simpler code
- ✅ More idiomatic Clojure
- ✅ Data-driven (rules, handlers)

---

## 🚀 Ready to Start

**First command:**
```bash
cd /Users/darwinborges/finance-clj

# Create structure
mkdir -p src/trust_construction/protocols
mkdir -p src/trust_construction/store
mkdir -p test/trust_construction/store

# Start Phase 1, Day 1
touch src/trust_construction/protocols/store.clj
touch src/trust_construction/store/memory.clj
touch test/trust_construction/store/memory_test.clj

# Open PROTOCOL_SPECS_SIMPLIFIED.md and implement Store protocol
```

**When Phase 1-2 complete:**
```bash
# Should have:
# - 2 protocols working
# - 4 function namespaces working
# - 41 tests passing
# - 0 failures, 0 errors

# Ready for Phase 3 (Finance Domain)
```

---

**Implementation starts now! 🚀**
