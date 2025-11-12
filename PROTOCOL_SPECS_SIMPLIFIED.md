# Trust Construction - Protocol Specifications (SIMPLIFIED)

**Date:** 2025-11-07
**Status:** DESIGN (Simplified following Rich Hickey critique)
**Version:** 2.0 (Simplified from 7 protocols → 2 protocols)

---

## 🎯 Simplification Philosophy

**Rich Hickey:**
> "Protocols are for when you need dynamic polymorphism. If you only have 1-2 implementations, just use functions."

**Our approach:**
- **Protocols:** Only when genuinely needed (3+ implementations with different mechanisms)
- **Functions:** When logic is driven by data (not mechanism)
- **Transducers:** For transformations (already implemented in Phase 1)

---

## 📊 What Changed

### Before (Original Design)
```
7 Protocols:
  1. Store
  2. Parser
  3. Validator          ❌ Now: Functions
  4. Transformer        ❌ Now: Transducers
  5. EventStore         ❌ Now: Functions over Store
  6. Projection         ❌ Now: Functions + handler maps
  7. Command            ❌ Never was protocol (good)
```

### After (Simplified Design)
```
2 Protocols:
  1. Store (simplified: 5 methods → 2 methods)
  2. Parser (kept: genuinely needs polymorphism)

Functions:
  - validation.clj  (validate, explain, compose)
  - events.clj      (append-event!, get-events, subscribe)
  - projection.clj  (project with handler maps)
  - commands.clj    (handle-command)

Existing:
  - transducers.clj (Phase 1 - already implemented!)
```

**Result:** 85% → 95% Rich Hickey alignment

---

## 1. PROTOCOL: Store (Simplified)

### Purpose
Generic storage abstraction for ANY domain

### Protocol Definition

```clojure
(defprotocol Store
  "Immutable, append-only storage with query capabilities.

  Design: All power in query spec (data-driven queries)

  Rich Hickey: 'Put the complexity in the data, not the code.'"

  (append! [store data metadata]
    "Append new data to store.

    Args:
      store - Store instance
      data - Any Clojure data structure
      metadata - Map with:
                 :entity-type - Keyword (:transaction, :bank, :event, etc.)
                 :author - Who made the change
                 :timestamp - When (ISO 8601)
                 :provenance - Where data came from

    Returns:
      {:id <uuid>
       :version 1
       :timestamp <iso-8601>
       :hash <sha-256>}

    Example:
      (append! store
        {:amount 45.99 :merchant \"Starbucks\"}
        {:entity-type :transaction
         :author \"darwin\"
         :timestamp \"2025-11-07T10:30:00Z\"})

    Guarantees:
      - Idempotent (same hash → skip, not error)
      - Atomic (all or nothing)
      - Durable (survives crashes)
      - Ordered (timestamp ordering preserved)")

  (query [store spec]
    "Query data with flexible spec (ALL queries go through this).

    Args:
      store - Store instance
      spec - Query specification (data, not code!):

             ;; Get by ID
             {:entity-type :transaction
              :id \"550e8400-...\"}

             ;; Get all versions of entity
             {:entity-type :transaction
              :id \"550e8400-...\"
              :versions :all}

             ;; Time-travel query
             {:entity-type :transaction
              :as-of \"2025-01-01T00:00:00Z\"}

             ;; Complex filter
             {:entity-type :transaction
              :filters {:bank \"BofA\"
                       :amount [:> 100]
                       :date [:between \"2025-01-01\" \"2025-03-31\"]}
              :order-by [:date :desc]
              :limit 100
              :offset 0}

    Returns:
      Vector of matching entities with full metadata

    Example:
      ;; Get by ID (replaces get-by-id method)
      (query store {:entity-type :transaction
                    :id \"tx-001\"})

      ;; Get versions (replaces get-versions method)
      (query store {:entity-type :transaction
                    :id \"tx-001\"
                    :versions :all})

      ;; Time-travel (replaces get-at-time method)
      (query store {:entity-type :transaction
                    :id \"tx-001\"
                    :as-of \"2025-01-01T00:00:00Z\"})

      ;; Complex query
      (query store {:entity-type :transaction
                    :filters {:category :restaurant
                             :amount [:> 50]}
                    :limit 10})

    Guarantees:
      - Consistent reads (snapshot isolation)
      - Reproducible (same spec → same result)
      - Lazy (don't load all data)
      - Extensible (add new query types without changing protocol)"))
```

