(ns finance.api.handlers
  "API request handlers for Finance Trust Construction v2.0.

  Rich Hickey Principles Applied:
  1. Handlers are PURE FUNCTIONS (take data, return data)
  2. Transducers for ALL pipelines (no intermediate collections)
  3. Pipelines are composable data (testable, reusable)
  4. Context-independent (works with batch, streaming, channels)"
  (:require [datomic.api :as d]
            [taoensso.timbre :as log]
            [clojure.data.json :as json]
            [finance.orchestration.ml-pipeline :as ml-pipeline]))

;; ============================================================================
;; Transducers: Reusable Transformation Pipelines
;; ============================================================================

(def enrich-transaction
  "Transducer: Add denormalized fields for API response.

  Enriches transaction entities with human-readable names from refs.
  Context-independent: works with into, transduce, sequence, channels."
  (map (fn [tx]
         (cond-> tx
           (:transaction/bank tx)
           (assoc :bank-name (get-in tx [:transaction/bank :entity/canonical-name]))

           (:transaction/merchant tx)
           (assoc :merchant-name (get-in tx [:transaction/merchant :entity/canonical-name]))

           (:transaction/category tx)
           (assoc :category-name (get-in tx [:transaction/category :entity/canonical-name]))))))

(defn filter-by-type
  "Transducer factory: Filter by transaction type.

  Usage: (filter-by-type :GASTO)"
  [type]
  (filter #(= type (:transaction/type %))))

(defn filter-by-date-range
  "Transducer factory: Filter by date range.

  Usage: (filter-by-date-range from-date to-date)"
  [from-date to-date]
  (filter (fn [tx]
            (let [tx-date (:transaction/date tx)]
              (and (or (nil? from-date) (not (.before tx-date from-date)))
                   (or (nil? to-date) (not (.after tx-date to-date))))))))

(defn paginate
  "Transducer factory: Skip + take for pagination.

  Usage: (paginate 0 100) ; First 100 items"
  [offset limit]
  (comp
    (drop offset)
    (take limit)))

(defn build-response-pipeline
  "Build composable transformation pipeline for API responses.

  Returns transducer that can be used with:
  - (into [] xf data)
  - (transduce xf conj [] data)
  - (sequence xf data)
  - (async/pipeline n out xf in)

  Parameters:
  - type: Filter by transaction type (optional)
  - from-date: Start date (optional)
  - to-date: End date (optional)
  - offset: Skip N items (pagination)
  - limit: Take M items (pagination)

  Example:
    (into []
          (build-response-pipeline :GASTO nil nil 0 100)
          raw-transactions)
    ; Returns first 100 expense transactions, enriched, single pass"
  [{:keys [type from-date to-date offset limit]}]
  (cond-> (comp enrich-transaction)
    type       (comp (filter-by-type type))
    from-date  (comp (filter-by-date-range from-date to-date))
    true       (comp (paginate offset limit))))

;; ============================================================================
;; System Health
;; ============================================================================

(defn health-handler
  "Health check endpoint.

  Returns system status including:
  - API version
  - Datomic connection status
  - Timestamp

  Example response:
  {:status :healthy
   :version \"v1.0\"
   :timestamp \"2024-03-20T10:00:00Z\"
   :database {:connected true}}"
  [{:keys [conn] :as _request}]
  (let [db-status (try
                    (d/db conn)
                    {:connected true}
                    (catch Exception e
                      {:connected false
                       :error (.getMessage e)}))]
    {:status 200
     :body {:status (if (:connected db-status) :healthy :degraded)
            :version "v1.0"
            :timestamp (java.util.Date.)
            :database db-status}}))

;; ============================================================================
;; Transactions
;; ============================================================================

(defn list-transactions-handler
  "List all transactions with optional filters using transducers.

  Query params:
  - limit: Max results (default 100, max 1000)
  - offset: Skip N results (pagination)
  - type: Filter by transaction type (:GASTO, :INGRESO, etc.)
  - from-date: Start date (YYYY-MM-DD)
  - to-date: End date (YYYY-MM-DD)

  Example: GET /v1/transactions?limit=50&type=GASTO

  Performance: Uses transducers - single pass, no intermediate collections.
  Filtering + pagination + enrichment happens in ONE traversal."
  [{:keys [conn query-params] :as _request}]
  (let [db (d/db conn)

        ;; Parse params
        limit (min (parse-long (get query-params "limit" "100")) 1000)
        offset (parse-long (get query-params "offset" "0"))
        type (when-let [t (get query-params "type")]
               (keyword t))
        from-date (when-let [d (get query-params "from-date")]
                    (java.text.SimpleDateFormat. "yyyy-MM-dd"))
        to-date (when-let [d (get query-params "to-date")]
                  (java.text.SimpleDateFormat. "yyyy-MM-dd"))

        ;; Query Datomic - Pull full entities
        raw-txs (d/q '[:find [(pull ?e [* {:transaction/bank [:entity/canonical-name]
                                           :transaction/merchant [:entity/canonical-name]
                                           :transaction/category [:entity/canonical-name]}]) ...]
                       :where [?e :transaction/id]]
                     db)

        ;; Build transducer pipeline (NO data processed yet)
        pipeline (build-response-pipeline
                   {:type type
                    :from-date from-date
                    :to-date to-date
                    :offset offset
                    :limit limit})

        ;; Apply pipeline - SINGLE PASS through data!
        ;; Filters, enriches, and paginates in one traversal
        result (into [] pipeline raw-txs)

        ;; For total count, we need to count filtered (before pagination)
        ;; This is acceptable overhead - still single pass per count
        count-pipeline (build-response-pipeline
                         {:type type
                          :from-date from-date
                          :to-date to-date
                          :offset 0
                          :limit Integer/MAX_VALUE})
        total (transduce count-pipeline (completing (fn [n _] (inc n))) 0 raw-txs)]

    (log/info :event :list-transactions
              :count (count result)
              :total total
              :limit limit
              :offset offset
              :type type)

    {:status 200
     :body {:transactions result
            :count (count result)
            :total total
            :limit limit
            :offset offset
            :filters {:type type
                     :from-date from-date
                     :to-date to-date}}}))

