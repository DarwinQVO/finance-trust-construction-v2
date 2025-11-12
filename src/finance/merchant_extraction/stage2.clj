(ns finance.merchant-extraction.stage2
  "Stage 2: Counterparty Detection (Payment Aggregators)"
  (:require [finance.merchant-extraction.protocols :as proto]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]))

;; ============================================================================
;; RFC Extraction
;; ============================================================================

(defn- extract-rfc-from-context
  "Extracts Mexican RFC (Registro Federal de Contribuyentes) from context-lines
   RFC format: 3-4 letters + 6 digits + 3 alphanumeric characters
   Examples: SAT8410245V8, CNM980114PI2, DCO110714JH9"
  [context-lines]
  (when (seq context-lines)
    (let [;; RFC pattern: 3-4 uppercase letters, 6 digits, 3 alphanumeric
          rfc-pattern #"([A-Z]{3,4}\d{6}[A-Z0-9]{3})"
          ;; Search all context lines
          all-text (str/join " " context-lines)]
      (when-let [match (re-find rfc-pattern all-text)]
        (if (string? match) match (second match))))))

(defn- generic-merchant?
  "Returns true if merchant name is generic and needs RFC-based identification"
  [merchant-text]
  (when merchant-text
    (let [generic-terms #{"COBRANZA" "DOMICILIACION" "PAGO" "CARGO"}
          normalized (str/upper-case (str/trim merchant-text))]
      (contains? generic-terms normalized))))

;; ============================================================================
;; Pattern Matching
;; ============================================================================

(defn- pattern-matches?
  "Returns true if pattern matches description"
  [pattern description case-sensitive?]
  (let [desc (if case-sensitive? description (str/upper-case description))
        pat (if case-sensitive? pattern (str/upper-case pattern))
        regex (re-pattern pat)]
    (some? (re-find regex desc))))

(defn- extract-after-pattern
  "Extracts text after the pattern match"
  [description pattern case-sensitive?]
  (when pattern  ;; Return nil if pattern is nil
    (let [desc (if case-sensitive? description (str/upper-case description))
          pat (if case-sensitive? pattern (str/upper-case pattern))
          regex (re-pattern pat)]
      (when-let [match (re-find regex desc)]
        (let [match-str (if (string? match) match (first match))
              idx (.indexOf desc match-str)]
          (when (>= idx 0)
            (let [after-idx (+ idx (count match-str))
                  remaining (subs description after-idx)]
              (str/trim remaining))))))))

(defn- match-single-counterparty
  "Returns counterparty info if transaction matches, nil otherwise"
  [counterparty-key counterparty-def description case-sensitive?]
  (let [patterns (:patterns counterparty-def)]
    (some
     (fn [pattern]
       (when (pattern-matches? pattern description case-sensitive?)
         (let [merchant-hint (extract-after-pattern
                              description
                              (:extract-after counterparty-def)
                              case-sensitive?)]
           {:detected? true
            :counterparty-id counterparty-key
            :counterparty-type (:type counterparty-def)
            :category (:category counterparty-def)  ;; ← Add semantic category
            :actual-merchant-hint merchant-hint
            :extract-after (:extract-after counterparty-def)
            :confidence (:confidence counterparty-def)
            :matched-pattern pattern})))
     patterns)))

(defn- find-matching-counterparty
  "Returns first matching counterparty (respects priority order)"
  [typed-tx rules]
  (let [;; Use FULL text: description + context-lines for matching
        full-text (str (:description typed-tx "")
                       " "
                       (str/join " " (:context-lines typed-tx [])))
        counterparty-defs (:counterparties rules)
        config (:matching-config rules)
        case-sensitive? (:case-sensitive? config false)
        priority-order (:priority-order config (keys counterparty-defs))]

    ;; DEBUG: Log what we're searching in
    (println (format "🔍 Stage 2 searching in: '%s' (beneficiary-name: %s)"
                     (subs full-text 0 (min 80 (count full-text)))
                     (:beneficiary-name typed-tx "N/A")))

    ;; Try each counterparty in priority order
    (some
     (fn [counterparty-key]
       (when-let [counterparty-def (get counterparty-defs counterparty-key)]
         (match-single-counterparty counterparty-key counterparty-def full-text case-sensitive?)))
     priority-order)))

