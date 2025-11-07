(ns finance.orchestration.ml-pipeline
  "ML Detection Pipeline with core.async orchestration.

  Phase 3: Integration
  Date: 2025-11-06

  Rich Hickey Pattern: ROUTE service
  - Coordinates value flow through async channels
  - Uses transducers for efficient single-pass processing
  - Routes to human review queue for approval
  - Stores approved facts in Datomic

  Value Flow:
  Transaction → ML Detection → Review Queue → Human Decision → Store Facts"
  (:require [clojure.core.async :as async :refer [<! >! go go-loop chan pipeline-async]]
            [datomic.api :as d]
            [finance.clients.ml-service :as ml]
            [taoensso.timbre :as log]))

;; ============================================================================
;; Pipeline Channels
;; ============================================================================

;; Global channels for ML detection pipeline.
;; Rich Hickey: Queues decouple producers from consumers
(defonce pipeline-channels
  {:transactions (chan 100)          ; Input: transactions to classify
   :ml-results (chan 100)            ; ML detection results
   :review-queue (chan 1000)         ; Pending human review
   :approved (chan 100)              ; Human-approved classifications
   :rejected (chan 100)              ; Human-rejected classifications
   :corrections (chan 100)})         ; Human corrections

;; ============================================================================
;; Pipeline Helpers
;; ============================================================================

(defn get-historical-amounts
  "Fetch historical transaction amounts for a merchant.

  Args:
    conn: Datomic connection
    merchant: Merchant canonical name

  Returns:
    Vector of amounts [45.99 120.50 ...]"
  [conn merchant]
  (try
    (let [db (d/db conn)
          query '[:find [?amount ...]
                  :in $ ?merchant
                  :where
                  [?tx :transaction/merchant ?m]
                  [?m :entity/canonical-name ?merchant]
                  [?tx :transaction/amount ?amount]]
          amounts (d/q query db merchant)]
      (vec amounts))
    (catch Exception e
      (log/warn :event :historical-amounts-fetch-failed
                :merchant merchant
                :error (.getMessage e))
      [])))  ; Return empty if fetch fails

;; ============================================================================
;; Transducers (Context-Free Transformations)
;; ============================================================================

(defn enrich-with-merchant-detection
  "Transducer: Add merchant detection to transaction.

  Input:  {:transaction {...}}
  Output: {:transaction {...} :merchant-detection {...}}"
  []
  (map (fn [{:keys [transaction] :as ctx}]
         (log/info :event :merchant-detection-start
                   :tx-id (:id transaction))
         (let [result (ml/detect-merchant transaction)]
           (if (:success result)
             (assoc ctx :merchant-detection (:data result))
             (do
               (log/error :event :merchant-detection-failed
                          :tx-id (:id transaction)
                          :error (:error result))
               (assoc ctx :merchant-detection
                      {:merchant "UNKNOWN"
                       :confidence 0.0
                       :error (:error result)})))))))

(defn enrich-with-category-detection
  "Transducer: Add category detection to transaction.

  Input:  {:transaction {...} :merchant-detection {...}}
  Output: {:transaction {...} :merchant-detection {...} :category-detection {...}}"
  []
  (map (fn [{:keys [transaction merchant-detection] :as ctx}]
         (log/info :event :category-detection-start
                   :tx-id (:id transaction)
                   :merchant (:merchant merchant-detection))
         (let [merchant (:merchant merchant-detection)
               result (ml/detect-category transaction merchant)]
           (if (:success result)
             (assoc ctx :category-detection (:data result))
             (do
               (log/error :event :category-detection-failed
                          :tx-id (:id transaction)
                          :error (:error result))
               (assoc ctx :category-detection
                      {:category "UNKNOWN"
                       :confidence 0.0
                       :error (:error result)})))))))

