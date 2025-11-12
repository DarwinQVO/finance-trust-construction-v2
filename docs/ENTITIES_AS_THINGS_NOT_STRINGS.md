# 🎯 Entities as Things, Not Strings

**Fecha:** 2025-11-07
**Contexto:** Respuesta a la pregunta "las entitys son things, not strings.?"

---

## ❌ El Problema Actual (Strings)

### Lo que tienes ahora en `entity_registry.clj`:

```clojure
;; Merchant como STRING con metadata
{:merchant-text "SAT8410245V8"         ;; ← String
 :canonical-name "SAT"                 ;; ← String
 :category :taxes                      ;; ← Keyword suelto
 :confidence 0.95}                     ;; ← Number suelto
```

### 5 Problemas Arquitecturales:

1. **No hay identidad estable**
   ```clojure
   ;; ¿Qué pasa si cambio el RFC?
   "SAT8410245V8" → "SAT8410245V9"  ;; ¿Es el mismo entity?
   ```

2. **No hay relaciones**
   ```clojure
   ;; SAT es parte de gobierno mexicano → ¿Cómo representar esto?
   ;; Atlas Seguros tiene subsidiarias → ¿Cómo vincular?
   ```

3. **No hay versionado**
   ```clojure
   ;; Si SAT cambia de categoría o nombre → perdemos historia
   ;; No hay audit trail de cambios
   ```

4. **No hay rich metadata**
   ```clojure
   ;; ¿Quién clasificó esto? ¿Cuándo? ¿Con qué confianza?
   ;; ¿De qué fuente vino? ¿Manual o automático?
   ```

5. **String matching es frágil**
   ```clojure
   "SAT8410245V8" == "sat8410245v8"?  ;; Case-sensitive?
   "SAT - IMPUESTOS" matches "SAT8410245V8"?  ;; Fuzzy match?
   ```

---

## ✅ La Solución (Things)

### Entity como "Thing" con identidad:

```clojure
;; Entity con UUID estable
{:entity-id #uuid "123e4567-e89b-12d3-a456-426614174000"
 :entity-type :tax-authority
 :canonical-name "Servicio de Administración Tributaria"
 :rfc "SAT8410245V8"
 :category :taxes

 ;; Relationships
 :parent-org #uuid "gov-mexico-uuid"
 :subsidiaries []

 ;; Metadata
 :confidence 1.0
 :source :manual-classification
 :classified-by "darwin"
 :created-at #inst "2024-01-01"
 :version 1

 ;; Variations (cómo aparece en text)
 :variations [{:variation-text "SAT8410245V8"
               :variation-source :pdf-extraction
               :variation-confidence 1.0}
              {:variation-text "COBRANZA SAT"
               :variation-source :user-input
               :variation-confidence 0.95}
              {:variation-text "SAT - IMPUESTOS"
               :variation-source :csv-import
               :variation-confidence 0.90}]}
```

---

## 🏗️ Ventajas de "Things"

### 1. **Identidad Estable (UUID)**

```clojure
;; UUID nunca cambia, propiedades pueden cambiar
(def sat-v1 {:entity-id uuid-123
             :canonical-name "SAT"
             :rfc "SAT8410245V8"
             :version 1})

(def sat-v2 {:entity-id uuid-123       ;; ← MISMO UUID
             :canonical-name "SAT"
             :rfc "SAT8410245V9"       ;; ← RFC cambió
             :version 2})              ;; ← Nueva versión

;; Queries funcionan con UUID, no con texto
(get-entity-history uuid-123)
;; => [sat-v1 sat-v2]
```

### 2. **Relaciones Explícitas**

```clojure
;; SAT es parte de gobierno mexicano
{:entity-id uuid-sat
 :parent-org uuid-gobierno-mexico
 :canonical-name "SAT"}

;; Gobierno mexicano tiene entidades hijas
{:entity-id uuid-gobierno-mexico
 :subsidiaries [uuid-sat uuid-imss uuid-infonavit]}

;; Query: ¿Qué transacciones son pagos a gobierno?
(defn government-transactions [db]
  (let [gov-id uuid-gobierno-mexico
        gov-entities (get-descendants gov-id)]  ;; SAT, IMSS, INFONAVIT
    (filter #(contains? gov-entities (:entity-id %)) transactions)))
```

### 3. **Versionado Temporal**

```clojure
;; Version 1: Clasificación inicial
{:entity-id uuid-123
 :canonical-name "GOOGLE YOUTUBEPREMIUM"
 :category :streaming
 :version 1
 :created-at #inst "2024-01-01"}

;; Version 2: Reclasificación
{:entity-id uuid-123
 :canonical-name "Google - YouTube Premium"
 :category :subscriptions  ;; ← Cambió
 :version 2
 :updated-at #inst "2024-06-15"
 :updated-by "darwin"}

;; Time-travel query: ¿Cómo se veía este entity en marzo 2024?
(get-entity-at-time uuid-123 #inst "2024-03-01")
;; => Version 1
```

