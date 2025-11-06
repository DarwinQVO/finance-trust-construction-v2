(ns finance.api.routes
  "API routing configuration using Reitit.

  Routes are versioned under /api/v1/* for forward compatibility.
  Uses Muuntaja for content negotiation (EDN, Transit, JSON).

  Route Structure:
  - /api/v1/health         - Health check
  - /api/v1/transactions   - List transactions (with filters)
  - /api/v1/transactions/:id - Get single transaction
  - /api/v1/stats          - System statistics
  - /api/v1/rules          - Classification rules"
  (:require [reitit.ring :as ring]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [muuntaja.core :as m]
            [finance.api.handlers :as handlers]))

;; ============================================================================
;; Muuntaja Configuration (Format Negotiation)
;; ============================================================================

(def muuntaja-instance
  "Muuntaja instance for format negotiation.

  Supports:
  - application/edn (Clojure EDN - preferred for Clojure clients)
  - application/transit+json (Transit - efficient binary format)
  - application/json (JSON - for JavaScript clients)

  Default: EDN (most idiomatic for Clojure system)"
  (m/create
    (-> m/default-options
        (assoc-in [:formats "application/edn" :decoder-opts]
                  {:readers *data-readers*}))))

;; ============================================================================
;; Route Definitions
;; ============================================================================

(defn api-routes
  "Define API routes with handlers.

  All routes are versioned under /api/v1/* namespace.

  Route Parameters:
  - :id (path param) - Transaction entity ID

  Query Parameters (for /transactions):
  - limit (default: 100, max: 1000) - Max results per page
  - offset (default: 0) - Skip N results (pagination)
  - type - Filter by transaction type (:GASTO, :INGRESO, etc.)
  - from-date - Start date filter (YYYY-MM-DD)
  - to-date - End date filter (YYYY-MM-DD)

  Examples:
    GET /api/v1/health
    GET /api/v1/transactions?limit=50&type=GASTO
    GET /api/v1/transactions/17592186045418
    GET /api/v1/stats
    GET /api/v1/rules"
  []
  ["/api"
   ["/v1"
    ["/health"
     {:get {:handler handlers/health-handler
            :summary "Health check endpoint"
            :description "Returns system status including database connection"}}]

    ["/transactions"
     [""
      {:get {:handler handlers/list-transactions-handler
             :summary "List all transactions"
             :description "Returns paginated list of transactions with optional filters"
             :parameters {:query {:limit {:type :long :default 100}
                                 :offset {:type :long :default 0}
                                 :type {:type :keyword :optional true}
                                 :from-date {:type :string :optional true}
                                 :to-date {:type :string :optional true}}}}}]

     ["/:id"
      {:get {:handler handlers/get-transaction-handler
             :summary "Get single transaction"
             :description "Returns full details for a single transaction"
             :parameters {:path {:id :long}}}}]]

    ["/stats"
     {:get {:handler handlers/stats-handler
            :summary "Get system statistics"
            :description "Returns counts, date ranges, and aggregations"}}]

    ["/rules"
     {:get {:handler handlers/list-rules-handler
            :summary "List classification rules"
            :description "Returns all merchant classification rules from EDN file"}}]

    ;; ========================================================================
    ;; ML Classification & Review Queue (Phase 3)
    ;; ========================================================================

    ["/transactions/:id/classify"
     {:post {:handler handlers/classify-transaction-handler
             :summary "Submit transaction for ML classification"
             :description "Queues transaction for merchant/category detection via Python ML service"
             :parameters {:path {:id :long}}}}]

    ["/review-queue"
     [""
      {:get {:handler handlers/get-review-queue-handler
             :summary "Get pending review queue items"
             :description "Returns all transactions awaiting human review"}}]

     ["/:id/approve"
      {:post {:handler handlers/approve-classification-handler
              :summary "Approve ML classification"
              :description "Human approval of ML-detected merchant and category"
              :parameters {:path {:id :long}
                          :body {:merchant :string
                                :category :string
                                :approved-by :string}}}}]

     ["/:id/reject"
      {:post {:handler handlers/reject-classification-handler
              :summary "Reject ML classification"
              :description "Human rejection of ML classification with reason"
              :parameters {:path {:id :long}
                          :body {:reason :string
                                :rejected-by :string}}}}]

     ["/:id/correct"
      {:post {:handler handlers/correct-classification-handler
              :summary "Correct ML classification"
              :description "Human correction of ML-detected merchant and category"
              :parameters {:path {:id :long}
                          :body {:corrected-merchant :string
                                :corrected-category :string
                                :corrected-by :string}}}}]]]])

;; ============================================================================
;; Router Creation
;; ============================================================================

(defn create-router
  "Create Reitit router with middleware.

  Middleware stack:
  1. Muuntaja - Format negotiation (EDN/Transit/JSON)
  2. Parameters - Parse query/path parameters

  Note: Error handling, CORS, and logging are applied at app level
        via middleware.clj, not at router level.

  Returns:
  - Ring handler function (request → response)"
  []
  (ring/ring-handler
    (ring/router
      (api-routes)
      {:data {:muuntaja muuntaja-instance
              :middleware [muuntaja/format-middleware
                          parameters/parameters-middleware]}})
    (ring/create-default-handler
      {:not-found handlers/not-found-handler})))

;; ============================================================================
;; Development Helpers
;; ============================================================================

(comment
  ;; Test route resolution
  (require '[reitit.core :as r])

  (def router (ring/router (api-routes)))

  ;; Match health route
  (r/match-by-path router "/api/v1/health")
  ;; => #Match{:template "/api/v1/health", ...}

  ;; Match transactions route with ID
  (r/match-by-path router "/api/v1/transactions/12345")
  ;; => #Match{:template "/api/v1/transactions/:id", :path-params {:id "12345"}}

  ;; Print all routes
  (doseq [route (r/routes router)]
    (println (:path route)))
  ;; /api/v1/health
  ;; /api/v1/transactions
  ;; /api/v1/transactions/:id
  ;; /api/v1/stats
  ;; /api/v1/rules
  )
