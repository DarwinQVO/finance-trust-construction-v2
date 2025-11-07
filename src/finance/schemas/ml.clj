(ns finance.schemas.ml
  "Malli schemas for ML pipeline stages, confidence tracking, and anomaly detection.

  Philosophy (Rich Hickey aligned):
  - ML pipeline is a SERIES of transformations on immutable data
  - Each stage adds context (never mutates)
  - Confidence scores are explicit (not hidden in code)
  - Errors are data, not exceptions (when possible)

  Pipeline flow:
    Transaction → Context → ML Detection → Confidence Check → Review Queue

  Each stage validates inputs/outputs with these schemas.
  "
  (:require [malli.core :as m]
            [malli.error :as me]
            [finance.schemas.core :as core]))

;;; ============================================================================
;;; ML Pipeline Context (Data flowing through pipeline)
;;; ============================================================================

(def PipelineContext
  "Context object flowing through ML pipeline.

  Rich Hickey: Context accumulates information, never loses it.
  Each stage adds to context without mutating previous data.

  Stages:
  1. Start: {:transaction tx}
  2. After merchant detection: {:transaction tx :merchant-detection md}
  3. After category detection: {:transaction tx :merchant-detection md :category-detection cd}
  4. After anomaly detection: {:transaction tx ... :anomaly-detection ad}

  Example:
    {:transaction {:id \"123...\" :date \"2024-11-06\" ...}
     :merchant-detection {:canonical-name \"Starbucks\" :confidence 0.95}
     :category-detection {:name \"Coffee & Tea\" :confidence 0.90}
     :anomaly-detection {:is-anomaly false :confidence 0.85}}
  "
  [:map
   ;; Initial data
   [:transaction core/Transaction]

   ;; ML detection results (added as pipeline progresses)
   [:merchant-detection {:optional true}
    [:map
     [:canonical-name core/NonEmptyString]
     [:confidence core/Confidence]
     [:method {:optional true} core/NonEmptyString]
     [:alternatives {:optional true}
      [:vector [:map
                [:name core/NonEmptyString]
                [:confidence core/Confidence]]]]]]

   [:category-detection {:optional true}
    [:map
     [:name core/NonEmptyString]
     [:confidence core/Confidence]
     [:rule-id {:optional true} core/NonEmptyString]
     [:alternatives {:optional true}
      [:vector [:map
                [:name core/NonEmptyString]
                [:confidence core/Confidence]]]]]]

   [:anomaly-detection {:optional true}
    [:map
     [:is-anomaly :boolean]
     [:confidence core/Confidence]
     [:z-score {:optional true} :double]
     [:reason {:optional true} core/NonEmptyString]]]

   ;; Pipeline metadata
   [:pipeline-stage {:optional true} [:enum :start :merchant :category :anomaly :complete]]
   [:processing-time-ms {:optional true} :int]
   [:errors {:optional true} [:vector :string]]])

;;; ============================================================================
;;; Historical Data Schemas (For anomaly detection)
;;; ============================================================================

(def HistoricalAmounts
  "Historical transaction amounts for a merchant.

  Used for anomaly detection (Z-score calculation).

  Example:
    {:merchant \"Starbucks\"
     :amounts [42.50 43.00 45.99 46.50 47.00]
     :count 5
     :mean 45.00
     :std-dev 1.87}
  "
  [:map
   [:merchant core/NonEmptyString]
   [:amounts [:vector :double]]
   [:count :int]
   [:mean {:optional true} :double]
   [:std-dev {:optional true} :double]
   [:min {:optional true} :double]
   [:max {:optional true} :double]])

;;; ============================================================================
;;; Confidence Thresholds (Configuration as data)
;;; ============================================================================

(def ConfidenceThresholds
  "Confidence thresholds for auto-approval vs manual review.

  Rich Hickey: Configuration is DATA, not code.

  Example:
    {:high-confidence 0.90    ; Auto-approve if >= 0.90
     :medium-confidence 0.70  ; Flag for review if 0.70-0.89
     :low-confidence 0.60}    ; Reject if < 0.60
  "
  [:map
   [:high-confidence [:double {:min 0.0 :max 1.0}]]
   [:medium-confidence [:double {:min 0.0 :max 1.0}]]
   [:low-confidence [:double {:min 0.0 :max 1.0}]]

   ;; Validation: high > medium > low
   [:fn {:error/message "Thresholds must be high > medium > low"}
    (fn [{:keys [high-confidence medium-confidence low-confidence]}]
      (and (> high-confidence medium-confidence)
           (> medium-confidence low-confidence)))]])

(def default-confidence-thresholds
  "Default confidence thresholds (can be overridden)."
  {:high-confidence 0.90
   :medium-confidence 0.70
   :low-confidence 0.60})

;;; ============================================================================
;;; Anomaly Detection Schemas
;;; ============================================================================

(def AnomalyConfig
  "Configuration for anomaly detection.

  Example:
    {:z-score-threshold 2.5  ; Flag if |z-score| > 2.5
     :min-historical-samples 5  ; Need at least 5 samples
     :enabled true}
  "
  [:map
   [:z-score-threshold {:optional true} [:double {:min 0.0}]]
   [:min-historical-samples {:optional true} [:int {:min 1}]]
   [:enabled {:optional true} :boolean]])

(def default-anomaly-config
  "Default anomaly detection config."
  {:z-score-threshold 2.5
   :min-historical-samples 5
   :enabled true})

(def AnomalyResult
  "Result of anomaly detection.

  Example:
    {:is-anomaly true
     :confidence 0.85
     :z-score 3.2
     :reason \"Amount is 3.2 std deviations above mean\"
     :historical-mean 45.00
     :historical-std-dev 10.00
     :current-amount 77.00}
  "
  [:map
   [:is-anomaly :boolean]
   [:confidence core/Confidence]
   [:z-score {:optional true} :double]
   [:reason {:optional true} core/NonEmptyString]
   [:historical-mean {:optional true} :double]
   [:historical-std-dev {:optional true} :double]
   [:current-amount {:optional true} :double]])

;;; ============================================================================
;;; Circuit Breaker Schemas (Resilience patterns)
;;; ============================================================================

(def CircuitBreakerState
  "State of circuit breaker for ML service.

  Rich Hickey: State is explicit, not hidden in objects.

  States:
  - :closed → Normal operation
  - :open → Service unavailable, fail fast
  - :half-open → Testing if service recovered

  Example:
    {:state :closed
     :failure-count 0
     :last-failure-at nil
     :last-success-at \"2024-11-06T10:30:00Z\"}
  "
  [:map
   [:state [:enum :closed :open :half-open]]
   [:failure-count :int]
   [:last-failure-at {:optional true} core/ISODateString]
   [:last-success-at {:optional true} core/ISODateString]
   [:opened-at {:optional true} core/ISODateString]
   [:half-open-at {:optional true} core/ISODateString]])

(def CircuitBreakerConfig
  "Configuration for circuit breaker.

  Example:
    {:failure-threshold 5  ; Open after 5 failures
     :timeout-ms 60000     ; Stay open for 60 seconds
     :half-open-requests 3 ; Allow 3 requests in half-open}
  "
  [:map
   [:failure-threshold [:int {:min 1}]]
   [:timeout-ms [:int {:min 1000}]]
   [:half-open-requests [:int {:min 1}]]])

(def default-circuit-breaker-config
  "Default circuit breaker config."
  {:failure-threshold 5
   :timeout-ms 60000
   :half-open-requests 3})

;;; ============================================================================
;;; Retry Policy Schemas
;;; ============================================================================

(def RetryPolicy
  "Retry policy for ML service calls.

  Rich Hickey: Policies are DATA, not code.

  Example:
    {:max-attempts 3
     :initial-delay-ms 1000
     :max-delay-ms 10000
     :backoff-multiplier 2.0
     :retryable-errors [:timeout :connection-refused :503]}
  "
  [:map
   [:max-attempts [:int {:min 1 :max 10}]]
   [:initial-delay-ms [:int {:min 100}]]
   [:max-delay-ms [:int {:min 1000}]]
   [:backoff-multiplier [:double {:min 1.0 :max 10.0}]]
   [:retryable-errors [:vector [:enum :timeout :connection-refused :500 :503 :504]]]])

(def default-retry-policy
  "Default retry policy."
  {:max-attempts 3
   :initial-delay-ms 1000
   :max-delay-ms 10000
   :backoff-multiplier 2.0
   :retryable-errors [:timeout :connection-refused :503]})

;;; ============================================================================
;;; ML Pipeline Configuration (Complete config as data)
;;; ============================================================================

(def MLPipelineConfig
  "Complete ML pipeline configuration.

  Rich Hickey: All configuration in one place, as DATA.

  Example:
    {:confidence-thresholds {...}
     :anomaly-detection {...}
     :circuit-breaker {...}
     :retry-policy {...}
     :ml-service-url \"http://localhost:8000\"
     :timeout-ms 5000}
  "
  [:map
   [:confidence-thresholds ConfidenceThresholds]
   [:anomaly-detection AnomalyConfig]
   [:circuit-breaker CircuitBreakerConfig]
   [:retry-policy RetryPolicy]
   [:ml-service-url [:string {:pattern #"^https?://.*"}]]
   [:timeout-ms [:int {:min 1000 :max 60000}]]])

(def default-ml-pipeline-config
  "Default ML pipeline configuration."
  {:confidence-thresholds default-confidence-thresholds
   :anomaly-detection default-anomaly-config
   :circuit-breaker default-circuit-breaker-config
   :retry-policy default-retry-policy
   :ml-service-url "http://localhost:8000"
   :timeout-ms 5000})

;;; ============================================================================
;;; Pipeline Stage Results (For testing/debugging)
;;; ============================================================================

(def StageResult
  "Result of a single pipeline stage.

  Rich Hickey: Make errors explicit as data.

  Example:
    {:stage :merchant-detection
     :success true
     :context {...}  ; Updated context
     :duration-ms 150
     :error nil}

  Or on failure:
    {:stage :merchant-detection
     :success false
     :context {...}  ; Original context
     :duration-ms 150
     :error {:type :timeout
             :message \"ML service timed out after 5000ms\"}}
  "
  [:map
   [:stage [:enum :merchant :category :anomaly :confidence-check :review-queue]]
   [:success :boolean]
   [:context PipelineContext]
   [:duration-ms :int]
   [:error {:optional true}
    [:map
     [:type [:enum :timeout :connection-error :validation-error :circuit-breaker-open :unknown]]
     [:message core/NonEmptyString]
     [:details {:optional true} :any]]]])

;;; ============================================================================
;;; ML Service Health (Monitoring)
;;; ============================================================================

(def MLServiceHealth
  "Health status of ML service.

  Example:
    {:status :healthy
     :uptime-ms 1234567
     :total-requests 100
     :successful-requests 95
     :failed-requests 5
     :average-latency-ms 150
     :circuit-breaker-state :closed
     :last-error nil}
  "
  [:map
   [:status [:enum :healthy :degraded :unhealthy]]
   [:uptime-ms [:int {:min 0}]]
   [:total-requests [:int {:min 0}]]
   [:successful-requests [:int {:min 0}]]
   [:failed-requests [:int {:min 0}]]
   [:average-latency-ms [:double {:min 0.0}]]
   [:circuit-breaker-state [:enum :closed :open :half-open]]
   [:last-error {:optional true}
    [:map
     [:timestamp core/ISODateString]
     [:message core/NonEmptyString]]]])

;;; ============================================================================
;;; Exports (Public API)
;;; ============================================================================

(def schemas
  "Map of all ML schemas for easy access."
  {:pipeline-context PipelineContext
   :historical-amounts HistoricalAmounts
   :confidence-thresholds ConfidenceThresholds
   :anomaly-config AnomalyConfig
   :anomaly-result AnomalyResult
   :circuit-breaker-state CircuitBreakerState
   :circuit-breaker-config CircuitBreakerConfig
   :retry-policy RetryPolicy
   :ml-pipeline-config MLPipelineConfig
   :stage-result StageResult
   :ml-service-health MLServiceHealth})

;;; ============================================================================
;;; Validation Helpers (Convenience functions)
;;; ============================================================================

(defn validate-context
  "Validate pipeline context at stage boundary.

  Usage:
    (validate-context ctx :merchant)
    => {:valid? true}
  "
  [context stage]
  (let [result (m/validate PipelineContext context)]
    (if result
      {:valid? true :stage stage}
      {:valid? false
       :stage stage
       :errors (-> (m/explain PipelineContext context)
                   (me/humanize))})))

(defn validate-config
  "Validate ML pipeline configuration.

  Usage:
    (validate-config config)
    => {:valid? true}
  "
  [config]
  (if (m/validate MLPipelineConfig config)
    {:valid? true}
    {:valid? false
     :errors (-> (m/explain MLPipelineConfig config)
                 (me/humanize))}))

(defn check-confidence-threshold
  "Check if confidence meets threshold for auto-approval.

  Returns:
    :high → Auto-approve
    :medium → Flag for review
    :low → Reject or flag

  Usage:
    (check-confidence-threshold 0.95 default-confidence-thresholds)
    => :high
  "
  [confidence thresholds]
  (cond
    (>= confidence (:high-confidence thresholds)) :high
    (>= confidence (:medium-confidence thresholds)) :medium
    (>= confidence (:low-confidence thresholds)) :low
    :else :very-low))

(comment
  ;; REPL experiments

  ;; 1. Validate pipeline context
  (def sample-context
    {:transaction {:id "123e4567-e89b-12d3-a456-426614174000"
                   :date "2024-11-06"
                   :amount 45.99
                   :description "STARBUCKS #12345"
                   :type :expense
                   :provenance {:source-file "bofa_nov_2024.csv"
                                :source-line 23
                                :imported-at "2024-11-06"}}
     :merchant-detection {:canonical-name "Starbucks"
                          :confidence 0.95
                          :method "pattern-matching"}
     :category-detection {:name "Coffee & Tea"
                          :confidence 0.90
                          :rule-id "rule-15"}
     :pipeline-stage :complete})

  (m/validate PipelineContext sample-context)
  ;; => true

  ;; 2. Check confidence threshold
  (check-confidence-threshold 0.95 default-confidence-thresholds)
  ;; => :high

  (check-confidence-threshold 0.75 default-confidence-thresholds)
  ;; => :medium

  (check-confidence-threshold 0.55 default-confidence-thresholds)
  ;; => :low

  ;; 3. Validate complete ML config
  (m/validate MLPipelineConfig default-ml-pipeline-config)
  ;; => true

  ;; 4. Invalid config (thresholds not ordered)
  (def bad-thresholds
    {:high-confidence 0.70
     :medium-confidence 0.90  ; > high (invalid!)
     :low-confidence 0.60})

  (m/validate ConfidenceThresholds bad-thresholds)
  ;; => false

  (-> (m/explain ConfidenceThresholds bad-thresholds)
      (me/humanize))
  ;; => {:fn ["Thresholds must be high > medium > low"]}
  )
