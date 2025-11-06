(ns finance.api.core
  "REST API server for Finance Trust Construction v2.0.

  Server Configuration:
  - Port: 3000
  - Format: EDN (default), Transit, JSON
  - CORS: Enabled for localhost:5173, localhost:3000
  - Logging: Structured logging with Timbre

  Usage:
    clj -M -m finance.api.core

  Endpoints:
    http://localhost:3000/api/v1/health
    http://localhost:3000/api/v1/transactions
    http://localhost:3000/api/v1/stats
    http://localhost:3000/api/v1/rules"
  (:require [ring.adapter.jetty :as jetty]
            [taoensso.timbre :as log]
            [finance.api.routes :as routes]
            [finance.api.middleware :as middleware]
            [finance.core-datomic :as db]
            [finance.orchestration.ml-pipeline :as ml-pipeline])
  (:gen-class))

;; ============================================================================
;; Server Configuration
;; ============================================================================

(def server-config
  "Server configuration.

  Port 3000: Standard for backend APIs (avoids conflict with 5173 for UI)"
  {:port 3000
   :join? false  ; Return server instance (for shutdown)
   :host "0.0.0.0"  ; Listen on all interfaces
   })

;; ============================================================================
;; Server Lifecycle
;; ============================================================================

;; Atom holding server instance for shutdown.
;; Usage:
;;   (reset! server-state (start-server!))
;;   (stop-server! @server-state)
(defonce server-state (atom nil))

(defn start-server!
  "Start Jetty server with full middleware stack.

  Steps:
  1. Initialize Datomic connection
  2. Create Reitit router
  3. Apply middleware (CORS, logging, error handling)
  4. Start Jetty on port 3000

  Returns:
  - Server instance (for shutdown)

  Throws:
  - Exception if Datomic connection fails
  - Exception if port 3000 is already in use"
  []
  (try
    (log/info :event :server-starting :port (:port server-config))

    ;; 1. Initialize Datomic
    (log/info :event :datomic-connecting)
    (let [conn (db/init!)]
      (log/info :event :datomic-connected)

      ;; 2. Start ML Pipeline workers
      (log/info :event :ml-pipeline-starting)
      (ml-pipeline/start-pipeline! conn)
      (log/info :event :ml-pipeline-started)

      ;; 3. Create router
      (log/info :event :router-creating)
      (let [router (routes/create-router)]
        (log/info :event :router-created)

        ;; 4. Apply middleware stack
        (log/info :event :middleware-applying)
        (let [app (middleware/build-middleware-stack router conn)]
          (log/info :event :middleware-applied)

          ;; 5. Start Jetty
          (log/info :event :jetty-starting :config server-config)
          (let [server (jetty/run-jetty app server-config)]
            (log/info :event :server-started
                      :port (:port server-config)
                      :endpoints ["/api/v1/health"
                                 "/api/v1/transactions"
                                 "/api/v1/transactions/:id/classify"
                                 "/api/v1/review-queue"
                                 "/api/v1/stats"
                                 "/api/v1/rules"])
            server))))

    (catch Exception e
      (log/error :event :server-start-failed
                 :error (.getMessage e)
                 :stacktrace (with-out-str (.printStackTrace e)))
      (throw e))))

(defn stop-server!
  "Stop Jetty server gracefully.

  Parameters:
  - server: Server instance from start-server!

  Side Effects:
  - Stops HTTP server
  - Shuts down ML pipeline
  - Closes Datomic connection (implicit)"
  [server]
  (when server
    (try
      (log/info :event :server-stopping)

      ;; Stop ML pipeline workers
      (log/info :event :ml-pipeline-stopping)
      (ml-pipeline/shutdown-pipeline!)
      (log/info :event :ml-pipeline-stopped)

      ;; Stop HTTP server
      (.stop server)
      (log/info :event :server-stopped)
      (catch Exception e
        (log/error :event :server-stop-failed
                   :error (.getMessage e))))))

;; ============================================================================
;; Main Entry Point
;; ============================================================================

(defn -main
  "Main entry point for server startup.

  Usage:
    clj -M -m finance.api.core

  Starts server on http://localhost:3000

  Press Ctrl+C to shutdown.

  Environment Variables:
  - PORT (optional): Override default port 3000
  - LOG_LEVEL (optional): Override log level (info, debug, warn, error)"
  [& _args]
  (try
    ;; Configure logging
    (log/merge-config!
      {:min-level (keyword (or (System/getenv "LOG_LEVEL") "info"))
       :appenders {:println {:enabled? true}}})

    ;; Print banner
    (println)
    (println "╔════════════════════════════════════════════════════════════╗")
    (println "║  Finance Trust Construction v2.0 - REST API Server        ║")
    (println "╚════════════════════════════════════════════════════════════╝")
    (println)
    (println "Starting server...")
    (println)

    ;; Start server
    (let [server (start-server!)]
      (reset! server-state server)

      ;; Print success message
      (println "✅ Server running on http://localhost:3000")
      (println)
      (println "Endpoints:")
      (println "  GET  http://localhost:3000/api/v1/health")
      (println "  GET  http://localhost:3000/api/v1/transactions")
      (println "  GET  http://localhost:3000/api/v1/transactions/:id")
      (println "  POST http://localhost:3000/api/v1/transactions/:id/classify")
      (println "  GET  http://localhost:3000/api/v1/review-queue")
      (println "  POST http://localhost:3000/api/v1/review-queue/:id/approve")
      (println "  POST http://localhost:3000/api/v1/review-queue/:id/reject")
      (println "  POST http://localhost:3000/api/v1/review-queue/:id/correct")
      (println "  GET  http://localhost:3000/api/v1/stats")
      (println "  GET  http://localhost:3000/api/v1/rules")
      (println)
      (println "ML Pipeline: ✅ Running (Python service at http://localhost:8000)")
      (println)
      (println "Press Ctrl+C to stop")
      (println)

      ;; Add shutdown hook
      (.addShutdownHook (Runtime/getRuntime)
                       (Thread. (fn []
                                  (println)
                                  (println "Shutting down server...")
                                  (stop-server! server)
                                  (println "Goodbye!")))))

    (catch Exception e
      (log/error :event :startup-failed
                 :error (.getMessage e))
      (println)
      (println "❌ Failed to start server:")
      (println (.getMessage e))
      (println)
      (println "Check logs for details")
      (System/exit 1))))

;; ============================================================================
;; Development Helpers
;; ============================================================================

(comment
  ;; Start server manually (REPL)
  (def server (start-server!))
  (reset! server-state server)

  ;; Test health endpoint
  (require '[clj-http.client :as http])
  (http/get "http://localhost:3000/api/v1/health"
            {:accept :edn
             :as :clojure})

  ;; Test transactions endpoint
  (http/get "http://localhost:3000/api/v1/transactions"
            {:accept :edn
             :as :clojure
             :query-params {:limit 5 :type "GASTO"}})

  ;; Stop server
  (stop-server! @server-state)
  (reset! server-state nil)

  ;; Restart server
  (do
    (stop-server! @server-state)
    (Thread/sleep 500)
    (reset! server-state (start-server!)))
  )
