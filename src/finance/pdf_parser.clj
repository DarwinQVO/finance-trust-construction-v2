(ns finance.pdf-parser
  "PDF parser for Scotiabank bank statements"
  (:require [clojure.string :as str])
  (:import [org.apache.pdfbox.pdmodel PDDocument]
           [org.apache.pdfbox.text PDFTextStripper]))

;; ============================================================================
;; PDF Text Extraction
;; ============================================================================

(defn extract-text-from-pdf
  "Extracts all text from a PDF file"
  [pdf-file]
  (with-open [document (PDDocument/load pdf-file)]
    (let [stripper (PDFTextStripper.)]
      (.getText stripper document))))

;; ============================================================================
;; Transaction Parsing (Scotiabank Format)
;; ============================================================================

(defn parse-scotiabank-line
  "Parses a single line from Scotiabank statement
   Expected format: DD MMM DESCRIPTION... $AMOUNT $BALANCE"
  [line]
  ;; Match: "17 JUN TRANSF... $3,140.00 $3,319.18"
  (let [result (re-matches #"(\d{1,2})\s+([A-Z]{3})\s+(.+?)\s+(\$[\d,]+\.\d{2}\s+\$[\d,]+\.\d{2})" line)]
    (when-not result
      (println "❌ FAILED to parse line:")
      (println "   Line: " line)
      (println "   Length: " (count line))
      (println "   Last 20 chars: " (subs line (max 0 (- (count line) 20)))))
    (when-let [[_ day month desc amounts] result]
      (let [;; Extract the two amounts from "$3,140.00 $3,319.18"
            amount-parts (re-seq #"\$[\d,]+\.\d{2}" amounts)  ;; ["$3,140.00" "$3,319.18"]
            first-amount (first amount-parts)  ;; "$3,140.00"
            amount-str (subs first-amount 1)   ;; Remove $ sign: "3,140.00"
            ]
        {:date (format "%s-%s" day month)
         :description (str/trim desc)
         :amount (-> amount-str
                     (str/replace "," "")
                     Double/parseDouble)}))))

(defn detect-transaction-type
  "Detects if transaction is debit (retiro) or credit (deposit)"
  [line]
  (cond
    (str/includes? line "RETIRO") :retiro
    (str/includes? line "CARGO") :retiro
    (str/includes? line "DEPOSITO") :deposit
    (str/includes? line "ABONO") :deposit
    (str/includes? line "TRANSF") :deposit
    :else :retiro))  ;; Default to retiro if unclear

(defn- is-relevant-context-line?
  "Returns true if line is relevant context (not header/footer/noise)"
  [line]
  (let [line-trimmed (str/trim line)
        line-upper (str/upper-case line-trimmed)]
    (and
      ;; Not empty
      (> (count line-trimmed) 2)

      ;; Not page numbers or dates alone
      (not (re-matches #"^\d+$" line-trimmed))
      (not (re-matches #"^Página\s+\d+$" line-upper))

      ;; Not common headers/footers
      (not (str/includes? line-upper "SCOTIABANK"))
      (not (str/includes? line-upper "ESTADO DE CUENTA"))
      (not (str/includes? line-upper "PERIODO"))
      (not (str/includes? line-upper "FECHA DE CORTE"))
      (not (str/includes? line-upper "SALDO"))
      (not (str/starts-with? line-upper "FECHA"))

      ;; ========== PDF FOOTER PATTERNS (Legal disclaimers, contact info, etc.) ==========
      ;; Interest rate disclaimers
      (not (str/includes? line-upper "LAS TASAS DE INTERES"))
      (not (str/includes? line-upper "TERMINOS ANUALES"))

      ;; SPEI/Transfer disclaimers
      (not (str/includes? line-upper "EN EL CASO DE ENVIO DE TRANSFERENCIAS"))
      (not (str/includes? line-upper "NOMBRE DEL BENEFICIARIO ES UN DATO NO CERTIFICADO"))

      ;; Commissions and fees
      (not (str/includes? line-upper "COMISIONES APLICADAS"))
      (not (str/includes? line-upper "COMISION POR"))

      ;; Abbreviations section
      (not (str/includes? line-upper "ABREVIATURAS"))
      (not (str/includes? line-upper "ABR."))

      ;; Contact information
      (not (str/includes? line-upper "PARA MAYOR INFORMACION"))
      (not (str/includes? line-upper "PARA MAS INFORMACION"))
      (not (str/includes? line-upper "TELEFONO"))
      (not (str/includes? line-upper "CONSULTE"))
      (not (str/includes? line-upper "WWW."))
      (not (str/includes? line-upper "HTTP"))

      ;; Warnings and legal notices
      (not (str/includes? line-upper "ADVERTENCIAS"))
      (not (str/includes? line-upper "AVISO"))
      (not (str/includes? line-upper "CONSULTA TU ESTADO DE CUENTA"))

      ;; Bank footer branding
      (not (str/includes? line-upper "SCOTIABANK INVERLAT"))
      (not (str/includes? line-upper "BANCO SCOTIABANK"))

      ;; Privacy and legal
      (not (str/includes? line-upper "PRIVACIDAD"))
      (not (str/includes? line-upper "DATOS PERSONALES"))
      (not (str/includes? line-upper "PROTECCION DE DATOS"))

      ;; Not just symbols or separators
      (not (re-matches #"^[-=_*]+$" line-trimmed))

      ;; Not just spaces/dots/dashes
      (not (re-matches #"^[\s\.\-]+$" line-trimmed))

      ;; Has actual content (letters or numbers)
      (re-find #"[A-Za-z0-9]" line-trimmed))))

(defn- group-transaction-lines
  "Groups transaction line with its context lines (until next transaction)
   FILTERS out header/footer/noise from context"
  [lines]
  (let [indexed-lines (map-indexed vector lines)
        ;; Find indices of transaction lines
        tx-indices (->> indexed-lines
                       (filter (fn [[_ line]] (re-find #"^\d{1,2}\s+[A-Z]{3}\s+" line)))
                       (map first))]
    ;; For each transaction, get lines until next transaction
    (for [[idx next-idx] (partition 2 1 (concat tx-indices [(count lines)]))]
      (let [raw-context (take (- next-idx idx 1) (drop (inc idx) lines))
            ;; FILTER: Only keep relevant context lines
            filtered-context (filter is-relevant-context-line? raw-context)
            ;; LIMIT: Max 10 context lines per transaction (prevent text explosion)
            limited-context (take 10 filtered-context)]
        {:main-line (nth lines idx)
         :context-lines (vec limited-context)}))))

(defn- extract-merchant-from-context
  "Extracts merchant/beneficiary name from context lines using intelligent pattern matching"
  [context-lines]
  (when (seq context-lines)
    (println (format "🔍 PDF Parser extracting from %d context lines" (count context-lines)))

    ;; Strategy 1: Look for "TRANSFERENCIA A [NAME]" pattern (most reliable)
    (if-let [transfer-line (first (filter #(str/includes? % "TRANSFERENCIA A") context-lines))]
      (let [;; Extract name after "TRANSFERENCIA A"
            name (-> transfer-line
                     (str/replace #".*TRANSFERENCIA A\s+" "")  ;; Remove prefix
                     (str/replace #"/.*" "")                    ;; Remove suffix after /
                     str/trim
                     (str/replace #"\s+" " "))]                 ;; Normalize spaces
        (when (and (> (count name) 3)
                   (re-find #"^[A-Z\s]+$" name))
          (println (format "✅ Strategy 1 (TRANSFERENCIA A): '%s'" name))
          name))

      ;; Strategy 2: Score all person name candidates (not institutions)
      (let [institution-keywords #{"SANTANDER" "AZTECA" "BANAMEX" "WISE" "PAYMENTS" "LIMITED"
                                   "TRANSF" "INTERBANCARIA" "SPEI" "FECHA" "ABONO"
                                   "RFC" "CURP" "FOLIO" "REFERENCIA" "PAYMENT" "ORIGEN"}

            ;; Find and score candidates
            candidates (->> context-lines
                           (map str/trim)
                           (map #(str/replace % #"\s+" " "))  ;; Normalize spaces
                           (filter (fn [line]
                                     (and
                                       ;; All caps
                                       (re-find #"^[A-Z\s]+$" line)
                                       ;; Reasonable length
                                       (> (count line) 5)
                                       (< (count line) 50)
                                       ;; 2-5 words
                                       (let [words (str/split line #"\s+")]
                                         (and (>= (count words) 2)
                                              (<= (count words) 5)))
                                       ;; NOT an institution keyword
                                       (not-any? #(str/includes? line %) institution-keywords))))
                           ;; Score by length (longer names = more complete)
                           (map (fn [line]
                                  (let [words (str/split line #"\s+")
                                        ;; Bonus points for typical person name patterns
                                        pattern-bonus (cond
                                                       ;; 4 words (2 apellidos + 2 nombres)
                                                       (= (count words) 4) 10
                                                       ;; 3 words (apellido + 2 nombres)
                                                       (= (count words) 3) 8
                                                       ;; 2 words (nombre apellido)
                                                       (= (count words) 2) 5
                                                       :else 0)]
                                    {:line line
                                     :score (+ (count line) pattern-bonus)})))
                           (sort-by :score >))]

        (when-let [best (first candidates)]
          (println (format "✅ Strategy 2 (best candidate): '%s' (score: %d)"
                          (:line best) (:score best)))
          (:line best))))))

(defn- extract-rfc-from-context
  "Extracts RFC/CURP from context lines"
  [context-lines]
  (when-let [rfc-line (first (filter #(str/includes? % "RFC/CURP:") context-lines))]
    (-> rfc-line
        (str/replace #".*RFC/CURP:\s*" "")
        str/trim)))

(defn- extract-reference-from-context
  "Extracts reference numbers from context lines"
  [context-lines]
  (let [refs (->> context-lines
                  (filter #(or (str/includes? % "REFERENCIA:")
                              (str/includes? % "FOLIO:")))
                  (map str/trim))]
    (when (seq refs)
      (str/join " | " refs))))

(defn parse-scotiabank-transactions
  "Parses Scotiabank transactions from PDF text with full context"
  [pdf-text]
  (let [lines (str/split-lines pdf-text)
        grouped-txs (group-transaction-lines lines)]
    (->> grouped-txs
         (map (fn [{:keys [main-line context-lines]}]
                (when-let [parsed (parse-scotiabank-line main-line)]
                  (let [tx-type (detect-transaction-type main-line)
                        ;; Extract all useful info from context
                        merchant (extract-merchant-from-context context-lines)
                        rfc (extract-rfc-from-context context-lines)
                        reference (extract-reference-from-context context-lines)]
                    (merge parsed
                           {:retiro (when (= tx-type :retiro) (:amount parsed))
                            :deposit (when (= tx-type :deposit) (:amount parsed))
                            :beneficiary-name merchant
                            :rfc rfc
                            :reference reference
                            :context-lines context-lines  ;; Keep full context for debugging
                            :has-context (seq context-lines)})))))
         (filter some?)
         vec)))

;; ============================================================================
;; Public API
;; ============================================================================

(defn parse-pdf
  "Parses a Scotiabank PDF file and returns transactions"
  [pdf-file]
  (let [text (extract-text-from-pdf pdf-file)
        _ (println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        _ (println "RAW PDF TEXT (first 1000 chars):")
        _ (println (subs text 0 (min 1000 (count text))))
        _ (println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        lines (str/split-lines text)
        _ (println (format "Total lines: %d" (count lines)))
        _ (println "\nLines 30-80 (where transactions should be):")
        _ (doseq [[i line] (map-indexed vector (take 50 (drop 30 lines)))]
            (println (format "%3d: %s" (+ i 30) line)))
        _ (println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        transaction-lines (filter #(re-find #"^\d{1,2}\s+[A-Z]{3}\s+" %) lines)
        _ (println (format "Transaction lines matching pattern (DD MMM ...): %d" (count transaction-lines)))
        _ (when (seq transaction-lines)
            (println "First 5 matching lines:")
            (doseq [line (take 5 transaction-lines)]
              (println "  → " line)))
        transactions (parse-scotiabank-transactions text)
        _ (println (format "Parsed transactions: %d" (count transactions)))]
    {:success true
     :transaction-count (count transactions)
     :transactions transactions
     :debug {:raw-text-preview (subs text 0 (min 1000 (count text)))
             :total-lines (count lines)
             :transaction-lines (count transaction-lines)
             :sample-lines (vec (take 30 lines))}}))
