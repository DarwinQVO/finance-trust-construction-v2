(ns finance.entity-registry
  "Entity Registry - Persistent merchant/entity storage and variation matching"
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]))

;; ============================================================================
;; Registry File Management
;; ============================================================================

(def ^:private registry-file "resources/registry/merchant_registry.json")

(defn- ensure-registry-dir
  "Ensures registry directory exists"
  []
  (let [dir (io/file "resources/registry")]
    (when-not (.exists dir)
      (.mkdirs dir))))

(defn- load-registry-file
  "Loads merchant registry from JSON file"
  []
  (ensure-registry-dir)
  (let [file (io/file registry-file)]
    (if (.exists file)
      (try
        (json/read-str (slurp file) :key-fn keyword)
        (catch Exception e
          (println "⚠️  Error loading registry:" (.getMessage e))
          {:merchants {}}))
      {:merchants {}})))

(defn- save-registry-file
  "Saves merchant registry to JSON file"
  [registry]
  (ensure-registry-dir)
  (try
    (spit registry-file
          (json/write-str registry :indent true))
    true
    (catch Exception e
      (println "⚠️  Error saving registry:" (.getMessage e))
      false)))

;; ============================================================================
;; Variation Matching
;; ============================================================================

(defn- normalize-text
  "Normalizes text for matching (uppercase, remove extra spaces)"
  [text]
  (-> text
      str/upper-case
      str/trim
      (str/replace #"\s+" " ")))

(defn- exact-match?
  "Returns true if variation exactly matches (case-insensitive)"
  [variation search-text]
  (= (normalize-text variation)
     (normalize-text search-text)))

(defn- substring-match?
  "Returns true if variation is substring of search text (case-insensitive)"
  [variation search-text]
  (str/includes? (normalize-text search-text)
                 (normalize-text variation)))

(defn- find-merchant-by-variation
  "Finds merchant by matching any variation (exact or substring)"
  [registry search-text]
  (let [merchants (:merchants registry)]
    (some
     (fn [[merchant-id merchant-data]]
       (let [variations (:variations merchant-data [])
             canonical (:canonical-name merchant-data)]
         (when (or
                ;; Exact match with canonical name
                (exact-match? canonical search-text)
                ;; Exact match with any variation
                (some #(exact-match? % search-text) variations)
                ;; Substring match (weaker confidence)
                (some #(substring-match? % search-text) variations))
           (assoc merchant-data
                  :merchant-id merchant-id
                  :match-type (cond
                                (exact-match? canonical search-text) :exact-canonical
                                (some #(exact-match? % search-text) variations) :exact-variation
                                :else :substring-match)))))
     merchants)))

;; ============================================================================
;; Registry Lookup
;; ============================================================================

(defn lookup-merchant
  "Looks up merchant in registry by variation text
   Returns {:canonical-name :category :entity-type :confidence :match-type} or nil"
  [search-text]
  (let [registry (load-registry-file)]
    (when-let [match (find-merchant-by-variation registry search-text)]
      {:canonical-name (:canonical-name match)
       :category (:category match)
       :entity-type (:entity-type match)
       :merchant-id (:merchant-id match)
       :confidence (case (:match-type match)
                     :exact-canonical 1.0
                     :exact-variation 0.95
                     :substring-match 0.70
                     0.50)
       :match-type (:match-type match)
       :source :registry})))

(defn get-merchant-by-id
  "Gets merchant data by canonical merchant ID"
  [merchant-id]
  (let [registry (load-registry-file)]
    (get-in registry [:merchants (keyword merchant-id)])))

(defn list-all-merchants
  "Returns list of all merchants in registry"
  []
  (let [registry (load-registry-file)]
    (map (fn [[merchant-id merchant-data]]
           (assoc merchant-data :merchant-id merchant-id))
         (:merchants registry))))

;; ============================================================================
;; Registry Modifications
;; ============================================================================

(defn add-merchant
  "Adds new merchant to registry
   merchant-data: {:canonical-name :category :entity-type :variations}"
  [merchant-id merchant-data]
  (let [registry (load-registry-file)
        merchant-key (keyword merchant-id)
        updated-registry (assoc-in registry [:merchants merchant-key] merchant-data)]
    (when (save-registry-file updated-registry)
      {:success true
       :merchant-id merchant-id
       :message (str "✅ Merchant '" merchant-id "' added to registry")})))

(defn add-variation
  "Adds variation to existing merchant"
  [merchant-id new-variation]
  (let [registry (load-registry-file)
        merchant-key (keyword merchant-id)
        current-merchant (get-in registry [:merchants merchant-key])]
    (if current-merchant
      (let [updated-variations (conj (vec (:variations current-merchant [])) new-variation)
            updated-merchant (assoc current-merchant :variations updated-variations)
            updated-registry (assoc-in registry [:merchants merchant-key] updated-merchant)]
        (when (save-registry-file updated-registry)
          {:success true
           :merchant-id merchant-id
           :variation new-variation
           :message (str "✅ Variation '" new-variation "' added to merchant '" merchant-id "'")}))
      {:success false
       :error (str "❌ Merchant '" merchant-id "' not found in registry")})))

(defn update-merchant
  "Updates merchant data (canonical name, category, entity-type)"
  [merchant-id updates]
  (let [registry (load-registry-file)
        merchant-key (keyword merchant-id)
        current-merchant (get-in registry [:merchants merchant-key])]
    (if current-merchant
      (let [updated-merchant (merge current-merchant updates)
            updated-registry (assoc-in registry [:merchants merchant-key] updated-merchant)]
        (when (save-registry-file updated-registry)
          {:success true
           :merchant-id merchant-id
           :updates updates
           :message (str "✅ Merchant '" merchant-id "' updated")}))
      {:success false
       :error (str "❌ Merchant '" merchant-id "' not found in registry")})))

;; ============================================================================
;; Pending Classification Management
;; ============================================================================

(defn get-pending-classifications
  "Returns list of unknown merchants needing manual classification
   These are stored in a separate pending.json file"
  []
  (let [pending-file "resources/registry/pending.json"
        file (io/file pending-file)]
    (if (.exists file)
      (try
        (:pending (json/read-str (slurp file) :key-fn keyword) [])
        (catch Exception e
          (println "⚠️  Error loading pending classifications:" (.getMessage e))
          []))
      [])))

(defn add-pending-classification
  "Adds unknown merchant to pending classification list with FULL transaction provenance

   DEDUPLICATION: Only adds if merchant-text NOT already in pending (case-insensitive)

   Arguments:
   - merchant-text: The extracted merchant name
   - transaction-data: Full transaction map with ALL fields for complete provenance:
       {:transaction-id, :type, :date, :amount, :description, :currency,
        :account-name, :account-number, :bank, :beneficiary-name, :clean-merchant,
        :counterparty-info, :context-lines, etc.}

   This allows user to see COMPLETE context before deciding:
   - Create new merchant, OR
   - Add as variation to existing merchant"
  [merchant-text transaction-data]
  (let [pending-file "resources/registry/pending.json"
        current-pending (get-pending-classifications)

        ;; Check if merchant already exists in pending (case-insensitive)
        normalized-search (normalize-text merchant-text)
        already-exists? (some (fn [item]
                               (= (normalize-text (:merchant-text item))
                                  normalized-search))
                             current-pending)]

    (if already-exists?
      ;; Already in pending, skip
      {:success true
       :message (str "⏭️  Merchant '" merchant-text "' already in pending (skipped duplicate)")}

      ;; Not in pending, add it
      (let [;; Store FULL transaction data for complete provenance in UI
            ;; Use clean-merchant if available, fallback to merchant-text
            new-pending-item {:merchant-text (or (:clean-merchant transaction-data) merchant-text)
                              :transaction-id (:transaction-id transaction-data)
                              :transaction-type (:type transaction-data)
                              :timestamp (java.time.Instant/now)
                              :status :pending

                              ;; ✅ STAGE 4 FIELDS at top level (for JavaScript access)
                              :clean-merchant (:clean-merchant transaction-data)
                              :needs-manual-classification (:needs-manual-classification transaction-data true)
                              :entity-resolved? (:entity-resolved? transaction-data false)
                              :entity-id (:entity-id transaction-data)
                              :confidence (:confidence transaction-data)

                              ;; PROVENANCE: Complete transaction details
                              :full-context {:date (:date transaction-data)
                                            :amount (:amount transaction-data)
                                            :description (:description transaction-data)
                                            :currency (:currency transaction-data "MXN")
                                            :account-name (:account-name transaction-data)
                                            :account-number (:account-number transaction-data)
                                            :bank (:bank transaction-data)
                                            :beneficiary-name (:beneficiary-name transaction-data)
                                            :clean-merchant (:clean-merchant transaction-data)
                                            :confidence (:confidence transaction-data)
                                            :counterparty-info (:counterparty-info transaction-data)
                                            :context-lines (:context-lines transaction-data)}}

            updated-pending (conj (vec current-pending) new-pending-item)]
        (ensure-registry-dir)
        (try
          (spit pending-file
                (json/write-str {:pending updated-pending} :indent true))
          {:success true
           :message (str "✅ Merchant '" merchant-text "' added to pending classification with full provenance")}
          (catch Exception e
            {:success false
             :error (.getMessage e)}))))))

(defn remove-pending-classification
  "Removes merchant from pending list (after classification)"
  [merchant-text]
  (let [pending-file "resources/registry/pending.json"
        current-pending (get-pending-classifications)
        updated-pending (remove #(= (:merchant-text %) merchant-text) current-pending)]
    (try
      (spit pending-file
            (json/write-str {:pending updated-pending} :indent true))
      {:success true
       :message (str "✅ Pending classification for '" merchant-text "' removed")}
      (catch Exception e
        {:success false
         :error (.getMessage e)}))))

;; ============================================================================
;; Statistics
;; ============================================================================

(defn registry-statistics
  "Returns statistics about merchant registry"
  []
  (let [merchants (list-all-merchants)
        pending (get-pending-classifications)]
    {:total-merchants (count merchants)
     :total-variations (reduce + (map #(count (:variations % [])) merchants))
     :pending-classifications (count pending)
     :by-entity-type (frequencies (map :entity-type merchants))
     :by-category (frequencies (map :category merchants))}))

;; ============================================================================
;; Category Management
;; ============================================================================

(def ^:private category-registry-file "resources/registry/category_registry.json")

(defn- load-category-registry
  "Loads category registry from JSON file"
  []
  (ensure-registry-dir)
  (let [file (io/file category-registry-file)]
    (if (.exists file)
      (try
        (json/read-str (slurp file) :key-fn keyword)
        (catch Exception e
          (println "⚠️  Error loading category registry:" (.getMessage e))
          {:categories {}}))
      {:categories {}})))

(defn- save-category-registry
  "Saves category registry to JSON file"
  [registry]
  (ensure-registry-dir)
  (try
    (spit category-registry-file
          (json/write-str registry :indent true))
    true
    (catch Exception e
      (println "⚠️  Error saving category registry:" (.getMessage e))
      false)))

(defn list-all-categories
  "Returns list of all categories in registry"
  []
  (let [registry (load-category-registry)]
    (map (fn [[category-id category-data]]
           (assoc category-data :category-id (name category-id)))
         (:categories registry))))

(defn get-category-by-id
  "Gets category data by ID"
  [category-id]
  (let [registry (load-category-registry)]
    (get-in registry [:categories (keyword category-id)])))

(defn add-category
  "Adds new category to registry
   category-data: {:name :description :icon :type :created}"
  [category-id category-data]
  (let [registry (load-category-registry)
        category-key (keyword category-id)
        ;; Add timestamp if not provided
        category-with-timestamp (if (:created category-data)
                                   category-data
                                   (assoc category-data :created (java.time.Instant/now)))
        updated-registry (assoc-in registry [:categories category-key] category-with-timestamp)]
    (when (save-category-registry updated-registry)
      {:success true
       :category-id category-id
       :message (str "✅ Category '" category-id "' added to registry")})))

(defn update-category
  "Updates category data (name, description, icon, type)"
  [category-id updates]
  (let [registry (load-category-registry)
        category-key (keyword category-id)
        current-category (get-in registry [:categories category-key])]
    (if current-category
      (let [updated-category (merge current-category updates)
            updated-registry (assoc-in registry [:categories category-key] updated-category)]
        (when (save-category-registry updated-registry)
          {:success true
           :category-id category-id
           :updates updates
           :message (str "✅ Category '" category-id "' updated")}))
      {:success false
       :error (str "❌ Category '" category-id "' not found in registry")})))

(defn delete-category
  "Removes category from registry"
  [category-id]
  (let [registry (load-category-registry)
        category-key (keyword category-id)]
    (if (get-in registry [:categories category-key])
      (let [updated-registry (update registry :categories dissoc category-key)]
        (when (save-category-registry updated-registry)
          {:success true
           :category-id category-id
           :message (str "✅ Category '" category-id "' removed from registry")}))
      {:success false
       :error (str "❌ Category '" category-id "' not found in registry")})))

(defn category-statistics
  "Returns statistics about category registry"
  []
  (let [categories (list-all-categories)
        by-type (group-by :type categories)]
    {:total-categories (count categories)
     :by-type (into {} (map (fn [[k v]] [k (count v)]) by-type))
     :categories (mapv :category-id categories)}))

;; ============================================================================
;; Transaction History Persistence
;; ============================================================================

(def ^:private transaction-history-file "resources/registry/transaction_history.json")

(defn- load-transaction-history
  "Loads transaction history from JSON file"
  []
  (ensure-registry-dir)
  (let [file (io/file transaction-history-file)]
    (if (.exists file)
      (try
        (:transactions (json/read-str (slurp file) :key-fn keyword) [])
        (catch Exception e
          (println "⚠️  Error loading transaction history:" (.getMessage e))
          []))
      [])))

(defn- remove-nil-values
  "Recursively removes nil values from maps and nested structures"
  [data]
  (cond
    (map? data)
    (into {} (keep (fn [[k v]]
                     (when-not (nil? v)
                       [k (remove-nil-values v)]))
                   data))

    (sequential? data)
    (mapv remove-nil-values data)

    :else
    data))

(defn- save-transaction-history
  "Saves transaction history to JSON file (removes nil values for JSON compatibility)"
  [transactions]
  (ensure-registry-dir)
  (try
    (let [clean-transactions (mapv remove-nil-values transactions)]
      (spit transaction-history-file
            (json/write-str {:transactions clean-transactions} :indent true))
      true)
    (catch Exception e
      (println "⚠️  Error saving transaction history:" (.getMessage e))
      false)))

(defn add-transactions-to-history
  "Adds processed transactions to history with PDF source metadata"
  [transactions pdf-filename]
  (let [current-history (load-transaction-history)
        timestamp (str (java.time.Instant/now))

        ;; Add metadata to each transaction
        enriched-txs (map (fn [tx]
                            (assoc tx
                                   :pdf-source pdf-filename
                                   :processed-at timestamp
                                   :pipeline-version "1.0"))
                          transactions)

        ;; Append to history
        updated-history (concat current-history enriched-txs)]
    (when (save-transaction-history updated-history)
      {:success true
       :added (count enriched-txs)
       :total (count updated-history)
       :message (str "✅ Added " (count enriched-txs) " transactions from " pdf-filename)})))

(defn get-transaction-history
  "Returns all processed transactions from history"
  []
  (load-transaction-history))

(defn get-transaction-history-stats
  "Returns statistics about transaction history"
  []
  (let [history (load-transaction-history)]
    {:total-transactions (count history)
     :by-pdf-source (frequencies (map :pdf-source history))
     :by-type (frequencies (map :type history))
     :by-entity-resolved (frequencies (map :entity-resolved? history))
     :by-needs-classification (frequencies (map :needs-manual-classification history))}))

;; ============================================================================
;; Bank Entity Registry
;; ============================================================================

(def ^:private bank-registry-file "resources/registry/bank_registry.json")

(defn- load-bank-registry
  "Loads bank registry from JSON file"
  []
  (ensure-registry-dir)
  (let [file (io/file bank-registry-file)]
    (if (.exists file)
      (try
        (json/read-str (slurp file) :key-fn keyword)
        (catch Exception e
          (println "⚠️  Error loading bank registry:" (.getMessage e))
          {:banks {}}))
      {:banks {}})))

(defn- find-bank-by-variation
  "Finds bank by matching any variation (exact or substring)"
  [registry search-text]
  (let [banks (:banks registry)]
    (some
     (fn [[bank-id bank-data]]
       (let [variations (:variations bank-data [])
             canonical (:canonical-name bank-data)]
         (when (or
                ;; Exact match with canonical name
                (exact-match? canonical search-text)
                ;; Exact match with any variation
                (some #(exact-match? % search-text) variations)
                ;; Substring match (weaker confidence)
                (some #(substring-match? % search-text) variations))
           (assoc bank-data
                  :bank-id bank-id
                  :match-type (cond
                                (exact-match? canonical search-text) :exact-canonical
                                (some #(exact-match? % search-text) variations) :exact-variation
                                :else :substring-match)))))
     banks)))

(defn lookup-bank
  "Looks up bank in registry by variation text
   Returns {:canonical-name :bank-type :country :currency :payment-method :confidence :match-type} or nil"
  [search-text]
  (let [registry (load-bank-registry)]
    (when-let [match (find-bank-by-variation registry search-text)]
      {:canonical-name (:canonical-name match)
       :bank-type (:bank-type match)
       :country (:country match)
       :currency (:currency match)
       :default-payment-method (:default-payment-method match)
       :payment-network (:canonical-name match)
       :bank-id (:bank-id match)
       :confidence (case (:match-type match)
                     :exact-canonical 1.0
                     :exact-variation 0.95
                     :substring-match 0.70
                     0.50)
       :match-type (:match-type match)
       :source :bank-registry})))

(defn list-all-banks
  "Returns list of all banks in registry"
  []
  (let [registry (load-bank-registry)]
    (map (fn [[bank-id bank-data]]
           (assoc bank-data :bank-id (name bank-id)))
         (:banks registry))))

;; ============================================================================
;; Account Entity Registry
;; ============================================================================

(def ^:private account-registry-file "resources/registry/account_registry.json")

(defn- load-account-registry
  "Loads account registry from JSON file"
  []
  (ensure-registry-dir)
  (let [file (io/file account-registry-file)]
    (if (.exists file)
      (try
        (json/read-str (slurp file) :key-fn keyword)
        (catch Exception e
          (println "⚠️  Error loading account registry:" (.getMessage e))
          {:accounts {}}))
      {:accounts {}})))

(defn- find-account-by-variation
  "Finds account by matching any variation (exact or substring)"
  [registry search-text]
  (let [accounts (:accounts registry)]
    (some
     (fn [[account-id account-data]]
       (let [variations (:variations account-data [])
             canonical (:canonical-name account-data)]
         (when (or
                ;; Exact match with canonical name
                (exact-match? canonical search-text)
                ;; Exact match with any variation
                (some #(exact-match? % search-text) variations)
                ;; Substring match (weaker confidence)
                (some #(substring-match? % search-text) variations))
           (assoc account-data
                  :account-id account-id
                  :match-type (cond
                                (exact-match? canonical search-text) :exact-canonical
                                (some #(exact-match? % search-text) variations) :exact-variation
                                :else :substring-match)))))
     accounts)))

(defn lookup-account
  "Looks up account in registry by variation text
   Returns {:canonical-name :bank-entity :account-type :payment-method :confidence :match-type} or nil"
  [search-text]
  (let [registry (load-account-registry)]
    (when-let [match (find-account-by-variation registry search-text)]
      {:canonical-name (:canonical-name match)
       :bank-entity (:bank-entity match)
       :account-type (:account-type match)
       :currency (:currency match)
       :payment-method (:payment-method match)
       :payment-network (:payment-network match)
       :account-id (:account-id match)
       :confidence (case (:match-type match)
                     :exact-canonical 1.0
                     :exact-variation 0.95
                     :substring-match 0.70
                     0.50)
       :match-type (:match-type match)
       :source :account-registry})))

(defn list-all-accounts
  "Returns list of all accounts in registry"
  []
  (let [registry (load-account-registry)]
    (map (fn [[account-id account-data]]
           (assoc account-data :account-id (name account-id)))
         (:accounts registry))))

;; ============================================================================
;; Category Entity Lookup with Variations
;; ============================================================================

(defn- find-category-by-variation
  "Finds category by matching any variation (exact or substring)"
  [registry search-text]
  (let [categories (:categories registry)]
    (some
     (fn [[category-id category-data]]
       (let [variations (:variations category-data [])
             canonical (:canonical-name category-data)]
         (when (or
                ;; Exact match with canonical name
                (exact-match? canonical search-text)
                ;; Exact match with any variation
                (some #(exact-match? % search-text) variations)
                ;; Substring match (weaker confidence)
                (some #(substring-match? % search-text) variations))
           (assoc category-data
                  :category-id category-id
                  :match-type (cond
                                (exact-match? canonical search-text) :exact-canonical
                                (some #(exact-match? % search-text) variations) :exact-variation
                                :else :substring-match)))))
     categories)))

(defn lookup-category
  "Looks up category in registry by variation text
   Returns {:canonical-name :parent-category :budget-category :tax-treatment :confidence :match-type} or nil"
  [search-text]
  (let [registry (load-category-registry)]
    (when-let [match (find-category-by-variation registry search-text)]
      {:canonical-name (:canonical-name match)
       :parent-category (:parent-category match)
       :budget-category (:budget-category match)
       :budget-subcategory (:budget-subcategory match)
       :typical-tax-treatment (:typical-tax-treatment match)
       :typical-flow-type (:typical-flow-type match)
       :icon (:icon match)
       :category-id (:category-id match)
       :confidence (case (:match-type match)
                     :exact-canonical 1.0
                     :exact-variation 0.95
                     :substring-match 0.70
                     0.50)
       :match-type (:match-type match)
       :source :category-registry})))
