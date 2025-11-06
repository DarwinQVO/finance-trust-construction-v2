(ns trust.transducers
  "Generic transducers - Context-independent transformations.

  Core concepts (Rich Hickey):
  - Transformation logic separate from execution context
  - One transformation, many contexts (vector, stream, channel, etc.)
  - Composable via comp
  - No intermediate collections

  Provides:
  - Parsing transducers
  - Validation transducers
  - Classification transducers
  - Aggregation transducers
  - Error handling transducers"
  (:require [clojure.spec.alpha :as s]))

;; ============================================================================
;; PARSING TRANSDUCERS
;; ============================================================================

(defn xf-parse-csv-line
  "Transducer: Parse CSV line into fields.

  Handles quoted fields and escaped quotes.

  Example:
    (into []
      (xf-parse-csv-line)
      [\"field1,field2,field3\"
       \"\\\"quoted\\\",value,123\"])"
  []
  (map (fn [line]
         (if (string? line)
           (clojure.string/split line #",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")
           line))))

(defn xf-parse-number
  "Transducer: Parse numeric string to number.

  Handles currency symbols and commas.

  Example:
    (into []
      (xf-parse-number)
      [\"$1,234.56\" \"42\" \"-99.99\"])"
  []
  (map (fn [s]
         (when s
           (try
             (-> s
                 (clojure.string/replace #"[$,]" "")
                 (Double/parseDouble))
             (catch Exception _ s))))))

(defn xf-parse-date
  "Transducer: Parse date string to java.util.Date.

  Args:
    format - Date format string (default \"MM/dd/yyyy\")

  Example:
    (into []
      (xf-parse-date \"MM/dd/yyyy\")
      [\"03/20/2024\" \"12/25/2024\"])"
  ([]
   (xf-parse-date "MM/dd/yyyy"))
  ([format]
   (map (fn [s]
          (when (and s (string? s))
            (try
              (let [formatter (java.text.SimpleDateFormat. format)]
                (.parse formatter s))
              (catch Exception _ s)))))))

;; ============================================================================
;; VALIDATION TRANSDUCERS
;; ============================================================================

(defn xf-validate
  "Transducer: Validate items and tag with validation result.

  Args:
    validator-fn - Function (item -> {:valid? boolean :errors [...]})

  Returns items tagged with :validation metadata.

  Example:
    (into []
      (xf-validate (fn [x] (if (pos? x)
                            {:valid? true}
                            {:valid? false :errors [\"Must be positive\"]})))
      [1 -5 10])"
  [validator-fn]
  (map (fn [item]
         (let [result (validator-fn item)]
           (vary-meta item assoc :validation result)))))

(defn xf-remove-invalid
  "Transducer: Remove items that fail validation.

  Must be used after xf-validate.

  Example:
    (into []
      (comp (xf-validate my-validator)
            (xf-remove-invalid))
      items)"
  []
  (filter (fn [item]
            (get-in (meta item) [:validation :valid?]))))

(defn xf-collect-errors
  "Transducer: Collect validation errors into a separate channel.

  Returns items unchanged but side-effects errors.

  Example:
    (def errors (atom []))
    (into []
      (xf-collect-errors errors)
      validated-items)"
  [error-atom]
  (map (fn [item]
         (when-let [errors (get-in (meta item) [:validation :errors])]
           (swap! error-atom conj {:item item :errors errors}))
         item)))

;; ============================================================================
;; CLASSIFICATION TRANSDUCERS
;; ============================================================================

(defn xf-classify
  "Transducer: Classify items using a classifier function.

  Args:
    classifier-fn - Function (item -> classification-map)

  Merges classification into item.

  Example:
    (into []
      (xf-classify (fn [tx]
                    (if (pos? (:amount tx))
                      {:type :income}
                      {:type :expense})))
      transactions)"
  [classifier-fn]
  (map (fn [item]
         (merge item (classifier-fn item)))))

(defn xf-apply-rules
  "Transducer: Apply classification rules (data-driven).

  Args:
    rules - Vector of rule maps
    matcher-fn - Function (rule item -> match?)
    applier-fn - Function (rule item -> updated-item)

  Example:
    (into []
      (xf-apply-rules merchant-rules
        (fn [rule tx] (= (:pattern rule) (:merchant tx)))
        (fn [rule tx] (assoc tx :category (:category rule))))
      transactions)"
  [rules matcher-fn applier-fn]
  (map (fn [item]
         (if-let [rule (first (filter #(matcher-fn % item) rules))]
           (applier-fn rule item)
           item))))

(defn xf-confidence-filter
  "Transducer: Filter by confidence threshold.

  Example:
    (into []
      (xf-confidence-filter 0.8)
      classified-items)"
  [threshold]
  (filter (fn [item]
            (>= (or (:confidence item) 0.0) threshold))))

;; ============================================================================
;; AGGREGATION TRANSDUCERS
;; ============================================================================

(defn xf-sum-by
  "Transducer: Sum values extracted by key-fn.

  This is a reducing transducer (produces single value).

  Example:
    (transduce
      (xf-sum-by :amount)
      +
      0
      transactions)"
  [key-fn]
  (map key-fn))

(defn xf-group-by
  "Transducer: Group items by key-fn.

  Returns a map of key -> items.

  Example:
    (transduce
      (xf-group-by :type)
      (completing
        (fn [acc item]
          (update acc (:type item) (fnil conj []) item)))
      {}
      transactions)"
  [key-fn]
  (map (fn [item]
         [(key-fn item) item])))

(defn xf-count-by
  "Transducer: Count items by key-fn.

  Example:
    (transduce
      (xf-count-by :type)
      (completing
        (fn [acc [k _]]
          (update acc k (fnil inc 0))))
      {}
      transactions)"
  [key-fn]
  (xf-group-by key-fn))

;; ============================================================================
;; ENRICHMENT TRANSDUCERS
;; ============================================================================

(defn xf-enrich
  "Transducer: Enrich items with additional data.

  Args:
    lookup-fn - Function (item -> enrichment-map)

  Example:
    (into []
      (xf-enrich (fn [tx]
                  {:bank-name (get bank-registry (:bank-id tx))}))
      transactions)"
  [lookup-fn]
  (map (fn [item]
         (merge item (lookup-fn item)))))

(defn xf-add-metadata
  "Transducer: Add metadata to items.

  Args:
    metadata-fn - Function (item -> metadata-map)

  Example:
    (into []
      (xf-add-metadata (fn [tx]
                        {:processed-at (java.util.Date.)
                         :source \"pipeline-v1\"}))
      transactions)"
  [metadata-fn]
  (map (fn [item]
         (vary-meta item merge (metadata-fn item)))))

;; ============================================================================
;; ERROR HANDLING TRANSDUCERS
;; ============================================================================

(defn xf-try-catch
  "Transducer: Wrap transformation in try-catch.

  Args:
    xform - Transformation function
    error-handler - Function (item error -> recovery-value)

  Example:
    (into []
      (xf-try-catch
        (fn [x] (/ 100 x))
        (fn [item error] {:error (str error) :item item}))
      [10 0 5])"
  [xform error-handler]
  (map (fn [item]
         (try
           (xform item)
           (catch Exception e
             (error-handler item e))))))

(defn xf-error-boundary
  "Transducer: Catch errors and continue processing.

  Errors are tagged in metadata.

  Example:
    (into []
      (xf-error-boundary parse-fn)
      raw-data)"
  [xform]
  (xf-try-catch
    xform
    (fn [item error]
      (vary-meta item assoc :error {:message (.getMessage error)
                                    :type (type error)}))))

;; ============================================================================
;; DEDUPLICATION TRANSDUCERS
;; ============================================================================

(defn xf-dedupe-by
  "Transducer: Remove duplicates based on key-fn.

  Keeps first occurrence.

  Example:
    (into []
      (xf-dedupe-by :id)
      transactions)"
  [key-fn]
  (fn [rf]
    (let [seen (volatile! #{})]
      (fn
        ([] (rf))
        ([result] (rf result))
        ([result input]
         (let [k (key-fn input)]
           (if (contains? @seen k)
             result
             (do
               (vswap! seen conj k)
               (rf result input)))))))))

(defn xf-idempotency
  "Transducer: Skip items that match idempotency check.

  Args:
    exists? - Function (item -> boolean) checking if already processed

  Example:
    (into []
      (xf-idempotency (fn [tx] (db-contains? (:id tx))))
      transactions)"
  [exists?]
  (filter (fn [item]
            (not (exists? item)))))

;; ============================================================================
;; BATCHING TRANSDUCERS
;; ============================================================================

(defn xf-batch
  "Transducer: Batch items into groups of size n.

  Example:
    (into []
      (xf-batch 3)
      [1 2 3 4 5 6 7])
    ; => [[1 2 3] [4 5 6] [7]]"
  [n]
  (partition-all n))

(defn xf-sliding-window
  "Transducer: Create sliding windows of size n.

  Example:
    (into []
      (xf-sliding-window 3)
      [1 2 3 4 5])
    ; => [[1 2 3] [2 3 4] [3 4 5]]"
  [n]
  (partition n 1))

;; ============================================================================
;; COMPOSITION HELPERS
;; ============================================================================

(defn pipeline
  "Create a composable pipeline from multiple transducers.

  Example:
    (def tx-pipeline
      (pipeline
        [(xf-parse-csv-line)
         (xf-validate my-validator)
         (xf-remove-invalid)
         (xf-classify classifier)]))

    (into [] tx-pipeline raw-data)"
  [transducers]
  (apply comp transducers))

(defn fork
  "Fork processing into multiple parallel pipelines.

  Returns a map of pipeline-name -> results.

  Example:
    (fork data
      {:valid (comp (xf-validate v) (xf-remove-invalid))
       :invalid (comp (xf-validate v) (remove #(get-in (meta %) [:validation :valid?])))})"
  [data pipeline-map]
  (into {}
        (map (fn [[k xf]]
               [k (into [] xf data)]))
        pipeline-map))

;; ============================================================================
;; PERFORMANCE TRANSDUCERS
;; ============================================================================

(defn xf-take-until
  "Transducer: Take items until predicate is true.

  Example:
    (into []
      (xf-take-until #(> (:amount %) 1000))
      transactions)"
  [pred]
  (fn [rf]
    (let [done (volatile! false)]
      (fn
        ([] (rf))
        ([result] (rf result))
        ([result input]
         (if @done
           (reduced result)
           (if (pred input)
             (do
               (vreset! done true)
               (reduced result))
             (rf result input))))))))

(defn xf-limit
  "Transducer: Limit to first n items.

  Example:
    (into []
      (xf-limit 10)
      transactions)"
  [n]
  (take n))

;; ============================================================================
;; EXAMPLE USAGE (for documentation)
;; ============================================================================

(comment
  ;; Basic pipeline
  (def process-transactions
    (pipeline
      [(xf-parse-csv-line)
       (xf-validate transaction-validator)
       (xf-remove-invalid)
       (xf-classify classifier)
       (xf-confidence-filter 0.8)]))

  (into [] process-transactions raw-csv-lines)

  ;; Apply to different contexts
  (into [] process-transactions raw-data)  ; Vector
  (sequence process-transactions raw-data)  ; Lazy sequence
  (transduce process-transactions conj [] raw-data)  ; Custom reducing

  ;; Fork processing
  (fork transactions
    {:high-confidence (xf-confidence-filter 0.9)
     :medium-confidence (comp (xf-confidence-filter 0.7)
                              (remove #(>= (:confidence %) 0.9)))
     :low-confidence (remove #(>= (:confidence %) 0.7))})

  ;; Error handling
  (def safe-pipeline
    (pipeline
      [(xf-error-boundary parse-transaction)
       (xf-error-boundary classify-transaction)
       (xf-collect-errors error-log)]))

  (into [] safe-pipeline raw-data)

  ;; Aggregation
  (transduce
    (comp (filter #(= (:type %) :expense))
          (xf-sum-by :amount))
    +
    0
    transactions)
  ; => Total expenses

  ;; Batching for database inserts
  (doseq [batch (into []
                      (xf-batch 1000)
                      transactions)]
    (db/insert-batch! batch))
  )
