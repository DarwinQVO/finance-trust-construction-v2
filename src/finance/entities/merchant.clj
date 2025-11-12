(ns finance.entities.merchant
  "Merchant as 'thing' - entity with identity and properties"
  (:require [clojure.spec.alpha :as s]))

;; ============================================================================
;; Merchant Entity Schema (Thing, not String)
;; ============================================================================

;; Identity (stable, unique, immutable)
(s/def ::entity-id uuid?)
(s/def ::entity-type #{:merchant :person :business :government-agency :tax-authority})

;; Core Properties
(s/def ::canonical-name string?)
(s/def ::rfc (s/nilable string?))  ;; Mexican RFC
(s/def ::category keyword?)        ;; :taxes, :restaurants, :shopping, etc.

;; Relationships (other entities)
(s/def ::parent-org (s/nilable uuid?))  ;; Parent organization (e.g., SAT → gob-mexico)
(s/def ::subsidiaries (s/coll-of uuid?))  ;; Child entities

;; Metadata
(s/def ::confidence (s/double-in :min 0.0 :max 1.0))
(s/def ::source #{:manual-classification :automatic-match :ml-prediction})
(s/def ::classified-by string?)
(s/def ::created-at inst?)
(s/def ::updated-at inst?)
(s/def ::version pos-int?)

;; Variations (how this entity appears in text)
(s/def ::variation-text string?)
(s/def ::variation-source #{:pdf-extraction :csv-import :user-input})
(s/def ::variation-confidence (s/double-in :min 0.0 :max 1.0))
(s/def ::variation (s/keys :req-un [::variation-text ::variation-source ::variation-confidence]))
(s/def ::variations (s/coll-of ::variation))

;; Complete Entity Spec
(s/def ::merchant-entity
  (s/keys :req-un [::entity-id ::entity-type ::canonical-name ::category
                   ::confidence ::source ::classified-by ::created-at ::version]
          :opt-un [::rfc ::parent-org ::subsidiaries ::variations ::updated-at]))

;; ============================================================================
;; Entity Constructor
;; ============================================================================

(defn create-merchant-entity
  "Creates a new merchant entity (thing) from classification"
  [{:keys [canonical-name rfc category entity-type classified-by variations]
    :or {entity-type :merchant
         variations []}}]
  {:pre [(string? canonical-name)
         (keyword? category)
         (string? classified-by)]}
  {:entity-id (java.util.UUID/randomUUID)
   :entity-type entity-type
   :canonical-name canonical-name
   :rfc rfc
   :category category
   :confidence 1.0  ;; Manual classification = 100% confident
   :source :manual-classification
   :classified-by classified-by
   :created-at (java.time.Instant/now)
   :version 1
   :variations (mapv (fn [var-text]
                       {:variation-text var-text
                        :variation-source :user-input
                        :variation-confidence 1.0})
                     variations)
   :parent-org nil
   :subsidiaries []})

;; ============================================================================
;; Entity Operations
;; ============================================================================

(defn add-variation
  "Adds new variation to entity (returns updated entity)"
  [entity variation-text source confidence]
  {:pre [(s/valid? ::merchant-entity entity)
         (string? variation-text)
         (keyword? source)
         (number? confidence)]}
  (let [new-variation {:variation-text variation-text
                       :variation-source source
                       :variation-confidence confidence}]
    (-> entity
        (update :variations conj new-variation)
        (assoc :updated-at (java.time.Instant/now))
        (update :version inc))))

(defn update-properties
  "Updates entity properties (returns new version)"
  [entity updates]
  {:pre [(s/valid? ::merchant-entity entity)
         (map? updates)]}
  (-> entity
      (merge updates)
      (assoc :updated-at (java.time.Instant/now))
      (update :version inc)))

(defn matches-variation?
  "Returns true if text matches any variation (case-insensitive)"
  [entity search-text]
  {:pre [(s/valid? ::merchant-entity entity)
         (string? search-text)]}
  (let [normalized-search (clojure.string/upper-case (clojure.string/trim search-text))]
    (some (fn [variation]
            (= (clojure.string/upper-case (:variation-text variation))
               normalized-search))
          (:variations entity))))

(defn get-best-matching-variation
  "Returns best matching variation (highest confidence) or nil"
  [entity search-text]
  {:pre [(s/valid? ::merchant-entity entity)
         (string? search-text)]}
  (when (matches-variation? entity search-text)
    (->> (:variations entity)
         (filter #(= (clojure.string/upper-case (:variation-text %))
                     (clojure.string/upper-case (clojure.string/trim search-text))))
         (sort-by :variation-confidence >)
         first)))

;; ============================================================================
;; Serialization (Thing → JSON-compatible map)
;; ============================================================================

(defn entity->json-map
  "Converts entity to JSON-compatible map (for storage)"
  [entity]
  {:pre [(s/valid? ::merchant-entity entity)]}
  (-> entity
      (update :entity-id str)
      (update :parent-org #(when % (str %)))
      (update :subsidiaries #(mapv str %))
      (update :created-at str)
      (update :updated-at #(when % (str %)))))

(defn json-map->entity
  "Converts JSON map back to entity (from storage)"
  [json-map]
  (-> json-map
      (update :entity-id #(java.util.UUID/fromString %))
      (update :parent-org #(when % (java.util.UUID/fromString %)))
      (update :subsidiaries (fn [ids] (mapv #(java.util.UUID/fromString %) ids)))
      (update :created-at #(java.time.Instant/parse %))
      (update :updated-at #(when % (java.time.Instant/parse %)))))

;; ============================================================================
;; Example Entities
;; ============================================================================

(comment
  ;; SAT as structured entity
  (def sat-entity
    (create-merchant-entity
     {:canonical-name "Servicio de Administración Tributaria"
      :rfc "SAT8410245V8"
      :category :taxes
      :entity-type :tax-authority
      :classified-by "human"
      :variations ["SAT8410245V8" "COBRANZA SAT" "SAT - IMPUESTOS"]}))

  ;; Atlas Seguros as structured entity
  (def atlas-entity
    (create-merchant-entity
     {:canonical-name "Atlas Seguros"
      :rfc "CNM980114PI2"
      :category :insurance
      :entity-type :business
      :classified-by "human"
      :variations ["CNM980114PI2" "COBRANZA ATLAS" "ATLAS SEGUROS"]}))

  ;; FARM PARIS as structured entity
  (def farm-paris-entity
    (create-merchant-entity
     {:canonical-name "Farm Paris Yaxchilan"
      :rfc "PIF880519GH0"
      :category :restaurants
      :entity-type :merchant
      :classified-by "human"
      :variations ["FARM PARIS YAXCHILAN" "PIF880519GH0" "FARM PARIS"]}))

  ;; Add new variation to SAT
  (def sat-updated
    (add-variation sat-entity
                   "PAGO SAT MENSUAL"
                   :pdf-extraction
                   0.95))

  ;; Match variation
  (matches-variation? sat-entity "SAT8410245V8")  ;; => true
  (matches-variation? sat-entity "GOOGLE")        ;; => false

  ;; Get best match
  (get-best-matching-variation sat-entity "SAT8410245V8")
  ;; => {:variation-text "SAT8410245V8"
  ;;     :variation-source :user-input
  ;;     :variation-confidence 1.0}
)