;; ============================================================================
;; Counterparty Detector Implementation
;; ============================================================================

(defrecord CounterpartyDetector [config]
  proto/CounterpartyDetector
  (detect-counterparty [this typed-tx rules]
    ;; Only detect if merchant extraction is needed
    (if-not (:merchant? typed-tx)
      ;; No merchant expected, add empty counterparty-info and stage-2 metadata
      (merge typed-tx
             {:counterparty-info {:detected? false}
              :stage-2 {:detected-by :no-merchant-expected
                        :matched-rule nil
                        :timestamp (java.time.Instant/now)}})

      ;; Merchant expected, check for counterparty
      (let [match-result (find-matching-counterparty typed-tx rules)
            ;; For DOMICILIACION + generic merchant, ALWAYS extract RFC
            tx-type (:type typed-tx)
            description (:description typed-tx "")  ;; ← USE DESCRIPTION (exists at Stage 2)
            context-lines (:context-lines typed-tx [])
            is-domiciliacion? (= tx-type :domiciliacion)
            ;; Check if description contains generic terms (COBRANZA, DOMICILIACION, etc.)
            is-generic-description? (let [desc-upper (str/upper-case description)]
                                     (or (str/includes? desc-upper "COBRANZA")
                                         (str/includes? desc-upper "DOMICILIACION")
                                         (str/includes? desc-upper "PAGO DOMICILIADO")
                                         (str/includes? desc-upper "CARGO DOMICILIADO")))

            ;; DEBUG RFC EXTRACTION
            _ (println (format "\n🔍 RFC EXTRACTION DEBUG (FIXED):"))
            _ (println (format "  tx-type: %s" tx-type))
            _ (println (format "  description: %s" (subs description 0 (min 60 (count description)))))
            _ (println (format "  is-domiciliacion?: %s" is-domiciliacion?))
            _ (println (format "  is-generic-description?: %s" is-generic-description?))
            _ (println (format "  context-lines (%d): %s"
                             (count context-lines)
                             (str/join " | " (take 3 context-lines))))

            ;; Extract RFC if DOMICILIACION + generic description
            rfc (when (and is-domiciliacion? is-generic-description?)
                  (let [extracted (extract-rfc-from-context context-lines)]
                    (println (format "  🎯 RFC extracted: %s" extracted))
                    extracted))]

        ;; DEBUG RFC CONDITION
        (println (format "  ✓ RFC final value: %s" rfc))
        (println (format "  ✓ Will use RFC?: %s" (some? rfc)))
        (if match-result
          ;; Counterparty detected - but STILL use RFC for DOMICILIACION + COBRANZA
          (merge typed-tx
                 {:counterparty-info (if rfc
                                       ;; If RFC extracted, use it as merchant hint
                                       (assoc match-result :actual-merchant-hint rfc
                                                          :rfc rfc
                                                          :rfc-extracted? true)
                                       ;; No RFC, use original match-result
                                       match-result)
                  ;; Add semantic category if provided by counterparty rule
                  :semantic-category (:category match-result)
                  :confidence (min (:confidence typed-tx 1.0)
                                   (:confidence match-result 1.0))
                  :stage-2 {:detected-by :pattern-match
                            :matched-rule (:counterparty-id match-result)
                            :matched-pattern (:matched-pattern match-result)
                            :semantic-category (:category match-result)
                            :rfc-extraction (when rfc
                                              {:source :context-lines
                                               :rfc rfc
                                               :replaced-generic description})
                            :timestamp (java.time.Instant/now)}})

          ;; No counterparty detected (direct merchant)
          ;; For DOMICILIACION with generic description, try RFC extraction
          (let [description (:description typed-tx "")
                beneficiary (:beneficiary-name typed-tx)
                context-lines (:context-lines typed-tx [])
                tx-type (:type typed-tx)

                ;; Check if this is DOMICILIACION with generic description
                is-domiciliacion? (= tx-type :domiciliacion)
                is-generic-description? (let [desc-upper (str/upper-case description)]
                                         (or (str/includes? desc-upper "COBRANZA")
                                             (str/includes? desc-upper "DOMICILIACION")
                                             (str/includes? desc-upper "PAGO DOMICILIADO")
                                             (str/includes? desc-upper "CARGO DOMICILIADO")))

                ;; If generic description in DOMICILIACION, extract RFC
                rfc (when (and is-domiciliacion? is-generic-description?)
                      (extract-rfc-from-context context-lines))

                ;; Use RFC as merchant identifier if available
                final-merchant-hint (if rfc
                                      rfc  ;; Use RFC as unique identifier
                                      (or beneficiary description))]  ;; Fall back to beneficiary or description

            (merge typed-tx
                   {:counterparty-info {:detected? false
                                        :actual-merchant-hint final-merchant-hint
                                        :rfc-extracted? (some? rfc)
                                        :rfc rfc}
                    :stage-2 {:detected-by :no-match
                              :matched-rule nil
                              :rfc-extraction (when rfc
                                                {:source :context-lines
                                                 :rfc rfc
                                                 :replaced-generic description})
                              :timestamp (java.time.Instant/now)}})))))))