(defn enrich-with-anomaly-detection
  "Transducer: Add anomaly detection to transaction.

  Input:  {:transaction {...} :merchant-detection {...} :category-detection {...}}
  Output: {:transaction {...} :merchant-detection {...} :category-detection {...} :anomaly-detection {...}}

  Note: Requires historical amounts for same merchant (fetched from Datomic)"
  [conn]
  (map (fn [{:keys [transaction merchant-detection] :as ctx}]
         (log/info :event :anomaly-detection-start
                   :tx-id (:id transaction))
         (let [merchant (:merchant merchant-detection)
               ;; Fetch historical amounts for this merchant
               historical-amounts (get-historical-amounts conn merchant)
               result (ml/detect-anomaly transaction historical-amounts)]
           (if (:success result)
             (assoc ctx :anomaly-detection (:data result))
             (do
               (log/error :event :anomaly-detection-failed
                          :tx-id (:id transaction)
                          :error (:error result))
               (assoc ctx :anomaly-detection
                      {:is_anomaly false
                       :anomaly_score 0.0
                       :confidence 0.0
                       :error (:error result)})))))))

(defn route-to-review-queue
  "Transducer: Route low-confidence results to human review.

  High confidence (>= 0.90): Auto-approve
  Medium confidence (0.70-0.89): Review queue
  Low confidence (< 0.70): Review queue (flagged)"
  [review-queue-chan]
  (map (fn [{:keys [transaction merchant-detection category-detection] :as ctx}]
         (let [merchant-conf (:confidence merchant-detection)
               category-conf (:confidence category-detection)
               min-conf (min merchant-conf category-conf)]
           (cond
             ;; High confidence: auto-approve
             (>= min-conf 0.90)
             (do
               (log/info :event :auto-approved
                         :tx-id (:id transaction)
                         :merchant-conf merchant-conf
                         :category-conf category-conf)
               (assoc ctx :review-status :auto-approved))

             ;; Medium/Low confidence: needs review
             :else
             (do
               (log/info :event :needs-review
                         :tx-id (:id transaction)
                         :min-confidence min-conf)
               (go (>! review-queue-chan ctx))  ; Send to review queue
               (assoc ctx :review-status :pending-review)))))))

;; ============================================================================
;; Pipeline Workers
;; ============================================================================

(defn start-detection-pipeline
  "Start async pipeline for ML detection.

  Pipeline stages (using transducers):
  1. Enrich with merchant detection (LLM)
  2. Enrich with category detection (rules + LLM)
  3. Enrich with anomaly detection (statistical)
  4. Route to review queue or auto-approve

  Args:
    conn: Datomic connection

  Returns:
    nil (runs in background)"
  [conn]
  (let [{:keys [transactions ml-results review-queue]} pipeline-channels
        ;; Compose transducers (single-pass processing!)
        xform (comp
               (enrich-with-merchant-detection)
               (enrich-with-category-detection)
               (enrich-with-anomaly-detection conn)
               (route-to-review-queue review-queue))]

    (log/info :event :detection-pipeline-started)

    ;; Process transactions through pipeline
    ;; Note: Using thread (not go) to allow try/catch for error handling
    ;; Rich Hickey: thread is appropriate for I/O operations and error handling
    (async/thread
      (loop []
        (when-let [transaction (async/<!! transactions)]
          (try
            (log/info :event :pipeline-processing
                      :tx-id (:id transaction))

            ;; Apply full transformation pipeline
            (let [ctx {:transaction transaction}
                  result (sequence xform [ctx])]
              (async/>!! ml-results (first result)))

            (catch Exception e
              (log/error :event :pipeline-error
                         :tx-id (:id transaction)
                         :error (.getMessage e))))
          (recur))))))

(defn start-review-queue-processor
  "Process review queue - stores pending items in Datomic.

  Creates review-queue entities that UI can query for display.

  Args:
    conn: Datomic connection

  Returns:
    nil (runs in background)"
  [conn]
  (let [{:keys [review-queue]} pipeline-channels]
    (log/info :event :review-queue-processor-started)

    ;; Note: Using thread (not go) to allow try/catch for error handling
    (async/thread
      (loop []
        (when-let [ctx (async/<!! review-queue)]
          (try
            (let [{:keys [transaction merchant-detection category-detection
                          anomaly-detection]} ctx
                  tx-id (:id transaction)]

              (log/info :event :review-queue-item-received
                        :tx-id tx-id)

              ;; Store in review queue (Datomic)
              (d/transact conn
                [{:review-queue/transaction-id tx-id
                  :review-queue/transaction (pr-str transaction)
                  :review-queue/merchant-detection (pr-str merchant-detection)
                  :review-queue/category-detection (pr-str category-detection)
                  :review-queue/anomaly-detection (pr-str anomaly-detection)
                  :review-queue/status :pending
                  :review-queue/created-at (java.util.Date.)}])

              (log/info :event :review-queue-item-stored
                        :tx-id tx-id))

            (catch Exception e
              (log/error :event :review-queue-processor-error
                         :error (.getMessage e))))
          (recur))))))

