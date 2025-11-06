(ns finance.clients.ml-service
  "HTTP client for Python ML Service.

  Phase 3: Integration
  Date: 2025-11-06

  Rich Hickey Pattern: MOVE + ROUTE service
  - Transports values to Python
  - Routes based on response (success/error)
  - Retries on failure (exponential backoff)
  - Circuit breaker pattern"
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [taoensso.timbre :as log]
            [clojure.core.async :as async]))

;; ============================================================================
;; Configuration
;; ============================================================================

(def ^:private ml-service-config
  {:url (or (System/getenv "ML_SERVICE_URL") "http://localhost:8000")
   :timeout 30000                       ; 30 seconds
   :max-retries 3
   :initial-retry-delay 1000            ; 1 second
   :circuit-breaker-threshold 5         ; Failures before opening circuit
   :circuit-breaker-timeout 60000})     ; 60 seconds before half-open

;; Circuit breaker state
(defonce ^:private circuit-state
  (atom {:state :closed                 ; :closed, :open, :half-open
         :failure-count 0
         :last-failure-time nil}))

;; ============================================================================
;; Circuit Breaker
;; ============================================================================

(defn- reset-circuit!
  "Reset circuit breaker to closed state."
  []
  (log/info :event :circuit-breaker-reset)
  (swap! circuit-state assoc
         :state :closed
         :failure-count 0
         :last-failure-time nil))

(defn- record-success!
  "Record successful request (reset failure count)."
  []
  (when (> (:failure-count @circuit-state) 0)
    (log/info :event :circuit-breaker-success-after-failures
              :previous-failures (:failure-count @circuit-state))
    (reset-circuit!)))

(defn- record-failure!
  "Record failed request (increment failure count, maybe open circuit)."
  []
  (let [new-state (swap! circuit-state update :failure-count inc)
        failures (:failure-count new-state)
        threshold (:circuit-breaker-threshold ml-service-config)]

    (when (>= failures threshold)
      (log/warn :event :circuit-breaker-opened
                :failure-count failures
                :threshold threshold)
      (swap! circuit-state assoc
             :state :open
             :last-failure-time (System/currentTimeMillis)))))

(defn- circuit-open?
  "Check if circuit is open (should reject requests)."
  []
  (let [{:keys [state last-failure-time]} @circuit-state
        timeout (:circuit-breaker-timeout ml-service-config)]

    (case state
      :closed false
      :open (if (and last-failure-time
                     (> (- (System/currentTimeMillis) last-failure-time) timeout))
              ;; Transition to half-open
              (do
                (log/info :event :circuit-breaker-half-open)
                (swap! circuit-state assoc :state :half-open)
                false)
              true)
      :half-open false)))

;; ============================================================================
;; Retry Logic
;; ============================================================================

(defn- retry-with-backoff
  "Retry function with exponential backoff.

  Args:
    f: Function to retry (should return {:success true/false ...})
    max-attempts: Maximum number of attempts
    initial-delay: Initial delay in ms

  Returns:
    Result from f or {:success false :error ...}"
  [f max-attempts initial-delay]
  (loop [attempt 1
         delay initial-delay]
    (let [result (f)]
      (if (:success result)
        result
        (if (< attempt max-attempts)
          (do
            (log/warn :event :ml-service-retry
                      :attempt attempt
                      :max-attempts max-attempts
                      :next-delay-ms delay)
            (Thread/sleep delay)
            (recur (inc attempt) (* 2 delay)))  ; Exponential backoff
          (do
            (log/error :event :ml-service-all-retries-failed
                       :attempts max-attempts)
            result))))))

;; ============================================================================
;; HTTP Helpers
;; ============================================================================

