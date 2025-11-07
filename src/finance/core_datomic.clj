(ns finance.core-datomic
  "Main API for finance domain with Datomic backend.

  ⚡ PHASE 4: PROCESS/PERCEPTION SEPARATION (Rich Hickey Aligned) ⚡

  This namespace now separates:
  - PROCESS (writes) - All mutations go to finance.process
  - PERCEPTION (reads) - All queries go to finance.perception

  Rich Hickey Principle:
  'Information systems conflate process and perception. Database updates are
   process. Queries are perception. Keep them separate.'

  This is the PRODUCTION version using Datomic for:
  - Persistent storage (survives restarts)
  - Time-travel queries (d/as-of)
  - Automatic audit trail
  - ACID transactions

  High-level operations:
  - PROCESS: Importing, classifying, correcting (finance.process)
  - PERCEPTION: Querying, aggregating, filtering (finance.perception)"
  (:require [datomic.api :as d]
            [trust.datomic-schema :as schema]
            [trust.events-datomic :as events]
            [trust.identity-datomic :as identity]
            [finance.entities :as entities]
            [finance.parsers.bofa :as bofa]
            [finance.parsers.apple :as apple]
            [finance.parsers.stripe :as stripe]
            [finance.parsers.wise :as wise]
            [finance.classification :as classify]
            [finance.reconciliation :as reconcile]
            [finance.process :as process]
            [finance.perception :as perception]
            [clojure.java.io :as io]))

;; ============================================================================
;; DATABASE CONNECTION
;; ============================================================================

;; Global connection atom (only stores connection, data is in Datomic).
(defonce ^:private conn-atom (atom nil))

