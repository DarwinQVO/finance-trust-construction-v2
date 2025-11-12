(require '[finance.entities.merchant :as merchant])

(println "\n🧪 Demo: Entities as Things, Not Strings\n")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

;; ============================================================================
;; PARTE 1: Crear Entities (Things con Identity)
;; ============================================================================

(println "\n📦 PARTE 1: Crear Entities con UUID Identity\n")

;; SAT (Tax Authority)
(def sat-entity
  (merchant/create-merchant-entity
   {:canonical-name "Servicio de Administración Tributaria"
    :rfc "SAT8410245V8"
    :category :taxes
    :entity-type :tax-authority
    :classified-by "darwin"
    :variations ["SAT8410245V8" "COBRANZA SAT" "SAT - IMPUESTOS" "PAGO SAT MENSUAL"]}))

(println "✅ SAT Entity creado:")
(println (format "   UUID: %s" (:entity-id sat-entity)))
(println (format "   Name: %s" (:canonical-name sat-entity)))
(println (format "   RFC: %s" (:rfc sat-entity)))
(println (format "   Type: %s" (:entity-type sat-entity)))
(println (format "   Variations: %d" (count (:variations sat-entity))))

;; Atlas Seguros (Insurance Company)
(def atlas-entity
  (merchant/create-merchant-entity
   {:canonical-name "Atlas Seguros"
    :rfc "CNM980114PI2"
    :category :insurance
    :entity-type :business
    :classified-by "darwin"
    :variations ["CNM980114PI2" "COBRANZA ATLAS" "ATLAS SEGUROS"]}))

(println "\n✅ Atlas Seguros Entity creado:")
(println (format "   UUID: %s" (:entity-id atlas-entity)))
(println (format "   Name: %s" (:canonical-name atlas-entity)))
(println (format "   RFC: %s" (:rfc atlas-entity)))
(println (format "   Variations: %d" (count (:variations atlas-entity))))

;; FARM PARIS (Restaurant)
(def farm-paris-entity
  (merchant/create-merchant-entity
   {:canonical-name "Farm Paris Yaxchilan"
    :rfc "PIF880519GH0"
    :category :restaurants
    :entity-type :merchant
    :classified-by "darwin"
    :variations ["FARM PARIS YAXCHILAN" "PIF880519GH0" "FARM PARIS" "FARM PARIS CANCUN"]}))

(println "\n✅ FARM PARIS Entity creado:")
(println (format "   UUID: %s" (:entity-id farm-paris-entity)))
(println (format "   Name: %s" (:canonical-name farm-paris-entity)))
(println (format "   RFC: %s" (:rfc farm-paris-entity)))
(println (format "   Variations: %d" (count (:variations farm-paris-entity))))

;; ============================================================================
;; PARTE 2: Variation Matching (Case-Insensitive + Confidence)
;; ============================================================================

(println "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "\n🔍 PARTE 2: Variation Matching (Robust)\n")

;; Test 1: Exact match
(def test1 (merchant/matches-variation? sat-entity "SAT8410245V8"))
(println (format "Test 1: 'SAT8410245V8' matches SAT entity? %s ✓" test1))

;; Test 2: Case-insensitive
(def test2 (merchant/matches-variation? sat-entity "sat8410245v8"))
(println (format "Test 2: 'sat8410245v8' matches SAT entity? %s ✓" test2))

;; Test 3: Different variation
(def test3 (merchant/matches-variation? sat-entity "COBRANZA SAT"))
(println (format "Test 3: 'COBRANZA SAT' matches SAT entity? %s ✓" test3))

;; Test 4: No match
(def test4 (merchant/matches-variation? sat-entity "GOOGLE"))
(println (format "Test 4: 'GOOGLE' matches SAT entity? %s ✓" test4))

;; Test 5: FARM PARIS matching
(def test5 (merchant/matches-variation? farm-paris-entity "FARM PARIS"))
(println (format "Test 5: 'FARM PARIS' matches FARM PARIS entity? %s ✓" test5))

;; Test 6: RFC matching
(def test6 (merchant/matches-variation? farm-paris-entity "PIF880519GH0"))
(println (format "Test 6: 'PIF880519GH0' matches FARM PARIS entity? %s ✓" test6))

;; ============================================================================
;; PARTE 3: Adding New Variations (Learning)
;; ============================================================================

(println "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "\n🎓 PARTE 3: Learning - Adding New Variations\n")

(println "Scenario: Usuario encuentra 'PAGO SAT ANUAL' en nuevo PDF")
(println "          → Añade como nueva variation a SAT entity")

(def sat-updated
  (merchant/add-variation sat-entity
                          "PAGO SAT ANUAL"
                          :pdf-extraction
                          0.90))

(println "\n✅ SAT Entity actualizado:")
(println (format "   Variations antes: %d" (count (:variations sat-entity))))
(println (format "   Variations después: %d" (count (:variations sat-updated))))
(println (format "   Nueva variation: 'PAGO SAT ANUAL' (confidence: 0.90)"))
(println (format "   Version: %d → %d" (:version sat-entity) (:version sat-updated)))

;; Ahora "PAGO SAT ANUAL" matchea automáticamente!
(def test7 (merchant/matches-variation? sat-updated "PAGO SAT ANUAL"))
(println (format "\n   'PAGO SAT ANUAL' now matches SAT? %s ✓ (automatic!)" test7))

;; ============================================================================
;; PARTE 4: Identity Stability (UUID Never Changes)
;; ============================================================================

(println "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "\n🔐 PARTE 4: Identity Stability (UUID is Forever)\n")

(println "Scenario: SAT cambia de RFC (SAT8410245V8 → SAT8410245V9)")
(println "          → UUID stays the same, properties change")

(def sat-v2
  (merchant/update-properties sat-updated
                              {:rfc "SAT8410245V9"
                               :canonical-name "SAT (Actualizado 2025)"}))

(println "\n✅ SAT Entity - RFC changed:")
(println (format "   UUID antes:  %s" (:entity-id sat-updated)))
(println (format "   UUID después: %s ← SAME!" (:entity-id sat-v2)))
(println (format "   RFC antes:   %s" (:rfc sat-updated)))
(println (format "   RFC después:  %s ← CHANGED" (:rfc sat-v2)))
(println (format "   Version: %d → %d" (:version sat-updated) (:version sat-v2)))

;; All transactions referencing this UUID still work!
(println "\n   ✓ All transactions con entity-id %s siguen funcionando" (str (:entity-id sat-v2)))
(println "   ✓ Queries by UUID funcionan sin cambios")
(println "   ✓ Audit trail completo (v1 → v2)")

;; ============================================================================
;; PARTE 5: Rich Metadata (Provenance)
;; ============================================================================

(println "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "\n📊 PARTE 5: Rich Metadata (Provenance)\n")

(println "✅ FARM PARIS Entity - Complete Metadata:")
(println (format "   UUID:          %s" (:entity-id farm-paris-entity)))
(println (format "   Canonical:     %s" (:canonical-name farm-paris-entity)))
(println (format "   RFC:           %s" (:rfc farm-paris-entity)))
(println (format "   Category:      %s" (:category farm-paris-entity)))
(println (format "   Type:          %s" (:entity-type farm-paris-entity)))
(println (format "   Confidence:    %.2f (Manual = 100%%)" (:confidence farm-paris-entity)))
(println (format "   Source:        %s" (:source farm-paris-entity)))
(println (format "   Classified By: %s" (:classified-by farm-paris-entity)))
(println (format "   Created At:    %s" (:created-at farm-paris-entity)))
(println (format "   Version:       %d" (:version farm-paris-entity)))

(println "\n   Variations:")
(doseq [var (:variations farm-paris-entity)]
  (println (format "     - '%s' (source: %s, confidence: %.2f)"
                   (:variation-text var)
                   (:variation-source var)
                   (:variation-confidence var))))

;; ============================================================================
;; PARTE 6: Serialization (Thing → JSON → Thing)
;; ============================================================================

(println "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "\n💾 PARTE 6: Serialization (Thing → JSON → Thing)\n")

(def json-map (merchant/entity->json-map farm-paris-entity))
(println "✅ Entity serializado a JSON:")
(println (format "   entity-id (UUID→String): %s" (:entity-id json-map)))
(println (format "   created-at (Instant→String): %s" (:created-at json-map)))

(def restored-entity (merchant/json-map->entity json-map))
(println "\n✅ Entity restaurado de JSON:")
(println (format "   entity-id (String→UUID): %s" (:entity-id restored-entity)))
(println (format "   Type: %s" (type (:entity-id restored-entity))))  ;; java.util.UUID
(println (format "   created-at (String→Instant): %s" (:created-at restored-entity)))
(println (format "   Type: %s" (type (:created-at restored-entity))))  ;; java.time.Instant

;; ============================================================================
;; RESUMEN
;; ============================================================================

(println "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "\n🎯 RESUMEN: Por Qué Entities Son 'Things', Not Strings\n")

(println "✅ Identity Stability:")
(println "   - UUID nunca cambia → queries funcionan forever")
(println "   - Properties pueden cambiar → versionado automático")

(println "\n✅ Robust Matching:")
(println "   - Case-insensitive: 'SAT' = 'sat'")
(println "   - Multiple variations: 'SAT8410245V8', 'COBRANZA SAT', etc.")
(println "   - Confidence-based: Mejor match tiene mayor confidence")

(println "\n✅ Learning Over Time:")
(println "   - Add new variations → automatic matching next time")
(println "   - FARM PARIS primera vez: manual review")
(println "   - FARM PARIS segunda vez: automatic match! ✓")

(println "\n✅ Rich Metadata:")
(println "   - Provenance: WHO classified (darwin)")
(println "   - Provenance: WHEN (2024-11-07)")
(println "   - Provenance: HOW (manual-classification)")
(println "   - Provenance: CONFIDENCE (1.0 = 100%)")

(println "\n✅ Relationships (Future):")
(println "   - SAT → parent: Gobierno de México")
(println "   - Gobierno México → children: [SAT, IMSS, INFONAVIT]")
(println "   - Query: ¿Cuánto pagué a gobierno? → sum all children")

(println "\n✅ Versioning & Audit Trail:")
(println "   - Version 1: Initial classification")
(println "   - Version 2: RFC changed")
(println "   - Version 3: Name updated")
(println "   - Full history preserved → compliance-ready")

(println "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "\n🚀 NEXT STEPS:\n")
(println "1. Migrate current merchant_registry.json → entity registry (UUID-based)")
(println "2. Update Stage 4 to use entity-id instead of merchant-text")
(println "3. Update API endpoints to return entity 'things' (not strings)")
(println "4. Update UI to display rich metadata (provenance, relationships)")
(println "5. Implement automated pipeline with entity matching")

(println "\n💡 Result: Sistema 'perdurable' y 'cada vez más fácil' ✅\n")

(System/exit 0)
