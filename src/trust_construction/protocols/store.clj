(ns trust-construction.protocols.store
  "Store protocol - Immutable, append-only storage with query capabilities.

  Design Philosophy (Rich Hickey):
  - Put complexity in data (query spec), not code (methods)
  - Protocols are for polymorphism (multiple storage backends)
  - Small interface = better (2 methods, not 5)

  Implementations:
  - MemoryStore: In-memory (fast, ephemeral)
  - SQLiteStore: Persistent (durable, crash-safe)
  - FileStore: Append-only files (simple, auditable)

  Version: 2.0 (Simplified from 5 methods → 2 methods)")

(defprotocol Store
  "Immutable, append-only storage with query capabilities.

  All power is in the query spec (data-driven queries).

  Rich Hickey: 'Put the complexity in the data, not the code.'"

  (append! [store data metadata]
    "Append new data to store.

    Args:
      store    - Store instance
      data     - Any Clojure data structure (map, vector, etc.)
      metadata - Map with:
                 :entity-type - Keyword (:transaction, :bank, :event, etc.)
                 :author      - String (who made the change)
                 :timestamp   - ISO 8601 string (when)
                 :provenance  - Map (where data came from)

    Returns:
      {:id        \"550e8400-e29b-41d4-a716-446655440000\"
       :version   1
       :timestamp \"2025-11-07T10:30:00.000Z\"
       :hash      \"sha256:abc123...\"}

    Example:
      (append! store
        {:amount 45.99 :merchant \"Starbucks\"}
        {:entity-type :transaction
         :author \"darwin\"
         :timestamp \"2025-11-07T10:30:00Z\"
         :provenance {:source-file \"bofa_march.csv\" :line 23}})

    Guarantees:
      - Idempotent: Same hash → skip (not error)
      - Atomic: All or nothing
      - Durable: Survives crashes (persistent stores)
      - Ordered: Timestamp ordering preserved")

  (query [store spec]
    "Query data with flexible spec (ALL queries go through this).

    Args:
      store - Store instance
      spec  - Query specification (data, not code!):

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
      Vector of matching entities with full metadata:
      [{:id \"550e8400-...\"
        :entity-type :transaction
        :version 1
        :data {:amount 45.99 :merchant \"Starbucks\"}
        :metadata {:author \"darwin\" :timestamp \"...\" :provenance {...}}
        :hash \"sha256:abc123...\"}]

    Examples:
      ;; Get by ID (replaces old get-by-id method)
      (query store {:entity-type :transaction
                    :id \"tx-001\"})

      ;; Get versions (replaces old get-versions method)
      (query store {:entity-type :transaction
                    :id \"tx-001\"
                    :versions :all})

      ;; Time-travel (replaces old get-at-time method)
      (query store {:entity-type :transaction
                    :id \"tx-001\"
                    :as-of \"2025-01-01T00:00:00Z\"})

      ;; Complex query
      (query store {:entity-type :transaction
                    :filters {:category :restaurant
                             :amount [:> 50]}
                    :limit 10})

    Guarantees:
      - Consistent reads: Snapshot isolation
      - Reproducible: Same spec → same result
      - Lazy: Don't load all data at once
      - Extensible: Add new query types without changing protocol"))

;; Helper functions (not in protocol)

(defn supports-query-type?
  "Check if store supports a specific query type.

  Not in protocol - just a helper function."
  [store query-type]
  ;; All stores should support basic queries
  ;; Some stores might support advanced features
  (contains? #{:by-id :by-filter :time-travel :versions} query-type))

(defn compute-hash
  "Compute SHA-256 hash of data for idempotency.

  This ensures same data = same hash = skip duplicate."
  [data metadata]
  (let [content (str data metadata)
        bytes (.getBytes content "UTF-8")
        md (java.security.MessageDigest/getInstance "SHA-256")
        digest (.digest md bytes)
        hex-str (apply str (map #(format "%02x" %) digest))]
    (format "sha256:%s" hex-str)))

(defn generate-id
  "Generate UUID for new entity."
  []
  (str (java.util.UUID/randomUUID)))

(defn now-iso8601
  "Get current timestamp in ISO 8601 format."
  []
  (str (java.time.Instant/now)))