(defn get-transaction-handler
  "Get a single transaction by ID.

  Path param:
  - id: Transaction entity ID

  Example: GET /v1/transactions/12345"
  [{:keys [conn path-params] :as _request}]
  (let [db (d/db conn)
        tx-id (parse-long (:id path-params))

        ;; Pull entity
        tx (d/pull db
                   '[:transaction/id
                     :transaction/date
                     :transaction/amount
                     :transaction/description
                     :transaction/type
                     :transaction/source-file
                     :transaction/source-line
                     :transaction/confidence
                     {:transaction/bank [:entity/id :entity/canonical-name]}
                     {:transaction/merchant [:entity/id :entity/canonical-name]}
                     {:transaction/category [:entity/id :entity/canonical-name]}]
                   tx-id)]

    (if (:transaction/id tx)
      (do
        (log/info :event :get-transaction :id tx-id)
        {:status 200
         :body {:transaction tx}})
      (do
        (log/warn :event :transaction-not-found :id tx-id)
        {:status 404
         :body {:error "Transaction not found"
                :id tx-id}}))))

;; ============================================================================
;; Statistics
;; ============================================================================

(defn stats-handler
  "Get system statistics.

  Returns:
  - Total transactions
  - Counts by type
  - Date range
  - Top merchants

  Example: GET /v1/stats"
  [{:keys [conn] :as _request}]
  (let [db (d/db conn)

        ;; Total count
        total (d/q '[:find (count ?e) .
                     :where [?e :transaction/id]]
                   db)

        ;; By type
        by-type (d/q '[:find ?type (count ?e)
                       :where
                       [?e :transaction/id]
                       [?e :transaction/type ?type]]
                     db)

        ;; Date range
        dates (d/q '[:find (min ?date) (max ?date)
                     :where [?e :transaction/date ?date]]
                   db)
        [min-date max-date] (first dates)]

    (log/info :event :get-stats :total total)

    {:status 200
     :body {:total total
            :by-type (into {} (map (fn [[type count]]
                                    [(name type) count])
                                  by-type))
            :date-range {:from min-date
                        :to max-date}}}))

;; ============================================================================
;; Rules
;; ============================================================================

(defn list-rules-handler
  "List all classification rules.

  Returns rules from resources/rules/merchant-rules.edn

  Example: GET /v1/rules"
  [_request]
  (try
    (let [rules (-> "rules/merchant-rules.edn"
                   clojure.java.io/resource
                   slurp
                   read-string)]
      {:status 200
       :body {:rules rules
              :count (count rules)}})
    (catch Exception e
      (log/error :event :rules-load-failed :error (.getMessage e))
      {:status 500
       :body {:error "Failed to load rules"
              :message (.getMessage e)}})))

;; ============================================================================
;; ML Classification & Review Queue (Phase 3)
;; ============================================================================

(defn classify-transaction-handler
  "Submit transaction for ML classification.

  Path param:
  - id: Transaction ID

  Returns:
  - 202 Accepted (queued for processing)

  Example: POST /v1/transactions/123/classify"
  [{:keys [conn path-params] :as _request}]
  (let [db (d/db conn)
        tx-id (parse-long (:id path-params))

        ;; Fetch transaction
        tx (d/pull db
                   '[:transaction/id
                     :transaction/date
                     :transaction/amount
                     :transaction/description]
                   tx-id)]

    (if (:transaction/id tx)
      (do
        (log/info :event :classify-transaction-request :id tx-id)

        ;; Submit to ML pipeline
        (ml-pipeline/submit-transaction-for-classification
          {:id (:transaction/id tx)
           :description (:transaction/description tx)
           :amount (:transaction/amount tx)
           :date (:transaction/date tx)})

        {:status 202
         :body {:message "Transaction queued for classification"
                :transaction-id tx-id}})
      (do
        (log/warn :event :transaction-not-found :id tx-id)
        {:status 404
         :body {:error "Transaction not found"
                :id tx-id}}))))

