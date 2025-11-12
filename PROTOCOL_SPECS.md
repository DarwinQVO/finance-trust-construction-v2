# Trust Construction - Protocol Specifications

**Date:** 2025-11-07
**Status:** DESIGN
**Purpose:** Detailed specifications for core protocols before implementation

---

## 1. Store Protocol - Complete Specification

### Purpose
Generic storage abstraction that works with ANY domain (finance, healthcare, legal, etc.)

### Protocol Definition

```clojure
(defprotocol Store
  "Immutable, append-only storage with time-travel queries.

  Design Principles:
  - Never mutate existing data
  - All writes are appends
  - All data has temporal dimension
  - All operations return data (no side effects in return)

  Rich Hickey: 'The log IS the database. Everything else is a view.'"

  (append! [store data metadata]
    "Append new data to store.

    Args:
      store - Store instance
      data - Any Clojure data structure (map, vector, etc.)
      metadata - Map with:
                 :entity-type - Keyword (:transaction, :classification, etc.)
                 :author - Who made the change
                 :timestamp - When (ISO 8601)
                 :provenance - Where data came from

    Returns:
      {:id <uuid>
       :version 1
       :timestamp <iso-8601>
       :hash <sha-256>}

    Guarantees:
      - Idempotent (same hash → skip, not error)
      - Atomic (all or nothing)
      - Durable (survives crashes)
      - Ordered (timestamp ordering preserved)")

  (query [store spec]
    "Query data with time-travel support.

    Args:
      store - Store instance
      spec - Query specification map:
             {:entity-type :transaction
              :filters {:bank \"BofA\" :amount [:> 100]}
              :as-of-time \"2025-11-01T00:00:00Z\"  ; Optional time-travel
              :limit 100
              :offset 0
              :order-by [:timestamp :desc]}

    Returns:
      Vector of matching entities with full provenance

    Guarantees:
      - Consistent reads (snapshot isolation)
      - Reproducible (same spec + time → same result)
      - Lazy (don't load all data)")

  (get-by-id [store entity-type id]
    "Get current version of entity by ID.

    Args:
      store - Store instance
      entity-type - Keyword (:transaction, :bank, etc.)
      id - UUID string

    Returns:
      Entity map or nil if not found

    Guarantees:
      - O(log n) lookup
      - Returns latest non-expired version")

  (get-versions [store entity-type id]
    "Get all versions of an entity (history).

    Args:
      store - Store instance
      entity-type - Keyword
      id - UUID string

    Returns:
      Vector of all versions, oldest first:
      [{:id <uuid>
        :version 1
        :data {...}
        :valid-from <timestamp>
        :valid-to <timestamp or nil>
        :metadata {...}}]

    Guarantees:
      - Complete history (no gaps)
      - Chronological order
      - Immutable (can't change history)")

  (get-at-time [store entity-type id timestamp]
    "Get entity as it was at specific time.

    Args:
      store - Store instance
      entity-type - Keyword
      id - UUID string
      timestamp - ISO 8601 string

    Returns:
      Entity map as it existed at that time, or nil

    Guarantees:
      - Point-in-time consistency
      - Reproducible (same time → same data)
      - No 'current' bias (treats all times equally)"))
```

---

## 2. Parser Protocol - Complete Specification

