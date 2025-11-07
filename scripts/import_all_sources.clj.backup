(ns scripts.import-all-sources
  "Import script for transactions_ALL_SOURCES.csv (4,877 transactions).

  This CSV has 14 columns:
  - Date
  - Description
  - Amount_Original
  - Amount_Numeric
  - Transaction_Type
  - Category
  - Merchant
  - Currency
  - Account_Name
  - Account_Number
  - Bank
  - Source_File
  - Line_Number
  - Classification_Notes"
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [datomic.api :as d]
            [trust.datomic-schema :as schema]
            [trust.identity-datomic :as identity]
            [trust.events-datomic :as events]
            [finance.entities :as entities])
  (:import [java.text SimpleDateFormat]
           [java.security MessageDigest]))

;; ============================================================================
;; PARSING
;; ============================================================================

(defn parse-date
  "Parse date string (various formats supported).

  Rich Hickey Principle: NEVER FABRICATE DATA (Bug #15 fix).
  - Parse failure = EXPLICIT ERROR
  - NO silent defaults to 'NOW'
  - Preserves temporal causality (can't corrupt time-travel)

  Supported formats:
  - MM/dd/yyyy (e.g., '03/20/2024')
  - yyyy-MM-dd (e.g., '2024-03-20')

  Throws ex-info if date cannot be parsed.

  Why this matters:
  - Silent 'NOW' collapses different transactions to same timestamp
  - Destroys historical analysis and reconciliation
  - Creates false temporal ordering
  - Makes time-travel queries meaningless"
  [date-str]
  (when date-str
    (try
      ;; Try MM/dd/yyyy format first
      (.parse (SimpleDateFormat. "MM/dd/yyyy") date-str)
      (catch Exception e1
        (try
          ;; Try yyyy-MM-dd format
          (.parse (SimpleDateFormat. "yyyy-MM-dd") date-str)
          (catch Exception e2
            ;; FAIL LOUDLY - never fabricate timestamps!
            (throw (ex-info (format "Invalid date format: '%s'. Expected: MM/dd/yyyy or yyyy-MM-dd" date-str)
                            {:input date-str
                             :tried-formats ["MM/dd/yyyy" "yyyy-MM-dd"]
                             :error-1 (.getMessage e1)
                             :error-2 (.getMessage e2)}))))))))

(defn parse-amount
  "Parse amount string to double.

  ALWAYS returns POSITIVE amount (Math/abs).
  Direction is encoded in :transaction/type, not amount polarity.

  Rich Hickey Principle: NEVER FABRICATE DATA (Bug #15 fix).
  - Parse failure = EXPLICIT ERROR
  - NO silent default to 0.0 (ambiguous with valid zero)
  - Preserves distinction between 'zero amount' and 'parse error'

  Examples:
    (parse-amount \"-45.99\")  ; => 45.99 (positive)
    (parse-amount \"$1,234\")  ; => 1234.0
    (parse-amount \"invalid\") ; => ERROR! (was: 0.0 - ambiguous)

  Rationale (Rich Hickey principle):
  - Amount is a magnitude (always >= 0)
  - Type (:income/:expense/:transfer) is the direction semantic
  - Separates 'what' (amount) from 'how' (type)
  - Zero is VALID (authorization holds, fee waivers)
  - Using 0.0 as error sentinel ambiguates data

  Throws ex-info if amount cannot be parsed."
  [amount-str]
  (when amount-str
    (try
      (-> amount-str
          (clojure.string/replace #"[$,]" "")
          (Double/parseDouble)
          Math/abs)  ; Always positive - direction comes from :type
      (catch Exception e
        ;; FAIL LOUDLY - never fabricate amounts!
        (throw (ex-info (format "Invalid amount format: '%s'. Expected numeric value (e.g., '45.99', '$1,234', '-100.00')" amount-str)
                        {:input amount-str
                         :error (.getMessage e)}))))))

(defn normalize-type
  "Normalize transaction type to keyword.

  Rich Hickey Principle: FAIL LOUDLY on unknown types (Bug #12 fix).
  - NEVER silently reclassify (silent default = data corruption!)
  - Unknown type = EXPLICIT ERROR
  - Forces caller to handle bad data explicitly

  Valid types:
  - GASTO → :expense
  - INGRESO → :income
  - PAGO_TARJETA → :transfer
  - TRASPASO → :transfer

  Throws ex-info if type is unknown."
  [type-str]
  (when type-str
    (let [upper (.toUpperCase type-str)]
      (case upper
        "GASTO" :expense
        "INGRESO" :income
        "PAGO_TARJETA" :transfer
        "TRASPASO" :transfer
        ;; FAIL LOUDLY - no silent defaults!
        (throw (ex-info (format "Unknown transaction type: '%s'. Expected: GASTO, INGRESO, PAGO_TARJETA, TRASPASO" type-str)
                        {:type type-str
                         :type-upper upper
                         :valid-types ["GASTO" "INGRESO" "PAGO_TARJETA" "TRASPASO"]}))))))

(defn normalize-bank
  "Normalize bank name to keyword."
  [bank-str]
  (when bank-str
    (let [upper (.toUpperCase bank-str)]
      (cond
        (.contains upper "BOFA") :bofa
        (.contains upper "BANK OF AMERICA") :bofa
        (.contains upper "APPLE") :apple-card
        (.contains upper "STRIPE") :stripe
        (.contains upper "WISE") :wise
        (.contains upper "SCOTIA") :scotiabank
        :else :unknown))))

(defn normalize-category
  "Normalize category name to keyword."
  [category-str]
  (when category-str
    (let [lower (.toLowerCase category-str)]
      (cond
        (.contains lower "restaurant") :restaurants
        (.contains lower "food") :restaurants
        (.contains lower "grocer") :groceries
        (.contains lower "shopping") :shopping
        (.contains lower "transport") :transportation
        (.contains lower "entertainment") :entertainment
        (.contains lower "salary") :salary
        (.contains lower "freelance") :freelance
        (.contains lower "payment") :payment
        (.contains lower "transfer") :transfer
        :else :uncategorized))))

(defn normalize-merchant
  "Normalize merchant name to keyword, PRESERVING INFORMATION.

  Extracts core merchant name from description strings:
  - 'STARBUCKS #123' → :starbucks (known merchant)
  - 'AMAZON PRIME VIDEO' → :amazon (known merchant)
  - 'WHOLE FOODS MARKET' → :whole-foods-market (preserves full name!)
  - 'UBER RIDE 45678, DES: TRIP' → :uber-ride (truncates at delimiter)

  Information Preservation Principle (Rich Hickey):
  - NEVER truncate to first word only
  - Only remove: special delimiters (DES:, #), trailing IDs (5+ digits)
  - Multi-word merchants preserved with hyphens

  Returns :unknown-merchant if cannot extract."
  [merchant-str]
  (when (and merchant-str (not= merchant-str ""))
    (let [upper (.toUpperCase (clojure.string/trim merchant-str))
          ;; Remove special delimiters and everything after
          cleaned (-> upper
                     (clojure.string/split #"\s*,\s*DES:")
                     first
                     (clojure.string/split #"\s*PURCHASE\s*:")
                     first
                     (clojure.string/split #"\s*#")
                     first
                     (clojure.string/split #"\s*ID:")
                     first
                     (clojure.string/trim))
          ;; Remove trailing numbers that look like IDs (5+ digits)
          without-id (clojure.string/replace cleaned #"\s+\d{5,}$" "")
          ;; Convert to keyword-friendly format
          normalized (.toLowerCase (clojure.string/trim without-id))
          ;; Replace spaces with hyphens for multi-word merchants
          keyword-str (clojure.string/replace normalized #"\s+" "-")]
      (cond
        ;; Known merchants (exact match preferred)
        (.contains upper "STARBUCKS") :starbucks
        (.contains upper "AMAZON") :amazon
        (.contains upper "UBER") :uber
        (.contains upper "STRIPE") :stripe
        (.contains upper "APPLE") :apple
        (.contains upper "GOOGLE") :google
        (.contains upper "NETFLIX") :netflix
        (.contains upper "SPOTIFY") :spotify
        ;; Generic: PRESERVE FULL MERCHANT NAME (fix for Bug #10)
        (and keyword-str (> (count keyword-str) 2))
        (keyword keyword-str)
        :else :unknown-merchant))))

(defn compute-transaction-id
  "Compute deterministic transaction ID from stable fields.

  Uses SHA-256 hash of:
  - date (formatted as ISO string)
  - normalized description (uppercase, trimmed)
  - amount (rounded to 2 decimals)
  - source-file
  - line-number

  This ensures:
  - Same transaction imported twice = same ID
  - Deduplication works correctly
  - Identity derived from immutable facts (Rich Hickey principle)

  Example:
    (compute-transaction-id
      #inst \"2024-03-20\"
      \"STARBUCKS #123\"
      45.99
      \"bofa_march.csv\"
      23)
    ; => \"tx-a3b4c5d6e7f8...\""
  [date description amount source-file line-number]
  (let [;; Normalize inputs
        date-str (when date (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") date))
        desc-normalized (-> (str description)
                            .toUpperCase
                            clojure.string/trim)
        amount-rounded (format "%.2f" (double amount))

        ;; Concatenate stable fields
        stable-data (str date-str "|"
                        desc-normalized "|"
                        amount-rounded "|"
                        source-file "|"
                        line-number)

        ;; Compute SHA-256 hash
        digest (MessageDigest/getInstance "SHA-256")
        hash-bytes (.digest digest (.getBytes stable-data "UTF-8"))

        ;; Convert to hex string
        hash-hex (apply str (map #(format "%02x" %) hash-bytes))]

    (str "tx-" (subs hash-hex 0 16))))

(defn parse-csv-row
  "Parse a single CSV row into transaction map.

  CSV columns (0-indexed):
  0: Date
  1: Description
  2: Amount_Original
  3: Amount_Numeric
  4: Transaction_Type
  5: Category
  6: Merchant
  7: Currency
  8: Account_Name
  9: Account_Number
  10: Bank
  11: Source_File
  12: Line_Number
  13: Classification_Notes"
  [row idx]
  (when (>= (count row) 11)  ; At least basic fields
    (let [[date desc amount-orig amount-num tx-type category merchant
           currency account-name account-num bank source-file line-num notes] row
          parsed-date (parse-date date)
          parsed-amount (parse-amount (or amount-num amount-orig "0"))
          parsed-desc (or desc "")
          parsed-source (or source-file "transactions_ALL_SOURCES.csv")
          parsed-line (if line-num
                       (try (Integer/parseInt line-num)
                            (catch Exception _ idx))
                       idx)]
      {:id (compute-transaction-id
             parsed-date
             parsed-desc
             parsed-amount
             parsed-source
             parsed-line)
       :date parsed-date
       :description parsed-desc
       :amount parsed-amount
       :type (normalize-type tx-type)
       :category-id (normalize-category category)
       :merchant (or merchant "")
       :merchant-id (normalize-merchant (or merchant desc))
       :currency (or currency "USD")
       :bank (normalize-bank bank)
       :source-file parsed-source
       :source-line parsed-line
       :confidence (if (and category (not= category ""))
                    0.85  ; Has category from CSV
                    0.0)})))  ; No category

;; ============================================================================
;; IMPORT
;; ============================================================================

(defn read-csv
  "Read CSV file and return parsed transactions."
  [file-path]
  (with-open [reader (io/reader file-path)]
    (let [rows (csv/read-csv reader)
          data-rows (rest rows)]  ; Skip header
      (vec
        (keep-indexed
          (fn [idx row]
            (parse-csv-row row (+ idx 2)))
          data-rows)))))

(defn import-transaction!
  "Import a single transaction to Datomic."
  [conn tx]
  (let [db (d/db conn)

        ;; Check if already exists
        exists? (d/q '[:find ?e .
                       :in $ ?id
                       :where [?e :transaction/id ?id]]
                     db
                     (:id tx))]

    (when-not exists?
      (let [;; Get bank entity ID
            bank-eid (when (:bank tx)
                      (d/q '[:find ?e .
                             :in $ ?id
                             :where [?e :entity/id ?id]]
                           db
                           (:bank tx)))

            ;; Get category entity ID
            category-eid (when (:category-id tx)
                          (d/q '[:find ?e .
                                 :in $ ?id
                                 :where [?e :entity/id ?id]]
                               db
                               (:category-id tx)))

            ;; Get or auto-create merchant entity ID (Bug #11 fix)
            ;; Problem: Only 9 merchants pre-registered (Starbucks, Amazon, etc.)
            ;; Solution: Auto-create merchant entity on first encounter
            merchant-eid (when (:merchant-id tx)
                          (let [existing (d/q '[:find ?e .
                                                :in $ ?id
                                                :where [?e :entity/id ?id]]
                                              db
                                              (:merchant-id tx))]
                            (if existing
                              existing
                              ;; Auto-create merchant entity
                              (let [merchant-name (name (:merchant-id tx))
                                    ;; Convert :whole-foods-market → "Whole Foods Market"
                                    canonical-name (clojure.string/join " "
                                                     (map clojure.string/capitalize
                                                       (clojure.string/split merchant-name #"-")))
                                    ;; Transact new merchant entity
                                    result @(d/transact conn [{:entity/id (:merchant-id tx)
                                                                :entity/canonical-name canonical-name}])
                                    db-after (:db-after result)]
                                ;; Query to get new entity ID
                                (d/q '[:find ?e .
                                       :in $ ?id
                                       :where [?e :entity/id ?id]]
                                     db-after
                                     (:merchant-id tx))))))

            ;; Build transaction entity
            tx-tempid (d/tempid :db.part/user)
            tx-entity (cond-> {:db/id tx-tempid
                               :transaction/id (:id tx)
                               :transaction/date (:date tx)
                               :transaction/description (:description tx)
                               :transaction/amount (:amount tx)
                               :transaction/currency (:currency tx)
                               :transaction/type (:type tx)
                               :transaction/source-file (:source-file tx)
                               :transaction/source-line (:source-line tx)
                               ;; DEPRECATED: Keep for backward compat, use Classification instead
                               :transaction/confidence (:confidence tx)
                               :temporal/business-time (:date tx)}

                        bank-eid
                        (assoc :transaction/bank bank-eid)

                        category-eid
                        (assoc :transaction/category category-eid)

                        merchant-eid
                        (assoc :transaction/merchant merchant-eid))

            ;; Build classification entity (separates facts from inferences)
            classification-entity {:db/id (d/tempid :db.part/user)
                                   :classification/transaction tx-tempid
                                   :classification/merchant-id (:merchant-id tx)
                                   :classification/category-id (:category-id tx)
                                   :classification/confidence (:confidence tx)
                                   :classification/method :csv-import
                                   :classification/timestamp (java.util.Date.)
                                   :classification/version "import-v1.0"}]

        ;; Transact both entities together
        @(d/transact conn [tx-entity classification-entity])
        true))))

(defn detect-duplicates!
  "Detect potential duplicate transactions across different sources.

  A transaction is a potential duplicate if:
  - Date within 2 days
  - Amount within $0.50
  - Different source files (cross-source duplicates)

  Returns: List of duplicate pairs [{:tx1 eid :tx2 eid :score 0.95} ...]

  Note: This is DETECTION, not decision-making.
  User should review and decide whether to merge or keep separate."
  [conn]
  (let [db (d/db conn)

        ;; Get all transactions
        all-txs (d/q '[:find ?e ?date ?amount ?source
                       :where
                       [?e :transaction/id]
                       [?e :transaction/date ?date]
                       [?e :transaction/amount ?amount]
                       [?e :transaction/source-file ?source]]
                     db)

        ;; Find potential duplicates
        duplicates (for [[e1 date1 amt1 src1] all-txs
                         [e2 date2 amt2 src2] all-txs
                         :when (and (< e1 e2)  ; Avoid comparing same or reverse pairs
                                   (not= src1 src2)  ; Different sources
                                   (< (Math/abs (- (.getTime date1) (.getTime date2)))
                                      (* 2 24 60 60 1000))  ; Within 2 days
                                   (< (Math/abs (- amt1 amt2)) 0.50))]  ; Within $0.50
                     {:tx1 e1
                      :tx2 e2
                      :date-diff-days (/ (Math/abs (- (.getTime date1) (.getTime date2)))
                                         (* 24 60 60 1000.0))
                      :amount-diff (Math/abs (- amt1 amt2))
                      :score (- 1.0 (* 0.3 (/ (Math/abs (- (.getTime date1) (.getTime date2)))
                                               (* 2 24 60 60 1000.0)))
                                    (* 0.7 (/ (Math/abs (- amt1 amt2)) 0.50)))})]

    duplicates))

(defn import-all!
  "Import all transactions from CSV.

  Returns {:imported N :skipped M :errors [...]}

  Example:
    (import-all! conn \"/Users/darwinborges/finance/transactions_ALL_SOURCES.csv\")"
  [conn file-path]
  (println (format "\n🚀 Importing transactions from: %s\n" file-path))

  (let [transactions (read-csv file-path)
        total (count transactions)]

    (println (format "📊 Found %d transactions in CSV\n" total))
    (println "⏳ Importing to Datomic...\n")

    (let [result (reduce
                   (fn [acc tx]
                     (try
                       (if (import-transaction! conn tx)
                         (do
                           (when (zero? (mod (:imported acc) 500))
                             (println (format "  ✓ Imported %d / %d" (:imported acc) total)))
                           (update acc :imported inc))
                         (update acc :skipped inc))
                       (catch Exception e
                         (update acc :errors conj
                                 {:transaction tx
                                  :error (.getMessage e)}))))
                   {:imported 0 :skipped 0 :errors []}
                   transactions)]

      (println (format "\n✅ Import complete!"))
      (println (format "   Imported: %d" (:imported result)))
      (println (format "   Skipped:  %d" (:skipped result)))
      (println (format "   Errors:   %d\n" (count (:errors result))))

      (when (seq (:errors result))
        (println "❌ Errors:")
        (doseq [{:keys [transaction error]} (take 5 (:errors result))]
          (println (format "   - %s: %s" (:id transaction) error))))

      ;; Log event
      (events/append-event! conn :bulk-import-completed
        {:file file-path
         :imported (:imported result)
         :skipped (:skipped result)
         :errors (count (:errors result))})

      result)))

;; ============================================================================
;; CATALOG EXTRACTION (Bug #14 fix)
;; ============================================================================

(defn load-rules
  "Load merchant classification rules from resources.

  Rich Hickey Principle: Single Source of Truth (Bug #14 fix).
  - Rules define categories and merchants
  - Catalog should be DERIVED from rules, not maintained separately
  - One source of truth = zero drift between rules and entities

  Returns: Vector of rule maps from resources/rules/merchant-rules.edn"
  []
  (try
    (-> "rules/merchant-rules.edn"
        io/resource
        slurp
        edn/read-string)
    (catch Exception e
      (println (format "⚠️  WARNING: Could not load rules file: %s" (.getMessage e)))
      [])))

(defn extract-categories-from-rules
  "Extract unique categories from rules.

  Returns: Set of category keywords (e.g., #{:restaurants :groceries :pharmacy})"
  [rules]
  (into #{} (keep :category rules)))

(defn extract-merchants-from-rules
  "Extract unique merchants from rules.

  Returns: Set of merchant keywords (e.g., #{:starbucks :amazon :cvs})"
  [rules]
  (into #{} (keep :merchant rules)))

(defn build-catalog-from-rules
  "Build entity registration catalog derived from rules.

  Rich Hickey Principle: Derived Data (Bug #14 fix).
  - Categories and merchants come from rules (single source of truth)
  - Auto-infer entity types from keyword namespace/usage
  - No manual maintenance = no drift

  Returns: Vector suitable for identity/register-batch!"
  []
  (let [rules (load-rules)
        categories (extract-categories-from-rules rules)
        merchants (extract-merchants-from-rules rules)]

    (println (format "   📋 Found %d categories in rules: %s"
                     (count categories)
                     (clojure.string/join ", " (map name (sort categories)))))
    (println (format "   🏪 Found %d merchants in rules: %s"
                     (count merchants)
                     (clojure.string/join ", " (map name (sort merchants)))))

    ;; Build registration vector
    (vec
      (concat
        ;; Categories with inferred types
        (for [cat categories]
          [cat {:entity/canonical-name (-> cat name clojure.string/capitalize)
                :category/type (cond
                                ;; Income categories
                                (#{:salary :freelance :bonus} cat) :income
                                ;; Transfer categories
                                (#{:payment :transfer} cat) :transfer
                                ;; Everything else is expense
                                :else :expense)}])

        ;; Merchants (no type needed, just entity)
        (for [merchant merchants]
          [merchant {:entity/canonical-name (-> merchant
                                               name
                                               (clojure.string/replace #"-" " ")
                                               clojure.string/capitalize)}])))))

;; ============================================================================
;; USAGE
;; ============================================================================

(defn -main
  "Main entry point for import script.

  Rich Hickey Principle: Context at Edges (Bug #13 fix).
  - URI from config/env (caller decides), not hardcoded (mechanism decides)
  - Storage choice (memory vs persistent) = deployment concern
  - Domain logic should NOT make deployment decisions

  Usage:
    # In-memory (testing/development):
    clj -M -m scripts.import-all-sources

    # Persistent (production - RECOMMENDED):
    DATOMIC_URI='datomic:dev://localhost:4334/finance' clj -M -m scripts.import-all-sources

    # Or provide as first arg:
    clj -M -m scripts.import-all-sources datomic:dev://localhost:4334/finance /path/to/csv"
  [& args]
  (let [;; Config-driven URI (Bug #13 fix)
        ;; Priority: CLI arg > ENV var > Default (memory - for backwards compat)
        datomic-uri (or (second args)  ; CLI arg
                       (System/getenv "DATOMIC_URI")  ; ENV var (production)
                       "datomic:mem://finance")  ; Default (testing)
        file-path (or (first args)
                      "/Users/darwinborges/finance/transactions_ALL_SOURCES.csv")]

    (println "\n🎯 Finance Trust Construction - Data Import")
    (println (apply str (repeat 50 "=")))

    ;; Initialize Datomic (URI from config!)
    (println "\n1️⃣  Initializing Datomic...")
    (println (format "   URI: %s" datomic-uri))
    (when (.contains datomic-uri ":mem:")
      (println "   ⚠️  WARNING: Using in-memory database - data will be lost on restart!"))
    (d/create-database datomic-uri)
    (def conn (d/connect datomic-uri))

    ;; Install schema
    (println "2️⃣  Installing schema...")
    (schema/install-schema! conn)

    ;; Register default entities (Bug #14 fix: Derived from rules!)
    (println "3️⃣  Registering default entities...")
    (println "   Deriving catalog from rules (single source of truth)...")

    ;; Banks (still hardcoded - not in rules)
    (identity/register-batch! conn
      [[:bofa {:entity/canonical-name "Bank of America"
               :bank/type :bank}]
       [:apple-card {:entity/canonical-name "Apple Card"
                     :bank/type :credit-card}]
       [:stripe {:entity/canonical-name "Stripe"
                 :bank/type :payment-processor}]
       [:wise {:entity/canonical-name "Wise"
               :bank/type :payment-processor}]
       [:scotiabank {:entity/canonical-name "Scotiabank"
                     :bank/type :bank}]])

    ;; Categories + Merchants (DERIVED from rules - Bug #14 fix!)
    (identity/register-batch! conn (build-catalog-from-rules))

    ;; Fallback entities (always needed)
    (identity/register-batch! conn
      [[:uncategorized {:entity/canonical-name "Uncategorized"
                        :category/type :unknown}]
       [:unknown-merchant {:entity/canonical-name "Unknown Merchant"}]])

    ;; Import data
    (println "4️⃣  Importing transactions...")
    (def result (import-all! conn file-path))

    ;; Statistics
    (println "\n5️⃣  Statistics:")
    (let [db (d/db conn)
          total (d/q '[:find (count ?e) .
                       :where [?e :transaction/id]]
                     db)
          by-type (d/q '[:find ?type (count ?e)
                         :where
                         [?e :transaction/id]
                         [?e :transaction/type ?type]]
                       db)]
      (println (format "   Total transactions: %d" total))
      (println "   By type:")
      (doseq [[type count] by-type]
        (println (format "     - %s: %d" (name type) count))))

    ;; Duplicate detection (post-import analysis)
    (println "\n6️⃣  Detecting potential duplicates...")
    (let [duplicates (detect-duplicates! conn)]
      (println (format "   Found %d potential duplicate pairs" (count duplicates)))
      (when (seq duplicates)
        (println "   Top 5 potential duplicates:")
        (doseq [{:keys [tx1 tx2 score date-diff-days amount-diff]} (take 5 (sort-by :score > duplicates))]
          (println (format "     - Tx %d ↔ Tx %d (score: %.2f, date-diff: %.1f days, amt-diff: $%.2f)"
                          tx1 tx2 score date-diff-days amount-diff))))
      (when (empty? duplicates)
        (println "   ✓ No cross-source duplicates detected")))

    (println "\n✨ Done!\n")))

(comment
  ;; Run import
  (-main)

  ;; Or use as library
  (require '[datomic.api :as d])
  (d/create-database "datomic:mem://finance")
  (def conn (d/connect "datomic:mem://finance"))
  (schema/install-schema! conn)

  (import-all! conn "/Users/darwinborges/finance/transactions_ALL_SOURCES.csv")
  )