(defn start-approval-processor
  "Process approved classifications - stores as facts in Datomic.

  Args:
    conn: Datomic connection

  Returns:
    nil (runs in background)"
  [conn]
  (let [{:keys [approved]} pipeline-channels]
    (log/info :event :approval-processor-started)

    ;; Note: Using thread (not go) to allow try/catch for error handling
    (async/thread
      (loop []
        (when-let [{:keys [transaction-id merchant category approved-by]} (async/<!! approved)]
          (try
            (log/info :event :classification-approved
                      :tx-id transaction-id
                      :merchant merchant
                      :category category
                      :approved-by approved-by)

            ;; Store approved fact (following Human-in-the-Loop pattern)
            (d/transact conn
              [{:db/id [:transaction/id transaction-id]
                :transaction/merchant [:entity/canonical-name merchant]
                :transaction/category [:entity/canonical-name category]
                :transaction/classification-approved? true
                :transaction/classified-by approved-by
                :transaction/classified-at (java.util.Date.)}

               ;; Record event
               {:event/type :classification-approved
                :event/transaction-id transaction-id
                :event/merchant merchant
                :event/category category
                :event/approved-by approved-by
                :event/timestamp (java.util.Date.)}

               ;; Update review queue
               {:db/id [:review-queue/transaction-id transaction-id]
                :review-queue/status :approved
                :review-queue/resolved-at (java.util.Date.)
                :review-queue/resolved-by approved-by}])

            (log/info :event :approval-stored
                      :tx-id transaction-id)

            (catch Exception e
              (log/error :event :approval-processor-error
                         :tx-id transaction-id
                         :error (.getMessage e))))
          (recur))))))

(defn start-rejection-processor
  "Process rejected classifications - records decision in Datomic.

  Args:
    conn: Datomic connection

  Returns:
    nil (runs in background)"
  [conn]
  (let [{:keys [rejected]} pipeline-channels]
    (log/info :event :rejection-processor-started)

    ;; Note: Using thread (not go) to allow try/catch for error handling
    (async/thread
      (loop []
        (when-let [{:keys [transaction-id reason rejected-by]} (async/<!! rejected)]
          (try
            (log/info :event :classification-rejected
                      :tx-id transaction-id
                      :reason reason
                      :rejected-by rejected-by)

            ;; Record rejection event
            (d/transact conn
              [{:event/type :classification-rejected
                :event/transaction-id transaction-id
                :event/reason reason
                :event/rejected-by rejected-by
                :event/timestamp (java.util.Date.)}

               ;; Update review queue
               {:db/id [:review-queue/transaction-id transaction-id]
                :review-queue/status :rejected
                :review-queue/resolved-at (java.util.Date.)
                :review-queue/resolved-by rejected-by}])

            (log/info :event :rejection-stored
                      :tx-id transaction-id)

            (catch Exception e
              (log/error :event :rejection-processor-error
                         :tx-id transaction-id
                         :error (.getMessage e))))
          (recur))))))

(defn start-correction-processor
  "Process human corrections - stores corrected classification.

  Args:
    conn: Datomic connection

  Returns:
    nil (runs in background)"
  [conn]
  (let [{:keys [corrections]} pipeline-channels]
    (log/info :event :correction-processor-started)

    ;; Note: Using thread (not go) to allow try/catch for error handling
    (async/thread
      (loop []
        (when-let [{:keys [transaction-id corrected-merchant corrected-category
                           corrected-by]} (async/<!! corrections)]
          (try
            (log/info :event :classification-corrected
                      :tx-id transaction-id
                      :corrected-merchant corrected-merchant
                      :corrected-category corrected-category
                      :corrected-by corrected-by)

            ;; Store corrected classification
            (d/transact conn
              [{:db/id [:transaction/id transaction-id]
                :transaction/merchant [:entity/canonical-name corrected-merchant]
                :transaction/category [:entity/canonical-name corrected-category]
                :transaction/classification-approved? true
                :transaction/classification-corrected? true
                :transaction/classified-by corrected-by
                :transaction/classified-at (java.util.Date.)}

               ;; Record correction event
               {:event/type :classification-corrected
                :event/transaction-id transaction-id
                :event/corrected-merchant corrected-merchant
                :event/corrected-category corrected-category
                :event/corrected-by corrected-by
                :event/timestamp (java.util.Date.)}

               ;; Update review queue
               {:db/id [:review-queue/transaction-id transaction-id]
                :review-queue/status :corrected
                :review-queue/resolved-at (java.util.Date.)
                :review-queue/resolved-by corrected-by}])

            (log/info :event :correction-stored
                      :tx-id transaction-id)

            (catch Exception e
              (log/error :event :correction-processor-error
                         :tx-id transaction-id
                         :error (.getMessage e))))
          (recur))))))