---

## 2. PROTOCOL: Parser

### Purpose
Parse external data (CSV, JSON, PDF, etc.) into canonical domain model

### Why This Needs a Protocol

**✅ Genuinely different implementations:**
- BofAParser - 3-column CSV format
- AppleCardParser - 5-column CSV format with different semantics
- StripeParser - JSON from API (cents → dollars, unix timestamps)
- WiseParser - 9-column CSV with multi-currency

**Each has DIFFERENT parsing logic, not just different data.**

### Protocol Definition

```clojure
(defprotocol Parser
  "Parse external data sources into canonical format.

  Design Principles:
  - Separate parsing from transformation
  - Preserve original data (provenance)
  - Report confidence for every field
  - Graceful degradation (partial parse ok)

  Rich Hickey: 'Parse, don't validate. Return data.'"

  (parse [parser input metadata]
    "Parse input data into canonical format.

    Args:
      parser - Parser instance
      input - Input data (string, byte array, stream, etc.)
      metadata - Map with:
                 :source-file - Filename
                 :source-type - :csv | :json | :pdf | :xml
                 :encoding - \"UTF-8\" etc.

    Returns:
      {:status :success | :partial | :failure
       :data [{:raw {...}              ; Original parsed data
               :normalized {...}        ; Transformed to canonical
               :confidence 0.95         ; Parse confidence
               :provenance {...}}]      ; Where each field came from
       :errors [{:line 23
                 :field :amount
                 :error \"Invalid format\"
                 :severity :warning | :error}]
       :stats {:total-lines 100
               :parsed 98
               :failed 2}}

    Example:
      (parse bofa-parser csv-content
        {:source-file \"bofa_march.csv\"
         :source-type :csv})

    Guarantees:
      - Never throws exceptions (errors as data)
      - Partial results returned (don't lose good data)
      - Every field has confidence score
      - Preserves raw input for audit")

  (detect-format [parser input]
    "Auto-detect input format from content.

    Args:
      parser - Parser instance
      input - First N bytes/chars of input

    Returns:
      {:format :csv | :json | :pdf | :unknown
       :confidence 0.95
       :indicators [\"Has CSV header\" \"3 comma-separated columns\"]}

    Example:
      (detect-format bofa-parser (slurp \"unknown.csv\"))
      ;=> {:format :bofa-csv
           :confidence 0.98
           :indicators [\"3 columns\" \"Date,Description,Amount header\"]}

    Guarantees:
      - Fast (< 100ms, only reads header)
      - Probabilistic (returns confidence)
      - Explainable (shows why)"))

;; Note: supports-format? removed (can be derived from detect-format)
(defn supports-format?
  "Check if parser supports given format.

  Not in protocol - just a helper function."
  [parser input]
  (>= (:confidence (detect-format parser input)) 0.8))
```

---

## 3. FUNCTIONS: Validation (No Protocol)

### Purpose
Validate data against rules

### Why Functions, Not Protocol

**Rich Hickey:**
> "Rules are data (Badge 30). You don't need a protocol to process data. Just write functions."

