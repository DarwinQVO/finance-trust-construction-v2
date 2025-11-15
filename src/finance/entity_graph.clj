(ns finance.entity-graph
  "Linked Data capabilities for entity resolution

   Inspired by RDF/OWL concepts but simplified for finance domain:
   - owl:sameAs → Entity equivalences
   - Bidirectional relationships → Reverse lookups
   - Provenance → Complete resolution trail

   Philosophy: 80% of linked data benefits with 20% of complexity"
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [finance.entity-registry :as registry]
            [finance.entity-engine :as engine]))

;; ============================================================================
;; Entity Equivalences (owl:sameAs pattern)
;; ============================================================================

(defn resolve-equivalence
  "Follow owl:sameAs links to find canonical entity.

   Example:
     'starbucks-downtown' same-as 'starbucks-main'
     'starbucks-main' is canonical
     => returns 'starbucks-main'

   Prevents infinite loops by tracking visited entities."
  ([registry entity-id]
   (resolve-equivalence registry entity-id #{}))

  ([registry entity-id visited]
   (if (contains? visited entity-id)
     ;; Loop detected - return current
     entity-id

     ;; Check for same-as link
     (if-let [same-as (get-in registry [entity-id :same-as])]
       ;; Follow the link recursively
       (resolve-equivalence registry
                           (first same-as)
                           (conj visited entity-id))
       ;; No link - this is canonical
       entity-id))))

(defn add-equivalence!
  "Add owl:sameAs relationship to registry.

   Example:
     (add-equivalence! 'resources/registry/merchant_registry.json'
                       'starbucks-downtown'
                       'starbucks-main')

   This makes 'starbucks-downtown' an alias of 'starbucks-main'"
  [registry-file entity-id canonical-id]
  (let [registry (engine/load-registry registry-file "merchants")
        entity (get registry entity-id)

        ;; Add or update same-as field
        updated-entity (assoc entity :same-as [canonical-id])
        updated-registry (assoc registry entity-id updated-entity)]

    ;; Save back to file
    (spit registry-file
          (json/write-str {"merchants" updated-registry}
                         :indent true))))

(defn get-canonical-entity
  "Get canonical entity by following equivalences.

   Returns:
   {:entity-id canonical-id
    :entity-data {...}
    :hops-count N  ;; how many links followed
    :path [id1 id2 ... canonical-id]}"
  [registry entity-id]
  (loop [current-id entity-id
         path [entity-id]
         hops 0
         visited #{}]

    (if (contains? visited current-id)
      ;; Loop detected
      {:entity-id current-id
       :entity-data (get registry current-id)
       :hops-count hops
       :path path
       :loop-detected? true}

      ;; Check for same-as
      (if-let [same-as (get-in registry [current-id :same-as])]
        ;; Follow link
        (recur (first same-as)
               (conj path (first same-as))
               (inc hops)
               (conj visited current-id))

        ;; Found canonical
        {:entity-id current-id
         :entity-data (get registry current-id)
         :hops-count hops
         :path path
         :loop-detected? false}))))

;; ============================================================================
;; Bidirectional Relationships
;; ============================================================================

(defn build-reverse-index
  "Build reverse index for fast lookups.

   Example:
     merchants → categories (forward)
     categories → merchants (reverse)

   Returns:
     {:category/restaurants [merchant-1 merchant-2 ...]
      :category/healthcare [merchant-3 merchant-4 ...]}"
  [registry relationship-key]
  (reduce (fn [index [entity-id entity-data]]
            (let [rel-value (get entity-data relationship-key)]
              (if rel-value
                (update index rel-value (fnil conj []) entity-id)
                index)))
          {}
          registry))

(defn get-related-entities
  "Get all entities related through a relationship.

   Example:
     (get-related-entities merchant-registry :category 'restaurants')
     => ['starbucks' 'mcdonalds' 'chipotle' ...]"
  [registry relationship-key relationship-value]
  (let [reverse-index (build-reverse-index registry relationship-key)]
    (get reverse-index relationship-value [])))

(defn get-entity-relationships
  "Get all relationships for an entity (like RDF triples).

   Returns:
     {:merchant/id 'starbucks'
      :has-category 'restaurants'
      :has-mcc 5812
      :located-in 'seattle'
      :same-as ['starbucks-downtown' 'starbucks-airport']
      :inverse-of []  ;; entities that point to this one
     }"
  [registry entity-id]
  (let [entity-data (get registry entity-id)

        ;; Build forward relationships
        forward-rels {:merchant/id entity-id
                     :has-category (:category entity-data)
                     :has-mcc (:mcc entity-data)
                     :has-budget-category (:budget-category entity-data)
                     :same-as (:same-as entity-data)}

        ;; Find inverse relationships (who points to me?)
        inverse-rels (reduce (fn [acc [other-id other-data]]
                              (let [same-as (:same-as other-data)]
                                (if (and same-as (some #{entity-id} same-as))
                                  (conj acc other-id)
                                  acc)))
                            []
                            registry)]

    (assoc forward-rels :inverse-of inverse-rels)))

;; ============================================================================
;; Enhanced Provenance (Linked Data style)
;; ============================================================================

(defn enrich-provenance
  "Add complete provenance metadata to resolved entity.

   Includes:
   - When resolved
   - How matched (exact, variation, fuzzy)
   - Confidence score
   - Source text that was matched
   - Registry version
   - Resolver version
   - Equivalence chain (if followed same-as links)"
  [transaction entity-type entity-result]
  (let [now (java.time.Instant/now)

        provenance {:resolved-at (str now)
                   :entity-type entity-type
                   :matched-via (:match-type entity-result)
                   :confidence (:confidence entity-result)
                   :source-text (get transaction (keyword (str (name entity-type) "-text")))
                   :registry-version "1.0.0"  ;; TODO: get from registry metadata
                   :resolver-version "entity-engine/1.0"
                   :canonical-id (:entity-id entity-result)
                   :equivalence-chain (when (:hops-count entity-result)
                                       {:hops (:hops-count entity-result)
                                        :path (:path entity-result)})}]

    (assoc transaction
           (keyword (str (name entity-type) "-provenance"))
           provenance)))

(defn resolve-with-provenance
  "Resolve entity and add complete provenance trail.

   This is the main function that combines:
   1. Entity resolution (existing)
   2. Equivalence following (new)
   3. Provenance enrichment (new)"
  [transaction entity-def]
  (let [;; Step 1: Resolve entity (existing logic)
        resolved-tx (engine/resolve-entity-generic transaction entity-def)

        entity-id-key (keyword (str (name (:id entity-def)) "-entity"))
        entity-result (get resolved-tx entity-id-key)

        ;; Step 2: If resolved, follow equivalences
        enhanced-result (when entity-result
                         (let [registry (engine/load-registry
                                        (:registry-file entity-def)
                                        (:registry-key entity-def))
                               entity-id (:entity-id entity-result)
                               canonical (get-canonical-entity registry entity-id)]
                           (merge entity-result canonical)))

        ;; Step 3: Add enriched provenance
        final-tx (if enhanced-result
                  (-> resolved-tx
                      (assoc entity-id-key enhanced-result)
                      (enrich-provenance (:id entity-def) enhanced-result))
                  resolved-tx)]

    final-tx))

;; ============================================================================
;; Graph-style Queries (without graph database)
;; ============================================================================

(defn find-entities-by-property
  "Find entities matching a property value.

   Example:
     (find-entities-by-property merchant-registry :category 'restaurants')
     => [['starbucks' {...}] ['mcdonalds' {...}]]"
  [registry property value]
  (filter (fn [[entity-id entity-data]]
            (= value (get entity-data property)))
          registry))

(defn get-entity-subgraph
  "Get entity and all directly connected entities.

   Returns a subgraph showing:
   - The entity itself
   - All entities it points to (via same-as, etc.)
   - All entities that point to it

   This is like a 1-hop graph traversal."
  [registry entity-id]
  (let [entity (get registry entity-id)

        ;; Get entities this one points to
        same-as-entities (when-let [same-as (:same-as entity)]
                          (map #(vector % (get registry %)) same-as))

        ;; Get entities that point to this one
        inverse-entities (filter (fn [[other-id other-data]]
                                  (let [same-as (:same-as other-data)]
                                    (and same-as (some #{entity-id} same-as))))
                                registry)]

    {:center [entity-id entity]
     :outgoing same-as-entities
     :incoming inverse-entities}))

(defn export-to-triples
  "Export entity data as RDF-style triples for analysis.

   Returns:
     [[:merchant/starbucks :has-category :category/restaurants]
      [:merchant/starbucks :has-mcc 5812]
      [:merchant/starbucks :same-as :merchant/starbucks-main]]

   Useful for visualization or export to RDF tools."
  [registry]
  (mapcat (fn [[entity-id entity-data]]
            (let [base-id (keyword "merchant" (name entity-id))]
              (concat
               ;; Category triple
               (when-let [cat (:category entity-data)]
                 [[base-id :has-category (keyword "category" cat)]])

               ;; MCC triple
               (when-let [mcc (:mcc entity-data)]
                 [[base-id :has-mcc mcc]])

               ;; Same-as triples
               (when-let [same-as (:same-as entity-data)]
                 (map (fn [target]
                       [base-id :same-as (keyword "merchant" (name target))])
                     same-as)))))
          registry))

;; ============================================================================
;; Statistics & Analysis
;; ============================================================================

(defn analyze-equivalence-chains
  "Analyze equivalence chains in registry.

   Returns:
     {:total-chains N
      :max-chain-length N
      :avg-chain-length N
      :loops-detected N
      :chains [{:canonical-id ... :aliases [...] :length N}]}"
  [registry]
  (let [entities-with-same-as (filter (fn [[id data]] (:same-as data)) registry)

        chains (map (fn [[entity-id _]]
                     (get-canonical-entity registry entity-id))
                   entities-with-same-as)

        loops (filter :loop-detected? chains)
        valid-chains (remove :loop-detected? chains)

        chain-lengths (map :hops-count valid-chains)]

    {:total-chains (count chains)
     :valid-chains (count valid-chains)
     :loops-detected (count loops)
     :max-chain-length (if (seq chain-lengths) (apply max chain-lengths) 0)
     :avg-chain-length (if (seq chain-lengths)
                        (float (/ (reduce + chain-lengths) (count chain-lengths)))
                        0.0)
     :chains (take 10 valid-chains)  ;; Sample of chains
     :loops (vec loops)}))

(defn get-relationship-stats
  "Get statistics about relationships in registry.

   Returns:
     {:category {'restaurants' 15, 'healthcare' 8, ...}
      :entity-type {'business' 50, 'person' 5, ...}
      :has-same-as 12
      :isolated-entities 3}"
  [registry]
  (let [by-category (frequencies (map (comp :category second) registry))
        by-entity-type (frequencies (map (comp :entity-type second) registry))
        with-same-as (count (filter (comp :same-as second) registry))

        ;; Entities with no relationships
        isolated (count (filter (fn [[id data]]
                                 (and (nil? (:same-as data))
                                      (nil? (:category data))))
                               registry))]

    {:by-category by-category
     :by-entity-type by-entity-type
     :entities-with-equivalences with-same-as
     :isolated-entities isolated
     :total-entities (count registry)}))

;; ============================================================================
;; Usage Examples (for REPL)
;; ============================================================================

(comment
  ;; Load registry
  (def merchant-reg (engine/load-registry
                     "resources/registry/merchant_registry.json"
                     "merchants"))

  ;; 1. Add equivalence relationship
  (add-equivalence! "resources/registry/merchant_registry.json"
                    "starbucks-downtown"
                    "starbucks")

  ;; 2. Resolve with equivalence following
  (get-canonical-entity merchant-reg "starbucks-downtown")
  ;; => {:entity-id "starbucks"
  ;;     :entity-data {...}
  ;;     :hops-count 1
  ;;     :path ["starbucks-downtown" "starbucks"]}

  ;; 3. Find all restaurants
  (get-related-entities merchant-reg :category "restaurants")
  ;; => ["starbucks" "mcdonalds" "chipotle" ...]

  ;; 4. Get all relationships for an entity
  (get-entity-relationships merchant-reg "starbucks")
  ;; => {:merchant/id "starbucks"
  ;;     :has-category "restaurants"
  ;;     :same-as ["starbucks-downtown"]
  ;;     :inverse-of []}

  ;; 5. Analyze equivalence chains
  (analyze-equivalence-chains merchant-reg)
  ;; => {:total-chains 5
  ;;     :max-chain-length 2
  ;;     :loops-detected 0}

  ;; 6. Get relationship stats
  (get-relationship-stats merchant-reg)
  ;; => {:by-category {"restaurants" 15, "healthcare" 8}
  ;;     :entities-with-equivalences 12}

  ;; 7. Export to triples for visualization
  (take 10 (export-to-triples merchant-reg))
  ;; => [[:merchant/starbucks :has-category :category/restaurants]
  ;;     [:merchant/starbucks :has-mcc 5812]]

  ;; 8. Get entity subgraph (1-hop neighborhood)
  (get-entity-subgraph merchant-reg "starbucks")
  ;; => {:center ["starbucks" {...}]
  ;;     :outgoing []
  ;;     :incoming [["starbucks-downtown" {...}]]}
  )
