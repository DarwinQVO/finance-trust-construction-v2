(require '[finance.merchant-extraction.stage4 :as stage4])

(println "\n🔧 Testing RFC Display (SAT8410245V8 / CNM980114PI2)\\n")

;; ========================================================================
;; Test 1: SAT Transaction - Should show RFC "SAT8410245V8"
;; ========================================================================
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "Test 1: SAT Transaction (DOMICILIACION with RFC)")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")

(def sat-tx
  {:type :domiciliacion
   :clean-merchant "COBRANZA"
   :beneficiary-name nil
   :counterparty-info {:detected? true
                       :counterparty-id :sat-tax
                       :counterparty-type :tax-authority
                       :category :taxes
                       :confidence 0.98
                       :matched-pattern "RFC/CURP: SAT8410245V8"
                       :rfc nil  ;; Not extracted directly, but in pattern
                       }})

(println "Input:")
(println "  type: :domiciliacion")
(println "  clean-merchant: \"COBRANZA\"")
(println "  counterparty detected?: true")
(println "  matched-pattern: \"RFC/CURP: SAT8410245V8\"")
(println "  counterparty-id: :sat-tax\n")

(def sat-result (#'stage4/get-merchant-field-by-type sat-tx))

(println "Output (NEW LOGIC - RFC from pattern):")
(println "  merchant-text: \"" sat-result "\"")
(if (= sat-result "SAT8410245V8")
  (println "  ✅ CORRECT - Shows RFC 'SAT8410245V8' instead of 'SAT' or 'COBRANZA'")
  (println "  ❌ WRONG - Expected 'SAT8410245V8', got '" sat-result "'"))

;; ========================================================================
;; Test 2: Atlas Seguros - Should show RFC "CNM980114PI2"
;; ========================================================================
(println "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "Test 2: Atlas Seguros (DOMICILIACION with RFC)")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")

(def atlas-tx
  {:type :domiciliacion
   :clean-merchant "COBRANZA"
   :beneficiary-name nil
   :counterparty-info {:detected? true
                       :counterparty-id :atlas-seguros
                       :counterparty-type :insurance-provider
                       :category :insurance
                       :confidence 0.95
                       :matched-pattern "RFC/CURP: CNM980114PI2"
                       :rfc nil}})

(println "Input:")
(println "  type: :domiciliacion")
(println "  clean-merchant: \"COBRANZA\"")
(println "  counterparty detected?: true")
(println "  matched-pattern: \"RFC/CURP: CNM980114PI2\"")
(println "  counterparty-id: :atlas-seguros\n")

(def atlas-result (#'stage4/get-merchant-field-by-type atlas-tx))

(println "Output (NEW LOGIC - RFC from pattern):")
(println "  merchant-text: \"" atlas-result "\"")
(if (= atlas-result "CNM980114PI2")
  (println "  ✅ CORRECT - Shows RFC 'CNM980114PI2' instead of 'Atlas Seguros' or 'COBRANZA'")
  (println "  ❌ WRONG - Expected 'CNM980114PI2', got '" atlas-result "'"))

;; ========================================================================
;; Test 3: RFC extracted directly (from context-lines in Stage 2)
;; ========================================================================
(println "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "Test 3: RFC extracted directly (Stage 2 extraction)")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")

(def rfc-extracted-tx
  {:type :domiciliacion
   :clean-merchant "COBRANZA"
   :beneficiary-name nil
   :counterparty-info {:detected? false
                       :actual-merchant-hint "DCO110714JH9"  ;; From Stage 2
                       :rfc-extracted? true
                       :rfc "DCO110714JH9"}})  ;; Directly extracted

(println "Input:")
(println "  type: :domiciliacion")
(println "  clean-merchant: \"COBRANZA\"")
(println "  counterparty detected?: false")
(println "  rfc: \"DCO110714JH9\" (extracted from context-lines)\n")

(def rfc-extracted-result (#'stage4/get-merchant-field-by-type rfc-extracted-tx))

(println "Output (NEW LOGIC - RFC from extraction):")
(println "  merchant-text: \"" rfc-extracted-result "\"")
(if (= rfc-extracted-result "DCO110714JH9")
  (println "  ✅ CORRECT - Shows extracted RFC 'DCO110714JH9'")
  (println "  ❌ WRONG - Expected 'DCO110714JH9', got '" rfc-extracted-result "'"))

;; ========================================================================
;; Test 4: Google (no RFC, should use actual-merchant-hint)
;; ========================================================================
(println "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "Test 4: Google (card-purchase, no RFC)")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")

(def google-tx
  {:type :card-purchase
   :clean-merchant "GOOGLE YOUTUBEPREMIUM"
   :beneficiary-name nil
   :counterparty-info {:detected? true
                       :counterparty-id :google-services
                       :counterparty-type :subscription-provider
                       :category :subscription
                       :confidence 0.95
                       :matched-pattern "GOOGLE YOUTUBEPREMIUM"
                       :actual-merchant-hint "YOUTUBEPREMIUM"  ;; Extracted after GOOGLE
                       :rfc nil}})  ;; No RFC for international services

(println "Input:")
(println "  type: :card-purchase")
(println "  clean-merchant: \"GOOGLE YOUTUBEPREMIUM\"")
(println "  counterparty detected?: true")
(println "  actual-merchant-hint: \"YOUTUBEPREMIUM\"")
(println "  rfc: nil (no RFC for international service)\n")

(def google-result (#'stage4/get-merchant-field-by-type google-tx))

(println "Output (NEW LOGIC - use actual-merchant-hint):")
(println "  merchant-text: \"" google-result "\"")
(if (= google-result "YOUTUBEPREMIUM")
  (println "  ✅ CORRECT - Shows 'YOUTUBEPREMIUM' (actual-merchant-hint)")
  (println "  ❌ WRONG - Expected 'YOUTUBEPREMIUM', got '" google-result "'"))

;; ========================================================================
;; Test 5: Unknown merchant (no RFC, no counterparty)
;; ========================================================================
(println "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "Test 5: Unknown merchant (fallback to clean-merchant)")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")

(def unknown-tx
  {:type :domiciliacion
   :clean-merchant "COBRANZA"
   :beneficiary-name nil
   :counterparty-info {:detected? false
                       :rfc nil}})

(println "Input:")
(println "  type: :domiciliacion")
(println "  clean-merchant: \"COBRANZA\"")
(println "  counterparty detected?: false")
(println "  rfc: nil\n")

(def unknown-result (#'stage4/get-merchant-field-by-type unknown-tx))

(println "Output (NEW LOGIC - fallback):")
(println "  merchant-text: \"" unknown-result "\"")
(if (= unknown-result "COBRANZA")
  (println "  ✅ CORRECT - Falls back to 'COBRANZA' when no RFC or counterparty")
  (println "  ❌ WRONG - Expected 'COBRANZA', got '" unknown-result "'"))

;; ========================================================================
;; Summary
;; ========================================================================
(println "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "Summary")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")

(println "ANTES (hardcoded names):")
(println "  - SAT transaction showed: \"SAT\"")
(println "  - Atlas transaction showed: \"Atlas Seguros\"")
(println "  - Counterparty names were hardcoded in code\n")

(println "AHORA (RFC-driven, data from extraction):")
(println "  - SAT transaction shows: \"" sat-result "\"")
(println "  - Atlas transaction shows: \"" atlas-result "\"")
(println "  - RFC extracted transaction shows: \"" rfc-extracted-result "\"")
(println "  - Google transaction shows: \"" google-result "\"")
(println "  - Unknown merchant shows: \"" unknown-result "\"")
(println "\n  ✅ NO hardcoded names - RFC comes from extraction!")
(println "  ✅ For DOMICILIACION, RFC IS the merchant identifier!")
(println "  ✅ Rules as data - no code changes needed for new merchants!\n")

(System/exit 0)