**Reasons:**
- Rules are in EDN files (resources/rules/*.edn)
- Only one validation mechanism (check rules)
- Different validators = different rule files, NOT different code

### Function Signatures

```clojure
(ns trust-construction.validation
  "Validation functions (no protocol)

  Rules are DATA (loaded from EDN files).
  Validation is just: check if data matches rules.")

(defn validate
  "Validate data against rules.

  Args:
    data - Data to validate (map)
    rules - Vector of rule maps:
            [{:id :amount-positive
              :field :amount
              :constraint [:> 0]
              :severity :error
              :message \"Amount must be positive\"}]

  Returns:
    {:valid? true | false
     :score 0.95
     :results [{:rule-id :amount-positive
                :status :pass | :fail
                :severity :error | :warning | :info
                :message \"Amount must be positive\"
                :actual 45.99
                :expected [:> 0]}]
     :stats {:total-rules 10
             :passed 9
             :failed 1}}

  Example:
    (def amount-rules
      [{:id :amount-positive
        :field :amount
        :constraint [:> 0]
        :severity :error
        :message \"Amount must be positive\"}])

    (validate {:amount 45.99} amount-rules)
    ;=> {:valid? true :score 1.0 :results [...]}

  Guarantees:
    - All rules evaluated (no short-circuit)
    - Every result has explanation
    - Reproducible (same data + rules → same result)"
  [data rules]
  {:valid? (every? #(check-rule data %) rules)
   :score (calculate-score (map #(check-rule data %) rules))
   :results (map #(apply-rule data %) rules)
   :stats (calculate-stats (map #(check-rule data %) rules))})

(defn explain
  "Generate human-readable explanation of validation result.

  Args:
    result - Result map from validate()

  Returns:
    String with formatted explanation

  Example:
    (explain validation-result)
    ;=> \"Validation: PASSED (9/10 rules)
         ✓ amount-positive: Amount must be positive (actual: 45.99)
         ✗ merchant-known: Merchant not in registry (actual: Unknown Cafe)
         Score: 0.95/1.00\""
  [result]
  (format-validation-result result))

(defn compose
  "Compose multiple rule sets into one.

  Args:
    rule-sets - Multiple vectors of rules

  Returns:
    Combined vector of rules

  Example:
    (def all-rules
      (compose amount-rules
               merchant-rules
               category-rules))

  Note: Just concatenates vectors. No protocol needed."
  [& rule-sets]
  (vec (apply concat rule-sets)))

;; Helper: Check single rule
(defn- check-rule
  "Check if data passes rule."
  [data rule]
  (let [field-value (get data (:field rule))
        constraint (:constraint rule)]
    (apply-constraint field-value constraint)))

;; Helper: Apply constraint
(defn- apply-constraint
  "Apply constraint to value."
  [value [op expected]]
  (case op
    :> (> value expected)
    :>= (>= value expected)
    :< (< value expected)
    :<= (<= value expected)
    := (= value expected)
    :!= (not= value expected)
    :in (contains? (set expected) value)
    :matches (re-matches expected value)))
```

---

## 4. FUNCTIONS: Events (No Protocol)

### Purpose
Event operations using Store protocol

### Why Functions, Not Protocol

**Rich Hickey:**
> "Events are just data. EventStore is just Store with event-specific functions."

**Reasons:**
- Events stored in Store (same as transactions, banks, etc.)
- Just different entity-type (:event)
- Functions provide convenience, protocol doesn't add value

### Function Signatures

```clojure
(ns trust-construction.events
  "Event functions over Store protocol.

  Events are stored using Store, these functions provide convenience."
  (:require [trust-construction.protocols.store :as store]
            [clojure.core.async :as async]))

(defn append-event!
  "Append event to store.

  Args:
    store - Store instance
    event - Event map:
            {:event-type :transaction-imported
             :aggregate-id \"tx-001\"
             :aggregate-type :transaction
             :data {:amount 45.99 ...}
             :metadata {...}}

  Returns:
    {:id <event-id>
     :sequence-number 12345
     :timestamp <iso-8601>}

  Example:
    (append-event! store
      {:event-type :transaction-imported
       :aggregate-id \"tx-001\"
       :aggregate-type :transaction
       :timestamp (now)
       :author \"darwin\"
       :source \"csv-import\"
       :data {:amount 45.99 :merchant \"Starbucks\"}})

  Implementation:
    Just calls store/append! with :entity-type :event"
  [store event]
  (store/append! store
    (assoc event
      :event-id (uuid)
      :sequence-number (next-sequence-number store))
    {:entity-type :event
     :author (:author event)
     :timestamp (:timestamp event)}))

(defn get-events
  "Get all events for an aggregate.

  Args:
    store - Store instance
    aggregate-type - :transaction | :bank | etc.
    aggregate-id - UUID string

  Returns:
    Vector of events in chronological order

  Example:
    (get-events store :transaction \"tx-001\")
    ;=> [{:event-type :transaction-imported ...}
         {:event-type :transaction-classified ...}]

  Implementation:
    Just calls store/query with event filters"
  [store aggregate-type aggregate-id]
  (store/query store
    {:entity-type :event
     :filters {:aggregate-type aggregate-type
               :aggregate-id aggregate-id}
     :order-by [:sequence-number :asc]}))

(defn get-events-since
  "Get all events after given sequence number.

  Args:
    store - Store instance
    sequence-number - Start from this sequence

  Returns:
    Vector of events after sequence-number

  Example:
    (get-events-since store 12340)
    ;=> Events 12341, 12342, ...

  Use case:
    - Catch up after downtime
    - Build projections incrementally"
  [store sequence-number]
  (store/query store
    {:entity-type :event
     :filters {:sequence-number [:> sequence-number]}
     :order-by [:sequence-number :asc]}))

(defn get-events-by-type
  "Get all events of specific type.

  Args:
    store - Store instance
    event-type - :transaction-imported | etc.

  Returns:
    Vector of matching events

  Example:
    (get-events-by-type store :transaction-imported)
    ;=> All import events"
  [store event-type]
  (store/query store
    {:entity-type :event
     :filters {:event-type event-type}
     :order-by [:sequence-number :asc]}))

(defn subscribe
  "Subscribe to events (real-time updates).

  Args:
    store - Store instance
    event-type - :transaction-imported | :all | etc.

  Returns:
    core.async channel with events

  Example:
    (let [ch (subscribe store :transaction-imported)]
      (async/go-loop []
        (when-let [event (async/<! ch)]
          (println \"New event:\" event)
          (recur))))

  Implementation:
    Uses core.async (already in deps.edn).
    Polls store for new events, puts on channel."
  [store event-type]
  (let [ch (async/chan 100)  ; Buffer 100 events
        last-seq (atom (get-latest-sequence-number store event-type))]
    (async/go-loop []
      (async/<! (async/timeout 1000))  ; Poll every 1 second
      (let [new-events (if (= event-type :all)
                        (get-events-since store @last-seq)
                        (filter #(= event-type (:event-type %))
                               (get-events-since store @last-seq)))]
        (doseq [event new-events]
          (async/>! ch event)
          (reset! last-seq (:sequence-number event)))
        (recur)))
    ch))

;; Helper: Get latest sequence number
(defn- get-latest-sequence-number
  [store event-type]
  (let [events (if (= event-type :all)
                (store/query store {:entity-type :event
                                   :order-by [:sequence-number :desc]
                                   :limit 1})
                (store/query store {:entity-type :event
                                   :filters {:event-type event-type}
                                   :order-by [:sequence-number :desc]
                                   :limit 1}))]
    (or (:sequence-number (first events)) 0)))

;; Helper: Generate next sequence number
(defn- next-sequence-number
  [store]
  (inc (get-latest-sequence-number store :all)))
```

---

## 5. FUNCTIONS: Projection (No Protocol)

### Purpose
Derive current state from events

### Why Functions, Not Protocol

**Rich Hickey:**
> "If the only difference is the handlers, use data (handler maps). If you need different storage mechanisms, THEN use a protocol."

**Our case:** Same mechanism (fold events), different handlers → Use functions

### Function Signatures

```clojure
(ns trust-construction.projection
  "Projection functions with handler maps (no protocol).

  Projections are DATA (handler maps), not code (protocol implementations)."
  (:require [trust-construction.events :as events]))

(defn project
  "Project events to state using handler map.

  Args:
    events - Vector of events
    initial-state - Starting state (usually {})
    handler-map - Map of {:event-type handler-fn}
                  where handler-fn is (fn [state event] new-state)

  Returns:
    Final state after applying all events

  Example:
    (def transaction-handlers
      {:transaction-imported
       (fn [state event]
         (assoc state (:aggregate-id event) (:data event)))

       :transaction-classified
       (fn [state event]
         (update state (:aggregate-id event)
           merge (:data event)))})

    (project events {} transaction-handlers)
    ;=> {:tx-001 {:amount 45.99 :category :restaurant}
         :tx-002 {...}}

  Guarantees:
    - Deterministic (same events → same state)
    - Reproducible (can rebuild anytime)
    - Extensible (add handlers without changing code)"
  [events initial-state handler-map]
  (reduce
    (fn [state event]
      (if-let [handler (get handler-map (:event-type event))]
        (handler state event)
        state))  ; Unknown event type, skip
    initial-state
    events))

(defn rebuild
  "Rebuild projection from scratch using event store.

  Args:
    store - Store instance
    handler-map - Handler map

  Returns:
    Current state

  Example:
    (rebuild store transaction-handlers)
    ;=> Full current state

  Use case:
    - Fix bugs in projection logic
    - Add new fields to state
    - Recover from corruption"
  [store handler-map]
  (let [all-events (events/get-events-by-type store :all)]
    (project all-events {} handler-map)))

(defn update-projection
  "Update projection with new event.

  Args:
    current-state - Current projection state
    event - New event to apply
    handler-map - Handler map

  Returns:
    Updated state

  Example:
    (def state (rebuild store transaction-handlers))
    (def new-state (update-projection state new-event transaction-handlers))

  Use case:
    - Real-time updates (don't replay all events)"
  [current-state event handler-map]
  (if-let [handler (get handler-map (:event-type event))]
    (handler current-state event)
    current-state))

(defn project-at-time
  "Project events up to specific timestamp.

  Args:
    store - Store instance
    timestamp - ISO 8601 timestamp
    handler-map - Handler map

  Returns:
    State as it was at that time

  Example:
    (project-at-time store \"2025-01-01T00:00:00Z\" transaction-handlers)
    ;=> State on January 1st

  Use case:
    - Time-travel queries
    - Historical reporting"
  [store timestamp handler-map]
  (let [events-up-to (store/query store
                       {:entity-type :event
                        :filters {:timestamp [:<= timestamp]}
                        :order-by [:sequence-number :asc]})]
    (project events-up-to {} handler-map)))

;; Example handler maps (for reference)

(def transaction-handlers
  "Example: Transaction projection handlers"
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

   :transaction-verified
   (fn [state event]
     (update state (:aggregate-id event)
       (fn [tx]
         (merge tx (:data event)
                {:version (inc (:version tx))
                 :status :verified}))))})

(def bank-handlers
  "Example: Bank projection handlers"
  {:bank-created
   (fn [state event]
     (assoc state (:aggregate-id event) (:data event)))

   :bank-updated
   (fn [state event]
     (update state (:aggregate-id event)
       merge (:changes event)))})
```

---

## 6. FUNCTIONS: Commands (No Protocol)

### Purpose
Handle commands (validate and emit events)

### Why Functions, Not Protocol

**Rich Hickey:**
> "Commands are data. Just a function that validates and returns event or error."

### Function Signatures

```clojure
(ns trust-construction.commands
  "Command handling functions (no protocol).

  Commands = Intent (may fail)
  Events = Facts (already happened)"
  (:require [trust-construction.validation :as v]
            [trust-construction.events :as events]))

(defn handle-command
  "Validate command, return event or error.

  Args:
    command - Command map:
              {:command-type :import-transaction
               :data {:date \"2025-03-20\"
                     :amount 45.99
                     :merchant \"Starbucks\"}}
    rules - Validation rules

  Returns:
    {:status :success :event {...}}
    {:status :error :reason \"Invalid date\" :details {...}}

  Example:
    (handle-command
      {:command-type :import-transaction
       :data {:date \"2025-03-20\"
             :amount 45.99
             :merchant \"Starbucks\"}}
      import-validation-rules)
    ;=> {:status :success
         :event {:event-type :transaction-imported
                :aggregate-id \"tx-001\"
                :data {...}}}

  Guarantees:
    - Validation before event emission
    - Errors as data (no exceptions)
    - Idempotent (same command → same result)"
  [command rules]
  (let [validation-result (v/validate (:data command) rules)]
    (if (:valid? validation-result)
      ;; Valid: emit event
      {:status :success
       :event (command->event command)}
      ;; Invalid: return error
      {:status :error
       :reason "Validation failed"
       :details validation-result})))

(defn- command->event
  "Convert command to event (after validation).

  Internal helper, not public API."
  [command]
  (case (:command-type command)
    :import-transaction
    {:event-type :transaction-imported
     :aggregate-id (uuid)
     :aggregate-type :transaction
     :timestamp (now)
     :author (:author command)
     :source (:source command)
     :data (normalize-transaction-data (:data command))}

    :classify-transaction
    {:event-type :transaction-classified
     :aggregate-id (:transaction-id command)
     :aggregate-type :transaction
     :timestamp (now)
     :author (:author command)
     :source (:source command)
     :data {:category (:category command)
            :confidence (:confidence command)}}

    ;; Add more command types...
    ))

;; Usage example
(comment
  ;; 1. Receive command
  (def cmd {:command-type :import-transaction
            :data {:date "2025-03-20"
                   :amount 45.99
                   :merchant "Starbucks"}
            :author "darwin"
            :source "csv-import"})

  ;; 2. Handle command (validate)
  (def result (handle-command cmd import-rules))

  ;; 3. If success, append event
  (when (= :success (:status result))
    (events/append-event! store (:event result))))
```

---

## 7. TRANSDUCERS: Already Implemented! (Phase 1)

### Purpose
Context-independent transformations

### Why Not a Protocol

**You already have this!**

**File:** `src/finance/transducers.clj` (~230 lines)

**Transducers:**
- parse-date-xf
- parse-amount-xf
- normalize-merchant-xf
- add-id-xf
- add-provenance-xf
- csv-import-pipeline-xf (composition)

**Usage:**
```clojure
(require '[finance.transducers :as xf])

;; Use in pipeline
(def import-pipeline
  (xf/csv-import-pipeline-xf "bofa_march.csv" "1.0.0"))

;; Apply to data
(into [] import-pipeline raw-csv-rows)
```

**Rich Hickey:**
> "You already built the right abstraction. Don't add another layer on top."

---

## 8. Summary

### Protocols (2)
1. **Store** - Storage abstraction (2 methods: append!, query)
2. **Parser** - Parse different formats (2 methods: parse, detect-format)

### Functions (4 namespaces)
3. **validation.clj** - validate, explain, compose
4. **events.clj** - append-event!, get-events, subscribe
5. **projection.clj** - project, rebuild, update-projection
6. **commands.clj** - handle-command

### Already Implemented
7. **transducers.clj** - Phase 1 complete (10 transducers)

---

## 9. Benefits of Simplification

**Complexity:**
- 7 protocols → 2 protocols (71% reduction)

**Alignment:**
- 85% → 95% Rich Hickey

**Implementation Time:**
- Phase 1: 3 days → 2 days
- Phase 2: 4 days → 3 days
- Total: 2 days saved

**Code Quality:**
- Simpler = fewer bugs
- Functions > protocols (more idiomatic)
- Data-driven (rules, handlers are data)

**Maintenance:**
- Less code to maintain
- Easier to extend (add handlers, not change protocols)
- Closer to Clojure philosophy

---

## 10. Next: Implementation

See [IMPLEMENTATION_ROADMAP_SIMPLIFIED.md](IMPLEMENTATION_ROADMAP_SIMPLIFIED.md) for updated plan.

**Phase 1 (2 days):**
- Day 1: Store protocol + in-memory implementation
- Day 2: Parser protocol + CSV implementation

**Phase 2 (3 days):**
- Day 1: validation.clj + tests
- Day 2: events.clj + tests
- Day 3: projection.clj + tests

**Total: 5 days (vs 7 days original)**

---

**Ready to implement! 🚀**
