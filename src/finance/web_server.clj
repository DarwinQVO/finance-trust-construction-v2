(ns finance.web-server
  "Web server for merchant extraction pipeline"
  (:require [ring.adapter.jetty :as jetty]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.util.response :as response]
            [clojure.java.io :as io]
            [cheshire.core :as json]  ;; Changed from clojure.data.json
            [clojure.string :as str]
            [finance.pdf-parser :as pdf]
            [finance.merchant-extraction.stage1 :as stage1]
            [finance.merchant-extraction.stage2 :as stage2]
            [finance.merchant-extraction.stage3 :as stage3]
            [finance.merchant-extraction.stage4 :as stage4]
            [finance.merchant-extraction.stage5 :as stage5]
            [finance.entity-registry :as registry]))

;; ============================================================================
;; Pipeline Processing
;; ============================================================================

(defn process-transactions
  "Runs complete pipeline (Stages 1-5) on transactions"
  [raw-txs]
  (let [;; Stage 1: Type Detection
        typed-txs (stage1/detect-batch raw-txs)

        ;; Stage 2: Counterparty Detection
        counterparty-txs (stage2/detect-batch typed-txs)

        ;; Stage 3: NER Extraction
        clean-txs (stage3/extract-batch counterparty-txs)

        ;; Stage 4: Entity Resolution (Registry Lookup)
        resolved-txs (stage4/resolve-batch clean-txs)

        ;; Stage 5: Multi-Dimensional Category Resolution
        categorized-txs (stage5/resolve-batch resolved-txs)]

    {:transactions categorized-txs
     :stats {:total (count categorized-txs)
             :with-merchant (count (filter :clean-merchant categorized-txs))
             :entity-resolved (count (filter :entity-resolved? categorized-txs))
             :category-resolved (count (filter :stage5-status categorized-txs))
             :pending-classification (count (filter :needs-manual-classification categorized-txs))
             :by-entity-type (frequencies (map :entity-type (filter :entity-resolved? categorized-txs)))
             :by-category (frequencies (map :merchant-category (filter :entity-resolved? categorized-txs)))
             :by-budget-category (frequencies (map :budget-category (filter :stage5-status categorized-txs)))
             :by-tax-category (frequencies (map :tax-category (filter :stage5-status categorized-txs)))}}))

;; ============================================================================
;; Routes
;; ============================================================================

(defn home-page []
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (slurp (io/resource "public/index.html"))})

(defn- prepare-for-json
  "Recursively removes nil values and converts Java objects to JSON-friendly types"
  [data]
  (cond
    ;; Convert Instant to string
    (instance? java.time.Instant data)
    (str data)

    ;; Handle maps
    (map? data)
    (into {} (keep (fn [[k v]]
                     (when-not (nil? v)
                       [k (prepare-for-json v)]))
                   data))

    ;; Handle sequences
    (sequential? data)
    (mapv prepare-for-json data)

    ;; Everything else as-is
    :else
    data))

