(require '[finance.merchant-extraction.stage2 :as stage2])

(println "\n🧪 Testing Stage 2 RFC Extraction Fix\n")

;; ========================================================================
;; Test 1: SAT Transaction (DOMICILIACION + COBRANZA + counterparty detected)
;; ========================================================================
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "Test 1: SAT Transaction (DOMICILIACION + COBRANZA)")
(println "       Should extract RFC EVEN WHEN counterparty detected")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")

(def sat-typed-tx
  {:type :domiciliacion
   :clean-merchant "COBRANZA"
   :description "DOMICILIACION COBRANZA"
   :context-lines ["RFC/CURP: SAT8410245V8" "IVA: 433.08"]
   :beneficiary-name nil
   :merchant? true
   :confidence 0.9})

(println "Input:")
(println "  type: :domiciliacion")
(println "  clean-merchant: \"COBRANZA\"")
(println "  context-lines: [\"RFC/CURP: SAT8410245V8\" \"IVA: 433.08\"]")
(println "  (SAT counterparty WILL be detected by Stage 2 rules)\n")

(def sat-result (stage2/detect sat-typed-tx))

(println "Output:")
(println "  counterparty detected?:" (get-in sat-result [:counterparty-info :detected?]))
(println "  counterparty-id:" (get-in sat-result [:counterparty-info :counterparty-id]))
(println "  RFC extracted?:" (get-in sat-result [:counterparty-info :rfc-extracted?]))
(println "  RFC:" (get-in sat-result [:counterparty-info :rfc]))
(println "  actual-merchant-hint:" (get-in sat-result [:counterparty-info :actual-merchant-hint]))

(if (= (get-in sat-result [:counterparty-info :actual-merchant-hint]) "SAT8410245V8")
  (println "\n  ✅ CORRECT - RFC 'SAT8410245V8' extracted and stored in :actual-merchant-hint")
  (println (format "\n  ❌ WRONG - Expected 'SAT8410245V8', got '%s'"
                   (get-in sat-result [:counterparty-info :actual-merchant-hint]))))