### Purpose
Parse external data (CSV, JSON, PDF, etc.) into canonical domain model

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

    Guarantees:
      - Fast (< 100ms, only reads header)
      - Probabilistic (returns confidence)
      - Explainable (shows why)")

  (supports-format? [parser format]
    "Check if parser supports given format.

    Args:
      parser - Parser instance
      format - Keyword (:csv, :json, etc.)

    Returns:
      Boolean

    Guarantees:
      - Fast (O(1) lookup)
      - Accurate (no false positives)"))
```

---

## 3. Validator Protocol - Complete Specification

### Purpose
Validate data against rules with detailed explanations

### Protocol Definition

```clojure
(defprotocol Validator
  "Validate data against rules, return detailed results.

  Design Principles:
  - Rules are data (not code)
  - All validations return explanations
  - Warnings != Errors (severity levels)
  - Composable (combine validators)

  Rich Hickey: 'Make the rules explicit, observable, testable.'"

  (validate [validator data rules]
    "Validate data against rules.

    Args:
      validator - Validator instance
      data - Data to validate (any structure)
      rules - Vector of rule maps:
              [{:id :amount-positive
                :rule {:field :amount
                       :constraint [:> 0]}
                :severity :error
                :message \"Amount must be positive\"}]

    Returns:
      {:valid? true | false
       :score 0.95                    ; Overall quality score
       :results [{:rule-id :amount-positive
                  :status :pass | :fail | :warning
                  :severity :info | :warning | :error
                  :message \"Amount must be positive\"
                  :actual 45.99
                  :expected [:> 0]}]
       :stats {:total-rules 10
               :passed 9
               :failed 1
               :warnings 0}}

    Guarantees:
      - All rules evaluated (no short-circuit)
      - Every result has explanation
      - Reproducible (same data + rules → same result)")

  (explain [validator result]
    "Generate human-readable explanation of validation result.

    Args:
      validator - Validator instance
      result - Result map from validate()

    Returns:
      String with formatted explanation:

      \"Validation Result: FAILED (9/10 passed)

       ✓ PASS: amount-positive (Amount must be positive)
         Expected: > 0
         Actual: 45.99

       ✗ FAIL: merchant-known (Merchant must be in registry)
         Expected: In [STARBUCKS, AMAZON, ...]
         Actual: UNKNOWN CAFE
         Severity: ERROR

       Score: 0.95 / 1.00\"

    Guarantees:
      - Human-readable (not just for machines)
      - Actionable (shows what to fix)
      - Complete (includes all failures)")

  (compose [validator & validators]
    "Compose multiple validators into one.

    Args:
      validator - Base validator
      validators - Additional validators to compose

    Returns:
      New validator that runs all validators

    Guarantees:
      - All validators run (no short-circuit)
      - Results merged (no data loss)
      - Order preserved (deterministic)"))
```

---

## 4. Transformer Protocol - Complete Specification

### Purpose
Transform data between representations (parsing, normalization, enrichment)

### Protocol Definition

```clojure
(defprotocol Transformer
  "Transform data between representations.

  Design Principles:
  - Pure functions (no side effects)
  - Transducer-friendly (composable)
  - Context-independent (same input → same output)
  - Traceable (every transformation logged)

  Rich Hickey: 'Transformation is not mutation.'"

  (transform [transformer data context]
    "Transform data from one representation to another.

    Args:
      transformer - Transformer instance
      data - Input data (any structure)
      context - Map with:
                :transformation-type - :parse | :normalize | :enrich | :classify
                :rules - Rules to apply (if applicable)
                :timestamp - When transformation occurred

    Returns:
      {:status :success | :partial | :failure
       :input data                    ; Original (immutable)
       :output {...}                  ; Transformed result
       :transformations [{:step :normalize-merchant
                          :before \"STARBUCKS #5678\"
                          :after \"Starbucks\"
                          :confidence 0.98}]
       :metadata {:transformer-version \"1.0.0\"
                  :duration-ms 5
                  :rules-applied [:merchant-normalization]}}

    Guarantees:
      - Input never modified (immutable)
      - Every step logged (audit trail)
      - Reversible (if possible)
      - Idempotent (transform twice = transform once)")

  (compose-transformers [& transformers]
    "Compose multiple transformers into pipeline.

    Args:
      transformers - Sequence of transformers

    Returns:
      New transformer that applies all in sequence

    Guarantees:
      - Left-to-right execution (data flows →)
      - Short-circuit on error (don't corrupt)
      - All steps logged (complete trace)"))
```

---

## 5. EventStore Protocol - Complete Specification

### Purpose
Append-only event log (foundation of event sourcing)

### Protocol Definition

```clojure
(defprotocol EventStore
  "Immutable, append-only event log.

  Design Principles:
  - Events are facts (never change)
  - Log is source of truth
  - State is derived (projections)
  - Time-travel via replay

  Rich Hickey: 'Events are the primary representation.'"

  (append-event! [store event]
    "Append event to log.

    Args:
      store - EventStore instance
      event - Event map:
              {:event-id <uuid>
               :event-type :transaction-imported | :classification-changed | etc.
               :aggregate-id <uuid>           ; Entity this affects
               :aggregate-type :transaction | :bank | etc.
               :timestamp <iso-8601>
               :author \"darwin\"
               :data {...}                    ; Event-specific data
               :metadata {:source-file \"...\"
                         :correlation-id <uuid>}}

    Returns:
      {:event-id <uuid>
       :sequence-number 12345
       :timestamp <iso-8601>}

    Guarantees:
      - Append-only (can't modify past)
      - Atomic (all or nothing)
      - Ordered (sequence preserved)
      - Durable (survives crashes)")

  (get-events [store aggregate-type aggregate-id]
    "Get all events for an aggregate.

    Args:
      store - EventStore instance
      aggregate-type - :transaction | :bank | etc.
      aggregate-id - UUID string

    Returns:
      Vector of events in chronological order

    Guarantees:
      - Complete history (no gaps)
      - Chronological (oldest → newest)
      - Immutable (can't change events)")

  (get-events-since [store sequence-number]
    "Get all events after given sequence number.

    Args:
      store - EventStore instance
      sequence-number - Start from this sequence

    Returns:
      Vector of events after sequence-number

    Use case:
      - Catch up after downtime
      - Build projections incrementally
      - Real-time updates")

  (subscribe [store event-type handler-fn]
    "Subscribe to events of given type.

    Args:
      store - EventStore instance
      event-type - :transaction-imported | :all | etc.
      handler-fn - (fn [event] ...) called for each event

    Returns:
      Subscription handle (for unsubscribe)

    Use case:
      - Real-time updates
      - Async processing
      - Event-driven workflows"))
```

---

## 6. Projection Protocol - Complete Specification

### Purpose
Derive current state from event log

### Protocol Definition

```clojure
(defprotocol Projection
  "Derive state from events (read models).

  Design Principles:
  - State = fold(events)
  - Rebuildable (delete and replay)
  - Eventually consistent
  - Independent (different projections don't affect each other)

  Rich Hickey: 'State is a snapshot of accumulated events.'"

  (project [projection events initial-state]
    "Project events into current state.

    Args:
      projection - Projection instance
      events - Vector of events to apply
      initial-state - Starting state (or nil)

    Returns:
      Final state after applying all events

    Example:
      (project transaction-projection
               [{:event-type :transaction-imported
                 :data {:id \"tx1\" :amount 100}}
                {:event-type :transaction-classified
                 :aggregate-id \"tx1\"
                 :data {:category :restaurant}}]
               nil)

      => {:tx1 {:id \"tx1\"
                :amount 100
                :category :restaurant
                :version 2}}

    Guarantees:
      - Deterministic (same events → same state)
      - Reproducible (can rebuild anytime)
      - Idempotent (project twice = project once)")

  (rebuild! [projection event-store]
    "Rebuild projection from scratch.

    Args:
      projection - Projection instance
      event-store - EventStore with all events

    Process:
      1. Clear current state
      2. Replay ALL events from event-store
      3. Build new state

    Returns:
      New state

    Use case:
      - Fix bugs in projection logic
      - Add new fields
      - Recover from corruption")

  (update! [projection event]
    "Update projection with single event.

    Args:
      projection - Projection instance (with current state)
      event - New event to apply

    Returns:
      Updated state

    Use case:
      - Real-time updates (don't replay all)
      - Incremental processing"))
```

---

## 7. Implementation Order

### Phase 1: Core Protocols (Week 1)
1. Store protocol + in-memory implementation
2. Parser protocol + CSV implementation
3. Validator protocol + basic rules

### Phase 2: Event Sourcing (Week 2)
4. EventStore protocol + SQLite implementation
5. Projection protocol + transaction projection
6. Transformer protocol + normalization

### Phase 3: Finance Application (Week 3)
7. Finance domain models using protocols
8. Finance-specific parsers (BofA, AppleCard, etc.)
9. Finance-specific validators

---

## 8. Success Criteria

**Trust Construction Core:**
- ✅ All protocols implemented
- ✅ Works with 3+ domains (not just finance)
- ✅ 0 domain-specific code in protocols
- ✅ 100% test coverage for protocols
- ✅ Complete documentation with examples

**Finance Application:**
- ✅ Uses ONLY trust-construction protocols
- ✅ Domain-specific code ONLY in finance/*
- ✅ Can swap implementations (SQLite → Datomic)
- ✅ Passes all existing tests
- ✅ Same functionality as current system

---

## 9. Key Design Decisions

### Decision 1: Why Protocols Over Multimethods?

**Protocols:**
- Faster (direct dispatch)
- Better IDE support (autocomplete)
- Explicit contracts (see all methods)
- Java interop (if needed)

**Multimethods:**
- More flexible dispatch
- Can dispatch on arbitrary function
- But: slower, less discoverable

**Choice:** Protocols (Rich uses them in core libraries)

---

### Decision 2: Why Separate Store and EventStore?

**Could combine them:**
```clojure
(defprotocol Store
  (append! [store data])
  (append-event! [store event]))  ; Why separate?
```

**Reason to separate:**
- **Store** = generic data storage (could be anything)
- **EventStore** = specific pattern (events, sequence, replay)
- Separation of concerns (can swap implementations)
- Store could be KV store, EventStore is always log

**Choice:** Separate (clearer concerns)

---

### Decision 3: Why Return Maps Everywhere?

**Alternative:** Return values directly
```clojure
(append! store data)  ; Returns: uuid
```

**Current approach:** Return rich maps
```clojure
(append! store data)  ; Returns: {:id uuid :version 1 :timestamp ...}
```

**Reason:**
- More information = better debugging
- Easy to extend (add fields without breaking)
- Supports error handling (can return :status :error)
- Rich Hickey: "Return data, not objects"

**Choice:** Return maps (more flexible)

---

**Next:** Create data schemas specification