(defn upload-pdf-handler
  "Handles PDF upload and processing"
  [request]
  (try
    (let [pdf-file (get-in request [:params "pdf" :tempfile])
          pdf-filename (get-in request [:params "pdf" :filename] "unknown.pdf")

          ;; ============================================================================
          ;; DEDUPLICATION CHECK: Prevent same PDF from being processed multiple times
          ;; ============================================================================
          history-stats (registry/get-transaction-history-stats)
          pdf-sources (keys (:by-pdf-source history-stats))
          already-processed? (some #(= % pdf-filename) pdf-sources)]

      (if already-processed?
        ;; PDF already processed - return warning
        (do
          (println (format "⚠️  PDF ALREADY PROCESSED: %s (skipping duplicate upload)" pdf-filename))
          {:status 400
           :headers {"Content-Type" "application/json"}
           :body (json/generate-string {:success false
                                        :error "PDF_ALREADY_PROCESSED"
                                        :message (str "El archivo '" pdf-filename "' ya fue procesado anteriormente. "
                                                     "No se permiten cargas duplicadas del mismo PDF.")
                                        :pdf-filename pdf-filename})})

        ;; New PDF - process normally
        (let [;; Step 1: Parse PDF
              {:keys [transactions transaction-count]} (pdf/parse-pdf pdf-file)

              ;; Step 2: Run pipeline
              results (process-transactions transactions)

              ;; Step 3: Add to history
              _ (registry/add-transactions-to-history (:transactions results) pdf-filename)

              ;; Step 4: Get ALL transactions (cumulative)
              all-transactions (registry/get-transaction-history)
              updated-history-stats (registry/get-transaction-history-stats)

              ;; Prepare data for JSON (remove nils + convert Instants to strings)
              clean-response (prepare-for-json {:success true
                                                :pdf-transactions transaction-count
                                                :pipeline-results results
                                                :all-transactions all-transactions
                                                :history-stats updated-history-stats})]

          {:status 200
           :headers {"Content-Type" "application/json"}
           :body (json/generate-string clean-response)})))

    (catch Exception e
      (println "ERROR uploading PDF:")
      (.printStackTrace e)
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:success false
                              :error (or (.getMessage e) (str "Exception: " (.getName (.getClass e))))})})))

;; ============================================================================
;; Registry API Endpoints
;; ============================================================================

(defn get-pending-merchants-handler
  "Returns list of merchants needing manual classification"
  [request]
  (try
    (let [pending (registry/get-pending-classifications)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:success true
                              :pending pending
                              :count (count pending)})})
    (catch Exception e
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:success false
                              :error (.getMessage e)})})))

(defn classify-merchant-handler
  "Handles manual merchant classification"
  [request]
  (try
    (let [body (slurp (:body request))
          params (json/parse-string body true)  ;; true keywordizes correctly (fixes hyphenated keys)
          {:keys [merchant-text canonical-name category entity-type variations]} params

          ;; Generate merchant ID from canonical name
          merchant-id (-> canonical-name
                          str/lower-case
                          (str/replace #"\s+" "-")
                          (str/replace #"[^a-z0-9\-]" ""))

          ;; Add merchant to registry
          result (registry/add-merchant merchant-id
                                       {:canonical-name canonical-name
                                        :category (keyword category)
                                        :entity-type (keyword entity-type)
                                        :variations (or variations [merchant-text])})

          ;; Remove from pending
          _ (registry/remove-pending-classification merchant-text)]

      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string (merge {:success true} result))})

    (catch Exception e
      (println "ERROR classifying merchant:")
      (.printStackTrace e)
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:success false
                              :error (.getMessage e)})})))

(defn get-all-merchants-handler
  "Returns all merchants in registry"
  [request]
  (try
    (let [merchants (registry/list-all-merchants)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:success true
                              :merchants merchants
                              :count (count merchants)})})
    (catch Exception e
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:success false
                              :error (.getMessage e)})})))

(defn get-registry-stats-handler
  "Returns registry statistics"
  [request]
  (try
    (let [stats (registry/registry-statistics)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:success true
                              :stats stats})})
    (catch Exception e
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:success false
                              :error (.getMessage e)})})))

(defn get-category-stats-handler
  "Returns multi-dimensional category statistics"
  [request]
  (try
    (let [all-transactions (registry/get-transaction-history)
          stats (stage5/category-statistics all-transactions)
          clean-stats (prepare-for-json stats)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:success true
                              :stats clean-stats})})
    (catch Exception e
      (println "ERROR generating category stats:")
      (.printStackTrace e)
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:success false
                              :error (.getMessage e)})})))

