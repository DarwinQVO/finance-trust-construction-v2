(ns finance.entity-engine
  "Generic entity resolution engine - driven by JSON configuration

   This engine allows adding new entity types WITHOUT modifying code.
   To add a new entity: just add JSON config + registry file.

   Philosophy: Configuration > Code"
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [finance.entity-registry :as registry]))

;; ============================================================================
;; Configuration Loading
;; ============================================================================

(def entity-definitions
  "Atom holding all entity type definitions loaded from config"
  (atom nil))

(defn load-entity-definitions!
  "Load entity definitions from JSON config file"
  []
  (try
    (let [config-file "resources/config/entity_definitions.json"
          file (io/file config-file)]
      (if (.exists file)
        (let [config (json/read-str (slurp file) :key-fn keyword)
              definitions (:entity-types config)]
          (reset! entity-definitions definitions)
          (println "✅ Loaded" (count definitions) "entity type definitions")
          definitions)
        (do
          (println "⚠️  Entity definitions file not found:" config-file)
          (reset! entity-definitions [])
          [])))
    (catch Exception e
      (println "❌ Error loading entity definitions:" (.getMessage e))
      (reset! entity-definitions [])
      [])))

;; Load at namespace initialization
(load-entity-definitions!)

(defn reload-entity-definitions!
  "Reload entity definitions without restart (hot reload)"
  []
  (println "🔄 Reloading entity definitions...")
  (load-entity-definitions!))

