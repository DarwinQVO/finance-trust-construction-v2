(ns finance.merchant-extraction.protocols
  "Protocol definitions for merchant extraction pipeline")

;; ============================================================================
;; Stage 1: Transaction Type Detection
;; ============================================================================

(defprotocol TransactionTypeDetector
  "Detects transaction type and determines if merchant extraction is needed"
  (detect-type [this raw-tx rules]
    "Returns map with :type, :direction, :merchant?, :confidence"))

;; ============================================================================
;; Stage 2: Counterparty Detection
;; ============================================================================

(defprotocol CounterpartyDetector
  "Identifies payment aggregators/marketplaces (CLIP, ZETTLE, etc.)"
  (detect-counterparty [this typed-tx rules]
    "Returns map with :counterparty-info or nil"))

;; ============================================================================
;; Stage 3: NER Extraction
;; ============================================================================

(defprotocol NERExtractor
  "Extracts clean merchant name by removing noise"
  (extract-merchant [this counterparty-tx rules]
    "Returns map with :clean-merchant, :removed-noise, :kept-context"))

;; ============================================================================
;; Stage 4: Entity Resolution (Registry Lookup)
;; ============================================================================

(defprotocol EntityResolver
  "Resolves merchant to canonical entity via registry lookup"
  (resolve-entity [this clean-tx]
    "Returns map with :canonical-merchant, :merchant-category, :entity-type, :entity-resolved?"))

;; ============================================================================
;; Entity Store
;; ============================================================================

(defprotocol EntityStore
  "Entity storage and retrieval"
  (get-entity [this entity-id]
    "Returns entity or nil")

  (find-by-alias [this alias]
    "Returns entities matching alias")

  (find-by-rfc [this rfc]
    "Returns entity with matching RFC")

  (create-entity [this entity]
    "Creates new entity, returns entity-id")

  (update-entity [this entity-id updates]
    "Updates entity (creates new version)")

  (merge-entities [this source-id target-id reason]
    "Marks source as merged into target"))
