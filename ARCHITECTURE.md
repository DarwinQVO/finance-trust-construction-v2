# 🏗️ Architecture: Clojure-Python Split

**Date:** 2025-11-05
**Status:** 🚧 In Progress - Phase 1
**Philosophy:** Rich Hickey's Simplicity, Decomplecting, and Data-Oriented Design

---

## 🎯 Core Principles

### 1. Transducers: Process Transformation Over Data Transformation

**Rich Hickey's Key Insight:**
> "A transducer transforms the reducing process itself, not the data. It's context-independent—works with collections, streams, channels, or any reduction context."

**Why Critical For This System:**
```clojure
;; ❌ WITHOUT Transducers (intermediate collections)
(->> transactions
     (filter expensive?)        ; → 4,877 intermediate objects
     (map detect-merchant)      ; → 4,877 intermediate objects
     (map detect-category)      ; → 4,877 intermediate objects
     (take 100))                ; → 100 final objects
;; Memory: 14,631 objects created

;; ✅ WITH Transducers (single pass, no intermediates)
(transduce
  (comp
    (filter expensive?)
    (map detect-merchant)
    (map detect-category)
    (take 100))
  conj
  []
  transactions)
;; Memory: 100 objects created (97% reduction!)
```

**Benefits For Our Architecture:**
1. **Performance:** No intermediate collections = 10-100x less memory
2. **Composability:** Build pipelines as data (test/modify/reuse)
3. **Context-independence:** Same code for batch/streaming/channels
4. **Parallelism:** Order-independent operations auto-parallelize

**Application Across Phases:**
- Phase 1 (API): Handler pipelines with transducers
- Phase 2 (Python): Batch ML calls with transducers
- Phase 3 (Integration): core.async channels + transducers = perfect match

---

### 2. Separation of Concerns
```
Clojure: Control Plane
- Configuration (EDN/CUE)
- Orchestration (workflows, pipelines with transducers)
- Provenance (immutable event log)
- APIs (REST/GraphQL)
- Business rules (rules as data)

Python: ML Workloads
- Detectors (merchant, category, anomaly)
- LLM integration (OpenAI, Claude, etc.)
- Model lifecycle (load, inference, monitoring)
- Embeddings & vector search
```

### 2. Process-Level Isolation
```
NO embedded Python (libpython-clj2) ❌
YES separate processes (HTTP/gRPC) ✅

Why:
- Clean dependency boundaries
- Independent scaling
- Language-specific optimization
- Fault isolation (Python crash ≠ Clojure crash)
```

### 3. Communication via Data
```
Protocols: HTTP/JSON, gRPC, message queues
Schemas: Versioned (v1, v2, v3)
Formats: EDN (Clojure), Transit (Clojure↔Python), JSON (universal)

Example:
POST /v1/detect/merchant
Content-Type: application/transit+json

{:transaction/id "tx-12345"
 :transaction/description "STARBUCKS #1234 SEATTLE WA"
 :schema-version "v1.0"}

Response:
{:detection/merchant :starbucks
 :detection/confidence 0.95
 :detection/model "gpt-4-2024-01-01"
 :detection/timestamp "2024-03-20T10:00:00Z"
 :schema-version "v1.0"}
```

---

## 🏭 Rich Hickey's "Systems as Machines" Pattern