### 4. **Rich Metadata (Provenance)**

```clojure
;; Sabes QUIÉN, CUÁNDO, CÓMO clasificó
{:entity-id uuid-farm-paris
 :canonical-name "Farm Paris Yaxchilan"
 :rfc "PIF880519GH0"
 :category :restaurants

 ;; Provenance completo
 :source :manual-classification  ;; vs :automatic-match, :ml-prediction
 :classified-by "darwin"
 :created-at #inst "2024-11-07T10:30:00Z"
 :confidence 1.0  ;; Manual = 100% confident

 ;; Audit trail
 :classification-notes "Restaurant en Cancún, aparece frecuentemente en PDFs de Scotia"}
```

### 5. **Variation Matching Robusto**

```clojure
;; Entity tiene múltiples variaciones
{:entity-id uuid-sat
 :variations [{:variation-text "SAT8410245V8"
               :variation-confidence 1.0}
              {:variation-text "COBRANZA SAT"
               :variation-confidence 0.95}
              {:variation-text "sat - impuestos"
               :variation-confidence 0.85}
              {:variation-text "PAGO SAT MENSUAL"
               :variation-confidence 0.90}]}

;; Matching es case-insensitive y considera confidence
(matches-variation? sat-entity "sat - impuestos")  ;; => true (0.85 confidence)
(matches-variation? sat-entity "GOOGLE")           ;; => false
```

---

## 🔄 Migración: String Registry → Thing Registry

### Paso 1: Convertir merchant_registry.json actual

**ANTES (String-based):**
```json
{
  "merchants": {
    "google-youtube": {
      "canonical-name": "Google - YouTube Premium",
      "category": "subscriptions",
      "entity-type": "merchant",
      "variations": ["GOOGLE YOUTUBEPREMIUM", "YOUTUBE PREMIUM"]
    }
  }
}
```

**DESPUÉS (Thing-based):**
```json
{
  "entities": {
    "ent-google-youtube-uuid": {
      "entity-id": "123e4567-e89b-12d3-a456-426614174000",
      "entity-type": "merchant",
      "canonical-name": "Google - YouTube Premium",
      "rfc": null,
      "category": "subscriptions",
      "confidence": 1.0,
      "source": "manual-classification",
      "classified-by": "darwin",
      "created-at": "2024-01-01T00:00:00Z",
      "version": 1,
      "variations": [
        {
          "variation-text": "GOOGLE YOUTUBEPREMIUM",
          "variation-source": "pdf-extraction",
          "variation-confidence": 1.0
        },
        {
          "variation-text": "YOUTUBE PREMIUM",
          "variation-source": "csv-import",
          "variation-confidence": 0.95
        }
      ],
      "parent-org": null,
      "subsidiaries": []
    }
  }
}
```

### Paso 2: Script de migración

```clojure
(ns finance.migration.strings-to-things
  (:require [finance.entity-registry :as old-registry]
            [finance.entities.merchant :as merchant]))

(defn migrate-string-registry-to-things
  "Converts old string-based registry to new thing-based registry"
  []
  (let [old-merchants (old-registry/list-all-merchants)
        new-entities (mapv convert-merchant-to-entity old-merchants)]
    (save-entity-registry! new-entities)))

(defn convert-merchant-to-entity
  "Converts old merchant map to new entity 'thing'"
  [old-merchant]
  (merchant/create-merchant-entity
   {:canonical-name (:canonical-name old-merchant)
    :rfc nil  ;; Old registry didn't track RFC
    :category (:category old-merchant)
    :entity-type (:entity-type old-merchant)
    :classified-by "migration-script"
    :variations (:variations old-merchant [])}))

;; Run migration
(comment
  (migrate-string-registry-to-things)
  ;; => Converts all 50+ merchants to entity "things" with UUIDs
)
```

---

## 🎯 Cómo Esto Conecta con Tu Pipeline Ideal

### Tu visión:
```
PDF → Extract clean → Match automatic → Review manual → Registry perdurable
```

### Con "Things", el registry se vuelve:

**1. Perdurable (UUID nunca cambia)**
```clojure
;; Hoy
(classify-transaction tx uuid-sat)  ;; SAT

;; En 5 años (SAT cambió de RFC)
(classify-transaction tx uuid-sat)  ;; MISMO UUID, diferentes propiedades
```

**2. Más fácil cada vez (learning)**
```clojure
;; Primera vez: FARM PARIS → Manual review
(add-new-entity "Farm Paris Yaxchilan" :restaurants "PIF880519GH0")
;; => UUID creado

;; Segunda vez: "FARM PARIS" en PDF → Automatic match!
(lookup-by-variation "FARM PARIS")
;; => Entity uuid-farm-paris encontrado (substring match, confidence 0.85)
;; NO necesita review manual

;; Tercera vez: Usuario añade nueva variación
(add-variation uuid-farm-paris "FARM PARIS CANCUN" :user-input 1.0)
;; => Ahora "FARM PARIS CANCUN" también matchea automáticamente
```

