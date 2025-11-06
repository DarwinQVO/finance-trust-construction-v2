(ns finance.entities
  "Entity registries for banks, merchants, and categories.

  Uses trust.identity primitives to manage:
  - Banks (BofA, Apple Card, Stripe, Wise, etc.)
  - Merchants (Starbucks, Amazon, etc.)
  - Categories (Restaurants, Shopping, etc.)
  - Accounts (Checking, Savings, Credit Card)

  All entities have:
  - Canonical names
  - Aliases for normalization
  - Metadata"
  (:require [trust.identity :as id]))

;; ============================================================================
;; BANKS
;; ============================================================================

(def default-banks
  "Default bank entities."
  {:bofa {:canonical-name "Bank of America"
          :aliases ["BofA" "BoA" "Bank of America" "BANK OF AMERICA"]
          :type :bank
          :country "USA"}

   :apple-card {:canonical-name "Apple Card"
                :aliases ["Apple Card" "APPLE CARD" "Apple" "APPLE"]
                :type :credit-card
                :country "USA"}

   :stripe {:canonical-name "Stripe"
            :aliases ["Stripe" "STRIPE"]
            :type :payment-processor
            :country "USA"}

   :wise {:canonical-name "Wise"
          :aliases ["Wise" "TransferWise" "WISE"]
          :type :payment-processor
          :country "UK"}

   :scotiabank {:canonical-name "Scotiabank"
                :aliases ["Scotiabank" "SCOTIABANK" "Scotia"]
                :type :bank
                :country "Canada"}})

(defn register-default-banks!
  "Register all default banks in a registry.

  Example:
    (def banks (id/registry))
    (register-default-banks! banks)"
  [registry]
  (doseq [[bank-id bank-data] default-banks]
    (id/register! registry bank-id bank-data)))

