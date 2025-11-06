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
            [datomic.api :as d]
            [trust.datomic-schema :as schema]
            [trust.identity-datomic :as identity]
            [trust.events-datomic :as events]
            [finance.entities :as entities])
  (:import [java.text SimpleDateFormat]))

;; ============================================================================
;; PARSING
;; ============================================================================

(defn parse-date
  "Parse date string (various formats supported)."
  [date-str]
  (when date-str
    (try
      ;; Try MM/dd/yyyy format first
      (.parse (SimpleDateFormat. "MM/dd/yyyy") date-str)
      (catch Exception _
        (try
          ;; Try yyyy-MM-dd format
          (.parse (SimpleDateFormat. "yyyy-MM-dd") date-str)
          (catch Exception _
            (java.util.Date.)))))))

(defn parse-amount
  "Parse amount string to double."
  [amount-str]
  (when amount-str
    (try
      (-> amount-str
          (clojure.string/replace #"[$,]" "")
          (Double/parseDouble)
          Math/abs)  ; Always positive
      (catch Exception _ 0.0))))

(defn normalize-type
  "Normalize transaction type to keyword."
  [type-str]
  (when type-str
    (case (.toUpperCase type-str)
      "GASTO" :expense
      "INGRESO" :income
      "PAGO_TARJETA" :transfer
      "TRASPASO" :transfer
      :expense)))

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
           currency account-name account-num bank source-file line-num notes] row]
      {:id (str "tx-" (java.util.UUID/randomUUID))
       :date (parse-date date)
       :description (or desc "")
       :amount (parse-amount (or amount-num amount-orig "0"))
       :type (normalize-type tx-type)
       :category-id (normalize-category category)
       :merchant (or merchant "")
       :currency (or currency "USD")
       :bank (normalize-bank bank)
       :source-file (or source-file "transactions_ALL_SOURCES.csv")
       :source-line (if line-num
                     (try (Integer/parseInt line-num)
                          (catch Exception _ idx))
                     idx)
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

            ;; Build transaction entity
            tx-entity (cond-> {:db/id (d/tempid :db.part/user)
                               :transaction/id (:id tx)
                               :transaction/date (:date tx)
                               :transaction/description (:description tx)
                               :transaction/amount (:amount tx)
                               :transaction/currency (:currency tx)
                               :transaction/type (:type tx)
                               :transaction/source-file (:source-file tx)
                               :transaction/source-line (:source-line tx)
                               :transaction/confidence (:confidence tx)
                               :temporal/business-time (:date tx)}

                        bank-eid
                        (assoc :transaction/bank bank-eid)

                        category-eid
                        (assoc :transaction/category category-eid))]

        @(d/transact conn [tx-entity])
        true))))

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
;; USAGE
;; ============================================================================

(defn -main
  "Main entry point for import script.

  Usage:
    clj -M -m scripts.import-all-sources"
  [& args]
  (let [file-path (or (first args)
                      "/Users/darwinborges/finance/transactions_ALL_SOURCES.csv")]

    (println "\n🎯 Finance Trust Construction - Data Import")
    (println (apply str (repeat 50 "=")))

    ;; Initialize Datomic
    (println "\n1️⃣  Initializing Datomic...")
    (d/create-database "datomic:mem://finance")
    (def conn (d/connect "datomic:mem://finance"))

    ;; Install schema
    (println "2️⃣  Installing schema...")
    (schema/install-schema! conn)

    ;; Register default entities
    (println "3️⃣  Registering default entities...")
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
                     :bank/type :bank}]
       [:restaurants {:entity/canonical-name "Restaurants"
                      :category/type :expense}]
       [:groceries {:entity/canonical-name "Groceries"
                    :category/type :expense}]
       [:shopping {:entity/canonical-name "Shopping"
                   :category/type :expense}]
       [:transportation {:entity/canonical-name "Transportation"
                         :category/type :expense}]
       [:salary {:entity/canonical-name "Salary"
                 :category/type :income}]
       [:freelance {:entity/canonical-name "Freelance"
                    :category/type :income}]
       [:payment {:entity/canonical-name "Payment"
                  :category/type :transfer}]
       [:uncategorized {:entity/canonical-name "Uncategorized"
                        :category/type :unknown}]])

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