(defn add-variation-to-merchant-handler
  "Adds variation to existing merchant and removes from pending"
  [request]
  (try
    (let [body (slurp (:body request))
          _ (println "\n🔍 ======== ADD VARIATION HANDLER DEBUG ========")
          _ (println "📥 Raw body:" body)

          params (json/parse-string body true)  ;; true keywordizes correctly (fixes hyphenated keys)
          _ (println "📊 Parsed params:" params)
          _ (println "   Keys in params:" (keys params))

          {:keys [merchant-id variation merchant-text]} params

          _ (println "📋 Destructured values:")
          _ (println "   merchant-id:" (pr-str merchant-id))
          _ (println "   merchant-id type:" (type merchant-id))
          _ (println "   merchant-id nil?:" (nil? merchant-id))
          _ (println "   merchant-id empty?:" (if (string? merchant-id) (empty? merchant-id) "N/A"))
          _ (println "   variation:" (pr-str variation))
          _ (println "   merchant-text:" (pr-str merchant-text))

          ;; Add variation to existing merchant
          _ (println "📤 Calling registry/add-variation with:")
          _ (println "   merchant-id:" (pr-str merchant-id))
          _ (println "   variation:" (pr-str variation))

          result (registry/add-variation merchant-id variation)

          _ (println "📥 Result from add-variation:" result)
          _ (println "🔍 ======== END DEBUG ========\n")

          ;; Remove from pending (using merchant-text)
          _ (registry/remove-pending-classification (or merchant-text variation))]

      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string (merge {:success true} result))})

    (catch Exception e
      (println "❌ ERROR adding variation to merchant:")
      (.printStackTrace e)
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:success false
                              :error (.getMessage e)})})))

(defn delete-pending-merchant-handler
  "Removes a merchant from pending classifications"
  [request]
  (try
    (let [body (slurp (:body request))
          params (json/parse-string body true)  ;; true keywordizes correctly (fixes hyphenated keys)
          {:keys [merchant-text]} params

          ;; Remove from pending
          result (registry/remove-pending-classification merchant-text)]

      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:success true
                              :message (str "Removed " merchant-text " from pending")})})

    (catch Exception e
      (println "ERROR deleting pending merchant:")
      (.printStackTrace e)
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:success false
                              :error (.getMessage e)})})))

;; ============================================================================
;; Category API Endpoints
;; ============================================================================

(defn get-all-categories-handler
  "Returns all categories in registry"
  [request]
  (try
    (let [categories (registry/list-all-categories)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:success true
                              :categories categories
                              :count (count categories)})})
    (catch Exception e
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:success false
                              :error (.getMessage e)})})))

(defn get-category-by-id-handler
  "Returns specific category by ID"
  [request]
  (try
    (let [category-id (last (str/split (:uri request) #"/"))
          category (registry/get-category-by-id category-id)]
      (if category
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string {:success true
                                :category category})}
        {:status 404
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string {:success false
                                :error (str "Category '" category-id "' not found")})}))
    (catch Exception e
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:success false
                              :error (.getMessage e)})})))

(defn add-category-handler
  "Adds new category to registry"
  [request]
  (try
    (let [body (slurp (:body request))
          params (json/parse-string body true)  ;; true keywordizes correctly (fixes hyphenated keys)
          {:keys [category-id name description icon type]} params

          ;; Create category data
          category-data {:name name
                        :description description
                        :icon icon
                        :type (keyword type)}

          ;; Add to registry
          result (registry/add-category category-id category-data)]

      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string (merge {:success true} result))})

    (catch Exception e
      (println "ERROR adding category:")
      (.printStackTrace e)
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:success false
                              :error (.getMessage e)})})))

(defn update-category-handler
  "Updates existing category"
  [request]
  (try
    (let [category-id (last (str/split (:uri request) #"/"))
          body (slurp (:body request))
          params (json/parse-string body true)  ;; true keywordizes correctly (fixes hyphenated keys)

          ;; Extract updates (only include provided fields)
          updates (select-keys params [:name :description :icon :type])

          ;; Update category
          result (registry/update-category category-id updates)]

      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string (merge {:success true} result))})

    (catch Exception e
      (println "ERROR updating category:")
      (.printStackTrace e)
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:success false
                              :error (.getMessage e)})})))

(defn delete-category-handler
  "Deletes category from registry"
  [request]
  (try
    (let [category-id (last (str/split (:uri request) #"/"))
          result (registry/delete-category category-id)]

      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string (merge {:success true} result))})

    (catch Exception e
      (println "ERROR deleting category:")
      (.printStackTrace e)
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:success false
                              :error (.getMessage e)})})))

