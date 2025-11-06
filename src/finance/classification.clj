(ns finance.classification
  "Transaction classification using data-driven rules.

  Implements Rich Hickey's 'Data > Mechanism' principle:
  - Rules are pure data (EDN files)
  - Engine is generic (works with any rules)
  - Easy to add rules without code changes"
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]))

;; ============================================================================
;; RULE LOADING
;; ============================================================================

(defn load-rules
  "Load classification rules from EDN file.

  Args:
    file-path - Path to EDN file

  Returns vector of rule maps.

  Example:
    (load-rules \"resources/rules/merchant-rules.edn\")"
  [file-path]
  (-> file-path
      io/resource
      slurp
      edn/read-string))

;; Cached default rules.
(defonce ^:private default-rules
  (delay (load-rules "rules/merchant-rules.edn")))

(defn get-default-rules
  "Get default merchant classification rules.

  Rules are cached (loaded once).

  Example:
    (get-default-rules)"
  []
  @default-rules)

;; ============================================================================
;; RULE MATCHING
;; ============================================================================

(defn matches-pattern?
  "Check if a value matches a pattern.

  Pattern can be:
  - String: Exact match (case-insensitive)
  - Regex: Pattern match

  Example:
    (matches-pattern? \"STARBUCKS\" \"STARBUCKS\")  ; => true
    (matches-pattern? #\"STARBUCKS.*\" \"STARBUCKS #1234\")  ; => true"
  [pattern value]
  (when (and pattern value)
    (let [upper-value (.toUpperCase (str value))]
      (cond
        (string? pattern)
        (= (.toUpperCase pattern) upper-value)

        (instance? java.util.regex.Pattern pattern)
        (boolean (re-matches pattern upper-value))

        :else false))))

(defn matches-rule?
  "Check if a transaction matches a classification rule.

  Args:
    rule - Rule map with :pattern
    tx - Transaction map with :description or :merchant

  Returns true if rule matches.

  Example:
    (matches-rule?
      {:pattern \"STARBUCKS\"}
      {:description \"STARBUCKS #1234\"})"
  [rule tx]
  (let [pattern (:pattern rule)
        description (or (:description tx) "")
        merchant (or (:merchant tx) "")]
    (or (matches-pattern? pattern description)
        (matches-pattern? pattern merchant))))

(defn find-matching-rules
  "Find all rules that match a transaction.

  Returns vector of matching rules, sorted by priority (highest first).

  Example:
    (find-matching-rules rules tx)"
  [rules tx]
  (->> rules
       (filter #(matches-rule? % tx))
       (sort-by :priority >)
       vec))

(defn find-best-rule
  "Find the best matching rule (highest priority/confidence).

  Returns the best rule or nil if no match.

  Example:
    (find-best-rule rules tx)"
  [rules tx]
  (first (find-matching-rules rules tx)))

;; ============================================================================
;; CLASSIFICATION
;; ============================================================================

(defn apply-rule
  "Apply a classification rule to a transaction.

  Returns transaction with classification fields added.

  Example:
    (apply-rule
      {:merchant :starbucks :category :restaurants :confidence 0.95}
      {:description \"STARBUCKS #1234\" :amount 4.99})"
  [rule tx]
  (cond-> tx
    (:merchant rule)
    (assoc :merchant-id (:merchant rule))

    (:category rule)
    (assoc :category-id (:category rule))

    (:type rule)
    (assoc :type (:type rule))

    (:confidence rule)
    (assoc :confidence (:confidence rule))

    true
    (assoc :classification-rule-id (:id rule))))

(defn classify
  "Classify a single transaction using rules.

  Args:
    tx - Transaction map
    rules - (optional) Vector of rules, defaults to default rules

  Returns transaction with classification fields added.

  Example:
    (classify {:description \"STARBUCKS #1234\" :amount 4.99})
    ; => {:description \"STARBUCKS #1234\"
    ;     :amount 4.99
    ;     :merchant-id :starbucks
    ;     :category-id :restaurants
    ;     :type :expense
    ;     :confidence 0.95
    ;     :classification-rule-id :starbucks-prefix}"
  ([tx]
   (classify tx (get-default-rules)))
  ([tx rules]
   (if-let [rule (find-best-rule rules tx)]
     (apply-rule rule tx)
     ;; No matching rule - mark as unclassified
     (assoc tx
            :category-id :uncategorized
            :confidence 0.0))))

(defn classify-batch
  "Classify a batch of transactions.

  Args:
    txs - Vector of transactions
    rules - (optional) Vector of rules

  Returns vector of classified transactions.

  Example:
    (classify-batch transactions)"
  ([txs]
   (classify-batch txs (get-default-rules)))
  ([txs rules]
   (mapv #(classify % rules) txs)))

;; ============================================================================
;; CONFIDENCE FILTERING
;; ============================================================================

(defn high-confidence?
  "Check if transaction has high confidence (>= 0.9).

  Example:
    (high-confidence? tx)"
  [tx]
  (>= (or (:confidence tx) 0.0) 0.9))

(defn medium-confidence?
  "Check if transaction has medium confidence (0.7-0.89).

  Example:
    (medium-confidence? tx)"
  [tx]
  (let [conf (or (:confidence tx) 0.0)]
    (and (>= conf 0.7) (< conf 0.9))))

(defn low-confidence?
  "Check if transaction has low confidence (< 0.7).

  Example:
    (low-confidence? tx)"
  [tx]
  (< (or (:confidence tx) 0.0) 0.7))

(defn filter-by-confidence
  "Filter transactions by confidence level.

  Args:
    txs - Vector of transactions
    level - One of: :high :medium :low

  Example:
    (filter-by-confidence transactions :high)"
  [txs level]
  (let [pred (case level
               :high high-confidence?
               :medium medium-confidence?
               :low low-confidence?)]
    (filterv pred txs)))

;; ============================================================================
;; CLASSIFICATION STATISTICS
;; ============================================================================

(defn classification-stats
  "Calculate classification statistics for a batch of transactions.

  Returns:
    {:total N
     :classified M
     :unclassified K
     :high-confidence H
     :medium-confidence M
     :low-confidence L
     :by-category {...}
     :by-merchant {...}}

  Example:
    (classification-stats transactions)"
  [txs]
  (let [classified (filter :category-id txs)
        unclassified (remove :category-id txs)]
    {:total (count txs)
     :classified (count classified)
     :unclassified (count unclassified)
     :high-confidence (count (filter high-confidence? txs))
     :medium-confidence (count (filter medium-confidence? txs))
     :low-confidence (count (filter low-confidence? txs))
     :by-category (frequencies (map :category-id txs))
     :by-merchant (frequencies (map :merchant-id txs))}))

;; ============================================================================
;; MANUAL CLASSIFICATION
;; ============================================================================

(defn classify-manual
  "Manually classify a transaction.

  Use this when automatic classification fails or for corrections.

  Args:
    tx - Transaction
    opts - Classification:
           :merchant-id - Merchant ID
           :category-id - Category ID
           :type - Transaction type
           :confidence - Confidence (default 1.0 for manual)

  Example:
    (classify-manual tx
      {:merchant-id :new-cafe
       :category-id :restaurants
       :type :expense
       :confidence 1.0})"
  [tx {:keys [merchant-id category-id type confidence] :or {confidence 1.0}}]
  (cond-> tx
    merchant-id (assoc :merchant-id merchant-id)
    category-id (assoc :category-id category-id)
    type (assoc :type type)
    true (assoc :confidence confidence
                :classified-manually true)))

;; ============================================================================
;; RULE MANAGEMENT
;; ============================================================================

(defn add-rule
  "Add a new rule to the rules vector.

  Example:
    (add-rule rules
      {:id :new-cafe
       :pattern \"NEW CAFE\"
       :merchant :new-cafe
       :category :restaurants
       :type :expense
       :confidence 0.90
       :priority 15})"
  [rules new-rule]
  (conj rules new-rule))

(defn remove-rule
  "Remove a rule by ID.

  Example:
    (remove-rule rules :starbucks-exact)"
  [rules rule-id]
  (filterv #(not= (:id %) rule-id) rules))

(defn update-rule
  "Update a rule by ID.

  Example:
    (update-rule rules :starbucks-exact
      {:confidence 0.99})"
  [rules rule-id updates]
  (mapv (fn [rule]
          (if (= (:id rule) rule-id)
            (merge rule updates)
            rule))
        rules))

(defn save-rules
  "Save rules to EDN file.

  Example:
    (save-rules rules \"resources/rules/merchant-rules.edn\")"
  [rules file-path]
  (spit file-path (with-out-str (clojure.pprint/pprint rules))))

;; ============================================================================
;; EXAMPLE USAGE (for documentation)
;; ============================================================================

(comment
  ;; Load rules
  (def rules (get-default-rules))
  (count rules)  ; => 27 rules

  ;; Classify single transaction
  (classify {:description "STARBUCKS #1234" :amount 4.99})
  ; => {:description "STARBUCKS #1234"
  ;     :amount 4.99
  ;     :merchant-id :starbucks
  ;     :category-id :restaurants
  ;     :type :expense
  ;     :confidence 0.95
  ;     :classification-rule-id :starbucks-prefix}

  ;; Classify batch
  (def classified-txs (classify-batch transactions))

  ;; Filter by confidence
  (filter-by-confidence classified-txs :high)
  (filter-by-confidence classified-txs :low)

  ;; Statistics
  (classification-stats classified-txs)
  ; => {:total 156
  ;     :classified 142
  ;     :unclassified 14
  ;     :high-confidence 128
  ;     :medium-confidence 14
  ;     :low-confidence 14
  ;     :by-category {:restaurants 45 :shopping 32 ...}
  ;     :by-merchant {:starbucks 12 :amazon 8 ...}}

  ;; Manual classification
  (classify-manual tx
    {:merchant-id :new-cafe
     :category-id :restaurants
     :type :expense})

  ;; Rule management
  (def new-rules (add-rule rules
                   {:id :new-cafe
                    :pattern "NEW CAFE"
                    :merchant :new-cafe
                    :category :restaurants
                    :confidence 0.90}))

  (save-rules new-rules "resources/rules/merchant-rules.edn")
  )
