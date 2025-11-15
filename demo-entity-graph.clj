(ns demo-entity-graph
  "Demo of linked data capabilities in entity resolution"
  (:require [finance.entity-graph :as graph]
            [finance.entity-engine :as engine]))

(println "\n🔗 LINKED DATA DEMO - Entity Resolution")
(println "==========================================\n")

;; Load merchant registry
(def merchant-reg-raw (engine/load-registry
                       "resources/registry/merchant_registry.json"
                       "merchants"))

;; Convert array of arrays [[id data] ...] to map {id data ...}
(def merchant-reg (into {} merchant-reg-raw))

(println "📊 Registry loaded:" (count merchant-reg) "merchants\n")

;; ============================================================================
;; Demo 1: Relationship Statistics
;; ============================================================================

(println "1️⃣  RELATIONSHIP STATISTICS")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

(def stats (graph/get-relationship-stats merchant-reg))

(println "\n📈 By Category:")
(doseq [[category count] (take 5 (sort-by (comp - second) (:by-category stats)))]
  (println (format "   %s: %d merchants" category count)))

(println "\n🏢 By Entity Type:")
(doseq [[type count] (:by-entity-type stats)]
  (println (format "   %s: %d" type count)))

(println "\n🔗 Equivalences:")
(println "   Entities with same-as links:" (:entities-with-equivalences stats))
(println "   Isolated entities:" (:isolated-entities stats))
(println "   Total entities:" (:total-entities stats))

;; ============================================================================
;; Demo 2: Bidirectional Relationships
;; ============================================================================

(println "\n\n2️⃣  BIDIRECTIONAL RELATIONSHIPS")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

(println "\n🍔 All merchants in 'restaurants' category:")
(def restaurants (graph/get-related-entities merchant-reg :category "restaurants"))
(doseq [merchant-id (take 5 restaurants)]
  (let [merchant (get merchant-reg merchant-id)]
    (println (format "   - %s (%s)"
                    (:canonical-name merchant)
                    merchant-id))))

(println "\n💊 All merchants in 'healthcare' category:")
(def healthcare (graph/get-related-entities merchant-reg :category "healthcare"))
(doseq [merchant-id (take 5 healthcare)]
  (let [merchant (get merchant-reg merchant-id)]
    (println (format "   - %s (%s)"
                    (:canonical-name merchant)
                    merchant-id))))

;; ============================================================================
;; Demo 3: Entity Relationships (RDF-style triples)
;; ============================================================================

(println "\n\n3️⃣  ENTITY RELATIONSHIPS (RDF-style)")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

(when-let [first-merchant (first (keys merchant-reg))]
  (println "\n🏪 Relationships for:" first-merchant)
  (def rels (graph/get-entity-relationships merchant-reg first-merchant))

  (println (format "   has-category: %s" (:has-category rels)))
  (println (format "   has-mcc: %s" (:has-mcc rels)))
  (println (format "   has-budget-category: %s" (:has-budget-category rels)))
  (when (:same-as rels)
    (println (format "   same-as: %s" (pr-str (:same-as rels)))))
  (when (seq (:inverse-of rels))
    (println (format "   inverse-of: %s" (pr-str (:inverse-of rels))))))

;; ============================================================================
;; Demo 4: Export to Triples
;; ============================================================================

(println "\n\n4️⃣  EXPORT TO RDF TRIPLES")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━")

(def triples (graph/export-to-triples merchant-reg))

(println "\n📝 Sample triples (first 10):")
(doseq [triple (take 10 triples)]
  (println (format "   %s" (pr-str triple))))

(println (format "\n   Total triples: %d" (count triples)))

;; ============================================================================
;; Demo 5: Equivalence Analysis
;; ============================================================================

(println "\n\n5️⃣  EQUIVALENCE CHAIN ANALYSIS")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

(def equiv-analysis (graph/analyze-equivalence-chains merchant-reg))

(println "\n📊 Chain Statistics:")
(println (format "   Total chains: %d" (:total-chains equiv-analysis)))
(println (format "   Valid chains: %d" (:valid-chains equiv-analysis)))
(println (format "   Loops detected: %d" (:loops-detected equiv-analysis)))
(println (format "   Max chain length: %d" (:max-chain-length equiv-analysis)))
(println (format "   Avg chain length: %.2f" (:avg-chain-length equiv-analysis)))

(when (seq (:chains equiv-analysis))
  (println "\n🔗 Sample chains:")
  (doseq [chain (take 3 (:chains equiv-analysis))]
    (println (format "   %s → hops: %d"
                    (pr-str (:path chain))
                    (:hops-count chain)))))

;; ============================================================================
;; Demo 6: Find Entities by Property
;; ============================================================================

(println "\n\n6️⃣  QUERY BY PROPERTY")
(println "━━━━━━━━━━━━━━━━━━━━━")

(println "\n🔍 Find merchants with entity-type = 'person':")
(def people (graph/find-entities-by-property merchant-reg :entity-type "person"))
(doseq [[id data] (take 3 people)]
  (println (format "   - %s (id: %s)" (:canonical-name data) id)))

(println "\n🔍 Find merchants with mcc = 5812 (Restaurants):")
(def mcc-5812 (graph/find-entities-by-property merchant-reg :mcc 5812))
(doseq [[id data] (take 3 mcc-5812)]
  (println (format "   - %s (id: %s)" (:canonical-name data) id)))

;; ============================================================================
;; Demo 7: Entity Subgraph
;; ============================================================================

(println "\n\n7️⃣  ENTITY SUBGRAPH (1-hop neighborhood)")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

(when-let [sample-id (first (keys merchant-reg))]
  (println "\n🌐 Subgraph for:" sample-id)
  (def subgraph (graph/get-entity-subgraph merchant-reg sample-id))

  (println "\n   Center:")
  (let [[id data] (:center subgraph)]
    (println (format "     %s - %s" id (:canonical-name data))))

  (when (seq (:outgoing subgraph))
    (println "\n   Outgoing (same-as):")
    (doseq [[id data] (:outgoing subgraph)]
      (println (format "     → %s (%s)" id (:canonical-name data)))))

  (when (seq (:incoming subgraph))
    (println "\n   Incoming (referenced by):")
    (doseq [[id data] (:incoming subgraph)]
      (println (format "     ← %s (%s)" id (:canonical-name data))))))

;; ============================================================================
;; Summary
;; ============================================================================

(println "\n\n✅ DEMO COMPLETE")
(println "━━━━━━━━━━━━━━━")
(println "\nKey capabilities demonstrated:")
(println "  1. ✓ Relationship statistics")
(println "  2. ✓ Bidirectional lookups (category → merchants)")
(println "  3. ✓ RDF-style relationships")
(println "  4. ✓ Export to triples")
(println "  5. ✓ Equivalence chain analysis")
(println "  6. ✓ Property-based queries")
(println "  7. ✓ Entity subgraph exploration")

(println "\n💡 Next steps:")
(println "  - Add same-as relationships to test equivalences")
(println "  - Integrate with web UI for graph visualization")
(println "  - Use in transaction resolution pipeline")
(println "\n")
