(ns finance.merchant-extraction.stage5
  "Stage 5: Entity Resolution"
  (:require [finance.merchant-extraction.protocols :as proto]
            [clojure.java.io :as io]
            [clojure.edn :as edn]))

;; ============================================================================
;; Entity Store Implementation (In-Memory)
;; ============================================================================

(defrecord InMemoryEntityStore [entities]  ;; atom with map {entity-id -> entity}
  proto/EntityStore

  (get-entity [this entity-id]
    (get @entities entity-id))

  (find-by-alias [this alias]
    (filter (fn [[_ entity]]
              (contains? (:aliases entity #{}) alias))
            @entities))

  (find-by-rfc [this rfc]
    (first
     (filter (fn [[_ entity]]
               (= (:rfc entity) rfc))
             @entities)))

  (create-entity [this entity]
    (let [entity-id (or (:entity-id entity)
                        (:merchant-id entity)  ;; Use merchant-id as entity-id
                        (keyword (str "entity-" (java.util.UUID/randomUUID))))
          entity-with-metadata (merge entity
                                     {:entity-id entity-id
                                      :created-at (java.time.Instant/now)
                                      :transaction-count 1
                                      :aliases #{(:merchant-id entity)}
                                      :confidence-history [(:confidence entity 0.50)]})]
      (swap! entities assoc entity-id entity-with-metadata)
      entity-id))

  (update-entity [this entity-id updates]
    (when-let [entity (get @entities entity-id)]
      (let [updated (merge entity
                          updates
                          {:updated-at (java.time.Instant/now)
                           :transaction-count (inc (:transaction-count entity 0))
                           :aliases (into (:aliases entity #{})
                                         (:new-aliases updates #{}))
                           :confidence-history (conj (:confidence-history entity [])
                                                    (:confidence updates))})]
        (swap! entities assoc entity-id updated)
        entity-id)))

  (merge-entities [this source-id target-id reason]
    (when-let [source (get @entities source-id)]
      (when-let [target (get @entities target-id)]
        ;; Mark source as merged
        (swap! entities assoc source-id
               (assoc source
                      :state :merged
                      :merged-into target-id
                      :merged-at (java.time.Instant/now)
                      :merge-reason reason))
        ;; Update target with source's aliases
        (proto/update-entity this target-id
                            {:new-aliases (:aliases source #{})})
        target-id))))

;; ============================================================================
;; Entity Store Factory
;; ============================================================================

(defn create-entity-store
  "Creates an in-memory entity store"
  []
  (->InMemoryEntityStore (atom {})))

;; ============================================================================
;; Entity Resolution Logic
;; ============================================================================

(defn- find-existing-entity
  "Tries to find existing entity for merchant"
  [disambiguated-tx entity-store rules]
  (let [merchant-id (:merchant-id disambiguated-tx)
        strategy (:resolution-strategy rules)]

    ;; Try 1: Exact merchant-id match
    (when (:match-by-merchant-id strategy)
      (when-let [entity (proto/get-entity entity-store merchant-id)]
        (when-not (= :merged (:state entity))
          {:entity entity
           :match-method :exact-merchant-id})))

    ;; Try 2: Alias match (not implemented yet - placeholder)
    ;; (when (:match-by-alias strategy) ...)

    ;; Try 3: RFC match (not implemented yet - placeholder)
    ;; (when (:match-by-rfc strategy) ...)))
    ))

(defn- should-auto-resolve?
  "Determines if transaction should auto-resolve (no manual review)"
  [disambiguated-tx rules]
  (let [confidence (:confidence disambiguated-tx 0.0)
        threshold (get-in rules [:resolution-strategy :auto-resolve-threshold] 0.90)]
    (>= confidence threshold)))

(defn- needs-manual-review?
  "Determines if transaction needs manual review"
  [disambiguated-tx rules]
  (let [confidence (:confidence disambiguated-tx 0.0)
        threshold (get-in rules [:resolution-strategy :manual-review-threshold] 0.70)]
    (< confidence threshold)))

(defn- determine-entity-state
  "Determines initial state for new entity"
  [disambiguated-tx rules]
  (let [strategy (:resolution-strategy rules)
        is-fallback (get-in disambiguated-tx [:stage-4 :fallback?] false)
        is-rule-matched (= :rule-match (get-in disambiguated-tx [:stage-4 :disambiguation-method]))]
    (cond
      ;; Rule-matched merchants are canonical
      (and is-rule-matched (:rule-matched-entities-are-canonical strategy))
      :canonical

      ;; Fallback merchants are provisional
      (and is-fallback (:fallback-entities-are-provisional strategy))
      :provisional

      ;; Default
      :else
      (get-in rules [:entity-defaults :default-state] :provisional))))

(defn- determine-verification-reason
  "Determines why this entity needs verification"
  [disambiguated-tx existing-entity rules]
  (let [confidence (:confidence disambiguated-tx 0.0)
        manual-threshold (get-in rules [:resolution-strategy :manual-review-threshold] 0.70)
        is-fallback (get-in disambiguated-tx [:stage-4 :fallback?] false)
        reasons (get-in rules [:entity-defaults :verification-reasons])]
    (cond
      ;; Low confidence
      (< confidence manual-threshold)
      (:low-confidence reasons)

      ;; New entity (first time seeing this merchant)
      (nil? existing-entity)
      (:new-entity reasons)

      ;; Fallback
      is-fallback
      (:fallback reasons)

      ;; Default
      :else
      nil)))

;; ============================================================================
;; Entity Resolver Implementation
;; ============================================================================

(defrecord EntityResolver [config]
  proto/EntityResolver
  (resolve-entity [this disambiguated-tx entity-store rules]
    ;; Only resolve if merchant was disambiguated
    (if-not (:merchant-id disambiguated-tx)
      ;; No merchant-id, skip resolution
      (merge disambiguated-tx
             {:stage-5 {:resolution-method :skipped
                        :reason "No merchant ID available"
                        :timestamp (java.time.Instant/now)}})

      ;; Merchant-id exists, try to resolve
      (let [merchant-id (:merchant-id disambiguated-tx)
            existing-entity (find-existing-entity disambiguated-tx entity-store rules)

            ;; Create or update entity
            entity-id (if existing-entity
                        ;; Update existing
                        (do
                          (proto/update-entity entity-store
                                             (:entity-id (:entity existing-entity))
                                             {:confidence (:confidence disambiguated-tx 0.50)
                                              :last-seen (java.time.Instant/now)})
                          (:entity-id (:entity existing-entity)))

                        ;; Create new
                        (proto/create-entity entity-store
                                           {:entity-id merchant-id
                                            :merchant-id merchant-id
                                            :merchant-name (:merchant-name disambiguated-tx)
                                            :category (:merchant-category disambiguated-tx)
                                            :confidence (:confidence disambiguated-tx 0.50)
                                            :state (determine-entity-state disambiguated-tx rules)
                                            :first-seen (java.time.Instant/now)
                                            :last-seen (java.time.Instant/now)}))

            ;; Get final entity
            final-entity (proto/get-entity entity-store entity-id)

            ;; Determine if needs verification
            needs-verification? (or (needs-manual-review? disambiguated-tx rules)
                                   (nil? existing-entity))  ;; New entities need review

            verification-reason (when needs-verification?
                                 (determine-verification-reason disambiguated-tx existing-entity rules))]

        (merge disambiguated-tx
               {:entity final-entity
                :entity-id entity-id
                :entity-state (:state final-entity)
                :needs-verification needs-verification?
                :verification-reason verification-reason
                :stage-5 {:resolution-method (if existing-entity :existing-entity :new-entity)
                          :match-method (when existing-entity
                                         (:match-method existing-entity))
                          :transaction-number (:transaction-count final-entity)
                          :timestamp (java.time.Instant/now)}})))))

;; ============================================================================
;; Factory Function
;; ============================================================================

(defn create-resolver
  "Creates an EntityResolver instance"
  ([]
   (create-resolver {}))
  ([config]
   (->EntityResolver config)))

;; ============================================================================
;; Rules Loading
;; ============================================================================

(defn load-rules
  "Loads Stage 5 rules from EDN file"
  ([]
   (load-rules "rules/stage5_entity_resolution.edn"))
  ([resource-path]
   (with-open [r (io/reader (io/resource resource-path))]
     (edn/read (java.io.PushbackReader. r)))))

;; ============================================================================
;; Convenience Functions
;; ============================================================================

(defn resolve-single
  "Convenience function to resolve entity with default rules"
  [disambiguated-tx entity-store]
  (let [resolver (create-resolver)
        rules (load-rules)]
    (proto/resolve-entity resolver disambiguated-tx entity-store rules)))

(defn resolve-batch
  "Resolves entities for batch of disambiguated transactions"
  [disambiguated-txs entity-store]
  (let [resolver (create-resolver)
        rules (load-rules)]
    (map #(proto/resolve-entity resolver % entity-store rules) disambiguated-txs)))

;; ============================================================================
;; Statistics & Analysis
;; ============================================================================

(defn resolution-statistics
  "Returns statistics about entity resolution"
  [resolved-txs]
  (let [with-entities (filter :entity resolved-txs)
        new-entities (filter #(= :new-entity (get-in % [:stage-5 :resolution-method])) with-entities)
        existing-entities (filter #(= :existing-entity (get-in % [:stage-5 :resolution-method])) with-entities)
        needs-review (filter :needs-verification with-entities)
        by-state (group-by :entity-state with-entities)]
    {:total-transactions (count resolved-txs)
     :entities-resolved (count with-entities)
     :new-entities (count new-entities)
     :existing-entities (count existing-entities)
     :needs-verification (count needs-review)
     :verification-rate (if (pos? (count with-entities))
                          (format "%.1f%%" (* 100.0 (/ (count needs-review) (count with-entities))))
                          "N/A")
     :by-state (into {}
                     (map (fn [[state txs]]
                            [state (count txs)])
                          by-state))
     :unique-entities (count (distinct (map :entity-id with-entities)))}))

;; ============================================================================
;; Validation
;; ============================================================================

(defn validate-resolved-transaction
  "Validates that a resolved transaction has required fields"
  [resolved-tx]
  (or (not (:merchant-id resolved-tx))  ;; No merchant - skip ok
      (and (contains? resolved-tx :stage-5)  ;; Must have stage-5
           (or (contains? resolved-tx :entity)  ;; Has entity
               (= :skipped (get-in resolved-tx [:stage-5 :resolution-method]))))))  ;; Or was skipped

(defn validate-batch
  "Validates batch of resolved transactions"
  [resolved-txs]
  {:valid (count (filter validate-resolved-transaction resolved-txs))
   :invalid (count (remove validate-resolved-transaction resolved-txs))
   :validation-errors (remove validate-resolved-transaction resolved-txs)})