;; ============================================================================
;; Public API
;; ============================================================================

(defn start-pipeline!
  "Start all pipeline workers.

  Starts:
  1. Detection pipeline (ML enrichment)
  2. Review queue processor
  3. Approval processor
  4. Rejection processor
  5. Correction processor

  Args:
    conn: Datomic connection

  Returns:
    nil"
  [conn]
  (log/info :event :pipeline-starting)

  ;; Start all workers
  (start-detection-pipeline conn)
  (start-review-queue-processor conn)
  (start-approval-processor conn)
  (start-rejection-processor conn)
  (start-correction-processor conn)

  (log/info :event :pipeline-started
            :message "All workers started successfully"))

(defn submit-transaction-for-classification
  "Submit transaction for ML classification.

  Args:
    transaction: Transaction map with :id, :description, :amount, :date

  Returns:
    true (transaction queued for processing)"
  [transaction]
  (log/info :event :transaction-submitted
            :tx-id (:id transaction))

  (go (>! (:transactions pipeline-channels) transaction))
  true)

(defn approve-classification
  "Approve ML classification (human decision).

  Args:
    transaction-id: Transaction ID
    merchant: Approved merchant name
    category: Approved category name
    approved-by: User email/ID

  Returns:
    true (approval queued for processing)"
  [transaction-id merchant category approved-by]
  (log/info :event :approval-submitted
            :tx-id transaction-id
            :approved-by approved-by)

  (go (>! (:approved pipeline-channels)
          {:transaction-id transaction-id
           :merchant merchant
           :category category
           :approved-by approved-by}))
  true)

(defn reject-classification
  "Reject ML classification (human decision).

  Args:
    transaction-id: Transaction ID
    reason: Rejection reason
    rejected-by: User email/ID

  Returns:
    true (rejection queued for processing)"
  [transaction-id reason rejected-by]
  (log/info :event :rejection-submitted
            :tx-id transaction-id
            :rejected-by rejected-by)

  (go (>! (:rejected pipeline-channels)
          {:transaction-id transaction-id
           :reason reason
           :rejected-by rejected-by}))
  true)

(defn correct-classification
  "Submit corrected classification (human decision).

  Args:
    transaction-id: Transaction ID
    corrected-merchant: Corrected merchant name
    corrected-category: Corrected category name
    corrected-by: User email/ID

  Returns:
    true (correction queued for processing)"
  [transaction-id corrected-merchant corrected-category corrected-by]
  (log/info :event :correction-submitted
            :tx-id transaction-id
            :corrected-by corrected-by)

  (go (>! (:corrections pipeline-channels)
          {:transaction-id transaction-id
           :corrected-merchant corrected-merchant
           :corrected-category corrected-category
           :corrected-by corrected-by}))
  true)

(defn get-review-queue
  "Get all pending review queue items.

  Args:
    conn: Datomic connection

  Returns:
    Vector of review queue items"
  [conn]
  (let [db (d/db conn)
        query '[:find [(pull ?e [*]) ...]
                :where
                [?e :review-queue/status :pending]]
        results (d/q query db)]
    results))

(defn shutdown-pipeline!
  "Shutdown all pipeline workers.

  Closes all channels gracefully."
  []
  (log/info :event :pipeline-shutting-down)

  (doseq [[name ch] pipeline-channels]
    (log/info :event :channel-closing :channel name)
    (async/close! ch))

  (log/info :event :pipeline-shutdown-complete))
