(ns trust-construction.store.memory
  "In-memory implementation of Store protocol.

  Design:
  - Uses atom for thread-safe state
  - Fast (no I/O)
  - Ephemeral (data lost on restart)
  - Perfect for tests and development

  Storage structure:
  {:entities [{:id ... :data ... :metadata ...}]
   :hash-index {\"sha256:abc...\" \"entity-id-123\"}
   :sequence-number 12345}

  Version: 1.0"
  (:require [trust-construction.protocols.store :as store]))

;; Helper: Apply filter constraint (defined before defrecord)
(defn- apply-filter
  "Apply a filter constraint to a value."
  [data field constraint]
  (let [value (get data field)]
    (cond
      ;; Simple equality
      (not (vector? constraint))
      (= value constraint)

      ;; Constraint operators
      (vector? constraint)
      (let [[op arg] constraint]
        (case op
          :> (> value arg)
          :>= (>= value arg)
          :< (< value arg)
          :<= (<= value arg)
          := (= value arg)
          :!= (not= value arg)
          :in (contains? (set arg) value)
          :between (let [[start end] arg]
                    (and (>= value start) (<= value end)))
          :matches (re-matches arg (str value))
          ;; Default: equality
          (= value arg)))

      ;; Default: equality
      :else
      (= value constraint))))

(defrecord MemoryStore [state]
  store/Store

  (append! [this data metadata]
    (let [id (store/generate-id)
          timestamp (or (:timestamp metadata) (store/now-iso8601))
          hash (store/compute-hash data metadata)
          entity {:id id
                  :entity-type (:entity-type metadata)
                  :version (:version metadata 1)
                  :data data
                  :metadata (assoc metadata :timestamp timestamp)
                  :hash hash}]
      ;; Check if hash already exists (idempotency)
      (let [existing-id (get-in @state [:hash-index hash])]
        (if existing-id
          ;; Duplicate: return existing entity info
          (let [existing (first (filter #(= (:id %) existing-id)
                                       (:entities @state)))]
            {:id (:id existing)
             :version (:version existing)
             :timestamp (get-in existing [:metadata :timestamp])
             :hash hash
             :duplicate true})
          ;; New entity: append
          (do
            (swap! state
                   (fn [s]
                     (-> s
                         (update :entities conj entity)
                         (assoc-in [:hash-index hash] id)
                         (update :sequence-number inc))))
            {:id id
             :version (:version metadata 1)
             :timestamp timestamp
             :hash hash
             :duplicate false})))))

  (query [this spec]
    (let [entities (:entities @state)
          entity-type (:entity-type spec)
          id (:id spec)
          versions (:versions spec)
          as-of (:as-of spec)
          filters (:filters spec)
          order-by (:order-by spec)
          limit (:limit spec)
          offset (:offset spec 0)]

      ;; Start with all entities of given type
      (cond->> entities
        ;; Filter by entity-type
        entity-type
        (filter #(= (:entity-type %) entity-type))

        ;; Get by ID
        id
        (filter #(= (:id %) id))

        ;; Get all versions or just current?
        (and id (not= versions :all))
        (take 1)  ; Only current version (latest)

        ;; Time-travel query (as-of timestamp)
        as-of
        (filter #(<= (compare (get-in % [:metadata :timestamp]) as-of) 0))

        ;; Apply filters
        filters
        (filter (fn [entity]
                  (every? (fn [[field constraint]]
                            (apply-filter (:data entity) field constraint))
                          filters)))

        ;; Order by
        order-by
        (sort-by (fn [entity]
                   (get-in entity (if (vector? order-by)
                                   [:data (first order-by)]
                                   [:data order-by]))))

        ;; Offset
        (pos? offset)
        (drop offset)

        ;; Limit
        limit
        (take limit)

        ;; Convert to vector
        true
        vec))))

;; Constructor

(defn create-memory-store
  "Create a new in-memory store.

  Returns:
    MemoryStore instance

  Example:
    (def store (create-memory-store))
    (store/append! store {:amount 45.99}
                         {:entity-type :transaction
                          :author \"darwin\"})

  Thread-safe: Yes (uses atom)
  Persistent: No (data lost on restart)"
  []
  (->MemoryStore
    (atom {:entities []
           :hash-index {}
           :sequence-number 0})))

;; Utility functions

(defn count-entities
  "Count total entities in store."
  [store]
  (count (:entities @(:state store))))

(defn count-by-type
  "Count entities by type."
  [store entity-type]
  (count (filter #(= (:entity-type %) entity-type)
                (:entities @(:state store)))))

(defn clear!
  "Clear all data from store (for testing)."
  [store]
  (reset! (:state store)
          {:entities []
           :hash-index {}
           :sequence-number 0}))

(defn get-all-hashes
  "Get all stored hashes (for debugging)."
  [store]
  (keys (:hash-index @(:state store))))
