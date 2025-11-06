(ns finance.parsers.wise
  "Wise (formerly TransferWise) CSV parser with multi-currency support.

  CSV format (9 columns):
    Date, Payee Name, Amount, Currency, Exchange Rate, Amount USD, Description, Type, Status

  Example:
    03/20/2024,John Doe,500.00,EUR,0.93,537.63,Freelance payment,Money In,Completed

  Multi-currency:
  - Original amount in source currency (EUR, MXN, etc.)
  - Converted amount in USD
  - Exchange rate tracking"
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io])
  (:import [java.text SimpleDateFormat]))

;; ============================================================================
;; PARSING
;; ============================================================================

(defn parse-date
  "Parse Wise date format: MM/dd/yyyy"
  [date-str]
  (try
    (.parse (SimpleDateFormat. "MM/dd/yyyy") date-str)
    (catch Exception _ nil)))

(defn parse-amount
  "Parse amount string to double."
  [amount-str]
  (try
    (-> amount-str
        (clojure.string/replace #"[$,]" "")
        (Double/parseDouble))
    (catch Exception _ 0.0)))

(defn classify-type
  "Classify Wise transaction type.

  Wise types:
  - Money In = :income
  - Money Out = :expense
  - Transfer = :transfer (default)"
  [wise-type]
  (case (.toLowerCase (or wise-type ""))
    "money in" :income
    "money out" :expense
    :transfer))  ; Default for transfers

(defn build-description
  "Build description with currency conversion details.

  Example:
    (build-description \"Freelance payment\" 500.00 \"EUR\" 0.93 537.63)
    ; => \"Freelance payment | 500.00 EUR → $537.63 USD @ rate 0.9300\""
  [description original-amount original-currency exchange-rate usd-amount]
  (if (= original-currency "USD")
    description
    (format "%s | %.2f %s → $%.2f USD @ rate %.4f"
            description
            original-amount
            original-currency
            usd-amount
            exchange-rate)))

(defn parse-row
  "Parse a single CSV row into transaction map."
  [row source-file line-num]
  (let [[date-str payee-name amount-str currency exchange-rate-str
         amount-usd-str description wise-type status] row

        original-amount (parse-amount amount-str)
        exchange-rate (parse-amount (or exchange-rate-str "1.0"))
        usd-amount (parse-amount amount-usd-str)

        full-description (build-description
                          description
                          original-amount
                          currency
                          exchange-rate
                          usd-amount)]

    {:date (parse-date date-str)
     :description full-description
     :merchant payee-name
     :amount usd-amount  ; Always store in USD
     :type (classify-type wise-type)
     :currency "USD"
     :original-amount original-amount
     :original-currency currency
     :exchange-rate exchange-rate
     :source-file source-file
     :source-line line-num
     :bank :wise
     :status status}))

;; ============================================================================
;; PUBLIC API
;; ============================================================================

(defn parse
  "Parse Wise CSV file.

  Args:
    file-path - Path to CSV file

  Returns vector of transaction maps.

  Example:
    (parse \"data/wise_march_2024.csv\")"
  [file-path]
  (with-open [reader (io/reader file-path)]
    (let [rows (csv/read-csv reader)
          filename (.getName (io/file file-path))
          data-rows (rest rows)]  ; Skip header
      (vec
        (keep-indexed
          (fn [idx row]
            (when (>= (count row) 9)
              (parse-row row filename (+ idx 2))))
          data-rows)))))

;; ============================================================================
;; MULTI-CURRENCY UTILITIES
;; ============================================================================

(defn group-by-currency
  "Group transactions by original currency.

  Example:
    (group-by-currency transactions)
    ; => {\"USD\" [...] \"EUR\" [...] \"MXN\" [...]}"
  [transactions]
  (group-by :original-currency transactions))

(defn currency-summary
  "Calculate summary statistics by currency.

  Returns map of currency -> {:count N :total X :avg-rate R}

  Example:
    (currency-summary transactions)
    ; => {\"EUR\" {:count 5 :total 2500.00 :avg-rate 0.93}
    ;     \"MXN\" {:count 3 :total 5000.00 :avg-rate 0.05}}"
  [transactions]
  (let [by-currency (group-by-currency transactions)]
    (into {}
          (map (fn [[currency txs]]
                 [currency
                  {:count (count txs)
                   :total (reduce + (map :original-amount txs))
                   :avg-rate (/ (reduce + (map :exchange-rate txs))
                               (count txs))}])
               by-currency))))

;; ============================================================================
;; EXAMPLE USAGE (for documentation)
;; ============================================================================

(comment
  (def transactions (parse "data/wise_march_2024.csv"))

  (count transactions)
  ; => 42

  (first transactions)
  ; => {:date #inst "2024-03-20"
  ;     :description "Freelance payment | 500.00 EUR → $537.63 USD @ rate 0.9300"
  ;     :merchant "John Doe"
  ;     :amount 537.63  ; USD
  ;     :type :income
  ;     :currency "USD"
  ;     :original-amount 500.00
  ;     :original-currency "EUR"
  ;     :exchange-rate 0.93
  ;     :source-file "wise_march_2024.csv"
  ;     :source-line 2
  ;     :bank :wise
  ;     :status "Completed"}

  ;; Multi-currency analysis
  (group-by-currency transactions)
  ; => {"USD" [...] "EUR" [...] "MXN" [...]}

  (currency-summary transactions)
  ; => {"EUR" {:count 5 :total 2500.00 :avg-rate 0.93}
  ;     "MXN" {:count 3 :total 5000.00 :avg-rate 0.05}}
  )
