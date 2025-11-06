(ns finance.api.middleware
  "Ring middleware for Finance Trust Construction API.

  Middleware stack (applied bottom to top):
  1. wrap-inject-conn - Add Datomic conn to request
  2. wrap-exception - Catch errors, return 500
  3. wrap-logging - Log requests/responses
  4. wrap-cors - Allow CORS"
  (:require [ring.middleware.cors :refer [wrap-cors]]
            [taoensso.timbre :as log]))

;; ============================================================================
;; Middleware Functions
;; ============================================================================

(defn wrap-inject-conn
  "Middleware: Inject Datomic connection into request map.

  Adds :conn key to request map so handlers can access database.

  Usage:
    (wrap-inject-conn handler my-conn)

  Example:
    Request: {:uri \"/api/v1/health\" :method :get}
    →
    Request: {:uri \"/api/v1/health\" :method :get :conn <datomic-conn>}"
  [handler conn]
  (fn [request]
    (handler (assoc request :conn conn))))

(defn wrap-exception
  "Middleware: Catch exceptions, return 500 with error details.

  Provides graceful error handling for all handlers.
  Logs errors with structured logging (Timbre).
  Returns JSON-compatible error response.

  Example:
    Handler throws: (throw (ex-info \"Invalid transaction\" {...}))
    →
    Response: {:status 500
               :body {:error \"Internal server error\"
                      :message \"Invalid transaction\"}}"
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        (log/error :event :exception
                   :uri (:uri request)
                   :method (:request-method request)
                   :error (.getMessage e)
                   :stacktrace (with-out-str (.printStackTrace e)))
        {:status 500
         :body {:error "Internal server error"
                :message (.getMessage e)
                :uri (:uri request)}}))))

(defn wrap-logging
  "Middleware: Log all requests with structured logging.

  Logs:
  - Request method and URI
  - Response status code
  - Request duration in milliseconds

  Uses Timbre structured logging (EDN format) for easy parsing.

  Example log entry:
    {:event :http-request
     :method :get
     :uri \"/api/v1/transactions\"
     :status 200
     :duration-ms 45}"
  [handler]
  (fn [request]
    (let [start (System/currentTimeMillis)
          response (handler request)
          duration (- (System/currentTimeMillis) start)]
      (log/info :event :http-request
                :method (:request-method request)
                :uri (:uri request)
                :query-params (:query-params request)
                :status (:status response)
                :duration-ms duration)
      response)))

;; ============================================================================
;; Middleware Stack Builder
;; ============================================================================

(defn build-middleware-stack
  "Build complete middleware stack for API.

  Applies middleware in correct order:
  1. CORS (outermost - allow cross-origin requests)
  2. Logging (log all requests)
  3. Exception handling (catch errors)
  4. Connection injection (innermost - add DB conn)

  Parameters:
  - handler: Base Ring handler (usually Reitit router)
  - conn: Datomic connection to inject into requests

  Returns:
  - Wrapped handler with full middleware stack

  Usage:
    (def app
      (build-middleware-stack
        my-router
        my-datomic-conn))

  CORS Configuration:
  - Allows localhost:5173 (Vite dev server for future UI)
  - Allows localhost:3000 (API server itself)
  - Allows all HTTP methods
  - Allows Content-Type and Authorization headers"
  [handler conn]
  (-> handler
      (wrap-inject-conn conn)
      wrap-exception
      wrap-logging
      (wrap-cors :access-control-allow-origin [#"http://localhost:5173"
                                               #"http://localhost:3000"]
                 :access-control-allow-methods [:get :post :put :patch :delete]
                 :access-control-allow-headers ["Content-Type" "Authorization"])))