(defn get-category-stats-handler
  "Returns category statistics"
  [request]
  (try
    (let [stats (registry/category-statistics)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:success true
                              :stats stats})})
    (catch Exception e
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:success false
                              :error (.getMessage e)})})))

;; ============================================================================
;; Transaction History API Endpoints
;; ============================================================================

(defn get-transaction-history-handler
  "Returns all processed transactions from history"
  [request]
  (try
    (let [transactions (registry/get-transaction-history)
          stats (registry/get-transaction-history-stats)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:success true
                              :transactions transactions
                              :stats stats
                              :count (count transactions)})})
    (catch Exception e
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:success false
                              :error (.getMessage e)})})))

(defn merchant-review-ui []
  "Serves the enhanced vanilla JS manual review UI with full provenance"
  (try
    (let [file (io/file "public/merchant-review-enhanced.html")]
      (if (.exists file)
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (slurp file)}
        {:status 404
         :headers {"Content-Type" "text/plain"}
         :body "merchant-review-enhanced.html not found"}))
    (catch Exception e
      {:status 500
       :headers {"Content-Type" "text/plain"}
       :body (str "Error serving UI: " (.getMessage e))})))

(defn app-routes [request]
  (let [uri (:uri request)
        method (:request-method request)]
    (cond
      ;; Main pages
      (and (= method :get) (= uri "/")) (home-page)
      (and (= method :get) (= uri "/merchant-review")) (merchant-review-ui)

      ;; PDF processing
      (and (= method :post) (= uri "/api/upload")) (upload-pdf-handler request)

      ;; Merchant registry endpoints
      (and (= method :get) (= uri "/api/merchants/pending")) (get-pending-merchants-handler request)
      (and (= method :get) (= uri "/api/pending-classifications")) (get-pending-merchants-handler request)  ; Alias
      (and (= method :get) (= uri "/api/registry/pending")) (get-pending-merchants-handler request)  ; Alias for UI
      (and (= method :post) (= uri "/api/merchants/classify")) (classify-merchant-handler request)
      (and (= method :post) (= uri "/api/merchants/add-variation")) (add-variation-to-merchant-handler request)
      (and (= method :delete) (= uri "/api/merchants/pending")) (delete-pending-merchant-handler request)
      (and (= method :get) (= uri "/api/registry/merchants")) (get-all-merchants-handler request)
      (and (= method :get) (= uri "/api/registry/stats")) (get-registry-stats-handler request)

      ;; Category endpoints
      (and (= method :get) (= uri "/api/categories")) (get-all-categories-handler request)
      (and (= method :get) (str/starts-with? uri "/api/categories/") (not= uri "/api/categories/stats"))
        (get-category-by-id-handler request)
      (and (= method :post) (= uri "/api/categories")) (add-category-handler request)
      (and (= method :put) (str/starts-with? uri "/api/categories/"))
        (update-category-handler request)
      (and (= method :delete) (str/starts-with? uri "/api/categories/"))
        (delete-category-handler request)
      (and (= method :get) (= uri "/api/categories/stats")) (get-category-stats-handler request)

      ;; Transaction history endpoints
      (and (= method :get) (= uri "/api/transaction-history")) (get-transaction-history-handler request)
      (and (= method :get) (= uri "/api/registry/transaction-history")) (get-transaction-history-handler request)  ; Alias for UI

      ;; 404
      :else {:status 404
             :headers {"Content-Type" "text/plain"}
             :body "Not Found"})))

;; ============================================================================
;; Server
;; ============================================================================

(def app
  (-> app-routes
      wrap-params
      wrap-multipart-params
      (wrap-cors :access-control-allow-origin [#".*"]
                 :access-control-allow-methods [:get :post :put :delete])))

(defn start-server [port]
  (jetty/run-jetty app {:port port :join? false}))

(defn -main []
  (let [port 3000]
    (println (format "Starting merchant extraction server on http://localhost:%d" port))
    (start-server port)))