(defn get-review-queue-handler
  "Get all pending review queue items.

  Returns list of transactions awaiting human review.

  Example: GET /v1/review-queue"
  [{:keys [conn] :as _request}]
  (let [items (ml-pipeline/get-review-queue conn)]
    (log/info :event :get-review-queue :count (count items))

    {:status 200
     :body {:items items
            :count (count items)}}))

(defn approve-classification-handler
  "Approve ML classification (human decision).

  Path param:
  - id: Transaction ID

  Body:
  - merchant: Approved merchant name
  - category: Approved category name
  - approved-by: User email/ID

  Example: POST /v1/review-queue/123/approve
           Body: {\"merchant\": \"starbucks\", \"category\": \"cafe\", \"approved-by\": \"user@example.com\"}"
  [{:keys [path-params body-params] :as _request}]
  (let [tx-id (parse-long (:id path-params))
        merchant (get body-params "merchant")
        category (get body-params "category")
        approved-by (get body-params "approved-by")]

    (if (and merchant category approved-by)
      (do
        (log/info :event :approve-classification
                  :tx-id tx-id
                  :merchant merchant
                  :category category
                  :approved-by approved-by)

        ;; Submit approval to pipeline
        (ml-pipeline/approve-classification tx-id merchant category approved-by)

        {:status 200
         :body {:message "Classification approved"
                :transaction-id tx-id
                :merchant merchant
                :category category}})
      (do
        (log/warn :event :approve-classification-invalid-params
                  :tx-id tx-id)
        {:status 400
         :body {:error "Missing required fields"
                :required ["merchant" "category" "approved-by"]}}))))

(defn reject-classification-handler
  "Reject ML classification (human decision).

  Path param:
  - id: Transaction ID

  Body:
  - reason: Rejection reason
  - rejected-by: User email/ID

  Example: POST /v1/review-queue/123/reject
           Body: {\"reason\": \"Incorrect merchant\", \"rejected-by\": \"user@example.com\"}"
  [{:keys [path-params body-params] :as _request}]
  (let [tx-id (parse-long (:id path-params))
        reason (get body-params "reason")
        rejected-by (get body-params "rejected-by")]

    (if (and reason rejected-by)
      (do
        (log/info :event :reject-classification
                  :tx-id tx-id
                  :reason reason
                  :rejected-by rejected-by)

        ;; Submit rejection to pipeline
        (ml-pipeline/reject-classification tx-id reason rejected-by)

        {:status 200
         :body {:message "Classification rejected"
                :transaction-id tx-id
                :reason reason}})
      (do
        (log/warn :event :reject-classification-invalid-params
                  :tx-id tx-id)
        {:status 400
         :body {:error "Missing required fields"
                :required ["reason" "rejected-by"]}}))))

(defn correct-classification-handler
  "Submit corrected classification (human decision).

  Path param:
  - id: Transaction ID

  Body:
  - corrected-merchant: Corrected merchant name
  - corrected-category: Corrected category name
  - corrected-by: User email/ID

  Example: POST /v1/review-queue/123/correct
           Body: {\"corrected-merchant\": \"starbucks\", \"corrected-category\": \"cafe\", \"corrected-by\": \"user@example.com\"}"
  [{:keys [path-params body-params] :as _request}]
  (let [tx-id (parse-long (:id path-params))
        corrected-merchant (get body-params "corrected-merchant")
        corrected-category (get body-params "corrected-category")
        corrected-by (get body-params "corrected-by")]

    (if (and corrected-merchant corrected-category corrected-by)
      (do
        (log/info :event :correct-classification
                  :tx-id tx-id
                  :corrected-merchant corrected-merchant
                  :corrected-category corrected-category
                  :corrected-by corrected-by)

        ;; Submit correction to pipeline
        (ml-pipeline/correct-classification tx-id corrected-merchant corrected-category corrected-by)

        {:status 200
         :body {:message "Classification corrected"
                :transaction-id tx-id
                :corrected-merchant corrected-merchant
                :corrected-category corrected-category}})
      (do
        (log/warn :event :correct-classification-invalid-params
                  :tx-id tx-id)
        {:status 400
         :body {:error "Missing required fields"
                :required ["corrected-merchant" "corrected-category" "corrected-by"]}}))))

;; ============================================================================
;; Not Found
;; ============================================================================

(defn not-found-handler
  "404 handler for unknown routes.

  Note: Ring 1.9.6 doesn't auto-serialize maps to JSON, so we manually
  serialize the response body using clojure.data.json."
  [request]
  (log/warn :event :route-not-found :uri (:uri request))
  {:status 404
   :headers {"Content-Type" "application/json"}
   :body (json/write-str
           {:error "Route not found"
            :uri (:uri request)
            :method (name (:request-method request))})})
