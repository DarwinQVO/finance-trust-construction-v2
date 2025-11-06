(ns finance.parsers.bofa
  "Bank of America CSV parser.

  CSV format:
    Date, Description, Amount

  Example:
    03/20/2024,STARBUCKS #1234 DES:PURCHASE,-45.99
    03/21/2024,SALARY DEPOSIT DES:DIRECT DEP,2000.00"
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [trust.transducers :as xf])
  (:import [java.text SimpleDateFormat]))

;; ============================================================================
;; PARSING
;; ============================================================================

(defn parse-date
  "Parse BofA date format: MM/dd/yyyy

  Example:
    (parse-date \"03/20/2024\")
    ; => #inst \"2024-03-20\""
  [date-str]
  (try
    (.parse (SimpleDateFormat. "MM/dd/yyyy") date-str)
    (catch Exception _ nil)))

(defn parse-amount
  "Parse amount string to double.

  Example:
    (parse-amount \"-45.99\")
    ; => -45.99"
  [amount-str]
  (try
    (Double/parseDouble amount-str)
    (catch Exception _ 0.0)))

(defn extract-merchant
  "Extract merchant name from description.

  BofA format: MERCHANT DES:EXTRA_INFO

  Example:
    (extract-merchant \"STARBUCKS #1234 DES:PURCHASE\")
    ; => \"STARBUCKS\""
  [description]
  (let [parts (clojure.string/split description #"\s+DES:")]
    (-> (first parts)
        (clojure.string/trim)
        (clojure.string/split #"\s+")
        first)))

(defn classify-type
  "Classify transaction type based on amount and description.

  Rules:
  - Positive amount + \"SALARY|DEPOSIT|PAYMENT FROM\" = :income
  - Negative amount + \"CREDIT CARD PAYMENT\" = :transfer
  - Negative amount = :expense
  - Positive amount = :income (default)"
  [amount description]
  (let [upper-desc (.toUpperCase description)]
    (cond
      ;; Credit card payment
      (and (neg? amount)
           (or (.contains upper-desc "CREDIT CARD PAYMENT")
               (.contains upper-desc "PAYMENT - THANK YOU")))
      :transfer

      ;; Income indicators
      (or (pos? amount)
          (.contains upper-desc "SALARY")
          (.contains upper-desc "DEPOSIT")
          (.contains upper-desc "PAYMENT FROM"))
      :income

      ;; Expense (negative amount)
      (neg? amount)
      :expense

      ;; Default
      :else
      :expense)))

(defn parse-row
  "Parse a single CSV row into transaction map.

  Example:
    (parse-row [\"03/20/2024\" \"STARBUCKS #1234\" \"-45.99\"] \"bofa.csv\" 2)
    ; => {:date #inst \"2024-03-20\"
    ;     :description \"STARBUCKS #1234\"
    ;     :merchant \"STARBUCKS\"
    ;     :amount 45.99
    ;     :type :expense
    ;     :source-file \"bofa.csv\"
    ;     :source-line 2}"
  [row source-file line-num]
  (let [[date-str description amount-str] row
        raw-amount (parse-amount amount-str)
        abs-amount (Math/abs raw-amount)]
    {:date (parse-date date-str)
     :description description
     :merchant (extract-merchant description)
     :amount abs-amount
     :type (classify-type raw-amount description)
     :currency "USD"
     :source-file source-file
     :source-line line-num
     :bank :bofa}))

;; ============================================================================
;; PUBLIC API
;; ============================================================================

(defn parse
  "Parse BofA CSV file.

  Args:
    file-path - Path to CSV file

  Returns vector of transaction maps.

  Example:
    (parse \"data/bofa_march_2024.csv\")
    ; => [{:date #inst \"2024-03-20\" :merchant \"STARBUCKS\" ...}
    ;     {:date #inst \"2024-03-21\" :merchant \"SALARY\" ...}]"
  [file-path]
  (with-open [reader (io/reader file-path)]
    (let [rows (csv/read-csv reader)
          filename (.getName (io/file file-path))
          ;; Skip header row
          data-rows (rest rows)]
      (vec
        (keep-indexed
          (fn [idx row]
            (when (>= (count row) 3)
              (parse-row row filename (+ idx 2))))
          data-rows)))))

;; ============================================================================
;; EXAMPLE USAGE (for documentation)
;; ============================================================================

(comment
  ;; Parse BofA file
  (def transactions (parse "data/bofa_march_2024.csv"))

  (count transactions)
  ; => 156

  (first transactions)
  ; => {:date #inst "2024-03-20T00:00:00.000-00:00"
  ;     :description "STARBUCKS #1234 DES:PURCHASE"
  ;     :merchant "STARBUCKS"
  ;     :amount 45.99
  ;     :type :expense
  ;     :currency "USD"
  ;     :source-file "bofa_march_2024.csv"
  ;     :source-line 2
  ;     :bank :bofa}

  ;; Parse and classify
  (require '[finance.classification :as classify])
  (def classified (classify/classify-batch transactions))

  ;; Using transducers (context-independent)
  (def pipeline
    (comp
      (filter #(= (:type %) :expense))
      (map :amount)))

  (transduce pipeline + 0 transactions)
  ; => Total expenses
  )