;; ============================================================================
;; Factory Function
;; ============================================================================

(defn create-detector
  "Creates a CounterpartyDetector instance"
  ([]
   (create-detector {}))
  ([config]
   (->CounterpartyDetector config)))

;; ============================================================================
;; Rules Loading
;; ============================================================================

(defn load-rules
  "Loads Stage 2 rules from EDN file"
  ([]
   (load-rules "rules/stage2_counterparty_detection.edn"))
  ([resource-path]
   (with-open [r (io/reader (io/resource resource-path))]
     (edn/read (java.io.PushbackReader. r)))))

;; ============================================================================
;; Convenience Functions
;; ============================================================================

(defn detect
  "Convenience function to detect counterparty with default rules"
  [typed-tx]
  (let [detector (create-detector)
        rules (load-rules)]
    (proto/detect-counterparty detector typed-tx rules)))

(defn detect-batch
  "Detects counterparties for batch of typed transactions"
  [typed-txs]
  (let [detector (create-detector)
        rules (load-rules)]
    (map #(proto/detect-counterparty detector % rules) typed-txs)))

;; ============================================================================
;; Statistics & Analysis
;; ============================================================================

(defn counterparty-statistics
  "Returns statistics about detected counterparties"
  [counterparty-txs]
  (let [with-merchant (filter :merchant? counterparty-txs)
        detected (filter #(get-in % [:counterparty-info :detected?]) with-merchant)
        direct (filter #(not (get-in % [:counterparty-info :detected?])) with-merchant)
        by-counterparty (group-by #(get-in % [:counterparty-info :counterparty-id]) detected)]
    {:total-transactions (count counterparty-txs)
     :merchant-transactions (count with-merchant)
     :counterparty-detected (count detected)
     :direct-merchant (count direct)
     :detection-rate (if (pos? (count with-merchant))
                       (format "%.1f%%" (* 100.0 (/ (count detected) (count with-merchant))))
                       "N/A")
     :by-counterparty (into {}
                            (map (fn [[k v]]
                                   [k {:count (count v)
                                       :avg-confidence (/ (reduce + (map #(get-in % [:counterparty-info :confidence]) v))
                                                         (count v))}])
                                 (dissoc by-counterparty nil)))}))

;; ============================================================================
;; Validation
;; ============================================================================

(defn validate-counterparty-transaction
  "Validates that a counterparty transaction has required fields"
  [counterparty-tx]
  (and (contains? counterparty-tx :counterparty-info)
       (contains? counterparty-tx :stage-2)))

(defn validate-batch
  "Validates batch of counterparty transactions"
  [counterparty-txs]
  {:valid (count (filter validate-counterparty-transaction counterparty-txs))
   :invalid (count (remove validate-counterparty-transaction counterparty-txs))
   :validation-errors (remove validate-counterparty-transaction counterparty-txs)})
