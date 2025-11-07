(ns finance.transducers
  "Pure data transformation transducers (Rich Hickey aligned).

  Philosophy (from Rich Hickey's talks):
  ============================================

  1. **Transducers are Process Transformations** (not collection operations)
     'A transducer transforms the reducing process itself—the step-by-step
     mechanism of accumulation. Think of it as transforming the recipe,
     not the ingredients.'

  2. **Context-Independent** (Spec-ulation Keynote)
     'The transformation works whether items arrive on conveyor belts (streams),
     in pallets (collections), or through doors (channels).'

  3. **Composition without Intermediates**
     'Chain transformations into a pipeline description before applying to data.
     Then when data flows through, all transformations fuse into a single pass.
     No intermediate collections between stages.'

  4. **Order-Independence Enables Parallelism**
     'Because transformations describe process modification, not sequential
     collection manipulation, the system can partition work across cores
     and recombine results.'

  5. **Separation of Concerns** (Simple Made Easy)
     'Separate WHAT (transformation logic) from HOW (iteration strategy)
     from WHERE (source/sink: file, channel, collection).'

  Benefits:
  ---------
  - ✅ No intermediate collections (memory efficient)
  - ✅ Same logic works with: into, sequence, transduce, core.async/pipeline
  - ✅ Composition before execution (lazy composition)
  - ✅ Ready for parallelism (with reducers/fold)
  - ✅ Easier to test (pure functions)
  - ✅ Reusable across contexts

  Usage Examples:
  ---------------

  ;; With collections (into)
  (into [] transaction-pipeline-xf csv-lines)

  ;; With lazy sequences (sequence)
  (sequence transaction-pipeline-xf csv-lines)

  ;; With reduction (transduce)
  (transduce transaction-pipeline-xf + 0 amounts)

  ;; With core.async (pipeline)
  (async/pipeline 4 out-ch transaction-pipeline-xf in-ch)

  ;; With reducers (parallel processing)
  (r/fold + (r/map transaction-pipeline-xf) transactions)

  Rich Hickey Quotes:
  -------------------
  'If you're not generating your test data, you're missing bugs.'
  'Transformation, movement, routing, and memory as separate concerns.'
  'The log IS the database. Everything else is just a view.'"
  (:require [clojure.string :as str])
  (:import [java.text SimpleDateFormat]
           [java.security MessageDigest]))

;; ============================================================================
;; PARSING TRANSDUCERS (Pure Data Transformations)
;; ============================================================================

(defn parse-date-xf
  "Transducer: Parse date field from string to java.util.Date.

  Transformation: string → Date
  Contexts: CSV lines, JSON objects, EDN maps

  Supported formats:
  - MM/dd/yyyy (e.g., '03/20/2024')
  - yyyy-MM-dd (e.g., '2024-03-20')

  Error handling:
  - Invalid dates → map with :error key
  - Preserves original value in :raw-date

  Example:
    (into [] (parse-date-xf :date) [{:date \"03/20/2024\"}])
    => [{:date #inst \"2024-03-20T00:00:00\" :raw-date \"03/20/2024\"}]

    (into [] (parse-date-xf :date) [{:date \"invalid\"}])
    => [{:date nil :raw-date \"invalid\" :error {:field :date :message \"...\"}}]"
  [date-field]
  (map (fn [record]
         (let [date-str (get record date-field)]
           (if-not date-str
             record
             (try
               ;; Try MM/dd/yyyy format first
               (let [parsed (.parse (SimpleDateFormat. \"MM/dd/yyyy\") date-str)]
                 (assoc record
                   date-field parsed
                   :raw-date date-str))
               (catch Exception e1
                 (try
                   ;; Try yyyy-MM-dd format
                   (let [parsed (.parse (SimpleDateFormat. \"yyyy-MM-dd\") date-str)]
                     (assoc record
                       date-field parsed
                       :raw-date date-str))
                   (catch Exception e2
                     ;; FAIL GRACEFULLY - mark as error but continue processing
                     (assoc record
                       date-field nil
                       :raw-date date-str
                       :error {:field date-field
                               :message (format \"Invalid date format: '%s'. Expected: MM/dd/yyyy or yyyy-MM-dd\" date-str)
                               :tried-formats [\"MM/dd/yyyy\" \"yyyy-MM-dd\"]
                               :error-1 (.getMessage e1)
                               :error-2 (.getMessage e2)}))))))))))

(defn parse-amount-xf
  \"Transducer: Parse amount field from string to double (always positive).

  Transformation: string → double
  Contexts: CSV lines, JSON objects, EDN maps

  - Removes $, commas
  - Always returns positive (Math/abs)
  - Direction encoded in :type field

  Error handling:
  - Invalid amounts → map with :error key
  - Preserves original value in :raw-amount

  Example:
    (into [] (parse-amount-xf :amount) [{:amount \"$1,234.56\"}])
    => [{:amount 1234.56 :raw-amount \"$1,234.56\"}]

    (into [] (parse-amount-xf :amount) [{:amount \"-45.99\"}])
    => [{:amount 45.99 :raw-amount \"-45.99\"}]  ; Positive!

    (into [] (parse-amount-xf :amount) [{:amount \"invalid\"}])
    => [{:amount nil :raw-amount \"invalid\" :error {...}}]\"
  [amount-field]
  (map (fn [record]
         (let [amount-str (get record amount-field)]
           (if-not amount-str
             record
             (try
               (let [parsed (-> amount-str
                               str
                               (str/replace #\"[$,]\" \"\")
                               Double/parseDouble
                               Math/abs)]  ; Always positive
                 (assoc record
                   amount-field parsed
                   :raw-amount amount-str))
               (catch Exception e
                 ;; FAIL GRACEFULLY - mark as error but continue
                 (assoc record
                   amount-field nil
                   :raw-amount amount-str
                   :error {:field amount-field
                           :message (format \"Invalid amount format: '%s'. Expected numeric value\" amount-str)
                           :error (.getMessage e)}))))))))

(defn normalize-type-xf
  \"Transducer: Normalize transaction type to keyword.

  Transformation: string → keyword
  Contexts: CSV lines, JSON objects, EDN maps

  Valid types:
  - GASTO → :expense
  - INGRESO → :income
  - PAGO_TARJETA → :transfer
  - TRASPASO → :transfer

  Error handling:
  - Unknown types → :unknown + error key
  - Preserves original value in :raw-type

  Example:
    (into [] (normalize-type-xf :type) [{:type \"GASTO\"}])
    => [{:type :expense :raw-type \"GASTO\"}]

    (into [] (normalize-type-xf :type) [{:type \"INVALID\"}])
    => [{:type :unknown :raw-type \"INVALID\" :error {...}}]\"
  [type-field]
  (map (fn [record]
         (let [type-str (get record type-field)]
           (if-not type-str
             record
             (let [upper (str/upper-case type-str)
                   normalized (case upper
                               \"GASTO\" :expense
                               \"INGRESO\" :income
                               \"PAGO_TARJETA\" :transfer
                               \"TRASPASO\" :transfer
                               :unknown)]
               (if (= normalized :unknown)
                 ;; Unknown type - mark as error
                 (assoc record
                   type-field :unknown
                   :raw-type type-str
                   :error {:field type-field
                           :message (format \"Unknown transaction type: '%s'. Expected: GASTO, INGRESO, PAGO_TARJETA, TRASPASO\" type-str)
                           :valid-types [\"GASTO\" \"INGRESO\" \"PAGO_TARJETA\" \"TRASPASO\"]})
                 ;; Valid type
                 (assoc record
                   type-field normalized
                   :raw-type type-str))))))))

(defn normalize-bank-xf
  \"Transducer: Normalize bank name to keyword.

  Transformation: string → keyword
  Contexts: CSV lines, JSON objects, EDN maps

  Supported banks:
  - Bank of America / BofA → :bofa
  - Apple Card → :apple-card
  - Stripe → :stripe
  - Wise → :wise
  - Scotiabank → :scotiabank
  - Unknown → :unknown

  Example:
    (into [] (normalize-bank-xf :bank) [{:bank \"Bank of America\"}])
    => [{:bank :bofa :raw-bank \"Bank of America\"}]\"
  [bank-field]
  (map (fn [record]
         (let [bank-str (get record bank-field)]
           (if-not bank-str
             record
             (let [upper (str/upper-case bank-str)
                   normalized (cond
                               (or (str/includes? upper \"BOFA\")
                                   (str/includes? upper \"BANK OF AMERICA\")) :bofa
                               (str/includes? upper \"APPLE\") :apple-card
                               (str/includes? upper \"STRIPE\") :stripe
                               (str/includes? upper \"WISE\") :wise
                               (str/includes? upper \"SCOTIA\") :scotiabank
                               :else :unknown)]
               (assoc record
                 bank-field normalized
                 :raw-bank bank-str)))))))

(defn normalize-merchant-xf
  \"Transducer: Normalize merchant name to keyword (preserving information).

  Transformation: string → keyword
  Contexts: CSV lines, JSON objects, EDN maps

  Information Preservation Principle (Rich Hickey):
  - NEVER truncate to first word only
  - Remove special delimiters: DES:, #, PURCHASE:, ID:
  - Remove trailing IDs (5+ digits)
  - Multi-word merchants preserved with hyphens

  Examples:
    'STARBUCKS #123' → :starbucks
    'UBER RIDE 45678, DES: TRIP' → :uber-ride
    'WHOLE FOODS MARKET' → :whole-foods-market (full name!)

  Example:
    (into [] (normalize-merchant-xf :merchant) [{:merchant \"STARBUCKS #123\"}])
    => [{:merchant :starbucks :raw-merchant \"STARBUCKS #123\"}]\"
  [merchant-field]
  (map (fn [record]
         (let [merchant-str (get record merchant-field)]
           (if-not (and merchant-str (not= merchant-str \"\"))
             (assoc record merchant-field :unknown-merchant)
             (let [upper (str/upper-case (str/trim merchant-str))
                   ;; Remove delimiters
                   cleaned (-> upper
                              (str/split #\"\\s*,\\s*DES:\")
                              first
                              (str/split #\"\\s*PURCHASE\\s*:\")
                              first
                              (str/split #\"\\s*#\")
                              first
                              (str/split #\"\\s*ID:\")
                              first
                              str/trim)
                   ;; Remove trailing IDs
                   without-id (str/replace cleaned #\"\\s+\\d{5,}$\" \"\")
                   ;; Convert to keyword
                   normalized (-> without-id
                                 str/lower-case
                                 (str/replace #\"\\s+\" \"-\")
                                 (str/replace #\"[^a-z0-9-]\" \"\")
                                 keyword)]
               (assoc record
                 merchant-field normalized
                 :raw-merchant merchant-str)))))))

;; ============================================================================
;; VALIDATION TRANSDUCERS (Filter Invalid Records)
;; ============================================================================

(defn filter-errors-xf
  \"Transducer: Filter out records with :error key.

  Transformation: [record] → [record] (subset)
  Contexts: Any sequence of maps

  This separates valid from invalid records for downstream processing.

  Example:
    (into [] filter-errors-xf
      [{:id 1 :amount 100}
       {:id 2 :error {:message \"Invalid\"}}
       {:id 3 :amount 200}])
    => [{:id 1 :amount 100} {:id 3 :amount 200}]\"
  []
  (remove :error))

(defn filter-valid-amount-xf
  \"Transducer: Filter records with valid positive amounts.

  Transformation: [record] → [record] (subset)

  Example:
    (into [] (filter-valid-amount-xf :amount)
      [{:amount 100}
       {:amount nil}
       {:amount 0}
       {:amount 50}])
    => [{:amount 100} {:amount 50}]\"
  [amount-field]
  (filter (fn [record]
            (when-let [amount (get record amount-field)]
              (and (number? amount)
                   (pos? amount))))))

(defn filter-valid-date-xf
  \"Transducer: Filter records with valid date objects.

  Transformation: [record] → [record] (subset)

  Example:
    (into [] (filter-valid-date-xf :date)
      [{:date #inst \"2024-11-06\"}
       {:date nil}
       {:date #inst \"2024-11-07\"}])
    => [{:date #inst \"2024-11-06\"} {:date #inst \"2024-11-07\"}]\"
  [date-field]
  (filter (fn [record]
            (instance? java.util.Date (get record date-field)))))

;; ============================================================================
;; ENRICHMENT TRANSDUCERS (Add Computed Fields)
;; ============================================================================

(defn add-id-xf
  \"Transducer: Add unique ID field (UUID).

  Transformation: record → record+id
  Contexts: Any map

  Example:
    (into [] (add-id-xf :transaction-id) [{:amount 100}])
    => [{:amount 100 :transaction-id \"123e4567-...\"}]\"
  [id-field]
  (map (fn [record]
         (assoc record id-field (str (java.util.UUID/randomUUID))))))

(defn add-idempotency-hash-xf
  \"Transducer: Add idempotency hash for deduplication.

  Transformation: record → record+hash
  Hash based on: date + amount + merchant + bank

  Example:
    (into [] (add-idempotency-hash-xf)
      [{:date \"2024-11-06\" :amount 45.99 :merchant :starbucks :bank :bofa}])
    => [{... :idempotency-hash \"a3f5...\"}]\"
  []
  (map (fn [record]
         (let [hash-input (str (:date record)
                              (:amount record)
                              (:merchant record)
                              (:bank record))
               digest (MessageDigest/getInstance \"SHA-256\")
               hash-bytes (.digest digest (.getBytes hash-input \"UTF-8\"))
               hash-hex (apply str (map #(format \"%02x\" %) hash-bytes))]
           (assoc record :idempotency-hash hash-hex)))))

(defn add-provenance-xf
  \"Transducer: Add provenance metadata (where data came from).

  Transformation: record → record+provenance

  Provenance includes:
  - Source file
  - Line number
  - Import timestamp
  - Parser version

  Example:
    (into [] (add-provenance-xf \"data.csv\" \"1.0.0\")
      [{:amount 100}])
    => [{:amount 100
         :provenance {:source-file \"data.csv\"
                      :imported-at #inst \"2024-11-06\"
                      :parser-version \"1.0.0\"}}]\"
  [source-file parser-version]
  (map-indexed (fn [idx record]
                 (assoc record
                   :provenance {:source-file source-file
                                :source-line (inc idx)
                                :imported-at (java.util.Date.)
                                :parser-version parser-version}))))

;; ============================================================================
;; COMPOSED PIPELINES (Ready-to-Use Transformations)
;; ============================================================================

(defn csv-import-pipeline-xf
  \"Complete CSV import pipeline (composed transducers).

  Transformation: CSV row map → Validated transaction map

  Steps:
  1. Parse date, amount
  2. Normalize type, bank, merchant
  3. Filter invalid records
  4. Add IDs and hashes
  5. Add provenance

  Rich Hickey Principle:
  - Composition BEFORE execution
  - All transformations fuse into single pass
  - No intermediate collections

  Example:
    (into [] (csv-import-pipeline-xf \"data.csv\" \"1.0.0\")
      csv-row-maps)
    => [validated-transaction-maps]

  Usage with different contexts:

    ;; Eager collection
    (into [] pipeline-xf csv-rows)

    ;; Lazy sequence
    (sequence pipeline-xf csv-rows)

    ;; Reduction
    (transduce pipeline-xf conj [] csv-rows)

    ;; core.async
    (async/pipeline 4 out-ch pipeline-xf in-ch)\"
  [source-file parser-version]
  (comp
    ;; Step 1: Parse primitives
    (parse-date-xf :date)
    (parse-amount-xf :amount)

    ;; Step 2: Normalize fields
    (normalize-type-xf :type)
    (normalize-bank-xf :bank)
    (normalize-merchant-xf :merchant)

    ;; Step 3: Filter invalid
    (filter-errors-xf)
    (filter-valid-amount-xf :amount)
    (filter-valid-date-xf :date)

    ;; Step 4: Enrich
    (add-id-xf :transaction-id)
    (add-idempotency-hash-xf)
    (add-provenance-xf source-file parser-version)))

(defn classification-pipeline-xf
  \"Classification enrichment pipeline (composed transducers).

  Transformation: transaction → transaction+classification

  Note: Classification logic separated for testability.
  This transducer adds structure, logic provided as arg.

  Example:
    (defn classify-fn [tx]
      (assoc tx
        :category (classify-category tx)
        :confidence (calculate-confidence tx)))

    (into [] (classification-pipeline-xf classify-fn) transactions)\"
  [classify-fn]
  (map classify-fn))

;; ============================================================================
;; HELPER FUNCTIONS (Context-Specific Applications)
;; ============================================================================

(defn process-csv-file
  \"Process CSV file using transducers (I/O separated).

  This is where I/O meets pure transformations.
  I/O: Reading file
  Transformation: Pipeline of transducers
  I/O: Writing to database (separate)

  Rich Hickey Principle: Separate I/O from transformation.

  Example:
    (process-csv-file \"data.csv\" pipeline-xf)
    => [validated-transactions]\"
  [file-path pipeline-xf]
  (with-open [reader (clojure.java.io/reader file-path)]
    (let [csv-data (clojure.data.csv/read-csv reader)]
      ;; Skip header row, convert to maps
      (let [headers (map keyword (first csv-data))
            rows (rest csv-data)
            row-maps (map #(zipmap headers %) rows)]
        ;; Apply transducer pipeline
        (into [] pipeline-xf row-maps)))))

(comment
  ;; ============================================================================
  ;; REPL EXAMPLES (Rich Hickey Style)
  ;; ============================================================================

  ;; Example 1: Parse dates
  (into []
    (parse-date-xf :date)
    [{:date \"03/20/2024\"}
     {:date \"2024-11-06\"}
     {:date \"invalid\"}])
  ;; => [{:date #inst \"2024-03-20\" :raw-date \"03/20/2024\"}
  ;;     {:date #inst \"2024-11-06\" :raw-date \"2024-11-06\"}
  ;;     {:date nil :raw-date \"invalid\" :error {...}}]

  ;; Example 2: Parse amounts
  (into []
    (parse-amount-xf :amount)
    [{:amount \"$1,234.56\"}
     {:amount \"-45.99\"}
     {:amount \"invalid\"}])
  ;; => [{:amount 1234.56 :raw-amount \"$1,234.56\"}
  ;;     {:amount 45.99 :raw-amount \"-45.99\"}
  ;;     {:amount nil :raw-amount \"invalid\" :error {...}}]

  ;; Example 3: Complete pipeline
  (def sample-csv-rows
    [{:date \"03/20/2024\"
      :amount \"$45.99\"
      :type \"GASTO\"
      :bank \"Bank of America\"
      :merchant \"STARBUCKS #123\"}])

  (into []
    (csv-import-pipeline-xf \"test.csv\" \"1.0.0\")
    sample-csv-rows)
  ;; => [{:date #inst \"2024-03-20\"
  ;;      :amount 45.99
  ;;      :type :expense
  ;;      :bank :bofa
  ;;      :merchant :starbucks
  ;;      :transaction-id \"...\"
  ;;      :idempotency-hash \"...\"
  ;;      :provenance {...}}]

  ;; Example 4: Same pipeline, different context (lazy)
  (def lazy-result
    (sequence (csv-import-pipeline-xf \"test.csv\" \"1.0.0\")
              sample-csv-rows))
  ;; Lazy! Not evaluated until consumed

  ;; Example 5: Same pipeline, reduction context
  (transduce
    (comp (csv-import-pipeline-xf \"test.csv\" \"1.0.0\")
          (map :amount))
    +
    0
    sample-csv-rows)
  ;; => 45.99  (sum of amounts)

  ;; Example 6: core.async (requires core.async loaded)
  (require '[clojure.core.async :as async])
  (let [in-ch (async/to-chan sample-csv-rows)
        out-ch (async/chan 10)
        pipeline-xf (csv-import-pipeline-xf \"test.csv\" \"1.0.0\")]
    (async/pipeline 4 out-ch pipeline-xf in-ch)
    (async/<!! (async/into [] out-ch)))
  ;; => Same result, but processed asynchronously with 4 workers!

  )