(defn get-entity-definition
  "Get entity definition by ID"
  [entity-id]
  (first (filter #(= (keyword entity-id) (keyword (:id %))) @entity-definitions)))

(defn list-entity-types
  "List all configured entity types"
  []
  (map :id @entity-definitions))

(defn list-enabled-entities
  "List only enabled entity types, sorted by priority"
  []
  (->> @entity-definitions
       (filter :enabled)
       (sort-by :priority)))

;; ============================================================================
;; String Matching Utilities
;; ============================================================================

(defn- normalize-text
  "Normalize text for comparison (lowercase, trim)"
  [text]
  (when text
    (-> text str str/trim str/lower-case)))

(defn- exact-match?
  "Check if two strings match exactly (case-insensitive)"
  [canonical search-text]
  (when (and canonical search-text)
    (= (normalize-text canonical) (normalize-text search-text))))

(defn- substring-match?
  "Check if search-text contains the pattern (case-insensitive)"
  [pattern search-text]
  (when (and pattern search-text)
    (str/includes? (normalize-text search-text) (normalize-text pattern))))

(defn- calculate-match-type
  "Determine match type and confidence"
  [canonical search-text variations]
  (cond
    (exact-match? canonical search-text)
    {:type :exact-canonical :confidence 1.0}

    (some #(exact-match? % search-text) variations)
    {:type :exact-variation :confidence 0.95}

    (some #(substring-match? % search-text) variations)
    {:type :substring-match :confidence 0.70}

    :else
    {:type :no-match :confidence 0.0}))

;; ============================================================================
;; Generic Registry Operations
;; ============================================================================

(defn load-registry
  "Load registry from JSON file - works for ANY entity type"
  [registry-file registry-key]
  (try
    (let [file (io/file registry-file)]
      (if (.exists file)
        (let [data (json/read-str (slurp file) :key-fn keyword)]
          (get data (keyword registry-key) {}))
        (do
          (println "⚠️  Registry file not found:" registry-file)
          {})))
    (catch Exception e
      (println "❌ Error loading registry:" registry-file (.getMessage e))
      {})))

(defn find-by-variation
  "Generic fuzzy matching - works for ALL entities"
  [registry search-text]
  (when (and registry search-text)
    (some
     (fn [[entity-id entity-data]]
       (let [variations (get entity-data :variations [])
             canonical (get entity-data :canonical-name)
             match-info (calculate-match-type canonical search-text variations)]
         (when (pos? (:confidence match-info))
           (assoc entity-data
                  :entity-id (name entity-id)
                  :match-type (:type match-info)
                  :confidence (:confidence match-info)))))
     registry)))

(defn lookup-entity
  "Generic entity lookup - works for ANY entity type based on definition"
  [entity-def search-text]
  (when (and entity-def search-text)
    (let [registry (load-registry (:registry-file entity-def)
                                  (:registry-key entity-def))]
      (find-by-variation registry search-text))))

;; ============================================================================
;; Text Extraction (from transaction based on entity config)
;; ============================================================================

(defn- get-field-value
  "Get field value from transaction (handles keyword or string keys)"
  [transaction field-name]
  (when field-name
    (or (get transaction (keyword field-name))
        (get transaction field-name))))

(defn- call-extractor-fn
  "Call custom extractor function if specified"
  [fn-name transaction params]
  (try
    (case fn-name
      "extract-bank-from-pdf-source"
      (let [pdf-source (get-field-value transaction (first params))]
        (when pdf-source
          (-> pdf-source
              (str/split #"_")
              first
              str/lower-case)))

      ;; Add more custom extractors here as needed
      nil)
    (catch Exception e
      (println "⚠️  Error calling extractor" fn-name ":" (.getMessage e))
      nil)))

(defn- derive-text
  "Derive text from template (e.g. '{bank-canonical} Checking')"
  [transaction derivation]
  (when derivation
    (let [template (:template derivation)
          required-fields (:required-fields derivation)
          ;; Check all required fields are present
          all-present? (every? #(get-field-value transaction %) required-fields)]
      (when all-present?
        ;; Replace placeholders with actual values
        (reduce (fn [text field]
                  (str/replace text
                              (str "{" field "}")
                              (str (get-field-value transaction field))))
                template
                required-fields)))))

(defn extract-text-for-entity
  "Extract search text from transaction based on entity configuration"
  [transaction entity-def]
  (let [extraction (get entity-def :extraction {})

        ;; Try 1: Source field
        text (get-field-value transaction (:source-field extraction))

        ;; Try 2: Extractor function if specified
        text (or text
                 (when-let [fn-name (:extractor-fn extraction)]
                   (call-extractor-fn fn-name transaction (:extractor-params extraction))))

        ;; Try 3: Fallback field
        text (or text (get-field-value transaction (:fallback-field extraction)))

        ;; Try 4: Derivation if specified
        text (or text (derive-text transaction (:derivation extraction)))]

    text))

;; ============================================================================
;; Generic Entity Resolution
;; ============================================================================

(defn resolve-entity-generic
  "Resolve ANY entity type based on its definition

   Takes:
   - transaction: The transaction map
   - entity-def: Entity definition from config

   Returns:
   - transaction with entity fields merged"
  [transaction entity-def]
  (let [entity-id (keyword (:id entity-def))
        entity-id-str (name entity-id)

        ;; Extract search text
        search-text (extract-text-for-entity transaction entity-def)

        ;; Lookup entity
        entity (when search-text (lookup-entity entity-def search-text))

        ;; Build output keys dynamically
        entity-key (keyword (str entity-id-str "-entity"))
        resolved-key (keyword (str entity-id-str "-resolved?"))
        canonical-key (keyword (str entity-id-str "-canonical"))
        text-key (keyword (str entity-id-str "-text"))]

    (if entity
      ;; Entity found - merge resolved data
      (let [base-merge {entity-key entity
                       resolved-key true
                       canonical-key (:canonical-name entity)}

            ;; Add configured output fields
            output-fields (reduce (fn [acc field-def]
                                   (let [key (keyword (:key field-def))
                                         from (keyword (:from field-def))
                                         value (get entity from)]
                                     (assoc acc key value)))
                                 {}
                                 (:output-fields entity-def []))]

        (merge transaction base-merge output-fields))

      ;; Entity not found
      (merge transaction
             {entity-key nil
              resolved-key false
              text-key search-text}))))

;; ============================================================================
;; Main Resolution Pipeline
;; ============================================================================

(defn resolve-all-entities
  "Resolve ALL configured entity types in priority order

   Takes:
   - transaction: The transaction map

   Returns:
   - transaction with all entity fields resolved

   Uses entity_definitions.json to determine:
   - Which entities to resolve
   - In what order (priority)
   - Which fields to extract
   - Which fields to output"
  [transaction]
  (let [enabled-entities (list-enabled-entities)]
    (if (empty? enabled-entities)
      (do
        (println "⚠️  No entity definitions loaded!")
        transaction)
      (reduce (fn [tx entity-def]
                (resolve-entity-generic tx entity-def))
              transaction
              enabled-entities))))

(defn resolve-batch
  "Resolve entities for batch of transactions"
  [transactions]
  (map resolve-all-entities transactions))

;; ============================================================================
;; Validation & Stats
;; ============================================================================

(defn validate-entity-definition
  "Validate that entity definition has required fields"
  [entity-def]
  (let [required-fields [:id :registry-file :registry-key :extraction :enabled :priority]
        missing-fields (filter #(nil? (get entity-def %)) required-fields)]
    (if (empty? missing-fields)
      {:valid? true}
      {:valid? false
       :missing-fields missing-fields
       :message (str "Entity definition missing required fields: " missing-fields)})))

(defn get-resolution-stats
  "Get statistics about entity resolution"
  [transactions]
  (let [entity-types (list-entity-types)]
    (into {}
          (map (fn [entity-id]
                 (let [resolved-key (keyword (str (name entity-id) "-resolved?"))
                       resolved-count (count (filter #(get % resolved-key) transactions))
                       total-count (count transactions)]
                   [(keyword entity-id)
                    {:resolved resolved-count
                     :total total-count
                     :percentage (if (pos? total-count)
                                  (float (/ resolved-count total-count))
                                  0.0)}]))
               entity-types))))

;; ============================================================================
;; Debugging & Introspection
;; ============================================================================

(defn debug-entity-resolution
  "Debug entity resolution for a single transaction"
  [transaction]
  (println "\n🔍 Entity Resolution Debug")
  (println "=====================================")
  (doseq [entity-def (list-enabled-entities)]
    (let [entity-id (:id entity-def)
          search-text (extract-text-for-entity transaction entity-def)
          entity (when search-text (lookup-entity entity-def search-text))]
      (println "\n" (:icon entity-def "📌") (:display-name entity-def))
      (println "  ID:" entity-id)
      (println "  Search text:" (or search-text "<none>"))
      (println "  Found:" (if entity "✅" "❌"))
      (when entity
        (println "  Canonical:" (:canonical-name entity))
        (println "  Confidence:" (:confidence entity))
        (println "  Match type:" (:match-type entity)))))
  (println "=====================================\n"))

(comment
  ;; Usage examples:

  ;; Reload definitions without restart
  (reload-entity-definitions!)

  ;; List all entity types
  (list-entity-types)
  ;; => ("merchant" "bank" "account" "category")

  ;; Get specific definition
  (get-entity-definition "bank")

  ;; Resolve single transaction
  (def test-tx {:pdf-source "scotiabank_edo_2025-07.pdf"
                :clean-merchant "GOOGLE"
                :merchant-category "utilities"})
  (resolve-all-entities test-tx)

  ;; Debug resolution
  (debug-entity-resolution test-tx)

  ;; Get stats
  (get-resolution-stats [test-tx test-tx test-tx])
  )
