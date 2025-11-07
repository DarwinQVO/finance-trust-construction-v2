(ns finance.transducers
  "Pure data transformation transducers (Rich Hickey aligned)."
  (:require [clojure.string :as str])
  (:import [java.text SimpleDateFormat]
           [java.security MessageDigest]))

;;; Phase 1: PARSING TRANSDUCERS (Pure Data Transformations)

(defn parse-date-xf
  "Parse date field from string to java.util.Date."
  [date-field]
  (map (fn [record]
         (let [date-str (get record date-field)]
           (if-not date-str
             record
             (try
               (let [parsed (.parse (SimpleDateFormat. "MM/dd/yyyy") date-str)]
                 (assoc record
                   date-field parsed
                   :raw-date date-str))
               (catch Exception e1
                 (try
                   (let [parsed (.parse (SimpleDateFormat. "yyyy-MM-dd") date-str)]
                     (assoc record
                       date-field parsed
                       :raw-date date-str))
                   (catch Exception e2
                     (assoc record
                       date-field nil
                       :raw-date date-str
                       :error {:field date-field
                               :message (format "Invalid date format: '%s'" date-str)}))))))))))

(defn parse-amount-xf
  "Parse amount field from string to double (always positive)."
  [amount-field]
  (map (fn [record]
         (let [amount-str (get record amount-field)]
           (if-not amount-str
             record
             (try
               (let [parsed (-> amount-str
                               str
                               (str/replace #"[$,]" "")
                               Double/parseDouble
                               Math/abs)]
                 (assoc record
                   amount-field parsed
                   :raw-amount amount-str))
               (catch Exception e
                 (assoc record
                   amount-field nil
                   :raw-amount amount-str
                   :error {:field amount-field
                           :message (format "Invalid amount: '%s'" amount-str)}))))))))

(defn normalize-type-xf
  "Normalize transaction type to keyword."
  [type-field]
  (map (fn [record]
         (let [type-str (get record type-field)]
           (if-not type-str
             record
             (let [upper (str/upper-case type-str)
                   normalized (case upper
                               "GASTO" :expense
                               "INGRESO" :income
                               "PAGO_TARJETA" :transfer
                               "TRASPASO" :transfer
                               :unknown)]
               (if (= normalized :unknown)
                 (assoc record
                   type-field :unknown
                   :raw-type type-str
                   :error {:field type-field :message (format "Unknown type: '%s'" type-str)})
                 (assoc record
                   type-field normalized
                   :raw-type type-str))))))))

(defn normalize-bank-xf
  "Normalize bank name to keyword."
  [bank-field]
  (map (fn [record]
         (let [bank-str (get record bank-field)]
           (if-not bank-str
             record
             (let [upper (str/upper-case bank-str)
                   normalized (cond
                               (or (str/includes? upper "BOFA")
                                   (str/includes? upper "BANK OF AMERICA")) :bofa
                               (str/includes? upper "APPLE") :apple-card
                               (str/includes? upper "STRIPE") :stripe
                               (str/includes? upper "WISE") :wise
                               (str/includes? upper "SCOTIA") :scotiabank
                               :else :unknown)]
               (assoc record
                 bank-field normalized
                 :raw-bank bank-str)))))))

(defn normalize-merchant-xf
  "Normalize merchant name to keyword."
  [merchant-field]
  (map (fn [record]
         (let [merchant-str (get record merchant-field)]
           (if-not (and merchant-str (not= merchant-str ""))
             (assoc record merchant-field :unknown-merchant)
             (let [upper (str/upper-case (str/trim merchant-str))
                   cleaned (-> upper
                              (str/split #"\s*,\s*DES:")
                              first
                              (str/split #"\s*PURCHASE\s*:")
                              first
                              (str/split #"\s*#")
                              first
                              (str/split #"\s*ID:")
                              first
                              str/trim)
                   without-id (str/replace cleaned #"\s+\d{5,}$" "")
                   normalized (-> without-id
                                 str/lower-case
                                 (str/replace #"\s+" "-")
                                 (str/replace #"[^a-z0-9-]" "")
                                 keyword)]
               (assoc record
                 merchant-field normalized
                 :raw-merchant merchant-str)))))))

(defn normalize-category-xf
  "Normalize category name to keyword."
  [category-field]
  (map (fn [record]
         (let [category-str (get record category-field)]
           (if-not (and category-str (not= category-str ""))
             (assoc record category-field :uncategorized)
             (let [lower (str/lower-case category-str)
                   normalized (cond
                               (str/includes? lower "restaurant") :restaurants
                               (str/includes? lower "food") :restaurants
                               (str/includes? lower "grocer") :groceries
                               (str/includes? lower "shopping") :shopping
                               (str/includes? lower "transport") :transportation
                               (str/includes? lower "entertainment") :entertainment
                               (str/includes? lower "salary") :salary
                               (str/includes? lower "freelance") :freelance
                               (str/includes? lower "payment") :payment
                               (str/includes? lower "transfer") :transfer
                               :else :uncategorized)]
               (assoc record
                 category-field normalized
                 :raw-category category-str)))))))

(defn compute-deterministic-id-xf
  "Compute deterministic transaction ID from stable fields.

  Uses SHA-256 hash of: date, description, amount, source-file, source-line.
  This ensures same transaction imported twice gets same ID (idempotency)."
  []
  (map (fn [record]
         (let [;; Normalize inputs
               date-str (when-let [d (:date record)]
                         (when (instance? java.util.Date d)
                           (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") d)))
               desc-normalized (-> (str (:description record))
                                  str/upper-case
                                  str/trim)
               amount-rounded (format "%.2f" (double (:amount record)))
               source-file (or (:source-file record) "unknown")
               source-line (or (:source-line record) 0)

               ;; Concatenate stable fields
               stable-data (str date-str "|"
                              desc-normalized "|"
                              amount-rounded "|"
                              source-file "|"
                              source-line)

               ;; Compute SHA-256 hash
               digest (MessageDigest/getInstance "SHA-256")
               hash-bytes (.digest digest (.getBytes stable-data "UTF-8"))
               hash-hex (apply str (map #(format "%02x" %) hash-bytes))]

           (assoc record :id (str "tx-" (subs hash-hex 0 16)))))))

;;; VALIDATION TRANSDUCERS

(defn filter-errors-xf
  "Filter out records with :error key."
  []
  (remove :error))

(defn filter-valid-amount-xf
  "Filter records with valid positive amounts."
  [amount-field]
  (filter (fn [record]
            (when-let [amount (get record amount-field)]
              (and (number? amount)
                   (pos? amount))))))

(defn filter-valid-date-xf
  "Filter records with valid date objects."
  [date-field]
  (filter (fn [record]
            (instance? java.util.Date (get record date-field)))))

;;; ENRICHMENT TRANSDUCERS

(defn add-id-xf
  "Add unique ID field (UUID)."
  [id-field]
  (map (fn [record]
         (assoc record id-field (str (java.util.UUID/randomUUID))))))

(defn add-idempotency-hash-xf
  "Add idempotency hash for deduplication."
  []
  (map (fn [record]
         (let [hash-input (str (:date record)
                              (:amount record)
                              (:merchant record)
                              (:bank record))
               digest (MessageDigest/getInstance "SHA-256")
               hash-bytes (.digest digest (.getBytes hash-input "UTF-8"))
               hash-hex (apply str (map #(format "%02x" %) hash-bytes))]
           (assoc record :idempotency-hash hash-hex)))))

(defn add-provenance-xf
  "Add provenance metadata."
  [source-file parser-version]
  (map-indexed (fn [idx record]
                 (assoc record
                   :provenance {:source-file source-file
                                :source-line (inc idx)
                                :imported-at (java.util.Date.)
                                :parser-version parser-version}))))

;;; COMPOSED PIPELINES

(defn csv-import-pipeline-xf
  "Complete CSV import pipeline (composed transducers)."
  [source-file parser-version]
  (comp
    (parse-date-xf :date)
    (parse-amount-xf :amount)
    (normalize-type-xf :type)
    (normalize-bank-xf :bank)
    (normalize-merchant-xf :merchant)
    (filter-errors-xf)
    (filter-valid-amount-xf :amount)
    (filter-valid-date-xf :date)
    (add-id-xf :transaction-id)
    (add-idempotency-hash-xf)
    (add-provenance-xf source-file parser-version)))

(defn classification-pipeline-xf
  "Classification enrichment pipeline."
  [classify-fn]
  (map classify-fn))

;;; CLASSIFICATION TRANSDUCERS (Composable with parsing)

(defn classify-xf
  "Classify transactions using rule-based system.

  Takes a classify-fn that accepts a transaction and returns classified transaction.
  This separates LOGIC (classify-fn) from APPLICATION (transducer).

  Rich Hickey principle: Rules are data, engine is generic.

  Example:
    (require '[finance.classification :as class])
    (def rules (class/get-default-rules))
    (def pipeline (comp
                    (parse-date-xf :date)
                    (parse-amount-xf :amount)
                    (classify-xf #(class/classify % rules))))

    (into [] pipeline csv-rows)"
  [classify-fn]
  (map classify-fn))

(defn filter-confidence-xf
  "Filter transactions by confidence level.

  level - One of:
    :high   - >= 0.9
    :medium - 0.7 to 0.89
    :low    - < 0.7
    :any    - All transactions (no filter)

  Example:
    (def pipeline (comp
                    (classify-xf classifier)
                    (filter-confidence-xf :high)))

    (into [] pipeline transactions)
    ;; => Only high-confidence transactions"
  [level]
  (if (= level :any)
    identity
    (let [pred (case level
                 :high   (fn [tx] (>= (or (:confidence tx) 0.0) 0.9))
                 :medium (fn [tx] (let [c (or (:confidence tx) 0.0)]
                                   (and (>= c 0.7) (< c 0.9))))
                 :low    (fn [tx] (< (or (:confidence tx) 0.0) 0.7)))]
      (filter pred))))

(defn enrich-classification-metadata-xf
  "Add classification metadata to transactions.

  Adds:
  - :classified-at (timestamp)
  - :classification-version (version string)

  Example:
    (def pipeline (comp
                    (classify-xf classifier)
                    (enrich-classification-metadata-xf \"1.0.0\")))"
  [version]
  (map (fn [tx]
         (assoc tx
           :classified-at (java.util.Date.)
           :classification-version version))))

;;; COMPOSED CLASSIFICATION PIPELINE

(defn full-classification-pipeline-xf
  "Complete classification pipeline with parsing + classification.

  Takes:
  - classify-fn: Function to classify transactions
  - min-confidence: Minimum confidence level (:high/:medium/:low/:any)
  - version: Classification version

  Returns transducer that:
  1. Parses date/amount
  2. Normalizes type/bank/merchant/category
  3. Computes deterministic ID
  4. Filters errors
  5. Classifies using rules
  6. Filters by confidence
  7. Adds metadata

  Example:
    (require '[finance.classification :as class])
    (def rules (class/get-default-rules))

    (def pipeline (full-classification-pipeline-xf
                    #(class/classify % rules)
                    :high
                    \"1.0.0\"))

    (into [] pipeline csv-rows)"
  [classify-fn min-confidence version]
  (comp
    ;; Parsing
    (parse-date-xf :date)
    (parse-amount-xf :amount)
    (normalize-type-xf :type)
    (normalize-bank-xf :bank)
    (normalize-merchant-xf :merchant)
    (normalize-category-xf :category)
    (compute-deterministic-id-xf)
    (filter-errors-xf)
    (filter-valid-amount-xf :amount)
    (filter-valid-date-xf :date)

    ;; Classification
    (classify-xf classify-fn)
    (filter-confidence-xf min-confidence)
    (enrich-classification-metadata-xf version)))
