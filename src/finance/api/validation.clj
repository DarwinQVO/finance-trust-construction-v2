(ns finance.api.validation
  "Malli schema validation middleware for API boundaries.

  Rich Hickey Principle: Validate at BOUNDARIES, not scattered throughout code.

  Philosophy:
  - Validate ALL data entering the system (request bodies)
  - Validate ALL data leaving the system (response bodies)
  - Make validation EXPLICIT (not hidden in code)
  - Return CLEAR error messages (human-readable)
  - Log ALL validation failures (audit trail)

  Usage:
    (def routes
      (-> handler
          (wrap-validate-request schemas/Transaction)
          (wrap-validate-response schemas/TransactionResponse)))
  "
  (:require [malli.core :as m]
            [malli.error :as me]
            [taoensso.timbre :as log]
            [clojure.data.json :as json]
            [finance.schemas.core :as schemas]
            [finance.schemas.ml :as ml-schemas]))

;;; ============================================================================
;;; Validation Helpers
;;; ============================================================================

(defn humanize-errors
  "Convert Malli errors to human-readable format.

  Input: Malli explain output
  Output: Map of field → error messages

  Example:
    {:transaction/amount [\"Number must be positive\"]
     :transaction/date [\"Date must be in YYYY-MM-DD format\"]}
  "
  [explanation]
  (-> explanation
      (me/humanize)))

(defn validation-error-response
  "Create standardized validation error response.

  Rich Hickey: Errors are DATA, not exceptions (when possible).

  Returns 400 Bad Request with:
  - Error message
  - Validation errors (field → messages)
  - Timestamp
  - Request ID (for tracing)

  Example response:
    {:error \"Validation failed\"
     :validation-errors {:amount [\"Must be positive\"]
                         :date [\"Invalid format\"]}
     :timestamp \"2024-11-06T10:30:00Z\"
     :request-id \"req-123\"}
  "
  [errors request-id]
  {:status 400
   :headers {"Content-Type" "application/json"}
   :body (json/write-str
           {:error "Validation failed"
            :validation-errors errors
            :timestamp (str (java.time.Instant/now))
            :request-id request-id})})

(defn validate-data
  "Validate data against schema.

  Returns:
    {:valid? true}  ; If valid
    {:valid? false  ; If invalid
     :errors {...}}

  Usage:
    (validate-data schemas/Transaction tx)
  "
  [schema data]
  (if (m/validate schema data)
    {:valid? true}
    {:valid? false
     :errors (-> (m/explain schema data)
                 (humanize-errors))}))

;;; ============================================================================
;;; Request Validation Middleware
;;; ============================================================================

(defn wrap-validate-request-body
  "Middleware: Validate request body against schema.

  Validates BEFORE handler executes.
  Returns 400 if validation fails.

  Rich Hickey: Validate at system boundaries.

  Usage:
    (wrap-validate-request-body handler schemas/Transaction)

  Example:
    POST /v1/transactions
    Body: {\"amount\": -45.99}  ; Invalid (negative)
    Response: 400 {\"error\": \"Validation failed\", \"validation-errors\": {...}}
  "
  [handler schema]
  (fn [request]
    (let [body (:body-params request)
          request-id (or (get-in request [:headers "x-request-id"])
                        (str (java.util.UUID/randomUUID)))
          validation (validate-data schema body)]

      (if (:valid? validation)
        ;; Valid → Continue to handler
        (do
          (log/debug :event :request-validation-passed
                     :schema (m/type schema)
                     :request-id request-id)
          (handler request))

        ;; Invalid → Return 400
        (do
          (log/warn :event :request-validation-failed
                    :schema (m/type schema)
                    :errors (:errors validation)
                    :request-id request-id
                    :body body)
          (validation-error-response (:errors validation) request-id))))))

;;; ============================================================================
;;; Response Validation Middleware (Development/Testing)
;;; ============================================================================

(defn wrap-validate-response-body
  "Middleware: Validate response body against schema.

  Validates AFTER handler executes, BEFORE sending to client.
  Returns 500 if validation fails (internal error).

  Rich Hickey: Validate ALL data leaving the system.

  NOTE: In production, this might be too strict (performance).
        Consider enabling only in development/staging.

  Usage:
    (wrap-validate-response-body handler schemas/TransactionResponse)

  Example:
    Handler returns: {:transaction {...}}  ; Missing required field
    Response: 500 {\"error\": \"Internal validation error\"}
  "
  [handler schema]
  (fn [request]
    (let [response (handler request)
          body (:body response)
          request-id (or (get-in request [:headers "x-request-id"])
                        (str (java.util.UUID/randomUUID)))
          validation (validate-data schema body)]

      (if (:valid? validation)
        ;; Valid → Return response
        (do
          (log/debug :event :response-validation-passed
                     :schema (m/type schema)
                     :request-id request-id)
          response)

        ;; Invalid → Log ERROR and return 500
        ;; This is OUR bug, not client's bug
        (do
          (log/error :event :response-validation-failed
                     :schema (m/type schema)
                     :errors (:errors validation)
                     :request-id request-id
                     :body body)
          {:status 500
           :headers {"Content-Type" "application/json"}
           :body (json/write-str
                   {:error "Internal validation error"
                    :message "Response validation failed (this is a server bug)"
                    :request-id request-id
                    :timestamp (str (java.time.Instant/now))})})))))

;;; ============================================================================
;;; Endpoint-Specific Validators (Convenience)
;;; ============================================================================

(defn validate-classify-request
  "Validate /v1/transactions/:id/classify request.

  No body required (transaction ID in path).
  Just validates path param exists.
  "
  [request]
  (let [tx-id (get-in request [:path-params :id])]
    (if tx-id
      {:valid? true}
      {:valid? false
       :errors {:id ["Transaction ID is required"]}})))

(defn validate-approve-request
  "Validate /v1/review-queue/:id/approve request body.

  Required fields:
  - merchant: String (non-empty)
  - category: String (non-empty)
  - approved-by: String (non-empty)
  "
  [request]
  (let [body (:body-params request)
        merchant (get body "merchant")
        category (get body "category")
        approved-by (get body "approved-by")
        errors (cond-> {}
                 (not merchant) (assoc :merchant ["merchant is required"])
                 (not category) (assoc :category ["category is required"])
                 (not approved-by) (assoc :approved-by ["approved-by is required"]))]

    (if (empty? errors)
      {:valid? true}
      {:valid? false
       :errors errors})))

(defn validate-reject-request
  "Validate /v1/review-queue/:id/reject request body.

  Required fields:
  - reason: String (non-empty)
  - rejected-by: String (non-empty)
  "
  [request]
  (let [body (:body-params request)
        reason (get body "reason")
        rejected-by (get body "rejected-by")
        errors (cond-> {}
                 (not reason) (assoc :reason ["reason is required"])
                 (not rejected-by) (assoc :rejected-by ["rejected-by is required"]))]

    (if (empty? errors)
      {:valid? true}
      {:valid? false
       :errors errors})))

(defn validate-correct-request
  "Validate /v1/review-queue/:id/correct request body.

  Required fields:
  - corrected-merchant: String (non-empty)
  - corrected-category: String (non-empty)
  - corrected-by: String (non-empty)
  "
  [request]
  (let [body (:body-params request)
        merchant (get body "corrected-merchant")
        category (get body "corrected-category")
        corrected-by (get body "corrected-by")
        errors (cond-> {}
                 (not merchant) (assoc :corrected-merchant ["corrected-merchant is required"])
                 (not category) (assoc :corrected-category ["corrected-category is required"])
                 (not corrected-by) (assoc :corrected-by ["corrected-by is required"]))]

    (if (empty? errors)
      {:valid? true}
      {:valid? false
       :errors errors})))

;;; ============================================================================
;;; Handler Wrappers (Applies validation to specific endpoints)
;;; ============================================================================

(defn wrap-handler-with-validation
  "Wrap handler with validator function.

  validator: Function that takes request and returns {:valid? bool :errors map}

  Usage:
    (wrap-handler-with-validation approve-handler validate-approve-request)
  "
  [handler validator]
  (fn [request]
    (let [validation (validator request)
          request-id (or (get-in request [:headers "x-request-id"])
                        (str (java.util.UUID/randomUUID)))]

      (if (:valid? validation)
        ;; Valid → Continue
        (handler request)

        ;; Invalid → 400
        (do
          (log/warn :event :endpoint-validation-failed
                    :errors (:errors validation)
                    :request-id request-id
                    :uri (:uri request))
          (validation-error-response (:errors validation) request-id))))))

;;; ============================================================================
;;; Schema Registry (For easy lookup)
;;; ============================================================================

(def endpoint-schemas
  "Map of endpoint → schemas for validation.

  Structure:
    {endpoint-key {:request schema
                   :response schema}}

  Usage:
    (get-in endpoint-schemas [:classify-transaction :request])
  "
  {;; Transactions
   :list-transactions {:request nil  ; Query params, not body
                       :response nil}  ; TODO: Define response schema

   :get-transaction {:request nil
                     :response nil}

   :classify-transaction {:request nil  ; No body, just path param
                          :response nil}

   ;; Review Queue
   :get-review-queue {:request nil
                      :response nil}  ; TODO: Define response schema

   :approve-classification {:request nil  ; Validated by validate-approve-request
                            :response nil}

   :reject-classification {:request nil  ; Validated by validate-reject-request
                           :response nil}

   :correct-classification {:request nil  ; Validated by validate-correct-request
                            :response nil}

   ;; System
   :health {:request nil
            :response nil}  ; TODO: Define health response schema

   :stats {:request nil
           :response nil}})  ; TODO: Define stats response schema

;;; ============================================================================
;;; Exports (Public API)
;;; ============================================================================

(def validators
  "Map of all endpoint validators for easy access."
  {:classify-transaction validate-classify-request
   :approve-classification validate-approve-request
   :reject-classification validate-reject-request
   :correct-classification validate-correct-request})

(comment
  ;; REPL experiments

  ;; 1. Validate good approve request
  (def good-request
    {:body-params {"merchant" "Starbucks"
                   "category" "Coffee"
                   "approved-by" "user@example.com"}})

  (validate-approve-request good-request)
  ;; => {:valid? true}

  ;; 2. Validate bad approve request (missing fields)
  (def bad-request
    {:body-params {"merchant" "Starbucks"}})  ; Missing category and approved-by

  (validate-approve-request bad-request)
  ;; => {:valid? false
  ;;     :errors {:category ["category is required"]
  ;;              :approved-by ["approved-by is required"]}}

  ;; 3. Test validation error response
  (validation-error-response
    {:amount ["Must be positive"]
     :date ["Invalid format"]}
    "req-123")
  ;; => {:status 400
  ;;     :headers {"Content-Type" "application/json"}
  ;;     :body "{\"error\":\"Validation failed\", ...}"}

  ;; 4. Test with Malli schema
  (def sample-tx
    {:id "invalid-uuid"  ; Not a UUID
     :date "2024-13-40"  ; Invalid date
     :amount -45.99})    ; Negative

  (validate-data schemas/Transaction sample-tx)
  ;; => {:valid? false
  ;;     :errors {:id ["Must be a valid UUID"]
  ;;              :date ["Date must be in YYYY-MM-DD format"]
  ;;              :amount ["Number must be positive"]}}
  )
