# 🎯 Data-Driven Entity System Design

**Date:** 2025-11-12
**Goal:** Add new entities **WITHOUT touching code** - only JSON configuration
**Philosophy:** Configuration > Code

---

## ❌ Current Problem

**To add a 5th entity (e.g., "Payee"), you need to:**

1. ❌ Create `resources/registry/payee_registry.json`
2. ❌ Add `load-payee-registry` function in `entity_registry.clj`
3. ❌ Add `find-payee-by-variation` function
4. ❌ Add `lookup-payee` function
5. ❌ Add `list-all-payees` function
6. ❌ Add `resolve-payee-entity` function in `stage4.clj`
7. ❌ Update `resolve-all-entities` to call new function
8. ❌ Update UI to display payee fields
9. ❌ Restart server

**Time:** 20-30 minutes + testing
**Problem:** Requires code changes, compilation, restart

---

## ✅ Goal: Data-Driven System

**To add a 5th entity, you ONLY need to:**

1. ✅ Create `resources/registry/payee_registry.json`
2. ✅ Add entity config to `resources/config/entity_definitions.json`
3. ✅ Refresh browser (UI updates automatically)

**Time:** 2 minutes
**No code changes, no restart, no compilation**

---

## 🏗️ Architecture: Generic Entity Engine

### Core Concept: **Entity Definition as Data**

Instead of hardcoding 4 entity types, we have a **registry of entity types**:

```json
// resources/config/entity_definitions.json
{
  "entity-types": [
    {
      "id": "merchant",
      "display-name": "Merchant",
      "icon": "🏪",
      "registry-file": "resources/registry/merchant_registry.json",
      "registry-key": "merchants",
      "extraction": {
        "source-field": "clean-merchant",
        "fallback-field": null
      },
      "output-fields": [
        {"key": "entity-type", "from": "entity-type"},
        {"key": "category", "from": "category"},
        {"key": "mcc", "from": "mcc"}
      ],
      "enabled": true,
      "priority": 1
    },
    {
      "id": "bank",
      "display-name": "Bank",
      "icon": "🏦",
      "registry-file": "resources/registry/bank_registry.json",
      "registry-key": "banks",
      "extraction": {
        "source-field": "bank",
        "fallback-field": null,
        "extractor-fn": "extract-bank-from-pdf-source",
        "extractor-params": ["pdf-source"]
      },
      "output-fields": [
        {"key": "bank-type", "from": "bank-type"},
        {"key": "country", "from": "country"},
        {"key": "currency", "from": "currency"},
        {"key": "default-payment-method", "from": "default-payment-method"}
      ],
      "enabled": true,
      "priority": 2
    },
    {
      "id": "account",
      "display-name": "Account",
      "icon": "💳",
      "registry-file": "resources/registry/account_registry.json",
      "registry-key": "accounts",
      "extraction": {
        "source-field": "account-name",
        "fallback-field": null,
        "derivation": {
          "template": "{bank-canonical} Checking",
          "required-fields": ["bank-canonical"]
        }
      },
      "output-fields": [
        {"key": "account-type", "from": "account-type"},
        {"key": "currency", "from": "currency"},
        {"key": "payment-method", "from": "payment-method"}
      ],
      "enabled": true,
      "priority": 3
    },
    {
      "id": "category",
      "display-name": "Category",
      "icon": "📁",
      "registry-file": "resources/registry/category_registry.json",
      "registry-key": "categories",
      "extraction": {
        "source-field": "merchant-category"
      },
      "output-fields": [
        {"key": "budget-category", "from": "budget-category"},
        {"key": "budget-subcategory", "from": "budget-subcategory"},
        {"key": "icon", "from": "icon"}
      ],
      "enabled": true,
      "priority": 4
    }
  ]
}
```

---

## 🔧 Implementation: Generic Entity Resolver

### 1. Load Entity Definitions (Startup)

```clojure
(ns finance.entity-engine
  "Generic entity resolution engine - driven by configuration")

(def entity-definitions
  "Loaded from entity_definitions.json at startup"
  (atom nil))

(defn load-entity-definitions []
  "Load all entity type definitions from config"
  (let [config-file "resources/config/entity_definitions.json"
        config (json/read-str (slurp config-file) :key-fn keyword)]
    (reset! entity-definitions (:entity-types config))))

;; Load at startup
(load-entity-definitions)
```

### 2. Generic Registry Loader

```clojure
(defn load-registry
  "Generic registry loader - works for ANY entity type"
  [registry-file registry-key]
  (try
    (let [file (io/file registry-file)]
      (if (.exists file)
        (get (json/read-str (slurp file) :key-fn keyword)
             registry-key
             {})
        {}))
    (catch Exception e
      (println "⚠️  Error loading registry:" registry-file (.getMessage e))
      {})))
```

