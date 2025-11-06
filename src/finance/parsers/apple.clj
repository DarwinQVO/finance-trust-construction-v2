(ns finance.parsers.apple
  "Apple Card CSV parser.

  CSV format:
    Date, Description, Amount, Category, Merchant

  Example:
    03/20/2024,STARBUCKS PURCHASE,45.99,Restaurants,Starbucks"
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io])
  (:import [java.text SimpleDateFormat]))

;; ============================================================================
;; PARSING
;; ============================================================================

(defn parse-date
  "Parse Apple Card date format: MM/dd/yyyy"
  [date-str]
  (try
    (.parse (SimpleDateFormat. "MM/dd/yyyy") date-str)
    (catch Exception _ nil)))

(defn parse-amount
  "Parse amount string to double."
  [amount-str]
  (try
    (Double/parseDouble amount-str)
    (catch Exception _ 0.0)))

(defn classify-type
  "Classify transaction type.

  Apple Card transactions are:
  - :transfer if description contains PAYMENT
  - :expense otherwise (all charges)"
  [description]
  (let [upper-desc (.toUpperCase description)]
    (if (or (.contains upper-desc "PAYMENT")
            (.contains upper-desc "THANK YOU"))
      :transfer
      :expense)))

(defn parse-row
  "Parse a single CSV row into transaction map."
  [row source-file line-num]
  (let [[date-str description amount-str category merchant] row
        amount (parse-amount amount-str)]
    {:date (parse-date date-str)
     :description description
     :merchant (or merchant (first (clojure.string/split description #"\s+")))
     :amount amount
     :type (classify-type description)
     :category-hint category  ; Hint from Apple, may override with our rules
     :currency "USD"
     :source-file source-file
     :source-line line-num
     :bank :apple-card}))

;; ============================================================================
;; PUBLIC API
;; ============================================================================

(defn parse
  "Parse Apple Card CSV file.

  Args:
    file-path - Path to CSV file

  Returns vector of transaction maps.

  Example:
    (parse \"data/apple_march_2024.csv\")"
  [file-path]
  (with-open [reader (io/reader file-path)]
    (let [rows (csv/read-csv reader)
          filename (.getName (io/file file-path))
          data-rows (rest rows)]  ; Skip header
      (vec
        (keep-indexed
          (fn [idx row]
            (when (>= (count row) 5)
              (parse-row row filename (+ idx 2))))
          data-rows)))))

;; ============================================================================
;; EXAMPLE USAGE (for documentation)
;; ============================================================================

(comment
  (def transactions (parse "data/apple_march_2024.csv"))

  (first transactions)
  ; => {:date #inst "2024-03-20"
  ;     :description "STARBUCKS PURCHASE"
  ;     :merchant "Starbucks"
  ;     :amount 45.99
  ;     :type :expense
  ;     :category-hint "Restaurants"
  ;     :currency "USD"
  ;     :source-file "apple_march_2024.csv"
  ;     :source-line 2
  ;     :bank :apple-card}
  )
