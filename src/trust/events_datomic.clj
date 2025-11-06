(ns trust.events-datomic
  "Event sourcing with Datomic - IMMUTABLE event log with native time-travel.

  Why Datomic > In-Memory Collections:
  1. ✅ Immutable by default - Never overwrites, only adds
  2. ✅ Time-travel built-in - (d/as-of db t) is native
  3. ✅ Persistent - Survives REPL restarts
  4. ✅ Datalog queries - More powerful than filters
  5. ✅ ACID transactions - Coordinated updates
  6. ✅ Audit trail - See ALL changes forever

  Core concepts:
  - Events are facts in Datomic (Entity-Attribute-Value-Time)
  - Append-only by design (Datomic never overwrites)
  - Time-travel via d/as-of (no manual replay needed)
  - State derived by querying, not storing"
  (:require [datomic.api :as d]
            [trust.datomic-schema :as schema]
            [clojure.edn :as edn]))

;; ============================================================================
;; CONNECTION MANAGEMENT
;; ============================================================================

(defn create-event-db!
  "Create a new Datomic database for events.

  Args:
    uri - Datomic URI (e.g., \"datomic:mem://finance-events\")

  Returns the URI.

  Example:
    (create-event-db! \"datomic:mem://finance-events\")"
  [uri]
  (d/create-database uri)
  uri)

(defn connect-event-db
  "Connect to an existing event database.

  Args:
    uri - Datomic URI

  Returns Datomic connection.

  Example:
    (def conn (connect-event-db \"datomic:mem://finance-events\"))"
  [uri]
  (d/connect uri))

(defn init-event-store!
  "Initialize event store with schema.

  Args:
    conn - Datomic connection

  Example:
    (init-event-store! conn)"
  [conn]
  (schema/install-schema! conn))

;; ============================================================================
;; EVENT APPENDING
;; ============================================================================

(defn append-event!
  "Append an event to Datomic.

  Args:
    conn - Datomic connection
    event-type - Keyword event type
    data - Event data map
    metadata - (optional) Event metadata map

  Returns transaction result.

  Example:
    (append-event! conn :transaction-imported
      {:source :bofa :count 156}
      {:user-id \"darwin\"})"
  ([conn event-type data]
   (append-event! conn event-type data {}))
  ([conn event-type data metadata]
   (let [event-entity {:db/id (d/tempid :db.part/user)
                       :event/type event-type
                       :event/data (pr-str data)  ; Store as EDN string
                       :event/metadata (pr-str metadata)
                       :temporal/business-time (java.util.Date.)}]
     @(d/transact conn [event-entity]))))

;; ============================================================================
;; EVENT QUERIES (Native Datalog)
;; ============================================================================

(defn all-events
  "Get all events from the database.

  Args:
    db - Datomic database value

  Returns vector of event maps.

  Example:
    (all-events (d/db conn))"
  [db]
  (let [results (d/q '[:find ?e ?type ?data ?metadata ?time
                       :where
                       [?e :event/type ?type]
                       [?e :event/data ?data]
                       [?e :event/metadata ?metadata]
                       [?e :temporal/business-time ?time]]
                     db)]
    (mapv (fn [[eid type data metadata time]]
            {:db/id eid
             :event/type type
             :event/data (edn/read-string data)
             :event/metadata (edn/read-string metadata)
             :temporal/business-time time})
          results)))

(defn events-by-type
  "Get events of a specific type.

  Args:
    db - Datomic database value
    event-type - Keyword event type

  Example:
    (events-by-type (d/db conn) :transaction-imported)"
  [db event-type]
  (let [results (d/q '[:find ?e ?data ?metadata ?time
                       :in $ ?type
                       :where
                       [?e :event/type ?type]
                       [?e :event/data ?data]
                       [?e :event/metadata ?metadata]
                       [?e :temporal/business-time ?time]]
                     db
                     event-type)]
    (mapv (fn [[eid data metadata time]]
            {:db/id eid
             :event/type event-type
             :event/data (edn/read-string data)
             :event/metadata (edn/read-string metadata)
             :temporal/business-time time})
          results)))

(defn events-in-range
  "Get events within a time range.

  Args:
    db - Datomic database value
    start-time - Start instant
    end-time - End instant

  Example:
    (events-in-range (d/db conn)
      #inst \"2024-03-01\"
      #inst \"2024-03-31\")"
  [db start-time end-time]
  (let [results (d/q '[:find ?e ?type ?data ?metadata ?time
                       :in $ ?start ?end
                       :where
                       [?e :event/type ?type]
                       [?e :event/data ?data]
                       [?e :event/metadata ?metadata]
                       [?e :temporal/business-time ?time]
                       [(<= ?start ?time)]
                       [(< ?time ?end)]]
                     db
                     start-time
                     end-time)]
    (mapv (fn [[eid type data metadata time]]
            {:db/id eid
             :event/type type
             :event/data (edn/read-string data)
             :event/metadata (edn/read-string metadata)
             :temporal/business-time time})
          results)))

(defn latest-event
  "Get the most recent event (optionally filtered by type).

  Example:
    (latest-event (d/db conn))
    (latest-event (d/db conn) :transaction-imported)"
  ([db]
   (last (sort-by :temporal/business-time (all-events db))))
  ([db event-type]
   (last (sort-by :temporal/business-time (events-by-type db event-type)))))

(defn count-events
  "Count total events in database.

  Example:
    (count-events (d/db conn))
    ; => 42"
  [db]
  (d/q '[:find (count ?e) .
         :where [?e :event/type]]
       db))

;; ============================================================================
;; TIME-TRAVEL (Datomic Native Feature!)
;; ============================================================================

(defn as-of
  "Get database value as it was at a specific time.

  This is NATIVE Datomic time-travel - not manual replay!

  Args:
    conn - Datomic connection
    time - Instant to query

  Returns database value at that point in time.

  Example:
    (def past-db (as-of conn #inst \"2024-03-20\"))
    (all-events past-db)  ; Events as they existed on March 20"
  [conn time]
  (d/as-of (d/db conn) time))

(defn since
  "Get database value with only changes since a specific time.

  Args:
    conn - Datomic connection
    time - Instant to query from

  Returns database value with only recent changes.

  Example:
    (def recent-db (since conn #inst \"2024-03-20\"))
    (all-events recent-db)  ; Only events added after March 20"
  [conn time]
  (d/since (d/db conn) time))

(defn history
  "Get historical database with ALL assertions and retractions.

  Note: In Datomic with append-only events, this is same as current db
  since we never retract events.

  Example:
    (def hist-db (history conn))
    (all-events hist-db)"
  [conn]
  (d/history (d/db conn)))

;; ============================================================================
;; REPLAY (Derived State from Events)
;; ============================================================================

(defn replay-events
  "Replay events to rebuild state.

  Args:
    db - Datomic database value
    initial-state - Starting state
    reducer - Function (state event -> new-state)
    opts - Optional filters:
           :event-type - Only replay events of this type
           :since - Only replay events after this time

  Returns final state after replaying.

  Example:
    (replay-events (d/db conn) {}
      (fn [state event]
        (update state :count (fnil + 0)
                (get-in event [:event/data :count])))
      {:event-type :transaction-imported})"
  ([db initial-state reducer]
   (replay-events db initial-state reducer {}))
  ([db initial-state reducer {:keys [event-type since-time]}]
   (let [events (cond
                  event-type (events-by-type db event-type)
                  since-time (events-in-range db since-time (java.util.Date.))
                  :else (all-events db))
         sorted-events (sort-by :temporal/business-time events)]
     (reduce reducer initial-state sorted-events))))

;; ============================================================================
;; AGGREGATIONS
;; ============================================================================

(defn aggregate-events
  "Aggregate events into summary statistics.

  Example:
    (aggregate-events (d/db conn) :transaction-imported
      (fn [acc event]
        (-> acc
            (update :total-files (fnil inc 0))
            (update :total-txs (fnil + 0)
                    (get-in event [:event/data :count])))))"
  [db event-type aggregate-fn]
  (let [events (events-by-type db event-type)]
    (reduce aggregate-fn {} events)))

(defn event-timeline
  "Get timeline of events with counts per day.

  Example:
    (event-timeline (d/db conn))
    ; => {#inst \"2024-03-01\" 42, #inst \"2024-03-02\" 38}"
  [db]
  (let [events (all-events db)]
    (frequencies
      (map #(-> % :temporal/business-time
                .toInstant
                .toString
                (subs 0 10)
                java.time.LocalDate/parse)
           events))))

;; ============================================================================
;; UTILITIES
;; ============================================================================

(defn event-types
  "Get list of all unique event types.

  Example:
    (event-types (d/db conn))
    ; => [:transaction-imported :transaction-classified]"
  [db]
  (d/q '[:find [?type ...]
         :where [_ :event/type ?type]]
       db))

;; ============================================================================
;; EXAMPLE USAGE
;; ============================================================================

(comment
  (require '[datomic.api :as d])

  ;; 1. Create and initialize database
  (create-event-db! "datomic:mem://finance-events")
  (def conn (connect-event-db "datomic:mem://finance-events"))
  (init-event-store! conn)

  ;; 2. Append events
  (append-event! conn :transaction-imported
    {:source :bofa :count 156 :file "bofa_march.csv"}
    {:user-id "darwin"})

  (append-event! conn :transaction-classified
    {:transaction-id "tx-001" :category "restaurants"}
    {:classifier-version "v1.0"})

  ;; 3. Query events
  (all-events (d/db conn))
  (count-events (d/db conn))  ; => 2

  (events-by-type (d/db conn) :transaction-imported)
  (latest-event (d/db conn) :transaction-imported)

  ;; 4. TIME-TRAVEL (Datomic native!)
  ;; Get database as it was 1 hour ago
  (def past-db (as-of conn (java.util.Date. (- (System/currentTimeMillis) 3600000))))
  (all-events past-db)  ; See what events existed then

  ;; Get only recent changes
  (def recent-db (since conn #inst "2024-03-20"))
  (all-events recent-db)

  ;; 5. Replay to rebuild state
  (def state
    (replay-events (d/db conn) {}
      (fn [state event]
        (case (:event/type event)
          :transaction-imported
          (-> state
              (update :total-imported (fnil + 0)
                      (get-in event [:event/data :count]))
              (update :files (fnil conj [])
                      (get-in event [:event/data :file])))

          :transaction-classified
          (update state :classified-count (fnil inc 0))

          state))))

  state
  ; => {:total-imported 156
  ;     :files ["bofa_march.csv"]
  ;     :classified-count 1}

  ;; 6. Aggregations
  (aggregate-events (d/db conn) :transaction-imported
    (fn [acc event]
      (-> acc
          (update :total-files (fnil inc 0))
          (update :total-txs (fnil + 0)
                  (get-in event [:event/data :count])))))

  ;; 7. Timeline
  (event-timeline (d/db conn))
  ; => {#inst "2024-03-20" 2}
  )