### 3. Generic Entity Lookup

```clojure
(defn lookup-entity
  "Generic entity lookup - works for ANY entity type"
  [entity-def search-text]
  (let [registry (load-registry (:registry-file entity-def)
                                (:registry-key entity-def))]
    (find-by-variation registry search-text)))

(defn find-by-variation
  "Generic fuzzy matching - works for ALL entities"
  [registry search-text]
  (some
   (fn [[entity-id entity-data]]
     (let [variations (:variations entity-data [])
           canonical (:canonical-name entity-data)]
       (when (or (exact-match? canonical search-text)
                 (some #(exact-match? % search-text) variations)
                 (some #(substring-match? % search-text) variations))
         (assoc entity-data
                :entity-id entity-id
                :match-type (calculate-match-type canonical search-text variations)))))
   registry))
```

### 4. Generic Text Extraction

```clojure
(defn extract-text-for-entity
  "Extract search text from transaction based on entity config"
  [transaction entity-def]
  (let [extraction (:extraction entity-def)
        source-field (:source-field extraction)
        fallback-field (:fallback-field extraction)

        ;; Try source field
        text (get transaction (keyword source-field))

        ;; Try extractor function if specified
        text (if (and (nil? text) (:extractor-fn extraction))
               (call-extractor-fn (:extractor-fn extraction)
                                  transaction
                                  (:extractor-params extraction))
               text)

        ;; Try fallback field
        text (or text (get transaction (keyword fallback-field)))

        ;; Try derivation if specified
        text (or text (derive-text transaction (:derivation extraction)))]

    text))

(defn derive-text
  "Derive text from template (e.g. '{bank-canonical} Checking')"
  [transaction derivation]
  (when derivation
    (let [template (:template derivation)
          required-fields (:required-fields derivation)
          all-present? (every? #(get transaction (keyword %)) required-fields)]
      (when all-present?
        (reduce (fn [text field]
                  (clojure.string/replace text
                                         (str "{" field "}")
                                         (str (get transaction (keyword field)))))
                template
                required-fields)))))
```

### 5. Generic Entity Resolution

```clojure
(defn resolve-entity-generic
  "Resolve ANY entity type based on its definition"
  [transaction entity-def]
  (let [entity-id (:id entity-def)
        search-text (extract-text-for-entity transaction entity-def)
        entity (when search-text (lookup-entity entity-def search-text))

        ;; Build output keys dynamically
        entity-key (keyword (str entity-id "-entity"))
        resolved-key (keyword (str entity-id "-resolved?"))
        canonical-key (keyword (str entity-id "-canonical"))
        text-key (keyword (str entity-id "-text"))]

    (if entity
      ;; Entity found - merge resolved data
      (merge transaction
             {entity-key entity
              resolved-key true
              canonical-key (:canonical-name entity)}
             ;; Merge configured output fields
             (into {} (map (fn [field-def]
                            [(keyword (:key field-def))
                             (get entity (keyword (:from field-def)))])
                          (:output-fields entity-def))))

      ;; Entity not found
      (merge transaction
             {entity-key nil
              resolved-key false
              text-key search-text}))))
```

### 6. Main Resolution Loop

```clojure
(defn resolve-all-entities
  "Resolve ALL configured entity types"
  [transaction]
  (let [enabled-entities (->> @entity-definitions
                             (filter :enabled)
                             (sort-by :priority))]
    (reduce (fn [tx entity-def]
              (resolve-entity-generic tx entity-def))
            transaction
            enabled-entities)))
```

---

## 🎨 Adding a New Entity (Example: Payee)

### Step 1: Create Registry File

**`resources/registry/payee_registry.json`**
```json
{
  "payees": {
    "john-doe": {
      "canonical-name": "John Doe",
      "entity-type": "person",
      "relationship": "family",
      "variations": ["John Doe", "J. Doe", "John"]
    },
    "acme-corp": {
      "canonical-name": "ACME Corp",
      "entity-type": "business",
      "relationship": "vendor",
      "variations": ["ACME Corp", "ACME", "Acme Corporation"]
    }
  }
}
```

### Step 2: Add to Entity Definitions

**`resources/config/entity_definitions.json`**
```json
{
  "id": "payee",
  "display-name": "Payee",
  "icon": "👤",
  "registry-file": "resources/registry/payee_registry.json",
  "registry-key": "payees",
  "extraction": {
    "source-field": "beneficiary-name",
    "fallback-field": "description"
  },
  "output-fields": [
    {"key": "payee-type", "from": "entity-type"},
    {"key": "payee-relationship", "from": "relationship"}
  ],
  "enabled": true,
  "priority": 5
}
```

### Step 3: Done! 🎉

**No code changes needed!**