(defn normalize-bank
  "Normalize bank name to canonical ID.

  Args:
    registry - Bank registry
    bank-name - Raw bank name (e.g., \"BofA\")

  Returns canonical bank ID (e.g., :bofa) or nil if not found.

  Example:
    (normalize-bank banks \"BofA\")
    ; => :bofa"
  [registry bank-name]
  (let [upper-name (.toUpperCase bank-name)]
    (->> (id/list-all registry)
         (filter (fn [[_ bank]]
                   (some #(= % upper-name)
                         (map #(.toUpperCase %) (:aliases bank)))))
         first
         first)))

;; ============================================================================
;; MERCHANTS
;; ============================================================================

(def default-merchants
  "Default merchant entities."
  {:starbucks {:canonical-name "Starbucks"
               :aliases ["STARBUCKS" "Starbucks" "SBUX"]
               :category :restaurants
               :confidence 0.98}

   :amazon {:canonical-name "Amazon"
            :aliases ["AMAZON" "Amazon" "AMZN"]
            :category :shopping
            :confidence 0.95}

   :uber {:canonical-name "Uber"
          :aliases ["UBER" "Uber"]
          :category :transportation
          :confidence 0.95}

   :netflix {:canonical-name "Netflix"
             :aliases ["NETFLIX" "Netflix"]
             :category :entertainment
             :confidence 0.98}

   :whole-foods {:canonical-name "Whole Foods"
                 :aliases ["WHOLE FOODS" "Whole Foods" "WFM"]
                 :category :groceries
                 :confidence 0.95}

   :apple-store {:canonical-name "Apple Store"
                 :aliases ["APPLE STORE" "Apple Store" "APPLE.COM"]
                 :category :shopping
                 :confidence 0.95}

   :target {:canonical-name "Target"
            :aliases ["TARGET" "Target"]
            :category :shopping
            :confidence 0.95}

   :cvs {:canonical-name "CVS Pharmacy"
         :aliases ["CVS" "CVS PHARMACY" "CVS Pharmacy"]
         :category :pharmacy
         :confidence 0.95}})

(defn register-default-merchants!
  "Register all default merchants in a registry.

  Example:
    (def merchants (id/registry))
    (register-default-merchants! merchants)"
  [registry]
  (doseq [[merchant-id merchant-data] default-merchants]
    (id/register! registry merchant-id merchant-data)))

(defn normalize-merchant
  "Normalize merchant name to canonical ID.

  Args:
    registry - Merchant registry
    merchant-name - Raw merchant name (e.g., \"STARBUCKS\")

  Returns canonical merchant ID (e.g., :starbucks) or nil if not found.

  Example:
    (normalize-merchant merchants \"STARBUCKS\")
    ; => :starbucks"
  [registry merchant-name]
  (let [upper-name (.toUpperCase merchant-name)]
    (->> (id/list-all registry)
         (filter (fn [[_ merchant]]
                   (some #(= % upper-name)
                         (map #(.toUpperCase %) (:aliases merchant)))))
         first
         first)))

(defn register-merchant!
  "Register a new merchant.

  Example:
    (register-merchant! merchants :new-cafe
      {:canonical-name \"New Cafe\"
       :aliases [\"NEW CAFE\" \"New Cafe\"]
       :category :restaurants
       :confidence 0.90})"
  [registry merchant-id merchant-data]
  (id/register! registry merchant-id merchant-data))

;; ============================================================================
;; CATEGORIES
;; ============================================================================

(def default-categories
  "Default category entities."
  {:restaurants {:name "Restaurants"
                 :type :expense
                 :color "#E74C3C"}

   :groceries {:name "Groceries"
               :type :expense
               :color "#3498DB"}

   :shopping {:name "Shopping"
              :type :expense
              :color "#9B59B6"}

   :transportation {:name "Transportation"
                    :type :expense
                    :color "#F39C12"}

   :entertainment {:name "Entertainment"
                   :type :expense
                   :color "#1ABC9C"}

   :pharmacy {:name "Pharmacy"
              :type :expense
              :color "#34495E"}

   :utilities {:name "Utilities"
               :type :expense
               :color "#95A5A6"}

   :salary {:name "Salary"
            :type :income
            :color "#27AE60"}

   :freelance {:name "Freelance"
               :type :income
               :color "#2ECC71"}

   :payment {:name "Payment"
             :type :transfer
             :color "#7F8C8D"}

   :transfer {:name "Transfer"
              :type :transfer
              :color "#BDC3C7"}

   :uncategorized {:name "Uncategorized"
                   :type :unknown
                   :color "#95A5A6"}})

(defn register-default-categories!
  "Register all default categories in a registry.

  Example:
    (def categories (id/registry))
    (register-default-categories! categories)"
  [registry]
  (doseq [[category-id category-data] default-categories]
    (id/register! registry category-id category-data)))

(defn register-category!
  "Register a new category.

  Example:
    (register-category! categories :pets
      {:name \"Pets\"
       :type :expense
       :color \"#E67E22\"})"
  [registry category-id category-data]
  (id/register! registry category-id category-data))

;; ============================================================================
;; ACCOUNTS
;; ============================================================================

(defn create-account
  "Create an account entity.

  Args:
    account-name - Display name
    bank-id - Bank identifier
    account-type - One of: :checking :savings :credit-card

  Returns account map.

  Example:
    (create-account \"Checking\" :bofa :checking)"
  [account-name bank-id account-type]
  {:name account-name
   :bank-id bank-id
   :type account-type
   :created-at (java.util.Date.)})

(defn register-account!
  "Register a new account.

  Example:
    (def accounts (id/registry))
    (register-account! accounts :bofa-checking
      (create-account \"Checking\" :bofa :checking))"
  [registry account-id account-data]
  (id/register! registry account-id account-data))

;; ============================================================================
;; NORMALIZATION
;; ============================================================================

(defn normalize-all
  "Normalize all entity references in a transaction.

  Args:
    tx - Transaction map
    banks-registry - Bank registry
    merchants-registry - Merchant registry

  Returns transaction with normalized IDs.

  Example:
    (normalize-all
      {:bank \"BofA\" :merchant \"STARBUCKS\"}
      banks
      merchants)
    ; => {:bank-id :bofa :merchant-id :starbucks ...}"
  [tx banks-registry merchants-registry]
  (cond-> tx
    (:bank tx)
    (assoc :bank-id (normalize-bank banks-registry (:bank tx)))

    (:merchant tx)
    (assoc :merchant-id (normalize-merchant merchants-registry (:merchant tx)))))

;; ============================================================================
;; QUERIES
;; ============================================================================

(defn get-bank
  "Get bank by ID.

  Example:
    (get-bank banks :bofa)"
  [registry bank-id]
  (id/lookup registry bank-id))

(defn get-merchant
  "Get merchant by ID.

  Example:
    (get-merchant merchants :starbucks)"
  [registry merchant-id]
  (id/lookup registry merchant-id))

(defn get-category
  "Get category by ID.

  Example:
    (get-category categories :restaurants)"
  [registry category-id]
  (id/lookup registry category-id))

(defn list-banks
  "List all banks.

  Example:
    (list-banks banks)"
  [registry]
  (id/list-all registry))

(defn list-merchants
  "List all merchants.

  Example:
    (list-merchants merchants)"
  [registry]
  (id/list-all registry))

(defn list-categories
  "List all categories.

  Example:
    (list-categories categories)"
  [registry]
  (id/list-all registry))

(defn merchants-by-category
  "Get all merchants in a specific category.

  Example:
    (merchants-by-category merchants :restaurants)"
  [registry category]
  (->> (id/list-all registry)
       (filter (fn [[_ merchant]]
                 (= (:category merchant) category)))
       (into {})))

;; ============================================================================
;; EXAMPLE USAGE (for documentation)
;; ============================================================================

(comment
  ;; Create registries
  (def banks (id/registry))
  (def merchants (id/registry))
  (def categories (id/registry))
  (def accounts (id/registry))

  ;; Register defaults
  (register-default-banks! banks)
  (register-default-merchants! merchants)
  (register-default-categories! categories)

  ;; Normalization
  (normalize-bank banks "BofA")  ; => :bofa
  (normalize-merchant merchants "STARBUCKS")  ; => :starbucks

  ;; Register new entities
  (register-merchant! merchants :new-cafe
    {:canonical-name "New Cafe"
     :aliases ["NEW CAFE" "New Cafe"]
     :category :restaurants
     :confidence 0.90})

  (register-category! categories :pets
    {:name "Pets"
     :type :expense
     :color "#E67E22"})

  (register-account! accounts :bofa-checking
    (create-account "Checking" :bofa :checking))

  ;; Queries
  (get-bank banks :bofa)
  (get-merchant merchants :starbucks)
  (get-category categories :restaurants)

  (list-banks banks)
  (list-merchants merchants)
  (merchants-by-category merchants :restaurants)

  ;; Normalize transaction
  (normalize-all
    {:bank "BofA"
     :merchant "STARBUCKS"
     :amount 45.99}
    banks
    merchants)
  ; => {:bank "BofA"
  ;     :bank-id :bofa
  ;     :merchant "STARBUCKS"
  ;     :merchant-id :starbucks
  ;     :amount 45.99}
  )
