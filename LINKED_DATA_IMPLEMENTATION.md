# Linked Data Implementation - Entity Resolution

**Fecha:** 2025-11-15
**Inspirado por:** RDF/OWL, Grafter (Clojure), Neo4j
**Filosofía:** 80% beneficios de linked data con 20% de complejidad

---

## 🎯 Qué implementamos

Sistema de linked data **SIN** la complejidad de triple stores, manteniendo JSON como storage pero agregando capacidades de:

1. **Entity Equivalences** (owl:sameAs)
2. **Bidirectional Relationships** (reverse lookups)
3. **Enhanced Provenance** (complete resolution trail)
4. **Graph-style Queries** (sin graph database)
5. **RDF Export** (para visualización/interoperabilidad)

---

## 📂 Archivos creados

### 1. `src/finance/entity_graph.clj`
**Namespace principal con 7 capacidades:**

```clojure
(require '[finance.entity-graph :as graph])

;; 1. Entity Equivalences
(graph/resolve-equivalence registry "starbucks-downtown")
;; => "starbucks-main"  (follows owl:sameAs links)

;; 2. Bidirectional Relationships
(graph/get-related-entities registry :category "restaurants")
;; => ["starbucks" "mcdonalds" "chipotle"]

;; 3. Entity Relationships (RDF-style)
(graph/get-entity-relationships registry "starbucks")
;; => {:has-category "restaurants"
;;     :has-mcc 5812
;;     :same-as ["starbucks-downtown"]
;;     :inverse-of []}

;; 4. Property Queries
(graph/find-entities-by-property registry :mcc 5812)
;; => [["starbucks" {...}] ["mcdonalds" {...}]]

;; 5. Subgraph Exploration (1-hop)
(graph/get-entity-subgraph registry "starbucks")
;; => {:center [...] :outgoing [...] :incoming [...]}

;; 6. RDF Triple Export
(graph/export-to-triples registry)
;; => [[:merchant/starbucks :has-category :category/restaurants]
;;     [:merchant/starbucks :has-mcc 5812]]

;; 7. Analytics
(graph/analyze-equivalence-chains registry)
(graph/get-relationship-stats registry)
```

### 2. `demo-entity-graph.clj`
**Demo completo que ejecuta todas las capacidades**

---

## 🔍 Capacidades detalladas

### 1. Entity Equivalences (owl:sameAs)

**Problema:** Un mismo merchant aparece con diferentes nombres.

**Solución:** Linked data pattern `owl:sameAs`

```json
// En merchant_registry.json
{
  "starbucks-downtown": {
    "canonical-name": "Starbucks Downtown",
    "same-as": ["starbucks-main"],  // ← Link to canonical
    "category": "restaurants"
  },
  "starbucks-main": {
    "canonical-name": "Starbucks",
    "category": "restaurants"
  }
}
```

```clojure
;; Resolver equivalence automáticamente
(get-canonical-entity registry "starbucks-downtown")
;; => {:entity-id "starbucks-main"
;;     :entity-data {...}
;;     :hops-count 1
;;     :path ["starbucks-downtown" "starbucks-main"]}
```

**Beneficio:** Deduplicación automática, trazabilidad de aliases.

---

### 2. Bidirectional Relationships

**Problema:** Buscar "qué merchants hay en categoría X" requiere full scan.

**Solución:** Reverse index automático.

```clojure
;; Forward (ya existe)
merchant → category

;; Reverse (nuevo)
category → [merchants]

(get-related-entities merchant-reg :category "restaurants")
;; => ["starbucks" "mcdonalds" "chipotle" "burger-king" ...]
```

**Beneficio:** Queries O(1) en lugar de O(n).

---

### 3. Enhanced Provenance

**Antes:**
```clojure
{:merchant-canonical "STARBUCKS"
 :merchant-entity {...}}
```

**Ahora:**
```clojure
{:merchant-canonical "STARBUCKS"
 :merchant-entity {...}
 :merchant-provenance
   {:resolved-at "2025-11-15T10:30:00Z"
    :matched-via :exact-variation
    :confidence 0.95
    :source-text "STARBUCKS CORP"
    :registry-version "1.0.0"
    :resolver-version "entity-engine/1.0"
    :canonical-id "starbucks"
    :equivalence-chain {:hops 1
                       :path ["starbucks-downtown" "starbucks"]}}}
```

**Beneficio:** Complete audit trail, debugging más fácil.

---

### 4. Graph-style Queries

**Sin migrar a graph database:**

```clojure
;; Find by property
(find-entities-by-property registry :entity-type "person")
;; => All people

(find-entities-by-property registry :mcc 5812)
;; => All restaurants (MCC 5812)

;; Get neighborhood (1-hop)
(get-entity-subgraph registry "starbucks")
;; => {:center entity
;;     :outgoing [entities this points to]
;;     :incoming [entities that point to this]}
```

**Beneficio:** Graph traversal sin Neo4j.

---

### 5. RDF Export

**Para visualización o export a RDF tools:**

```clojure
(export-to-triples merchant-reg)
;; => [[:merchant/starbucks :has-category :category/restaurants]
;;     [:merchant/starbucks :has-mcc 5812]
;;     [:merchant/starbucks :same-as :merchant/starbucks-main]
;;     [:merchant/mcdonalds :has-category :category/restaurants]]
```

**Uso:** Importar a GraphViz, Neo4j, o cualquier herramienta RDF.

---

## 📊 Demo Output