**Source:** [Simple Made Easy](https://www.infoq.com/presentations/Simple-Made-Easy/) & Systems thinking

### The Core Insight

> "Don't build systems as **objects that call each other to change state**. Build them as **machines where values flow through transformations**."

### The 4 Service Types (And ONLY 4)

Every service in our system does ONE of these:

#### 1. TRANSFORM (Pure Function at Service Scale)
```python
# ✅ CORRECT: Python ML Service
@app.post("/classify")
def classify_merchant(tx: Transaction) -> Classification:
    # Receives VALUE (immutable transaction data)
    result = ml_model.predict(tx.description)
    # Returns NEW VALUE (classification)
    return Classification(
        merchant=result.merchant,
        confidence=result.confidence,
        model_version="gpt-4-2024"
    )
    # NO state changed, NO database modified
    # PURE transformation: value → new value
```

```clojure
;; ✅ CORRECT: Clojure API Handler
(defn list-transactions-handler [{:keys [conn query-params]}]
  (let [raw-txs (query-datomic conn)
        pipeline (build-response-pipeline query-params)
        result (into [] pipeline raw-txs)]  ; ← TRANSFORM
    {:status 200 :body {:transactions result}}))
    ;; Receives request VALUE → Returns response VALUE
    ;; NO mutation, NO side effects (except READ from DB)
```

**Key:** Service receives value, transforms it, returns new value. Like a function.

---

#### 2. MOVE (Transport Data)
```clojure
;; ✅ CORRECT: core.async channels (Phase 3)
(let [tx-channel (async/chan 100)]
  ;; Channel ONLY moves values from A to B
  ;; It doesn't transform, decide, or remember
  (async/>!! tx-channel transaction)
  (async/<!! tx-channel))
  ;; Pure transportation
```

**Key:** Like a conveyor belt. Just moves, doesn't change.

---

#### 3. ROUTE (Make Decisions)
```clojure
;; ✅ CORRECT: Reitit Router
["/api/v1"
 ["/transactions" {:get list-transactions-handler}]
 ["/classify" {:post proxy-to-python-handler}]]

;; Or custom routing logic:
(defn route-for-classification [tx]
  (if (needs-ml? tx)
    (send-to-python-service tx)
    (use-rule-based-classifier tx)))
```

**Key:** Decides WHERE values go, doesn't change the values.

---

#### 4. REMEMBER (Store for Future)
```clojure
;; ✅ CORRECT: Datomic (append-only)
(d/transact conn [{:transaction/amount 100
                   :transaction/merchant "STARBUCKS"}])
;; Stores VALUE, doesn't modify existing data
;; Immutable, append-only log

;; ✅ CORRECT: Event sourcing
(store-event! {:event/type :transaction-classified
               :event/tx-id tx-id
               :event/classification result
               :event/timestamp (now)})
```

**Key:** Write values to durable storage. Never UPDATE, only APPEND.

---

### ❌ ANTI-PATTERN: "Revenge of Objects"

**DANGER ZONE - What NOT to do:**

```python
# ❌ WRONG: Service modifies another service's state
@app.post("/classify")
def classify_merchant(tx_id: str):
    # Calls Clojure API to CHANGE state
    clojure_api.update_transaction(tx_id, {
        "merchant": "STARBUCKS",
        "confidence": 0.95
    })
    return {"status": "updated"}
    # This is DISTRIBUTED OBJECTS - VERY BAD!
```

```clojure
;; ❌ WRONG: Clojure calls Python which calls back to Clojure
(defn classify-transaction! [tx-id]
  ;; Call Python
  (let [result (http/post python-url {:tx-id tx-id})]
    ;; Python internally calls BACK to Clojure to modify state
    ;; This creates circular dependencies and state chaos
    result))
```

**Why This is Bad:**
- Creates "distributed object graph" (spaghetti at system scale)
- State changes ripple unpredictably
- Impossible to debug ("who changed what when?")
- Can't test in isolation
- Can't scale (tight coupling)

---

### ✅ CORRECT PATTERN: Value Flow

**How our system WILL work (Phase 2-3):**

```
Transaction (immutable value)
      ↓
[1. ROUTE] Clojure API decides: "needs ML classification"
      ↓
[2. MOVE] core.async channel transports value to Python queue
      ↓
[3. TRANSFORM] Python ML receives value, transforms, returns new value
      ↓
[2. MOVE] core.async channel transports result back
      ↓
[1. ROUTE] Clojure decides: "high confidence, accept"
      ↓
[4. REMEMBER] Datomic appends classification to log
      ↓
Done. New immutable value stored.
```

**Key Properties:**
- ✅ No service "tells" another to "do something"
- ✅ Values flow like water through pipes
- ✅ Each step is testable (pure function)
- ✅ Easy to debug (trace value through pipeline)
- ✅ Easy to scale (add more workers at any step)

---

### Applied to Our Architecture

| Component | Role | Pattern | Example |
|-----------|------|---------|---------|
| **Clojure API** | TRANSFORM + ROUTE | Handler transforms request → response. Router decides which handler. | `(into [] pipeline raw-txs)` |
| **Python ML** | TRANSFORM ONLY | Receives tx data, returns classification. NO state. | `def classify(tx) -> result` |
| **core.async** | MOVE | Channels transport values between services. | `(async/>!! ch value)` |
| **Datomic** | REMEMBER | Append-only storage. Never UPDATE. | `(d/transact conn [fact])` |
| **HTTP Client** | MOVE + ROUTE | Transports requests to Python, routes errors/retries. | `(http/post url data)` |

---

### Phase 2 Implementation Checklist

When building Python ML Service, ensure:

- [ ] **Endpoints are pure functions**
  ```python
  @app.post("/v1/classify/merchant")
  def classify(tx: Transaction) -> Classification:
      # ✅ Receives value, returns value
      # ❌ NO database writes
      # ❌ NO calls back to Clojure API
  ```

- [ ] **No shared state between requests**
  ```python
  # ❌ WRONG: Global state
  classifications_cache = {}

  # ✅ CORRECT: Stateless
  def classify(tx: Transaction) -> Classification:
      # Each request is independent
      # No mutable global state
  ```

- [ ] **Clojure owns persistence**
  ```clojure
  ;; Python ONLY transforms:
  (let [result (http/post python-url tx-data)]
    ;; Clojure decides what to do with result:
    (if (> (:confidence result) 0.7)
      (d/transact conn [result])  ; ← Clojure writes to DB
      (log/warn "Low confidence, skipped")))
  ```

- [ ] **Services never call each other bidirectionally**
  ```
  ✅ CORRECT:
  Clojure → Python (one way, async preferred)

  ❌ WRONG:
  Clojure ↔ Python (circular, creates coupling)
  ```

---

### Why This Matters

**Without this pattern:**
- System becomes "distributed object graph"
- State changes ripple unpredictably
- Debugging nightmare ("who changed what?")
- Can't scale (everything coupled)

**With this pattern:**
- System is like assembly line (easy to understand)
- Each step testable in isolation
- Easy to debug (trace value through pipeline)
- Easy to scale (add workers at any step)
- Easy to modify (swap out any transform)

---

**Remember:** If a service "asks" another service to "do something", you're building distributed objects. **STOP.** Redesign as values flowing through transformations.

---

## 👤 Human-in-the-Loop Pattern

**The Missing Piece:** ML can predict, but humans decide what becomes truth.

### The Core Insight

> "The system doesn't accumulate ML predictions. It accumulates human-approved facts. ML transforms values, humans decide which transformed values become knowledge."

### The Complete Value Flow (with Human)

```
1. IMPORT
   Raw Transaction (from CSV/bank)
         ↓
   [REMEMBER] Store raw fact in Datomic
         ↓
   New immutable value exists

2. TRANSFORM (ML)
   Transaction value
         ↓
   [ROUTE] Clojure: "needs classification"
         ↓
   [MOVE] Send to Python ML
         ↓
   [TRANSFORM] Python: Transaction → Classification
         ↓
   [MOVE] Return classification value
         ↓
   Classification value exists (NOT stored yet!)

3. PRESENT FOR OBSERVATION (Human)
   Classification value (with confidence)
         ↓
   [ROUTE] Clojure: "show to human for review"
         ↓
   UI displays:
     • Transaction: "STARBUCKS #1234" ($4.99)
     • ML says: Merchant=Starbucks, Category=Café
     • Confidence: 95%
     • Actions: [Approve] [Reject] [Correct]
         ↓
   Human observes (no state change yet!)

4. DECISION (Human)
   Human clicks [Approve]
         ↓
   [TRANSFORM] UI: Button click → Decision event
         ↓
   Decision value exists

5. ACCUMULATE APPROVED FACT
   Decision value
         ↓
   [ROUTE] Clojure: "human approved this classification"
         ↓
   [REMEMBER] Store approved fact:
     • Original transaction (already stored)
     • Classification result (NOW stored)
     • Human decision event (audit trail)
     • Timestamp + who approved
         ↓
   Knowledge graph grows with APPROVED fact only
```

---

### The 3 Human Decision Types

**1. Approve (Accept ML)**
```clojure
;; User clicks [Approve]
{:event/type :classification-approved
 :event/tx-id "tx-12345"
 :event/classification {:merchant :starbucks
                        :category :cafe
                        :confidence 0.95}
 :event/approved-by "user@example.com"
 :event/timestamp #inst "2024-03-20T10:30:00Z"
 :event/ml-model "gpt-4-2024-01-01"}

;; Result: Classification stored in Datomic
(d/transact conn
  [{:transaction/id "tx-12345"
    :transaction/merchant [:entity/canonical-name "starbucks"]
    :transaction/category [:entity/canonical-name "cafe"]
    :transaction/classification-confidence 0.95
    :transaction/classified-at #inst "2024-03-20T10:30:00Z"
    :transaction/classified-by "user@example.com"}])
```

---

**2. Reject (ML wrong, don't store)**
```clojure
;; User clicks [Reject]
{:event/type :classification-rejected
 :event/tx-id "tx-12345"
 :event/classification {:merchant :starbucks
                        :category :cafe
                        :confidence 0.95}
 :event/rejected-by "user@example.com"
 :event/timestamp #inst "2024-03-20T10:30:00Z"
 :event/reason "Wrong merchant detected"
 :event/ml-model "gpt-4-2024-01-01"}

;; Result: Transaction remains unclassified
;; ML result logged for model improvement
;; NO classification stored
```

---

**3. Correct (ML close, human fixes)**
```clojure
;; User clicks [Correct], modifies fields, clicks [Save]
{:event/type :classification-corrected
 :event/tx-id "tx-12345"
 :event/original-classification {:merchant :starbucks
                                 :category :cafe
                                 :confidence 0.95}
 :event/corrected-classification {:merchant :starbucks
                                  :category :breakfast  ; ← User changed
                                  :confidence 1.0}      ; ← Human = 100%
 :event/corrected-by "user@example.com"
 :event/timestamp #inst "2024-03-20T10:30:00Z"
 :event/correction-reason "Coffee + food = breakfast"
 :event/ml-model "gpt-4-2024-01-01"}

;; Result: Corrected classification stored
;; Original ML result logged for model training
(d/transact conn
  [{:transaction/id "tx-12345"
    :transaction/merchant [:entity/canonical-name "starbucks"]
    :transaction/category [:entity/canonical-name "breakfast"]  ; ← Corrected
    :transaction/classification-confidence 1.0                  ; ← Human
    :transaction/classified-at #inst "2024-03-20T10:30:00Z"
    :transaction/classified-by "user@example.com"}])
```

---

### UI Pattern for Human Review

**Review Queue Screen:**
```
╔══════════════════════════════════════════════════════════════╗
║ CLASSIFICATION REVIEW QUEUE                            [?]   ║
╠══════════════════════════════════════════════════════════════╣
║ Pending Review: 42 transactions                             ║
║                                                              ║
║ ┌──────────────────────────────────────────────────────────┐ ║
║ │ Transaction #tx-12345                                    │ ║
║ │                                                          │ ║
║ │ Date:        2024-03-20                                 │ ║
║ │ Amount:      $4.99                                      │ ║
║ │ Description: STARBUCKS #1234 SEATTLE WA                 │ ║
║ │                                                          │ ║
║ │ ── ML CLASSIFICATION ─────────────────────────────────  │ ║
║ │ Merchant:    STARBUCKS                                  │ ║
║ │ Category:    Café                                       │ ║
║ │ Confidence:  ████████░░ 95% [HIGH] ✓                   │ ║
║ │ Model:       gpt-4-2024-01-01                           │ ║
║ │                                                          │ ║
║ │ [✓ Approve]  [✗ Reject]  [✏️ Correct]          [Skip]  │ ║
║ └──────────────────────────────────────────────────────────┘ ║
║                                                              ║
║ Next: [Enter]  Previous: [Backspace]  Quit: [q]            ║
╚══════════════════════════════════════════════════════════════╝
```

**Correction Screen (if user clicks [Correct]):**
```
╔══════════════════════════════════════════════════════════════╗
║ CORRECT CLASSIFICATION - Transaction #tx-12345         [?]   ║
╠══════════════════════════════════════════════════════════════╣
║                                                              ║
║ Original Transaction:                                        ║
║   Description: STARBUCKS #1234 SEATTLE WA                   ║
║   Amount:      $4.99                                        ║
║                                                              ║
║ ── ML SUGGESTED ─────────────────────────────────────────   ║
║ Merchant: STARBUCKS                                         ║
║ Category: Café                                              ║
║                                                              ║
║ ── YOUR CORRECTION ──────────────────────────────────────   ║
║ Merchant: [STARBUCKS              ] ← Edit if needed        ║
║ Category: [Breakfast              ] ← Changed               ║
║                                                              ║
║ Reason (optional):                                          ║
║ ┌──────────────────────────────────────────────────────┐    ║
║ │ Coffee + bagel = breakfast not café                  │    ║
║ └──────────────────────────────────────────────────────┘    ║
║                                                              ║
║ [💾 Save]  [Cancel]                                         ║
╚══════════════════════════════════════════════════════════════╝
```

---

### Event Schema for Human Decisions

```clojure
;; Datomic schema for human decision events
{:db/ident :event/type
 :db/valueType :db.type/keyword
 :db/cardinality :db.cardinality/one
 :db/doc "Type of human decision event"}

{:db/ident :event/tx-id
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc "Transaction ID this event refers to"}

{:db/ident :event/classification
 :db/valueType :db.type/ref
 :db/cardinality :db.cardinality/one
 :db/doc "Reference to classification result"}

{:db/ident :event/approved-by
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc "Email/ID of user who approved"}

{:db/ident :event/timestamp
 :db/valueType :db.type/instant
 :db/cardinality :db.cardinality/one
 :db/doc "When decision was made"}

{:db/ident :event/ml-model
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc "ML model version that generated classification"}

{:db/ident :event/reason
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc "Why decision was made (for reject/correct)"}

{:db/ident :event/original-classification
 :db/valueType :db.type/ref
 :db/cardinality :db.cardinality/one
 :db/doc "Original ML classification (for corrections)"}

{:db/ident :event/corrected-classification
 :db/valueType :db.type/ref
 :db/cardinality :db.cardinality/one
 :db/doc "Human-corrected classification"}
```

---

### Integration with Value Flow

**Complete flow showing ALL 4 service types + Human:**

```
Raw Transaction
      ↓
[4. REMEMBER] Import raw fact
      ↓
Transaction entity exists
      ↓
[1. ROUTE] Router: "needs ML classification?"
      ↓
[2. MOVE] core.async: Send to ML queue
      ↓
[3. TRANSFORM] Python ML: tx → classification
      ↓
[2. MOVE] core.async: Return result
      ↓
Classification value exists (NOT persisted)
      ↓
[1. ROUTE] Router: "confidence < 100%? → needs human review"
      ↓
[2. MOVE] Add to human review queue
      ↓
UI presents for observation
      ↓
👤 HUMAN OBSERVES (no state change)
      ↓
👤 HUMAN DECIDES:
   • Approve → [4. REMEMBER] Store approved classification
   • Reject → [4. REMEMBER] Log rejection (no classification stored)
   • Correct → [4. REMEMBER] Store corrected classification
      ↓
Knowledge graph updated with ONLY approved facts
      ↓
[4. REMEMBER] Log decision event (audit trail)
      ↓
Done. All decisions recorded, all facts immutable.
```

---

### Why This Pattern Works

**1. ML Predictions ≠ Facts**
```
ML Output: "I think this is Starbucks" (confidence 95%)
Human Decision: "Yes, this IS Starbucks" (fact)

System accumulates FACTS, not predictions.
```

**2. Human Review as a Service Type**
```
Human is another TRANSFORM service:
  Input: Transaction + ML classification
  Output: Approved classification OR Rejection OR Correction

Difference: Async (human takes minutes, ML takes seconds)
```

**3. Confidence Threshold Routes to Human**
```clojure
(defn route-for-review [classification]
  (if (>= (:confidence classification) 0.90)
    :auto-approve     ; High confidence → skip human
    :human-review))   ; Low confidence → needs human
```

**4. All Decisions are Events (Audit Trail)**
```
Every human decision stored as immutable event:
- What was suggested (ML output)
- What was decided (approve/reject/correct)
- Who decided (user ID)
- When decided (timestamp)
- Why decided (optional reason)

Can replay decisions, analyze patterns, improve ML.
```

---

### Benefits of This Pattern

**For the System:**
- ✅ Knowledge graph contains ONLY human-approved facts
- ✅ ML results don't pollute data until approved
- ✅ Complete audit trail of every decision
- ✅ Can improve ML from human corrections

**For the User:**
- ✅ Always in control (ML suggests, human decides)
- ✅ Can correct ML mistakes before they're stored
- ✅ Can see confidence scores (know when to trust ML)
- ✅ Can skip low-confidence items for later review

**For Rich Hickey:**
- ✅ Values flow through transformations ✓
- ✅ Human is just another transform (async) ✓
- ✅ Routing based on data (confidence threshold) ✓
- ✅ Immutable events (all decisions recorded) ✓
- ✅ No distributed objects (UI doesn't modify DB directly) ✓

---

### Phase 2 Implementation Checklist (Updated)

When building Python ML Service AND human review:

- [ ] **Python endpoints return classifications with confidence**
  ```python
  return Classification(
      merchant="starbucks",
      confidence=0.95,  # ← CRITICAL for routing
      model_version="gpt-4-2024"
  )
  ```

- [ ] **Clojure routes based on confidence**
  ```clojure
  (if (>= (:confidence result) confidence-threshold)
    (store-classification! result)      ; Auto-approve
    (add-to-review-queue! result))      ; Human review
  ```

- [ ] **Create human review queue**
  ```clojure
  ;; In-memory queue (or Datomic entity)
  (def review-queue (atom []))

  (defn add-to-review-queue! [classification]
    (swap! review-queue conj classification))

  (defn get-next-for-review []
    (first @review-queue))
  ```

- [ ] **Add API endpoints for human decisions**
  ```clojure
  POST /v1/reviews/:tx-id/approve
  POST /v1/reviews/:tx-id/reject
  POST /v1/reviews/:tx-id/correct
  GET  /v1/reviews/queue
  ```

- [ ] **Store decision events**
  ```clojure
  (defn store-decision-event! [decision]
    (d/transact conn [decision])
    ;; If approved/corrected, also update transaction
    ;; If rejected, just log the event
    )
  ```

- [ ] **Build UI for review queue** (Phase 4)
  - Show transactions needing review
  - Display ML classification + confidence
  - Approve/Reject/Correct buttons
  - Correction form with pre-filled ML values

---

### Example: End-to-End Flow with Human

**Scenario:** Import transaction → ML classifies → Human approves

```clojure
;; 1. Import raw transaction
(import-transaction!
  {:description "STARBUCKS #1234 SEATTLE WA"
   :amount 4.99
   :date "2024-03-20"})
;; → tx-12345 created

;; 2. Trigger ML classification
(classify-transaction! "tx-12345")
;; → Calls Python ML service
;; → Returns: {:merchant :starbucks :category :cafe :confidence 0.95}

;; 3. Check confidence, route to human
(route-classification! "tx-12345" classification)
;; → confidence 95% < 100% → add to review queue
;; → UI shows transaction in review queue

;; 4. Human reviews in UI, clicks [Approve]
;; → POST /v1/reviews/tx-12345/approve
(approve-classification! "tx-12345" "user@example.com")

;; 5. Store approved fact + decision event
(d/transact conn
  [;; Update transaction with approved classification
   {:transaction/id "tx-12345"
    :transaction/merchant [:entity/canonical-name "starbucks"]
    :transaction/category [:entity/canonical-name "cafe"]
    :transaction/classification-confidence 0.95}

   ;; Store decision event
   {:event/type :classification-approved
    :event/tx-id "tx-12345"
    :event/approved-by "user@example.com"
    :event/timestamp (java.util.Date.)}])

;; Done! Knowledge graph now contains human-approved fact.
```

---

### Lego Pieces Analogy

**Your insight:** "es como piezas de lego y como una linea de ensamblaje"

**Exactly! The pieces:**

1. **Import** (Lego piece #1): Raw transaction → System
2. **ML Transform** (Lego piece #2): Transaction → Classification
3. **Human Transform** (Lego piece #3): Classification → Decision
4. **Store** (Lego piece #4): Decision → Knowledge graph

**The assembly line:**
```
Raw CSV → [Import] → [ML] → [Human] → [Store] → Done
          piece 1    piece 2  piece 3   piece 4

Each piece:
- Takes values as input
- Transforms them
- Outputs new values
- Can be replaced/upgraded independently
- Can be tested in isolation
```

**Why this works:**
- ✅ Pieces don't know about each other
- ✅ Values flow through like items on conveyor belt
- ✅ Can add quality control at any stage
- ✅ Can run multiple assembly lines in parallel
- ✅ Can swap pieces without stopping factory

---

**Rich Hickey Would Say:**
> "Perfect. You understood the key insight: human review is not special—it's just another transformation in the pipeline. The only difference is latency (humans are slower than ML). But the pattern is identical: receive value, transform it, return new value. The routing logic (confidence threshold) decides which path values take. Beautiful."

---

## 🔄 Transducer Patterns (Applied)

### Pattern 1: API Response Pipelines

**Use Case:** Format transactions for API response with filtering/pagination

```clojure
;; Define reusable transducers
(def enrich-transaction
  "Add denormalized fields for API response."
  (map (fn [tx]
         (assoc tx
           :bank-name (get-in tx [:transaction/bank :entity/canonical-name])
           :merchant-name (get-in tx [:transaction/merchant :entity/canonical-name])
           :category-name (get-in tx [:transaction/category :entity/canonical-name])))))

(defn filter-by-type
  "Transducer factory: Filter by type."
  [type]
  (filter #(= type (:transaction/type %))))

(defn paginate
  "Transducer factory: Skip + take."
  [offset limit]
  (comp (drop offset) (take limit)))

;; Compose pipeline (NO data processed yet)
(defn build-response-pipeline [type offset limit]
  (cond-> (comp enrich-transaction)
    type   (comp (filter-by-type type))
    true   (comp (paginate offset limit))))

;; Apply to data - single pass!
(into [] (build-response-pipeline :GASTO 0 100) raw-transactions)
```

**Benefit:** Add filters without touching core code. Pipeline is testable data.

---

### Pattern 2: ML Detection Pipeline (Phase 3)

**Use Case:** Batch ML calls with transformations

```clojure
(def ml-detection-pipeline
  "Pipeline: batch → detect → enrich → store"
  (comp
    ;; Batch for efficiency (100 txs/request)
    (partition-all 100)

    ;; Parallel ML detection (mapcat flattens results)
    (mapcat (fn [batch]
              (batch-call-ml-service :merchant batch)))

    ;; Filter low-confidence (< 0.7)
    (filter #(> (:confidence %) 0.7))

    ;; Enrich with metadata
    (map (fn [detection]
           (assoc detection
             :processed-at (java.util.Date.)
             :version "v1.0")))

    ;; Store to Datomic
    (map store-detection!)))

;; Apply to transactions
(transduce ml-detection-pipeline + 0 transactions)
;; Returns: count of processed transactions
```

**Benefits:**
- 4,877 txs → 49 batches (100 each) → 49 HTTP requests (vs 4,877)
- Filters bad detections BEFORE storing
- Single pass through data
- Easy to add steps (e.g., (map log-detection))

---

### Pattern 3: Streaming with core.async (Phase 3)

**Use Case:** Real-time transaction processing

```clojure
(require '[clojure.core.async :as async])

;; Same pipeline definition!
(def streaming-detection-pipeline
  (comp
    (map detect-merchant)
    (filter high-confidence?)
    (map store-detection!)))

;; Apply to channel (context-independent!)
(let [in-chan (async/chan 100)
      out-chan (async/chan 100 streaming-detection-pipeline)]

  ;; Pipeline processes automatically
  (async/pipeline 4 out-chan streaming-detection-pipeline in-chan)

  ;; Feed transactions
  (async/>!! in-chan new-transaction))
```

**Benefit:** Same code for batch AND streaming. Change context, not logic.

---

### Pattern 4: Parallel Processing with Reducers (Future)

**Use Case:** Process 100K+ transactions in parallel

```clojure
(require '[clojure.core.reducers :as r])

;; Same transducer!
(def detection-xf
  (comp
    (map detect-merchant)
    (filter high-confidence?)))

;; Apply with parallel fold
(r/fold
  +                          ; Combine function
  (fn [acc tx]              ; Reducing function
    (inc acc))
  (detection-xf transactions))

;; Automatically partitions work across CPU cores
;; 8 cores → 8x speedup for order-independent operations
```

---

### Pattern 5: Testable Pipelines

**Use Case:** Test transformations in isolation

```clojure
;; Pipeline is data - easy to test!
(deftest test-detection-pipeline
  (let [pipeline (comp
                   (map detect-merchant)
                   (filter high-confidence?))

        test-data [{:description "STARBUCKS"}
                   {:description "UNKNOWN MERCHANT"}
                   {:description "AMAZON"}]

        result (into [] pipeline test-data)]

    (is (= 2 (count result)))  ; Low-confidence filtered out
    (is (= :starbucks (:merchant (first result))))))
```

**Benefit:** Test business logic without infrastructure (no DB, no HTTP).

---

## 🏛️ System Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                         UI Layer                             │
│                    (React + TypeScript)                      │
│                  Pure API Client - NO Logic                  │
└────────────────────────┬────────────────────────────────────┘
                         │ REST/GraphQL
                         ▼
┌─────────────────────────────────────────────────────────────┐
│                    Clojure Control Plane                     │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ REST API (Ring + Reitit)                             │   │
│  │ - Versioned endpoints (/v1/*, /v2/*)                 │   │
│  │ - EDN/Transit responses                              │   │
│  │ - Auth + CORS + Rate limiting                        │   │
│  └──────────────────────────────────────────────────────┘   │
│                         │                                    │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ Orchestration (Integrant/Mount)                      │   │
│  │ - Workflow engine (core.async)                       │   │
│  │ - Event sourcing (Datomic)                           │   │
│  │ - Provenance tracking                                │   │
│  └──────────────────────────────────────────────────────┘   │
│                         │                                    │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ HTTP Client (clj-http)                               │   │
│  │ - Circuit breaker                                    │   │
│  │ - Retry logic (exponential backoff)                 │   │
│  │ - Request/response logging                           │   │
│  └──────────────────────────────────────────────────────┘   │
└────────────────────────┬────────────────────────────────────┘
                         │ HTTP/JSON
                         ▼
┌─────────────────────────────────────────────────────────────┐
│                   Python ML Service                          │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ FastAPI + Uvicorn                                    │   │
│  │ - Async endpoints (/v1/detect/*)                     │   │
│  │ - Pydantic validation                                │   │
│  │ - OpenAPI documentation                              │   │
│  └──────────────────────────────────────────────────────┘   │
│                         │                                    │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ Detectors                                            │   │
│  │ - MerchantDetector                                   │   │
│  │ - CategoryDetector                                   │   │
│  │ - AnomalyDetector                                    │   │
│  └──────────────────────────────────────────────────────┘   │
│                         │                                    │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ Model Management                                     │   │
│  │ - Model registry (local/remote)                     │   │
│  │ - Model versioning                                   │   │
│  │ - LLM API clients (OpenAI, Claude)                  │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

---

## 📁 Project Structure

```
finance-clj/
├── src/
│   ├── finance/
│   │   ├── api/                    # NEW - REST API
│   │   │   ├── core.clj           # API server setup
│   │   │   ├── routes.clj         # Route definitions
│   │   │   ├── handlers.clj       # Request handlers
│   │   │   ├── middleware.clj     # Auth, CORS, logging
│   │   │   └── schemas.clj        # Response schemas
│   │   │
│   │   ├── orchestration/          # NEW - Workflow engine
│   │   │   ├── core.clj           # Orchestrator
│   │   │   ├── detectors.clj      # ML detector integration
│   │   │   └── workflows.clj      # Pipeline definitions
│   │   │
│   │   ├── clients/                # NEW - External clients
│   │   │   ├── ml_service.clj     # Python ML service client
│   │   │   └── retry.clj          # Circuit breaker + retry
│   │   │
│   │   ├── core_datomic.clj       # Existing - DB layer
│   │   ├── entities.clj           # Existing - Entity management
│   │   └── classification.clj     # Existing - Rules engine
│   │
│   ├── trust/                      # Existing - Trust layer
│   └── scripts/                    # Existing - Import scripts
│
├── python-ml/                      # NEW - Python ML service
│   ├── app/
│   │   ├── main.py                # FastAPI app
│   │   ├── config.py              # Configuration
│   │   ├── detectors/
│   │   │   ├── merchant.py
│   │   │   ├── category.py
│   │   │   └── anomaly.py
│   │   ├── models/
│   │   │   ├── registry.py        # Model management
│   │   │   └── loader.py
│   │   └── api/
│   │       └── v1/
│   │           └── endpoints.py   # API routes
│   │
│   ├── tests/                      # Python tests
│   ├── Dockerfile
│   ├── requirements.txt
│   └── docker-compose.yml
│
├── test/                           # Clojure tests
├── resources/
└── docs/
    └── api/                        # NEW - API documentation
        ├── openapi.yaml
        └── examples/
```

---

## 🔌 API Design

### Clojure REST API

**Base URL:** `http://localhost:3000/api`

**Endpoints:**

```clojure
;; Transactions
GET    /v1/transactions              ; List all
GET    /v1/transactions/:id          ; Get one
POST   /v1/transactions              ; Create
PATCH  /v1/transactions/:id          ; Update

;; Detections (triggers ML)
POST   /v1/detections/merchant       ; Detect merchant
POST   /v1/detections/category       ; Detect category
POST   /v1/detections/anomaly        ; Detect anomalies
POST   /v1/detections/batch          ; Batch detection

;; Rules
GET    /v1/rules                     ; List rules
POST   /v1/rules                     ; Create rule
GET    /v1/rules/:id                 ; Get rule
PATCH  /v1/rules/:id                 ; Update rule

;; System
GET    /v1/health                    ; Health check
GET    /v1/metrics                   ; System metrics
GET    /v1/schema                    ; API schema
```

### Python ML Service API

**Base URL:** `http://localhost:8000`

**Endpoints:**

```python
# Detections
POST   /v1/detect/merchant           # Detect merchant
POST   /v1/detect/category           # Detect category
POST   /v1/detect/anomaly            # Detect anomalies
POST   /v1/detect/batch              # Batch detection

# Models
GET    /v1/models                    # List available models
GET    /v1/models/:id                # Get model info
POST   /v1/models/:id/predict        # Run inference

# System
GET    /health                       # Health check
GET    /metrics                      # Service metrics
GET    /docs                         # OpenAPI docs
```

---

## 🔄 Data Flow Example

**Scenario:** User submits transaction → System detects merchant using ML

```
1. UI → POST /v1/transactions
   {
     :transaction/description "STARBUCKS #1234 SEATTLE WA"
     :transaction/amount 4.99
     :transaction/date "2024-03-20"
   }

2. Clojure API → Saves to Datomic
   → Emits :transaction-imported event
   → Returns transaction ID

3. Orchestrator → Detects new transaction
   → Triggers ML detection workflow
   → POST http://localhost:8000/v1/detect/merchant
   {
     "transaction_id": "tx-12345",
     "description": "STARBUCKS #1234 SEATTLE WA"
   }

4. Python ML Service → Runs GPT-4 inference
   → Returns detection
   {
     "merchant": "starbucks",
     "confidence": 0.95,
     "model": "gpt-4-2024-01-01"
   }

5. Orchestrator → Saves detection to Datomic
   → Emits :merchant-detected event
   → Updates transaction with merchant reference

6. UI → Polls GET /v1/transactions/:id
   → Sees updated transaction with merchant
```

---

## 🛡️ Error Handling

### Clojure → Python Communication

```clojure
;; Circuit breaker pattern
(defn call-ml-service [endpoint payload]
  (try
    (let [response (http/post
                     (str ml-service-url endpoint)
                     {:body (transit/write payload)
                      :content-type "application/transit+json"
                      :timeout 5000})]
      (if (= 200 (:status response))
        {:success true :data (transit/read (:body response))}
        {:success false :error :http-error :status (:status response)}))
    (catch Exception e
      {:success false :error :exception :message (.getMessage e)})))

;; Retry with exponential backoff
(defn call-with-retry [f max-attempts]
  (loop [attempt 1]
    (let [result (f)]
      (if (:success result)
        result
        (if (< attempt max-attempts)
          (do
            (Thread/sleep (* 1000 (Math/pow 2 (dec attempt))))
            (recur (inc attempt)))
          result)))))
```

---

## 📊 Monitoring & Observability

### Metrics to Track

**Clojure Side:**
- Request rate (requests/sec)
- Response time (p50, p95, p99)
- Error rate (%)
- ML service call latency
- Circuit breaker state

**Python Side:**
- Inference time (per model)
- Model cache hit rate
- Token usage (for LLMs)
- Error rate by detector
- Queue depth

### Logging Strategy

```clojure
;; Structured logging with provenance
(log/info :event :ml-detection-started
          :transaction-id "tx-12345"
          :detector :merchant
          :timestamp (java.util.Date.))

(log/info :event :ml-detection-completed
          :transaction-id "tx-12345"
          :detector :merchant
          :confidence 0.95
          :latency-ms 234
          :model "gpt-4-2024-01-01")
```

---

## 🚀 Deployment

### Development (Docker Compose)

```yaml
version: '3.8'
services:
  clojure-api:
    build: .
    ports:
      - "3000:3000"
    environment:
      - DATOMIC_URI=datomic:dev://localhost:4334/finance
      - ML_SERVICE_URL=http://python-ml:8000
    depends_on:
      - datomic
      - python-ml

  python-ml:
    build: ./python-ml
    ports:
      - "8000:8000"
    environment:
      - OPENAI_API_KEY=${OPENAI_API_KEY}
      - LOG_LEVEL=INFO

  datomic:
    image: datomic/datomic-free
    ports:
      - "4334:4334"
```

### Production

```
Clojure API:
- Deploy to AWS ECS/Fargate or Kubernetes
- Scale horizontally (stateless)
- Load balancer (ALB/NLB)

Python ML Service:
- Deploy to AWS ECS/Fargate or Kubernetes
- Scale based on CPU (inference-heavy)
- Consider GPU instances for large models

Datomic:
- Datomic Cloud (AWS) for production
- Backup strategy (daily snapshots)
```

---

## 📈 Scalability

### Horizontal Scaling

```
Clojure API: Stateless → Scale to N instances
Python ML: Stateless → Scale to M instances

Load distribution:
- Round-robin for Clojure
- Least-connections for Python (inference varies)
```

### Vertical Scaling

```
Clojure: CPU-bound (increase CPU)
Python: Memory-bound (large models) + CPU-bound (inference)

Recommendation:
- Clojure: 2 vCPU, 4GB RAM per instance
- Python: 4 vCPU, 8GB RAM per instance (without GPU)
```

---

## 🔐 Security

### API Authentication

```clojure
;; JWT-based auth
(defn wrap-auth [handler]
  (fn [request]
    (if-let [token (get-in request [:headers "authorization"])]
      (if (valid-token? token)
        (handler (assoc request :user (decode-token token)))
        {:status 401 :body "Unauthorized"})
      {:status 401 :body "Missing token"})))
```

### Python Service Security

```python
# API key validation
@app.middleware("http")
async def validate_api_key(request: Request, call_next):
    api_key = request.headers.get("X-API-Key")
    if api_key != settings.API_KEY:
        return JSONResponse(
            status_code=401,
            content={"error": "Invalid API key"}
        )
    return await call_next(request)
```

---

## ✅ Success Criteria

**Phase 1 Complete When:**
- ✅ Clojure REST API running on port 3000
- ✅ All endpoints respond with proper EDN/Transit
- ✅ Error handling + middleware working
- ✅ Tests passing (unit + integration)

**Phase 2 Complete When:**
- ✅ Python ML service running on port 8000
- ✅ 3 detectors functional (merchant, category, anomaly)
- ✅ Docker container builds successfully
- ✅ Health checks responding

**Phase 3 Complete When:**
- ✅ Clojure → Python communication working
- ✅ End-to-end detection flow successful
- ✅ Error handling + retries tested
- ✅ Performance acceptable (<1s for detection)

**Phase 4 Complete When:**
- ✅ Architecture documentation complete
- ✅ API documentation (OpenAPI)
- ✅ Deployment guide written
- ✅ Example workflows documented

---

## 🎯 Next Steps

**Now (Phase 1 - Week 1):**
1. Update deps.edn with Ring + Reitit
2. Create API namespace structure
3. Implement first endpoint (GET /v1/health)
4. Add middleware (CORS, logging)
5. Write first API test

**Later:**
- Phase 2: Python ML service
- Phase 3: Integration
- Phase 4: Documentation

---

**Rich Hickey Would Say:**
> "Good architecture. Clojure for orchestration keeps immutability and time. Python for ML chooses the right tool. Process boundaries maintain simplicity. Data as the interface decomplects systems."

---

*Generated: 2025-11-05*
*Finance Trust Construction v2.0 - Architectural Evolution*