**3. Queries poderosas**
```clojure
;; ¿Cuánto gasté en restaurantes este año?
(sum-amounts
 (filter #(= (:category (get-entity (:entity-id %))) :restaurants)
         transactions-2024))

;; ¿Cuánto pagué a gobierno (SAT + IMSS + INFONAVIT)?
(let [gov-entities (get-descendants uuid-gobierno-mexico)]
  (sum-amounts
   (filter #(contains? gov-entities (:entity-id %))
           transactions-2024)))

;; ¿Qué merchants son nuevos (nunca vistos antes)?
(filter #(< (:variation-count %) 2) all-entities)
;; => [farm-paris-uuid, new-restaurant-uuid]
```

---

## 📊 Comparación: String vs Thing

| Feature | String-based | Thing-based |
|---------|-------------|-------------|
| **Identity** | Text (mutable) | UUID (immutable) |
| **Relationships** | ❌ No soportado | ✅ parent-org, subsidiaries |
| **Versioning** | ❌ No | ✅ Time-travel queries |
| **Provenance** | ⚠️ Limitado | ✅ Completo (quién, cuándo, cómo) |
| **Variation matching** | ⚠️ Frágil (case-sensitive) | ✅ Robusto (confidence-based) |
| **Querying** | ⚠️ Text matching only | ✅ Rich queries (by type, category, relationships) |
| **Persistence** | ⚠️ String keys | ✅ UUID keys (never collide) |
| **Migration** | ❌ Difícil (text keys change) | ✅ Fácil (UUID stable) |

---

## 🚀 Plan de Implementación

### Phase 1: Crear Thing Infrastructure (HECHO ✅)
- ✅ `finance.entities.merchant` namespace
- ✅ Entity schema con clojure.spec
- ✅ Constructor functions
- ✅ Variation matching
- ✅ Serialization (thing → JSON)

### Phase 2: Migrar Registry (SIGUIENTE)
```clojure
;; 1. Backup actual registry
cp merchant_registry.json merchant_registry.json.backup

;; 2. Run migration
clojure -M -e "(require 'finance.migration.strings-to-things)
                (finance.migration.strings-to-things/migrate!)"

;; 3. Verify
clojure -M -e "(require 'finance.entities.registry)
                (println (count (finance.entities.registry/list-all-entities)))"
;; => 50+ entities migrados
```

### Phase 3: Actualizar Pipeline
```clojure
;; Stage 4 usa entity-id en lugar de string
(defn resolve-entity [clean-tx]
  (let [merchant-text (get-merchant-field-by-type clean-tx)
        entity (lookup-entity-by-variation merchant-text)]
    (if entity
      ;; Usar UUID, no string
      (assoc clean-tx :entity-id (:entity-id entity)
                      :entity-type (:entity-type entity)
                      :canonical-name (:canonical-name entity))
      ;; No encontrado → pending
      (add-pending clean-tx))))
```

### Phase 4: UI Actualizada
```clojure
;; API endpoint returns entity "things"
(defn get-pending-handler [request]
  {:status 200
   :body (json/write-str
          {:pending (map (fn [item]
                           (let [entity (get-entity (:entity-id item))]
                             {:merchant-text (:merchant-text item)
                              :entity entity  ;; ← Complete entity object
                              :transaction (:full-context item)}))
                         (get-pending-classifications))})})

;; UI muestra rich metadata
// Entity details
{
  "canonical_name": "Servicio de Administración Tributaria",
  "rfc": "SAT8410245V8",
  "category": "taxes",
  "entity_type": "tax-authority",
  "parent_org": "Gobierno de México",
  "confidence": 1.0,
  "classified_by": "darwin",
  "created_at": "2024-01-01T00:00:00Z",
  "variations": [
    {"text": "SAT8410245V8", "confidence": 1.0},
    {"text": "COBRANZA SAT", "confidence": 0.95}
  ]
}
```

---

## 💡 Conclusión

**"Las entities son things, not strings"** porque:

1. **Identity matters** - UUID estable permite versionado y relaciones
2. **Provenance matters** - Sabes quién, cuándo, cómo clasificó
3. **Relationships matter** - SAT → Gobierno, Atlas → Seguradoras
4. **History matters** - Time-travel queries, audit trail
5. **Learning matters** - Cada clasificación mejora el matching automático

**Tu pipeline ideal NECESITA entities como "things" para ser "perdurable" y "cada vez más fácil".**

---

## 📖 Referencias

- **Código:** `src/finance/entities/merchant.clj`
- **Registry actual:** `src/finance/entity_registry.clj`
- **Migration script:** `src/finance/migration/strings_to_things.clj` (TODO)
- **Tests:** `test/finance/entities/merchant_test.clj` (TODO)

---

**Próximo paso:** ¿Quieres que implemente la migración (Phase 2) o prefieres continuar con el pipeline automatizado primero?