The system will:
- ✅ Auto-load the payee registry
- ✅ Auto-resolve payee entities
- ✅ Auto-add payee fields to transactions
- ✅ Auto-display in UI (if UI is also data-driven)

---

## 📊 Benefits

### Before (Code-Driven):
- ❌ 20 minutes per new entity
- ❌ Requires code changes
- ❌ Requires compilation
- ❌ Requires server restart
- ❌ Risk of bugs in code
- ❌ Need to update 3+ files

### After (Data-Driven):
- ✅ 2 minutes per new entity
- ✅ Only JSON files
- ✅ No compilation
- ✅ Hot-reload config (optional)
- ✅ Zero risk of code bugs
- ✅ Update 2 files (registry + config)

---

## 🚀 Migration Path

### Phase 1: Create Generic Engine (2-3 hours)
1. Create `entity-engine.clj` with generic functions
2. Create `entity_definitions.json` config
3. Write tests for generic engine
4. Verify with existing 4 entities

### Phase 2: Migrate Existing Entities (1 hour)
1. Convert merchant → entity definition
2. Convert bank → entity definition
3. Convert account → entity definition
4. Convert category → entity definition
5. Delete old specific functions

### Phase 3: Update Pipeline (30 min)
1. Replace `resolve-all-entities` with generic version
2. Test with existing PDFs
3. Verify all 4 entities still work

### Phase 4: Documentation (30 min)
1. Document how to add new entities
2. Document entity definition schema
3. Add examples

**Total Time:** ~5 hours
**Payoff:** Every future entity takes 2 minutes instead of 20

---

## 🎯 Advanced Features (Optional)

### 1. Entity Dependencies
```json
{
  "id": "account",
  "depends-on": ["bank"],
  "extraction": {
    "derivation": {
      "template": "{bank-canonical} Checking",
      "required-fields": ["bank-canonical"]
    }
  }
}
```

### 2. Custom Extractors
```json
{
  "id": "bank",
  "extraction": {
    "extractor-fn": "extract-bank-from-pdf-source",
    "extractor-params": ["pdf-source"]
  }
}
```

### 3. Validation Rules
```json
{
  "id": "merchant",
  "validation": {
    "required": true,
    "min-confidence": 0.7,
    "max-length": 100
  }
}
```

### 4. Hot Reload
```clojure
(defn reload-entity-definitions! []
  "Reload entity definitions without restart"
  (load-entity-definitions)
  (println "✅ Entity definitions reloaded"))
```

---

## 📋 Entity Definition Schema

```json
{
  "id": "string (required) - Unique entity type identifier",
  "display-name": "string (required) - Human-readable name",
  "icon": "string (optional) - Emoji or icon",
  "registry-file": "string (required) - Path to registry JSON",
  "registry-key": "string (required) - Top-level key in registry",
  "extraction": {
    "source-field": "string (optional) - Primary field to extract from",
    "fallback-field": "string (optional) - Secondary field if source empty",
    "extractor-fn": "string (optional) - Custom extractor function name",
    "extractor-params": ["array (optional) - Params for extractor"],
    "derivation": {
      "template": "string (optional) - Template with {placeholders}",
      "required-fields": ["array (optional) - Fields needed for derivation"]
    }
  },
  "output-fields": [
    {
      "key": "string (required) - Output field name",
      "from": "string (required) - Source field in entity"
    }
  ],
  "validation": {
    "required": "boolean (optional) - Is this entity required?",
    "min-confidence": "float (optional) - Minimum confidence threshold",
    "max-length": "int (optional) - Max text length"
  },
  "enabled": "boolean (required) - Is this entity active?",
  "priority": "int (required) - Resolution order (lower = first)"
}
```

---

## 🎉 Conclusion

**Current System:** Adding entity = 20 min + code changes
**Data-Driven System:** Adding entity = 2 min + zero code

**Trade-off:**
- Initial implementation: ~5 hours
- Payoff: After 2nd new entity (saves 18 min × N entities)

**When it pays off:**
- If you plan to add 2+ more entities → Do it NOW
- If staying with 4 entities forever → Keep current

**Recommendation:** **Implement data-driven system** because:
1. ✅ You said you want to avoid code changes
2. ✅ Financial systems evolve (more entities likely)
3. ✅ 5 hours investment pays off quickly
4. ✅ More maintainable long-term
5. ✅ Can add entities in production without deployment

---

## 🚦 Decision Time

**Option A:** Keep current system (fast now, slow later)
- Pro: No work needed
- Con: Every entity = 20 min + code

**Option B:** Implement data-driven system (slow now, fast forever)
- Pro: Future entities take 2 minutes
- Con: 5 hours upfront investment

**Which do you prefer?**

---

**Created:** 2025-11-12
**Status:** ✅ Design Complete - Ready to Implement