;; ========================================================================
;; Test 2: Atlas Seguros (DOMICILIACION + COBRANZA + counterparty detected)
;; ========================================================================
(println "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "Test 2: Atlas Seguros (DOMICILIACION + COBRANZA)")
(println "       Should extract RFC EVEN WHEN counterparty detected")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")

(def atlas-typed-tx
  {:type :domiciliacion
   :clean-merchant "COBRANZA"
   :description "DOMICILIACION COBRANZA"
   :context-lines ["RFC/CURP: CNM980114PI2" "Poliza: 123456"]
   :beneficiary-name nil
   :merchant? true
   :confidence 0.9})

(println "Input:")
(println "  type: :domiciliacion")
(println "  clean-merchant: \"COBRANZA\"")
(println "  context-lines: [\"RFC/CURP: CNM980114PI2\" \"Poliza: 123456\"]")
(println "  (Atlas counterparty WILL be detected by Stage 2 rules)\n")

(def atlas-result (stage2/detect atlas-typed-tx))

(println "Output:")
(println "  counterparty detected?:" (get-in atlas-result [:counterparty-info :detected?]))
(println "  counterparty-id:" (get-in atlas-result [:counterparty-info :counterparty-id]))
(println "  RFC extracted?:" (get-in atlas-result [:counterparty-info :rfc-extracted?]))
(println "  RFC:" (get-in atlas-result [:counterparty-info :rfc]))
(println "  actual-merchant-hint:" (get-in atlas-result [:counterparty-info :actual-merchant-hint]))

(if (= (get-in atlas-result [:counterparty-info :actual-merchant-hint]) "CNM980114PI2")
  (println "\n  ✅ CORRECT - RFC 'CNM980114PI2' extracted and stored in :actual-merchant-hint")
  (println (format "\n  ❌ WRONG - Expected 'CNM980114PI2', got '%s'"
                   (get-in atlas-result [:counterparty-info :actual-merchant-hint]))))

;; ========================================================================
;; Test 3: DOMICILIACION + COBRANZA + NO counterparty detected
;; ========================================================================
(println "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "Test 3: Unknown RFC (DOMICILIACION + COBRANZA + NO counterparty)")
(println "       Should still extract RFC when no counterparty matches")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")

(def unknown-rfc-tx
  {:type :domiciliacion
   :clean-merchant "COBRANZA"
   :description "DOMICILIACION COBRANZA"
   :context-lines ["RFC/CURP: XYZ123456ABC" "Concepto: Pago"]
   :beneficiary-name nil
   :merchant? true
   :confidence 0.9})

(println "Input:")
(println "  type: :domiciliacion")
(println "  clean-merchant: \"COBRANZA\"")
(println "  context-lines: [\"RFC/CURP: XYZ123456ABC\" \"Concepto: Pago\"]")
(println "  (NO counterparty will match this RFC)\n")

(def unknown-result (stage2/detect unknown-rfc-tx))

(println "Output:")
(println "  counterparty detected?:" (get-in unknown-result [:counterparty-info :detected?]))
(println "  RFC extracted?:" (get-in unknown-result [:counterparty-info :rfc-extracted?]))
(println "  RFC:" (get-in unknown-result [:counterparty-info :rfc]))
(println "  actual-merchant-hint:" (get-in unknown-result [:counterparty-info :actual-merchant-hint]))

(if (= (get-in unknown-result [:counterparty-info :actual-merchant-hint]) "XYZ123456ABC")
  (println "\n  ✅ CORRECT - RFC 'XYZ123456ABC' extracted even when no counterparty detected")
  (println (format "\n  ❌ WRONG - Expected 'XYZ123456ABC', got '%s'"
                   (get-in unknown-result [:counterparty-info :actual-merchant-hint]))))

;; ========================================================================
;; Test 4: NOT DOMICILIACION (should NOT extract RFC)
;; ========================================================================
(println "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "Test 4: Card Purchase (NOT DOMICILIACION)")
(println "       Should NOT extract RFC (only for DOMICILIACION)")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")

(def card-purchase-tx
  {:type :card-purchase
   :clean-merchant "GOOGLE YOUTUBEPREMIUM"
   :description "COMPRA GOOGLE YOUTUBEPREMIUM"
   :context-lines ["RFC/CURP: ABC123456DEF"]  ;; RFC present but shouldn't extract
   :beneficiary-name nil
   :merchant? true
   :confidence 0.9})

(println "Input:")
(println "  type: :card-purchase (NOT domiciliacion)")
(println "  clean-merchant: \"GOOGLE YOUTUBEPREMIUM\"")
(println "  context-lines: [\"RFC/CURP: ABC123456DEF\"]")
(println "  (RFC present but should NOT extract for card-purchase)\n")

(def card-result (stage2/detect card-purchase-tx))

(println "Output:")
(println "  RFC extracted?:" (get-in card-result [:counterparty-info :rfc-extracted?]))
(println "  RFC:" (get-in card-result [:counterparty-info :rfc]))

(if (nil? (get-in card-result [:counterparty-info :rfc]))
  (println "\n  ✅ CORRECT - RFC NOT extracted (only for DOMICILIACION)")
  (println (format "\n  ❌ WRONG - RFC should be nil for non-DOMICILIACION, got '%s'"
                   (get-in card-result [:counterparty-info :rfc]))))

;; ========================================================================
;; Summary
;; ========================================================================
(println "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "Summary - Stage 2 RFC Extraction Logic")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")

(println "Expected Behavior:")
(println "  1. For DOMICILIACION + COBRANZA → ALWAYS extract RFC")
(println "  2. Extract RFC REGARDLESS of counterparty detection")
(println "  3. Store RFC in :actual-merchant-hint")
(println "  4. Stage 4 will display :actual-merchant-hint (the RFC)")
(println "\nThis ensures:")
(println "  - SAT8410245V8 and CNM980114PI2 are DIFFERENT merchants")
(println "  - No hardcoded display names in Stage 4")
(println "  - RFC comes from extraction (Stage 2), not transformation (Stage 4)")
(println "  - \"Rules as data\" principle maintained\n")

(System/exit 0)
