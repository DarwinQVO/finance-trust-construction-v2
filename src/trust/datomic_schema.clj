(ns trust.datomic-schema
  "Datomic schema definitions for trust construction.

  Rich Hickey's philosophy:
  - Schema as data
  - Attributes are reusable across entities
  - No rigid tables, just facts (Entity-Attribute-Value-Time)
  - Time-travel built-in

  Datomic advantages over collections:
  1. Immutable by default - Never overwrites
  2. Time-travel queries - (d/as-of db t)
  3. Auditability - See all changes forever
  4. Datalog - Powerful queries
  5. ACID transactions - Coordinated updates"
  (:require [datomic.api :as d]))

;; ============================================================================
;; CORE ATTRIBUTES (reusable across domains)
;; ============================================================================

(def identity-attributes
  "Attributes for identity management (Atom/Ref equivalent in Datomic)."
  [;; Unique identifier
   {:db/ident :entity/id
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Unique keyword identifier for entity"}

   ;; Canonical name
   {:db/ident :entity/canonical-name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Canonical display name"}

   ;; Aliases (many)
   {:db/ident :entity/alias
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many
    :db/doc "Alternative names/aliases for normalization"}

   ;; Metadata
   {:db/ident :entity/metadata
    :db/valueType :db.type/string  ; Store as EDN string
    :db/cardinality :db.cardinality/one
    :db/doc "Additional metadata as EDN"}])

(def temporal-attributes
  "Attributes for temporal model (4 time dimensions)."
  [;; Business time - when event occurred in real world
   {:db/ident :temporal/business-time
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/doc "When the event occurred in the real world"}

   ;; Valid-from - when data becomes valid
   {:db/ident :temporal/valid-from
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/doc "When this data becomes valid"}

   ;; Valid-until - when data stops being valid
   {:db/ident :temporal/valid-until
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/doc "When this data stops being valid (nil = forever)"}

   ;; Decision time - when decision was made
   {:db/ident :temporal/decision-time
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/doc "When the decision about this data was made"}])

(def event-attributes
  "Attributes for event sourcing."
  [;; Event type
   {:db/ident :event/type
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/doc "Type of event (e.g., :transaction-imported)"}

   ;; Event data (payload)
   {:db/ident :event/data
    :db/valueType :db.type/string  ; Store as EDN
    :db/cardinality :db.cardinality/one
    :db/doc "Event data payload as EDN string"}

   ;; Event metadata
   {:db/ident :event/metadata
    :db/valueType :db.type/string  ; Store as EDN
    :db/cardinality :db.cardinality/one
    :db/doc "Event metadata (user, source, etc.) as EDN"}])

;; ============================================================================
;; FINANCE DOMAIN ATTRIBUTES
;; ============================================================================

(def transaction-attributes
  "Attributes for financial transactions."
  [;; Transaction ID (unique)
   {:db/ident :transaction/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Unique transaction identifier"}

   ;; Date
   {:db/ident :transaction/date
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/doc "Transaction date"}

   ;; Description
   {:db/ident :transaction/description
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/fulltext true
    :db/doc "Transaction description"}

   ;; Amount
   {:db/ident :transaction/amount
    :db/valueType :db.type/double
    :db/cardinality :db.cardinality/one
    :db/doc "Transaction amount (always positive, type determines direction)"}

   ;; Currency
   {:db/ident :transaction/currency
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Currency code (USD, EUR, etc.)"}

   ;; Type (income, expense, transfer)
   {:db/ident :transaction/type
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index true
    :db/doc "Transaction type: :income :expense :transfer"}

   ;; References (using refs to other entities)
   {:db/ident :transaction/bank
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Reference to bank entity"}

   {:db/ident :transaction/merchant
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Reference to merchant entity"}

   {:db/ident :transaction/category
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Reference to category entity"}

   ;; Classification
   {:db/ident :transaction/confidence
    :db/valueType :db.type/double
    :db/cardinality :db.cardinality/one
    :db/doc "Classification confidence (0.0-1.0)"}

   ;; Provenance
   {:db/ident :transaction/source-file
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Source file name"}

   {:db/ident :transaction/source-line
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Line number in source file"}

   ;; Multi-currency (for Wise)
   {:db/ident :transaction/original-amount
    :db/valueType :db.type/double
    :db/cardinality :db.cardinality/one
    :db/doc "Original amount before conversion"}

   {:db/ident :transaction/original-currency
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Original currency before conversion"}

   {:db/ident :transaction/exchange-rate
    :db/valueType :db.type/double
    :db/cardinality :db.cardinality/one
    :db/doc "Exchange rate used for conversion"}])

(def bank-attributes
  "Attributes for bank entities."
  [;; Bank type
   {:db/ident :bank/type
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Bank type: :bank :credit-card :payment-processor"}

   ;; Country
   {:db/ident :bank/country
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Country where bank operates"}])

(def merchant-attributes
  "Attributes for merchant entities."
  [;; Merchant category
   {:db/ident :merchant/category
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Reference to category entity"}])

(def category-attributes
  "Attributes for category entities."
  [;; Category type
   {:db/ident :category/type
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Category type: :expense :income :transfer :unknown"}

   ;; Color (for UI)
   {:db/ident :category/color
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Hex color code for UI display"}])

;; ============================================================================
;; COMPLETE SCHEMA
;; ============================================================================

(def complete-schema
  "Complete Datomic schema for trust construction system."
  (concat identity-attributes
          temporal-attributes
          event-attributes
          transaction-attributes
          bank-attributes
          merchant-attributes
          category-attributes))

;; ============================================================================
;; SCHEMA INSTALLATION
;; ============================================================================

(defn install-schema!
  "Install schema into Datomic connection.

  Example:
    (def conn (d/connect \"datomic:mem://finance\"))
    (install-schema! conn)"
  [conn]
  @(d/transact conn complete-schema))

;; ============================================================================
;; EXAMPLE USAGE
;; ============================================================================

(comment
  (require '[datomic.api :as d])

  ;; Create in-memory database
  (d/create-database "datomic:mem://finance")
  (def conn (d/connect "datomic:mem://finance"))

  ;; Install schema
  (install-schema! conn)

  ;; Add a bank
  @(d/transact conn
     [{:db/id (d/tempid :db.part/user)
       :entity/id :bofa
       :entity/canonical-name "Bank of America"
       :entity/alias ["BofA" "BoA" "Bank of America"]
       :bank/type :bank
       :bank/country "USA"}])

  ;; Query banks
  (d/q '[:find ?name ?country
         :where
         [?e :entity/id :bofa]
         [?e :entity/canonical-name ?name]
         [?e :bank/country ?country]]
       (d/db conn))

  ;; Add a transaction
  (let [bank-id (d/q '[:find ?e .
                       :where [?e :entity/id :bofa]]
                     (d/db conn))]
    @(d/transact conn
       [{:db/id (d/tempid :db.part/user)
         :transaction/id "tx-001"
         :transaction/date #inst "2024-03-20"
         :transaction/description "STARBUCKS #1234"
         :transaction/amount 45.99
         :transaction/currency "USD"
         :transaction/type :expense
         :transaction/bank bank-id
         :transaction/confidence 0.95
         :transaction/source-file "bofa_march.csv"
         :transaction/source-line 23
         :temporal/business-time #inst "2024-03-20T14:30:00Z"}]))

  ;; Time-travel: See database as it was at a specific time
  (def past-db (d/as-of (d/db conn) #inst "2024-03-20"))

  ;; Query what we knew then
  (d/q '[:find ?desc ?amount
         :where
         [?tx :transaction/description ?desc]
         [?tx :transaction/amount ?amount]]
       past-db)
  )