(defn- make-request
  "Make HTTP request to ML service.

  Pure MOVE service:
  - Transports value to Python
  - Returns new value (or error)
  - NO state changes (except circuit breaker)

  Args:
    endpoint: API endpoint (e.g. \"/v1/detect/merchant\")
    payload: Request body (will be JSON-encoded)

  Returns:
    {:success true :data <response>} or {:success false :error <msg>}"
  [endpoint payload]
  (try
    (let [url (str (:url ml-service-config) endpoint)
          response (http/post url
                              {:body (json/write-str payload)
                               :content-type :json
                               :accept :json
                               :socket-timeout (:timeout ml-service-config)
                               :conn-timeout (:timeout ml-service-config)
                               :throw-exceptions false})]

      (if (= 200 (:status response))
        (do
          (record-success!)
          {:success true
           :data (json/read-str (:body response) :key-fn keyword)})
        (do
          (record-failure!)
          {:success false
           :error (str "HTTP " (:status response))
           :status (:status response)
           :body (:body response)})))

    (catch Exception e
      (record-failure!)
      {:success false
       :error (.getMessage e)
       :exception (class e)})))

(defn- call-ml-service
  "Call ML service with retry + circuit breaker.

  Args:
    endpoint: API endpoint
    payload: Request payload

  Returns:
    {:success true :data ...} or {:success false :error ...}"
  [endpoint payload]
  ;; Check circuit breaker
  (if (circuit-open?)
    (do
      (log/warn :event :circuit-breaker-rejected-request
                :endpoint endpoint)
      {:success false
       :error "Circuit breaker open"
       :circuit-state (:state @circuit-state)})

    ;; Make request with retries
    (retry-with-backoff
     #(make-request endpoint payload)
     (:max-retries ml-service-config)
     (:initial-retry-delay ml-service-config))))

;; ============================================================================
;; Public API - TRANSFORM Calls
;; ============================================================================

(defn detect-merchant
  "Detect merchant from transaction using Python ML service.

  TRANSFORM pattern:
  - Input: Transaction map
  - Output: MerchantDetection map (or error)

  Args:
    tx: Transaction map with :id, :description, :amount, :date

  Returns:
    {:success true :merchant ... :confidence ...} or {:success false :error ...}"
  [tx]
  (log/info :event :merchant-detection-request
            :transaction-id (:id tx))

  (let [payload {:id (:id tx)
                 :description (:description tx)
                 :amount (:amount tx)
                 :date (str (:date tx))}

        result (call-ml-service "/v1/detect/merchant" payload)]

    (if (:success result)
      (do
        (log/info :event :merchant-detection-success
                  :transaction-id (:id tx)
                  :merchant (get-in result [:data :merchant])
                  :confidence (get-in result [:data :confidence]))
        result)
      (do
        (log/error :event :merchant-detection-failed
                   :transaction-id (:id tx)
                   :error (:error result))
        result))))

(defn detect-category
  "Detect category from transaction + merchant.

  Args:
    tx: Transaction map
    merchant: Merchant string

  Returns:
    {:success true :category ... :confidence ...} or {:success false :error ...}"
  [tx merchant]
  (log/info :event :category-detection-request
            :transaction-id (:id tx)
            :merchant merchant)

  (let [payload {:id (:id tx)
                 :description (:description tx)
                 :amount (:amount tx)
                 :date (str (:date tx))}

        endpoint (str "/v1/detect/category?merchant=" merchant)
        result (call-ml-service endpoint payload)]

    (if (:success result)
      (log/info :event :category-detection-success
                :transaction-id (:id tx)
                :category (get-in result [:data :category]))
      (log/error :event :category-detection-failed
                 :transaction-id (:id tx)
                 :error (:error result)))
    result))

(defn detect-anomaly
  "Detect anomaly for transaction.

  Args:
    tx: Transaction map
    historical-amounts: Vector of historical amounts

  Returns:
    {:success true :is_anomaly ... :anomaly_score ...} or {:success false :error ...}"
  [tx historical-amounts]
  (log/info :event :anomaly-detection-request
            :transaction-id (:id tx)
            :historical-count (count historical-amounts))

  (let [payload {:transaction {:id (:id tx)
                               :description (:description tx)
                               :amount (:amount tx)
                               :date (str (:date tx))}
                 :historical_amounts historical-amounts}

        result (call-ml-service "/v1/detect/anomaly" payload)]

    (if (:success result)
      (log/info :event :anomaly-detection-success
                :transaction-id (:id tx)
                :is-anomaly (get-in result [:data :is_anomaly]))
      (log/error :event :anomaly-detection-failed
                 :transaction-id (:id tx)
                 :error (:error result)))
    result))

(defn health-check
  "Check if ML service is healthy.

  Returns:
    {:success true :status ...} or {:success false :error ...}"
  []
  (let [result (call-ml-service "/v1/health" {})]
    (if (:success result)
      (log/info :event :ml-service-health-check-ok
                :status (get-in result [:data :status]))
      (log/warn :event :ml-service-health-check-failed
                :error (:error result)))
    result))

;; ============================================================================
;; Circuit Breaker Info
;; ============================================================================

(defn get-circuit-state
  "Get current circuit breaker state (for monitoring).

  Returns:
    {:state :closed/:open/:half-open
     :failure-count N
     :last-failure-time timestamp}"
  []
  @circuit-state)