(defn init!
  "Initialize the finance system with Datomic backend.

  Rich Hickey Principle: Context at Edges (Bug #13 fix).
  - URI from ENV var (caller decides), not hardcoded
  - Storage choice = deployment concern, not domain logic
  - Zero-arity form reads from DATOMIC_URI env var

  Args:
    uri - Datomic URI (optional, reads from DATOMIC_URI env var if not provided)

  Returns the connection.

  Example:
    # In-memory (testing - NOT RECOMMENDED for production):
    (init!)  ; Falls back to memory if DATOMIC_URI not set

    # Persistent (production - RECOMMENDED):
    # Set env: export DATOMIC_URI='datomic:dev://localhost:4334/finance'
    (init!)  ; Reads from env

    # Explicit URI:
    (init! \"datomic:dev://localhost:4334/finance\")"
  ([]
   ;; Config-driven default (Bug #13 fix)
   ;; Priority: ENV var > Default (memory for backwards compat)
   (let [default-uri (or (System/getenv "DATOMIC_URI")
                        "datomic:mem://finance")]
     (when (= default-uri "datomic:mem://finance")
       (println "⚠️  WARNING: Using in-memory database - facts will be lost on restart!"))
     (init! default-uri)))
  ([uri]
   (d/create-database uri)
   (let [conn (d/connect uri)]
     ;; Install schema
     (schema/install-schema! conn)

     ;; Store connection
     (reset! conn-atom conn)

     ;; Register default entities (TODO: Implement register-defaults! or call individual functions)
     ; (entities/register-defaults! conn)

     ;; Log initialization event
     (events/append-event! conn :system-initialized
       {:timestamp (java.util.Date.)
        :uri uri
        :version "2.0-datomic"})

     conn)))

(defn get-conn
  "Get current Datomic connection.

  Throws if not initialized."
  []
  (or @conn-atom
      (throw (ex-info "System not initialized. Call (init!) first." {}))))

(defn get-db
  "Get current database value.

  Example:
    (get-db)  ; Current state
    (d/as-of (get-db) #inst \"2024-03-20\")  ; Historical state"
  []
  (d/db (get-conn)))

;; ============================================================================
;; DEFAULT ENTITIES
;; ============================================================================

(defn register-defaults!
  "Register default banks, merchants, and categories.

  Example:
    (register-defaults! conn)"
  [conn]
  ;; Register banks
  (identity/register-batch! conn
    [[:bofa {:entity/canonical-name "Bank of America"
             :entity/alias ["BofA" "BoA" "Bank of America" "BANK OF AMERICA"]
             :bank/type :bank
             :bank/country "USA"}]

     [:apple-card {:entity/canonical-name "Apple Card"
                   :entity/alias ["Apple Card" "APPLE CARD" "Apple" "APPLE"]
                   :bank/type :credit-card
                   :bank/country "USA"}]

     [:stripe {:entity/canonical-name "Stripe"
               :entity/alias ["Stripe" "STRIPE"]
               :bank/type :payment-processor
               :bank/country "USA"}]

     [:wise {:entity/canonical-name "Wise"
             :entity/alias ["Wise" "TransferWise" "WISE"]
             :bank/type :payment-processor
             :bank/country "UK"}]

     [:scotiabank {:entity/canonical-name "Scotiabank"
                   :entity/alias ["Scotiabank" "SCOTIABANK" "Scotia"]
                   :bank/type :bank
                   :bank/country "Canada"}]])

  ;; Register categories
  (identity/register-batch! conn
    [[:restaurants {:entity/canonical-name "Restaurants"
                    :category/type :expense
                    :category/color "#E74C3C"}]

     [:groceries {:entity/canonical-name "Groceries"
                  :category/type :expense
                  :category/color "#3498DB"}]

     [:shopping {:entity/canonical-name "Shopping"
                 :category/type :expense
                 :category/color "#9B59B6"}]

     [:transportation {:entity/canonical-name "Transportation"
                       :category/type :expense
                       :category/color "#F39C12"}]

     [:entertainment {:entity/canonical-name "Entertainment"
                      :category/type :expense
                      :category/color "#1ABC9C"}]

     [:salary {:entity/canonical-name "Salary"
               :category/type :income
               :category/color "#27AE60"}]

     [:freelance {:entity/canonical-name "Freelance"
                  :category/type :income
                  :category/color "#2ECC71"}]

     [:payment {:entity/canonical-name "Payment"
                :category/type :transfer
                :category/color "#7F8C8D"}]

     [:transfer {:entity/canonical-name "Transfer"
                 :category/type :transfer
                 :category/color "#BDC3C7"}]

     [:uncategorized {:entity/canonical-name "Uncategorized"
                      :category/type :unknown
                      :category/color "#95A5A6"}]]))

;; ============================================================================
;; PARSERS
;; ============================================================================

(defn detect-source
  "Detect the source type from filename.

  Returns one of: :bofa :apple-card :stripe :wise :unknown"
  [filename]
  (let [lower-name (.toLowerCase filename)]
    (cond
      (or (.contains lower-name "bofa")
          (.contains lower-name "bank-of-america")) :bofa
      (or (.contains lower-name "apple")
          (.contains lower-name "applecard")) :apple-card
      (.contains lower-name "stripe") :stripe
      (or (.contains lower-name "wise")
          (.contains lower-name "transferwise")) :wise
      :else :unknown)))

(defn parse-file
  "Parse a file using the appropriate parser."
  ([file-path]
   (let [filename (.getName (io/file file-path))
         source-type (detect-source filename)]
     (parse-file file-path source-type)))
  ([file-path source-type]
   (case source-type
     :bofa (bofa/parse file-path)
     :apple-card (apple/parse file-path)
     :stripe (stripe/parse file-path)
     :wise (wise/parse file-path)
     (throw (ex-info "Unknown source type"
                     {:source-type source-type
                      :file-path file-path})))))

;; ============================================================================
;; IMPORT PIPELINE
;; ============================================================================

(defn import-transaction!
  "Import a single transaction into Datomic.

  Returns transaction ID or nil if duplicate."
  [conn tx]
  (let [db (d/db conn)

        ;; Generate transaction ID
        tx-id (or (:id tx)
                  (str "tx-" (java.util.UUID/randomUUID)))

        ;; Check if already exists
        exists? (d/q '[:find ?e .
                       :in $ ?id
                       :where [?e :transaction/id ?id]]
                     db
                     tx-id)]

    (when-not exists?
      ;; Get entity IDs for references
      (let [bank-id (:bank tx)
            bank-eid (when bank-id
                      (d/q '[:find ?e .
                             :in $ ?id
                             :where [?e :entity/id ?id]]
                           db
                           bank-id))

            category-id (:category-id tx)
            category-eid (when category-id
                          (d/q '[:find ?e .
                                 :in $ ?id
                                 :where [?e :entity/id ?id]]
                               db
                               category-id))

            ;; Build transaction entity
            tx-entity (cond-> {:db/id (d/tempid :db.part/user)
                               :transaction/id tx-id
                               :transaction/date (:date tx)
                               :transaction/description (:description tx)
                               :transaction/amount (:amount tx)
                               :transaction/currency (or (:currency tx) "USD")
                               :transaction/type (or (:type tx) :expense)
                               :transaction/source-file (or (:source-file tx) "unknown")
                               :transaction/source-line (or (:source-line tx) 0)
                               :temporal/business-time (or (:date tx) (java.util.Date.))}

                        bank-eid
                        (assoc :transaction/bank bank-eid)

                        category-eid
                        (assoc :transaction/category category-eid)

                        (:confidence tx)
                        (assoc :transaction/confidence (:confidence tx))

                        (:original-amount tx)
                        (assoc :transaction/original-amount (:original-amount tx))

                        (:original-currency tx)
                        (assoc :transaction/original-currency (:original-currency tx))

                        (:exchange-rate tx)
                        (assoc :transaction/exchange-rate (:exchange-rate tx)))]

        @(d/transact conn [tx-entity])
        tx-id))))

(defn import-transactions!
  "Import transactions from a file.

  Full pipeline:
  1. Parse file
  2. Classify transactions
  3. Import to Datomic
  4. Log event

  Returns {:imported N :skipped M :classified K}"
  ([file-path]
   (import-transactions! file-path {}))
  ([file-path {:keys [source-type classify?] :or {classify? true}}]
   (let [conn (get-conn)
         source (or source-type (detect-source (.getName (io/file file-path))))

         ;; Parse
         _ (println (format "Parsing %s as %s..." file-path source))
         raw-txs (parse-file file-path source)

         ;; Classify
         classified-txs (if classify?
                          (do
                            (println "Classifying transactions...")
                            (classify/classify-batch raw-txs))
                          raw-txs)

         ;; Import to Datomic
         _ (println "Importing to Datomic...")
         result (reduce
                  (fn [acc tx]
                    (if (import-transaction! conn tx)
                      (update acc :imported inc)
                      (update acc :skipped inc)))
                  {:imported 0 :skipped 0 :classified (if classify? (count classified-txs) 0)}
                  classified-txs)]

     ;; Log event
     (events/append-event! conn :transactions-imported
       {:source source
        :file file-path
        :count (:imported result)
        :skipped (:skipped result)})

     (println (format "✓ Imported: %d / Skipped: %d"
                      (:imported result)
                      (:skipped result)))
     result)))

;; ============================================================================
;; QUERIES (PERCEPTION Layer)
;; ============================================================================
;;
;; ⚡ MIGRATION NOTE: These functions are now DEPRECATED.
;; Use finance.perception namespace instead for all queries.
;;
;; OLD (Conflates process/perception):
;;   (finance.core-datomic/get-all-transactions)
;;
;; NEW (Separated):
;;   (finance.perception/get-all-transactions (finance.core-datomic/get-db))
;;
;; Why? Rich Hickey: "Separate process (writes) from perception (reads)"
;; ============================================================================

(defn get-all-transactions
  "Get all transactions from Datomic.

  DEPRECATED: Use finance.perception/get-all-transactions instead.

  This function is kept for backward compatibility but will be removed.
  The new API separates reads (perception) from writes (process)."
  []
  (perception/get-all-transactions (get-db)))

(defn count-transactions
  "Count total transactions.

  DEPRECATED: Use finance.perception/count-transactions instead."
  []
  (perception/count-transactions (get-db)))

(defn transactions-by-type
  "Get transactions of a specific type.

  DEPRECATED: Use finance.perception/transactions-by-type instead."
  [tx-type]
  (perception/transactions-by-type (get-db) tx-type))

(defn transactions-in-range
  "Get transactions within a date range.

  DEPRECATED: Use finance.perception/transactions-in-range instead."
  [start-date end-date]
  (perception/transactions-in-range (get-db) start-date end-date))

;; ============================================================================
;; STATISTICS (PERCEPTION Layer)
;; ============================================================================

(defn transaction-stats
  "Calculate transaction statistics.

  DEPRECATED: Use finance.perception/transaction-statistics instead.

  Returns {:total N :by-type {...} :total-income X :total-expenses Y}"
  []
  (perception/transaction-statistics (get-db)))

;; ============================================================================
;; TIME-TRAVEL
;; ============================================================================

(defn transactions-as-of
  "Get transactions as they existed at a specific time.

  Example:
    (transactions-as-of #inst \"2024-03-20\")"
  [time]
  (let [db (d/as-of (d/db (get-conn)) time)
        eids (d/q '[:find [?e ...]
                    :where [?e :transaction/id]]
                  db)]
    (map #(d/touch (d/entity db %)) eids)))

;; ============================================================================
;; EXAMPLE USAGE
;; ============================================================================

(comment
  ;; 1. Initialize
  (init!)
  ; => #object[datomic.peer.LocalConnection ...]

  ;; 2. Import transactions
  (import-transactions! "/Users/darwinborges/finance/transactions_ALL_SOURCES.csv")
  ; => {:imported 4877 :skipped 0 :classified 4877}

  ;; 3. Query
  (count-transactions)
  ; => 4877

  (transaction-stats)
  ; => {:total 4877
  ;     :by-type {:expense 3200 :income 1500 ...}
  ;     :total-income 125000.00
  ;     :total-expenses 87000.00
  ;     :net-cashflow 38000.00}

  ;; 4. Filter
  (count (transactions-by-type :expense))
  ; => 3200

  (count (transactions-in-range
           #inst "2024-03-01"
           #inst "2024-03-31"))
  ; => 287

  ;; 5. Time-travel!
  (count (transactions-as-of #inst "2024-03-20"))
  ; => Number of transactions that existed on March 20

  ;; 6. Event log
  (events/count-events (get-db))
  ; => 2 (system-initialized + transactions-imported)

  (events/all-events (get-db))
  ; => All events
  )
