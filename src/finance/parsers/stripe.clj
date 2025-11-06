(ns finance.parsers.stripe
  "Stripe JSON parser (from API exports).

  JSON format:
    {\"data\": [
      {\"id\": \"txn_123\",
       \"amount\": 286770,  // cents
       \"currency\": \"usd\",
       \"description\": \"Payment from John Doe\",
       \"created\": 1735084800,  // Unix timestamp
       ...}
    ]}

  Example:
    https://stripe.com/docs/api/balance_transactions"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io])
  (:import [java.util Date]))

;; ============================================================================
;; PARSING
;; ============================================================================

(defn cents-to-dollars
  "Convert cents to dollars.

  Example:
    (cents-to-dollars 286770)
    ; => 2867.70"
  [cents]
  (/ cents 100.0))

(defn unix-to-date
  "Convert Unix timestamp to java.util.Date.

  Example:
    (unix-to-date 1735084800)
    ; => #inst \"2024-12-25\""
  [unix-timestamp]
  (Date. (* unix-timestamp 1000)))

(defn extract-merchant
  "Extract merchant from Stripe description.

  Stripe format: \"Payment from MERCHANT\" or just \"MERCHANT\"

  Example:
    (extract-merchant \"Payment from John Doe\")
    ; => \"John Doe\""
  [description]
  (if (.startsWith description "Payment from ")
    (subs description 13)
    description))

(defn classify-type
  "Classify Stripe transaction type.

  Stripe balance transactions are typically:
  - :income (payments received)
  - :expense (refunds, fees)"
  [stripe-type description]
  (let [type-lower (.toLowerCase (or stripe-type ""))]
    (cond
      (.contains type-lower "refund") :expense
      (.contains type-lower "fee") :expense
      :else :income)))  ; Most Stripe transactions are income

(defn parse-transaction
  "Parse a single Stripe balance transaction.

  Example:
    (parse-transaction
      {\"id\" \"txn_123\"
       \"amount\" 286770
       \"currency\" \"usd\"
       \"description\" \"Payment from John Doe\"
       \"created\" 1735084800
       \"type\" \"charge\"}
      \"stripe_march_2024.json\")"
  [tx source-file]
  (let [amount-cents (get tx "amount" 0)
        amount-dollars (cents-to-dollars amount-cents)
        description (get tx "description" "")
        tx-type (get tx "type" "")]
    {:id (get tx "id")
     :date (unix-to-date (get tx "created" 0))
     :description description
     :merchant (extract-merchant description)
     :amount amount-dollars
     :type (classify-type tx-type description)
     :currency (.toUpperCase (get tx "currency" "USD"))
     :source-file source-file
     :bank :stripe
     :stripe-type tx-type}))

;; ============================================================================
;; PUBLIC API
;; ============================================================================

(defn parse
  "Parse Stripe JSON export file.

  Args:
    file-path - Path to JSON file

  Returns vector of transaction maps.

  Example:
    (parse \"data/stripe_march_2024.json\")"
  [file-path]
  (let [filename (.getName (io/file file-path))
        json-data (json/read-str (slurp file-path))
        transactions (get json-data "data" [])]
    (vec
      (map #(parse-transaction % filename) transactions))))

;; ============================================================================
;; EXAMPLE USAGE (for documentation)
;; ============================================================================

(comment
  (def transactions (parse "data/stripe_march_2024.json"))

  (count transactions)
  ; => 23

  (first transactions)
  ; => {:id "txn_1abc123"
  ;     :date #inst "2024-12-25T00:00:00.000-00:00"
  ;     :description "Payment from John Doe"
  ;     :merchant "John Doe"
  ;     :amount 2867.70
  ;     :type :income
  ;     :currency "USD"
  ;     :source-file "stripe_march_2024.json"
  ;     :bank :stripe
  ;     :stripe-type "charge"}

  ;; Stripe API format example
  {:id "txn_1abc123"
   :amount 286770  ; cents
   :currency "usd"
   :description "Payment from John Doe"
   :created 1735084800  ; Unix timestamp
   :type "charge"}
  )