```bash
$ clojure -M demo-entity-graph.clj

🔗 LINKED DATA DEMO - Entity Resolution
==========================================

📊 Registry loaded: 20 merchants

1️⃣  RELATIONSHIP STATISTICS
━━━━━━━━━━━━━━━━━━━━━━━━━━━━

📈 By Category:
   utilities: 4 merchants
   restaurants: 3 merchants
   healthcare: 3 merchants
   groceries: 3 merchants
   home: 2 merchants

🏢 By Entity Type:
   business: 15
   person: 5

🔗 Equivalences:
   Entities with same-as links: 0
   Isolated entities: 0
   Total entities: 20


2️⃣  BIDIRECTIONAL RELATIONSHIPS
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

🍔 All merchants in 'restaurants' category:
   - TERE CAZOLA (tere-cazola)
   - XJ PUERTO CANCUN (xj-puerto-cancun)
   - HANAICHI (hanaichi)

💊 All merchants in 'healthcare' category:
   - BP DERMA COLOMBIANA (bp-derma-colombiana)
   - FARMACIA PARIS (farmacia-paris)
   - FARMACIA DEL AHORRO (farmacia-del-ahorro)


3️⃣  ENTITY RELATIONSHIPS (RDF-style)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

🏪 Relationships for: tere-cazola
   has-category: restaurants
   has-mcc: 5812
   has-budget-category: Living


4️⃣  EXPORT TO RDF TRIPLES
━━━━━━━━━━━━━━━━━━━━━━━━━

📝 Sample triples (first 10):
   [:merchant/tere-cazola :has-category :category/restaurants]
   [:merchant/tere-cazola :has-mcc 5812]
   [:merchant/bp-derma-colombiana :has-category :category/healthcare]
   [:merchant/bp-derma-colombiana :has-mcc 8011]
   ...

   Total triples: 35


6️⃣  QUERY BY PROPERTY
━━━━━━━━━━━━━━━━━━━━━

🔍 Find merchants with entity-type = 'person':
   - DIANA (id: diana)
   - DARWIN (id: darwin)
   - JESUS TORRES RENTA (id: jesus-torres-renta)

🔍 Find merchants with mcc = 5812 (Restaurants):
   - TERE CAZOLA (id: tere-cazola)
   - XJ PUERTO CANCUN (id: xj-puerto-cancun)
   - HANAICHI (id: hanaichi)


7️⃣  ENTITY SUBGRAPH (1-hop neighborhood)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

🌐 Subgraph for: tere-cazola

   Center:
     tere-cazola - TERE CAZOLA
```

---

## 🚀 Próximos pasos

### 1. Integrar con transaction resolution

```clojure
(require '[finance.entity-graph :as graph])

(defn resolve-with-graph-capabilities
  [transaction entity-def]
  ;; Usa graph/resolve-with-provenance en lugar de engine/resolve-entity-generic
  (graph/resolve-with-provenance transaction entity-def))
```

### 2. Agregar same-as relationships

```bash
# En REPL o script:
(graph/add-equivalence! "resources/registry/merchant_registry.json"
                        "starbucks-downtown"
                        "starbucks-main")
```

### 3. UI para graph visualization

```clojure
;; Endpoint API:
GET /api/v2/entities/:id/graph

;; Returns:
{:entity {...}
 :relationships {:outgoing [...] :incoming [...]}
 :triples [[:entity :has-category :category] ...]}
```

Usar D3.js o vis.js para renderizar graph interactivo.

### 4. Analytics dashboard

```clojure
GET /api/v2/analytics/entities

;; Returns:
{:by-category {...}
 :by-type {...}
 :equivalence-chains {...}
 :isolated-entities 3}
```

---

## 🎓 Conceptos aplicados

### De RDF/OWL:
- ✅ `owl:sameAs` → Entity equivalences
- ✅ Triples (subject-predicate-object)
- ✅ Bidirectional navigation
- ❌ NO: Full RDF serialization (overkill)
- ❌ NO: SPARQL (queries simples bastan)
- ❌ NO: Ontology reasoning (no necesario)

### De Grafter:
- ✅ Separation of concerns (parsing vs transformation)
- ✅ Modular architecture
- ❌ NO: RDF dependencies (mantenemos JSON)

### De Neo4j/Graph DBs:
- ✅ Graph traversal (1-hop neighborhood)
- ✅ Relationship queries
- ✅ Subgraph extraction
- ❌ NO: Full graph database (JSON es suficiente)
- ❌ NO: Cypher query language (Clojure queries bastan)

---

## 📈 Métricas

**Código agregado:**
- `entity_graph.clj`: ~400 lines
- `demo-entity-graph.clj`: ~200 lines
- Total: ~600 lines

**Capacidades ganadas:**
- 7 nuevas funciones principales
- Equivalence resolution
- Bidirectional lookups
- Enhanced provenance
- Graph queries
- RDF export
- Analytics

**Performance:**
- Bidirectional lookup: O(1) vs O(n)
- Equivalence resolution: O(hops) con loop detection
- Triple export: O(entities × relationships)

---

## ✅ Checklist de integración

- [x] Namespace `finance.entity-graph` creado
- [x] Demo script funcional
- [x] 7 capacidades implementadas
- [x] Documentación completa
- [ ] Integrar con transaction resolution pipeline
- [ ] Agregar API endpoints
- [ ] UI para graph visualization
- [ ] Analytics dashboard

---

## 📚 Referencias

- **Linked Data:** https://github.com/topics/linked-data
- **Grafter (Clojure):** https://github.com/Swirrl/grafter
- **RDFLib (Python):** https://github.com/RDFLib/rdflib
- **OWL sameAs:** https://www.w3.org/TR/owl-ref/#sameAs-def

---

**Siguiente:** Integrar con web UI para visualizar graph de entities.
