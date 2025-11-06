(ns trust.identity-datomic
  "Identity management with Datomic - Rich Hickey's Identity model, database-backed.

  Why Datomic > Atoms:
  1. ✅ Persistent - Survives restarts
  2. ✅ Time-travel - See entity history automatically
  3. ✅ Coordinated - ACID transactions across entities
  4. ✅ Queryable - Datalog > filtering atoms
  5. ✅ Audit trail - Every change forever

  Core concepts:
  - Entities ARE identities (stable entity IDs)
  - Values are attribute values at a point in time
  - State is current database value
  - History is built-in (no manual tracking)"
  (:require [datomic.api :as d]
            [trust.datomic-schema :as schema]
            [clojure.edn :as edn]))

;; ============================================================================
;; REGISTRY OPERATIONS
;; ============================================================================

(defn register!
  "Register a new entity in Datomic.

  Args:
    conn - Datomic connection
    entity-id - Keyword identifier (e.g., :bofa)
    entity-data - Map of attributes

  Returns transaction result.

  Example:
    (register! conn :bofa
      {:entity/canonical-name \"Bank of America\"
       :entity/alias [\"BofA\" \"BoA\"]
       :bank/type :bank
       :bank/country \"USA\"})"
  [conn entity-id entity-data]
  (let [entity-tx (merge {:db/id (d/tempid :db.part/user)
                          :entity/id entity-id}
                         entity-data)]
    @(d/transact conn [entity-tx])))

(defn lookup
  "Lookup an entity by ID.

  Args:
    db - Datomic database value
    entity-id - Keyword identifier

  Returns entity map or nil.

  Example:
    (lookup (d/db conn) :bofa)
    ; => {:db/id 17592186045418
    ;     :entity/id :bofa
    ;     :entity/canonical-name \"Bank of America\"
    ;     :entity/alias [\"BofA\" \"BoA\"]
    ;     :bank/type :bank
    ;     :bank/country \"USA\"}"
  [db entity-id]
  (when-let [eid (d/q '[:find ?e .
                        :in $ ?id
                        :where [?e :entity/id ?id]]
                      db
                      entity-id)]
    (d/touch (d/entity db eid))))

(defn update!
  "Update an entity by ID.

  Args:
    conn - Datomic connection
    entity-id - Keyword identifier
    updates - Map of attribute updates

  Returns transaction result.

  Example:
    (update! conn :bofa {:bank/country \"United States\"})"
  [conn entity-id updates]
  (when-let [eid (d/q '[:find ?e .
                        :in $ ?id
                        :where [?e :entity/id ?id]]
                      (d/db conn)
                      entity-id)]
    @(d/transact conn [(merge {:db/id eid} updates)])))

(defn remove!
  "Remove an entity (actually retracts it).

  Note: In Datomic, we typically don't delete - we retract.
  The entity will still exist in history.

  Example:
    (remove! conn :bofa)"
  [conn entity-id]
  (when-let [eid (d/q '[:find ?e .
                        :in $ ?id
                        :where [?e :entity/id ?id]]
                      (d/db conn)
                      entity-id)]
    @(d/transact conn [[:db/retractEntity eid]])))

(defn exists?
  "Check if an entity exists.

  Example:
    (exists? (d/db conn) :bofa)  ; => true"
  [db entity-id]
  (boolean (d/q '[:find ?e .
                  :in $ ?id
                  :where [?e :entity/id ?id]]
                db
                entity-id)))

(defn list-all
  "List all entities of a specific type.

  Args:
    db - Datomic database value
    type-attr - (optional) Attribute to filter by (e.g., :bank/type)
    type-value - (optional) Value to match

  Returns sequence of entity maps.

  Example:
    (list-all (d/db conn))  ; All entities
    (list-all (d/db conn) :bank/type :bank)  ; Only banks"
  ([db]
   (let [eids (d/q '[:find [?e ...]
                     :where [?e :entity/id]]
                   db)]
     (map #(d/touch (d/entity db %)) eids)))
  ([db type-attr type-value]
   (let [eids (d/q '[:find [?e ...]
                     :in $ ?attr ?val
                     :where
                     [?e :entity/id]
                     [?e ?attr ?val]]
                   db
                   type-attr
                   type-value)]
     (map #(d/touch (d/entity db %)) eids))))

;; ============================================================================
;; HISTORY (Datomic Native Time-Travel)
;; ============================================================================

(defn history
  "Get complete history of an entity.

  Returns vector of {:timestamp :value} maps showing all changes.

  Example:
    (history conn :bofa)
    ; => [{:timestamp #inst \"2024-03-20T10:00:00Z\"
    ;      :value {:entity/canonical-name \"Bank of America\"}}
    ;     {:timestamp #inst \"2024-03-21T14:30:00Z\"
    ;      :value {:entity/canonical-name \"Bank of America\"
    ;              :bank/country \"USA\"}}]"
  [conn entity-id]
  (when-let [eid (d/q '[:find ?e .
                        :in $ ?id
                        :where [?e :entity/id ?id]]
                      (d/db conn)
                      entity-id)]
    (let [hist-db (d/history (d/db conn))
          txs (d/q '[:find ?tx ?t
                     :in $ ?e
                     :where
                     [?e _ _ ?tx]
                     [?tx :db/txInstant ?t]]
                   hist-db
                   eid)]
      (->> txs
           (sort-by second)
           (mapv (fn [[tx-id timestamp]]
                   (let [db-at-time (d/as-of (d/db conn) timestamp)]
                     {:timestamp timestamp
                      :value (when-let [e (lookup db-at-time entity-id)]
                               (into {} e))})))))))

(defn as-of
  "Get entity value as it was at a specific time.

  Example:
    (as-of conn :bofa #inst \"2024-03-20\")
    ; => {:entity/id :bofa
    ;     :entity/canonical-name \"Bank of America\"}"
  [conn entity-id time]
  (let [past-db (d/as-of (d/db conn) time)]
    (lookup past-db entity-id)))

;; ============================================================================
;; QUERYING
;; ============================================================================

(defn find-by-alias
  "Find entity by one of its aliases.

  Example:
    (find-by-alias (d/db conn) \"BofA\")
    ; => {:entity/id :bofa ...}"
  [db alias-str]
  (when-let [eid (d/q '[:find ?e .
                        :in $ ?alias
                        :where
                        [?e :entity/alias ?alias]]
                      db
                      alias-str)]
    (d/touch (d/entity db eid))))

(defn find-by-name
  "Find entity by canonical name.

  Example:
    (find-by-name (d/db conn) \"Bank of America\")
    ; => {:entity/id :bofa ...}"
  [db name-str]
  (when-let [eid (d/q '[:find ?e .
                        :in $ ?name
                        :where
                        [?e :entity/canonical-name ?name]]
                      db
                      name-str)]
    (d/touch (d/entity db eid))))

(defn count-entities
  "Count number of entities in database.

  Example:
    (count-entities (d/db conn))  ; => 42
    (count-entities (d/db conn) :bank/type :bank)  ; => 5"
  ([db]
   (d/q '[:find (count ?e) .
          :where [?e :entity/id]]
        db))
  ([db type-attr type-value]
   (d/q '[:find (count ?e) .
          :in $ ?attr ?val
          :where
          [?e :entity/id]
          [?e ?attr ?val]]
        db
        type-attr
        type-value)))

;; ============================================================================
;; BULK OPERATIONS
;; ============================================================================

(defn register-batch!
  "Register multiple entities in a single transaction.

  Args:
    conn - Datomic connection
    entities - Vector of [entity-id entity-data] tuples

  Example:
    (register-batch! conn
      [[:bofa {:entity/canonical-name \"Bank of America\"}]
       [:apple {:entity/canonical-name \"Apple Card\"}]])"
  [conn entities]
  (let [txs (mapv (fn [[entity-id entity-data]]
                    (merge {:db/id (d/tempid :db.part/user)
                            :entity/id entity-id}
                           entity-data))
                  entities)]
    @(d/transact conn txs)))

;; ============================================================================
;; NORMALIZATION
;; ============================================================================

(defn normalize
  "Normalize a raw value to entity ID.

  Checks aliases and canonical name.

  Example:
    (normalize (d/db conn) \"BofA\")  ; => :bofa
    (normalize (d/db conn) \"Bank of America\")  ; => :bofa"
  [db raw-value]
  (let [upper-value (.toUpperCase (str raw-value))]
    (or
      ;; Try exact match
      (when-let [e (find-by-alias db raw-value)]
        (:entity/id e))
      ;; Try alias match (case-insensitive)
      (when-let [eids (d/q '[:find [?e ...]
                             :where [?e :entity/alias]]
                           db)]
        (some (fn [eid]
                (let [e (d/entity db eid)
                      aliases (:entity/alias e)]
                  (when (some #(= upper-value (.toUpperCase %)) aliases)
                    (:entity/id e))))
              eids))
      ;; Try canonical name match
      (when-let [e (find-by-name db raw-value)]
        (:entity/id e)))))

;; ============================================================================
;; EXAMPLE USAGE
;; ============================================================================

(comment
  (require '[datomic.api :as d])

  ;; 1. Setup
  (d/create-database "datomic:mem://entities")
  (def conn (d/connect "datomic:mem://entities"))
  (schema/install-schema! conn)

  ;; 2. Register entities
  (register! conn :bofa
    {:entity/canonical-name "Bank of America"
     :entity/alias ["BofA" "BoA" "Bank of America"]
     :bank/type :bank
     :bank/country "USA"})

  (register! conn :apple
    {:entity/canonical-name "Apple Card"
     :entity/alias ["Apple Card" "APPLE CARD" "Apple"]
     :bank/type :credit-card
     :bank/country "USA"})

  ;; 3. Lookup
  (lookup (d/db conn) :bofa)
  ; => {:db/id 17592186045418
  ;     :entity/id :bofa
  ;     :entity/canonical-name "Bank of America"
  ;     :entity/alias ["BofA" "BoA"]
  ;     :bank/type :bank
  ;     :bank/country "USA"}

  ;; 4. Update
  (update! conn :bofa {:bank/country "United States"})

  ;; 5. History (time-travel!)
  (history conn :bofa)
  ; => [{:timestamp #inst "2024-03-20T10:00:00Z"
  ;      :value {:entity/canonical-name "Bank of America"}}
  ;     {:timestamp #inst "2024-03-21T14:30:00Z"
  ;      :value {:bank/country "United States"}}]

  ;; 6. As-of (see entity as it was)
  (as-of conn :bofa #inst "2024-03-20T12:00:00Z")

  ;; 7. List all banks
  (list-all (d/db conn) :bank/type :bank)

  ;; 8. Find by alias
  (find-by-alias (d/db conn) "BofA")  ; => :bofa

  ;; 9. Normalize
  (normalize (d/db conn) "BofA")  ; => :bofa
  (normalize (d/db conn) "Bank of America")  ; => :bofa

  ;; 10. Batch registration
  (register-batch! conn
    [[:stripe {:entity/canonical-name "Stripe"
               :bank/type :payment-processor}]
     [:wise {:entity/canonical-name "Wise"
             :bank/type :payment-processor}]])
  )
