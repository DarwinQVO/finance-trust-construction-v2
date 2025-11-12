(ns test-rfc-detection
  "Test RFC-based merchant identification for COBRANZA DOMICILIADA"
  (:require [finance.merchant-extraction.stage1 :as stage1]
            [finance.merchant-extraction.stage2 :as stage2]
            [clojure.pprint :as pp]))

;; Test transactions with COBRANZA DOMICILIADA from different providers
(def test-txs
  [{:date "03-JUN"
    :description "COBRANZA DOMICILIADA"
    :amount 926249.688
    :retiro 926249.688
    :context-lines ["POR COBRANZA DOMICILIADA"
                    "RFC/CURP: SAT8410245V8"
                    "FECHA: 03 JUN"]}

   {:date "03-JUN"
    :description "COBRANZA DOMICILIADA"
    :amount 959450.444
    :retiro 959450.444
    :context-lines ["POR COBRANZA DOMICILIADA"
                    "RFC/CURP: CNM980114PI2"
                    "FECHA: 03 JUN"]}

   {:date "05-JUN"
    :description "COBRANZA DOMICILIADA"
    :amount 500.00
    :retiro 500.00
    :context-lines ["POR COBRANZA DOMICILIADA"
                    "RFC/CURP: ATT1234567XX"  ;; Mock AT&T RFC
                    "FECHA: 05 JUN"]}

   {:date "07-JUN"
    :description "COBRANZA DOMICILIADA"
    :amount 300.00
    :retiro 300.00
    :context-lines ["POR COBRANZA DOMICILIADA"
                    "RFC/CURP: XYZ9876543ZZ"  ;; Unknown RFC
                    "FECHA: 07 JUN"]}])

(println "\n🧪 TESTING RFC-BASED COBRANZA DOMICILIADA DETECTION")
(println "====================================================\n")

(doseq [[idx tx] (map-indexed vector test-txs)]
  (let [stage1-result (stage1/detect tx)
        stage2-result (stage2/detect stage1-result)
        rfc (first (filter #(clojure.string/includes? % "RFC/CURP:") (:context-lines tx)))]

    (println (format "\n━━━ Test %d: %s ━━━" (inc idx) rfc))
    (println (format "Description: %s" (:description tx)))
    (println (format "Amount: $%.2f" (:amount tx)))

    (println "\nStage 1 Result:")
    (println (format "  Type: %s (merchant?: %s)" (:type stage1-result) (:merchant? stage1-result)))

    (println "\nStage 2 Result:")
    (when-let [ci (:counterparty-info stage2-result)]
      (println (format "  Detected?: %s" (:detected? ci)))
      (when (:detected? ci)
        (println (format "  Counterparty ID: %s" (:counterparty-id ci)))
        (println (format "  Type: %s" (:counterparty-type ci)))
        (println (format "  Semantic Category: %s" (:category ci)))
        (println (format "  Confidence: %.0f%%" (* 100 (:confidence ci))))))

    (println (format "\n  Top-level Semantic Category: %s"
                     (or (:semantic-category stage2-result) "nil")))

    ;; Validation
    (let [expected-category (cond
                              (clojure.string/includes? rfc "SAT8410245V8") :taxes
                              (clojure.string/includes? rfc "CNM980114PI2") :insurance
                              (clojure.string/includes? rfc "ATT") :utilities
                              :else :utilities)  ;; Generic fallback
          actual-category (:semantic-category stage2-result)]
      (if (= expected-category actual-category)
        (println (format "  ✅ CORRECT: Expected %s, got %s" expected-category actual-category))
        (println (format "  ❌ WRONG: Expected %s, got %s" expected-category actual-category))))))

(println "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "✅ Test complete!")
(println "\nExpected behavior:")
(println "  1. SAT8410245V8 → :taxes (high confidence 98%)")
(println "  2. CNM980114PI2 → :insurance (high confidence 95%)")
(println "  3. ATT* RFC → :utilities (medium confidence 90%)")
(println "  4. Unknown RFC → :utilities (low confidence 30%, generic fallback)")
