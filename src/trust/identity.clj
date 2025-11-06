(ns trust.identity
  "Identity management primitives - Rich Hickey's Identity/Value/State model.

  Core concepts:
  - Identity: Stable reference that can be observed over time
  - Value: Immutable data at a point in time
  - State: Value of an identity at a specific time

  Provides:
  - registry: Create identity-backed registries (Atom-based)
  - coordinated-registry: Create coordinated registries (Ref-based)
  - History tracking
  - Generic CRUD operations"
  (:require [clojure.spec.alpha :as s]))

;; ============================================================================
;; SPECS
;; ============================================================================

(s/def ::id keyword?)
(s/def ::registry map?)
(s/def ::value any?)
(s/def ::timestamp inst?)

;; ============================================================================
;; ATOM-BASED REGISTRY (Simple, uncoordinated)
;; ============================================================================

(defn registry
  "Create a new identity-backed registry using an Atom.

  Returns an Atom containing an empty map.
  Perfect for independent entities (banks, merchants, categories).

  Example:
    (def banks (registry))
    @banks  ; => {}"
  []
  (atom {}))

(defn register!
  "Register a new entity in the registry.

  Args:
    registry - Atom containing the registry map
    id - Keyword identifier for the entity
    value - The entity data (immutable map)

  Returns the new registry state.

  Example:
    (register! banks :bofa {:canonical-name \"Bank of America\"
                            :aliases [\"BofA\" \"BoA\"]})"
  [registry id value]
  {:pre [(s/valid? ::id id)
         (instance? clojure.lang.IAtom registry)]}
  (swap! registry assoc id value))

(defn lookup
  "Lookup an entity by id.

  Returns the current value or nil if not found.

  Example:
    (lookup banks :bofa)
    ; => {:canonical-name \"Bank of America\" :aliases [\"BofA\" \"BoA\"]}"
  [registry id]
  {:pre [(s/valid? ::id id)]}
  (get @registry id))

(defn update!
  "Update an entity using a function.

  Args:
    registry - Atom containing the registry map
    id - Keyword identifier
    f - Update function (value -> new-value)
    args - Additional arguments to f

  Returns the new registry state.

  Example:
    (update! banks :bofa assoc :country \"USA\")"
  [registry id f & args]
  {:pre [(s/valid? ::id id)]}
  (apply swap! registry update id f args))

(defn remove!
  "Remove an entity from the registry.

  Returns the new registry state.

  Example:
    (remove! banks :bofa)"
  [registry id]
  {:pre [(s/valid? ::id id)]}
  (swap! registry dissoc id))

(defn list-all
  "List all registered entities.

  Returns a sequence of [id value] tuples.

  Example:
    (list-all banks)
    ; => ([:bofa {...}] [:apple {...}])"
  [registry]
  (seq @registry))

(defn exists?
  "Check if an entity exists in the registry.

  Example:
    (exists? banks :bofa)  ; => true"
  [registry id]
  {:pre [(s/valid? ::id id)]}
  (contains? @registry id))

;; ============================================================================
;; REF-BASED REGISTRY (Coordinated with STM)
;; ============================================================================

(defn coordinated-registry
  "Create a coordinated registry using a Ref (STM).

  Use this when multiple registries need to be updated atomically.
  Perfect for coordinated changes across banks, merchants, and categories.

  Example:
    (def banks (coordinated-registry))
    @banks  ; => {}"
  []
  (ref {}))

(defn register-coordinated!
  "Register an entity in a coordinated registry (uses STM transaction).

  Can be combined with other ref operations in a dosync block for atomicity.

  Example:
    (dosync
      (register-coordinated! banks :bofa {...})
      (register-coordinated! merchants :starbucks {...}))"
  [registry id value]
  {:pre [(s/valid? ::id id)
         (instance? clojure.lang.Ref registry)]}
  (dosync
    (alter registry assoc id value)))

(defn update-coordinated!
  "Update an entity in a coordinated registry (uses STM transaction).

  Example:
    (dosync
      (update-coordinated! banks :bofa assoc :country \"USA\")
      (update-coordinated! banks :chase assoc :country \"USA\"))"
  [registry id f & args]
  {:pre [(s/valid? ::id id)]}
  (dosync
    (apply alter registry update id f args)))

;; ============================================================================
;; HISTORY TRACKING
;; ============================================================================

(defn registry-with-history
  "Create a registry that automatically tracks history.

  Returns a map with:
    :current - Atom containing current state
    :history - Atom containing vector of all states

  Example:
    (def banks (registry-with-history))"
  []
  {:current (atom {})
   :history (atom [])})

(defn- record-history!
  "Internal: Record a snapshot in history."
  [history-atom snapshot]
  (swap! history-atom conj
         {:timestamp (java.util.Date.)
          :state snapshot}))

(defn register-with-history!
  "Register an entity and record in history.

  Example:
    (register-with-history! banks :bofa {...})"
  [registry-with-history id value]
  {:pre [(s/valid? ::id id)
         (map? registry-with-history)
         (contains? registry-with-history :current)
         (contains? registry-with-history :history)]}
  (let [{:keys [current history]} registry-with-history
        new-state (swap! current assoc id value)]
    (record-history! history new-state)
    new-state))

(defn update-with-history!
  "Update an entity and record in history.

  Example:
    (update-with-history! banks :bofa assoc :country \"USA\")"
  [registry-with-history id f & args]
  {:pre [(s/valid? ::id id)]}
  (let [{:keys [current history]} registry-with-history
        new-state (apply swap! current update id f args)]
    (record-history! history new-state)
    new-state))

(defn history
  "Get full history of all changes to the registry.

  Returns a vector of {:timestamp :state} maps.

  Example:
    (history banks)
    ; => [{:timestamp #inst \"2024-01-01\" :state {...}}
    ;     {:timestamp #inst \"2024-01-02\" :state {...}}]"
  [registry-with-history]
  @(:history registry-with-history))

(defn history-for-id
  "Get history of changes to a specific entity.

  Returns a vector of {:timestamp :value} maps showing how the entity changed.

  Example:
    (history-for-id banks :bofa)
    ; => [{:timestamp #inst \"2024-01-01\" :value {:canonical-name \"Bank of America\"}}
    ;     {:timestamp #inst \"2024-01-02\" :value {:canonical-name \"Bank of America\" :country \"USA\"}}]"
  [registry-with-history id]
  {:pre [(s/valid? ::id id)]}
  (->> (history registry-with-history)
       (keep (fn [{:keys [timestamp state]}]
               (when-let [value (get state id)]
                 {:timestamp timestamp
                  :value value})))))

;; ============================================================================
;; AGENT-BASED REGISTRY (Asynchronous updates)
;; ============================================================================

(defn async-registry
  "Create an asynchronous registry using an Agent.

  Updates happen asynchronously in a thread pool.
  Perfect for logging, notifications, or non-critical updates.

  Example:
    (def audit-log (async-registry))"
  []
  (agent {}))

(defn register-async!
  "Register an entity asynchronously.

  Update happens in background thread pool.

  Example:
    (register-async! audit-log :event-001 {...})
    ; Returns immediately, update happens in background"
  [registry id value]
  {:pre [(s/valid? ::id id)
         (instance? clojure.lang.Agent registry)]}
  (send registry assoc id value))

(defn await-registry
  "Block until all pending async operations complete.

  Example:
    (register-async! audit-log :event-001 {...})
    (await-registry audit-log)
    ; Now guaranteed to be complete"
  [registry]
  (await registry))

;; ============================================================================
;; UTILITIES
;; ============================================================================

(defn snapshot
  "Take an immutable snapshot of registry current state.

  Returns the value (map), not the identity (Atom/Ref/Agent).

  Example:
    (def snapshot-v1 (snapshot banks))
    ; Later...
    (def snapshot-v2 (snapshot banks))
    ; Compare versions
    (= snapshot-v1 snapshot-v2)"
  [registry]
  (cond
    (instance? clojure.lang.IAtom registry) @registry
    (instance? clojure.lang.Ref registry) @registry
    (instance? clojure.lang.Agent registry) @registry
    (map? registry) @(:current registry) ; registry-with-history
    :else (throw (ex-info "Unknown registry type" {:registry registry}))))

(defn registry?
  "Check if something is a registry (Atom, Ref, Agent, or registry-with-history).

  Example:
    (registry? banks)  ; => true
    (registry? {})     ; => false"
  [x]
  (or (instance? clojure.lang.IAtom x)
      (instance? clojure.lang.Ref x)
      (instance? clojure.lang.Agent x)
      (and (map? x)
           (contains? x :current)
           (contains? x :history))))

(defn count-entities
  "Count number of entities in registry.

  Example:
    (count-entities banks)  ; => 5"
  [registry]
  (count (snapshot registry)))

;; ============================================================================
;; EXAMPLE USAGE (for documentation)
;; ============================================================================

(comment
  ;; Simple registry (Atom-based)
  (def banks (registry))

  (register! banks :bofa
    {:canonical-name "Bank of America"
     :aliases ["BofA" "BoA"]})

  (lookup banks :bofa)
  ; => {:canonical-name "Bank of America" :aliases ["BofA" "BoA"]}

  (update! banks :bofa assoc :country "USA")

  @banks
  ; => {:bofa {:canonical-name "Bank of America" :aliases ["BofA" "BoA"] :country "USA"}}

  ;; Coordinated registry (Ref-based with STM)
  (def banks (coordinated-registry))
  (def merchants (coordinated-registry))

  ;; (dosync
  ;;   (register-coordinated! banks :bofa {...})
  ;;   (register-coordinated! merchants :starbucks {...}))
  ; Both succeed or both fail atomically

  ;; Registry with history
  (def banks (registry-with-history))

  (register-with-history! banks :bofa
    {:canonical-name "Bank of America"})

  (update-with-history! banks :bofa assoc :country "USA")

  (history banks)
  ; => [{:timestamp #inst "..." :state {...}}
  ;     {:timestamp #inst "..." :state {...}}]

  (history-for-id banks :bofa)
  ; => [{:timestamp #inst "..." :value {:canonical-name "Bank of America"}}
  ;     {:timestamp #inst "..." :value {...  :country "USA"}}]

  ;; Async registry (Agent-based)
  (def audit-log (async-registry))

  (register-async! audit-log :event-001
    {:type :bank-registered
     :bank-id :bofa
     :timestamp (java.util.Date.)})

  (await-registry audit-log)
  ; Block until complete
  )
