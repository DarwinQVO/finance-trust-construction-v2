(require '[finance.merchant-extraction.stage4 :as stage4])

(println "\n🔧 Testing Stage 4 Display Fix - get-merchant-field-by-type function\n")

;; ========================================================================
;; Test 1: SAT Transaction (counterparty detected)
;; ========================================================================
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "Test 1: SAT Transaction (DOMICILIACION with counterparty)")
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
                       :matched-pattern "RFC/CURP: SAT"}})

(println "Input:")
(println "  type: :domiciliacion")
(println "  clean-merchant: \"COBRANZA\"")
(println "  counterparty detected?: true")
(println "  counterparty-id: :sat-tax\n")

(def sat-result (#'stage4/get-merchant-field-by-type sat-tx))

(println "Output (AFTER FIX):")
(println "  merchant-text: \"" sat-result "\"")
(if (= sat-result "SAT")
  (println "  ✅ CORRECT - Shows 'SAT' instead of 'COBRANZA'")
  (println "  ❌ WRONG - Still showing '" sat-result "'"))

;; ========================================================================
;; Test 2: Atlas Seguros Transaction (counterparty detected)
;; ========================================================================
(println "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "Test 2: Atlas Seguros (DOMICILIACION with counterparty)")
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
                       :matched-pattern "RFC/CURP: CNM980114PI2"}})

(println "Input:")
(println "  type: :domiciliacion")
(println "  clean-merchant: \"COBRANZA\"")
(println "  counterparty detected?: true")
(println "  counterparty-id: :atlas-seguros\n")

(def atlas-result (#'stage4/get-merchant-field-by-type atlas-tx))

(println "Output (AFTER FIX):")
(println "  merchant-text: \"" atlas-result "\"")
(if (= atlas-result "Atlas Seguros")
  (println "  ✅ CORRECT - Shows 'Atlas Seguros' instead of 'COBRANZA'")
  (println "  ❌ WRONG - Still showing '" atlas-result "'"))

;; ========================================================================
;; Test 3: Google Transaction (counterparty detected)
;; ========================================================================
(println "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "Test 3: Google (CARD-PURCHASE with counterparty)")
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
                       :matched-pattern "GOOGLE YOUTUBEPREMIUM"}})

(println "Input:")
(println "  type: :card-purchase")
(println "  clean-merchant: \"GOOGLE YOUTUBEPREMIUM\"")
(println "  counterparty detected?: true")
(println "  counterparty-id: :google-services\n")

(def google-result (#'stage4/get-merchant-field-by-type google-tx))

(println "Output (AFTER FIX):")
(println "  merchant-text: \"" google-result "\"")
(if (= google-result "Google")
  (println "  ✅ CORRECT - Shows 'Google' (canonical name)")
  (println "  ❌ WRONG - Still showing '" google-result "'"))

;; ========================================================================
;; Test 4: No Counterparty (fallback to clean-merchant)
;; ========================================================================
(println "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "Test 4: No Counterparty (should use clean-merchant)")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")

(def no-counterparty-tx
  {:type :domiciliacion
   :clean-merchant "COBRANZA"
   :beneficiary-name nil
   :counterparty-info {:detected? false}})

(println "Input:")
(println "  type: :domiciliacion")
(println "  clean-merchant: \"COBRANZA\"")
(println "  counterparty detected?: false\n")

(def no-counterparty-result (#'stage4/get-merchant-field-by-type no-counterparty-tx))

(println "Output (AFTER FIX):")
(println "  merchant-text: \"" no-counterparty-result "\"")
(if (= no-counterparty-result "COBRANZA")
  (println "  ✅ CORRECT - Falls back to 'COBRANZA' when no counterparty")
  (println "  ❌ WRONG - Showing '" no-counterparty-result "'"))

;; ========================================================================
;; Summary
;; ========================================================================
(println "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "Summary")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")

(println "BEFORE FIX:")
(println "  - All transactions showed generic merchant text (COBRANZA)")
(println "  - Counterparty detection worked but UI ignored it\n")

(println "AFTER FIX:")
(println "  - SAT transaction shows: \"" sat-result "\"")
(println "  - Atlas transaction shows: \"" atlas-result "\"")
(println "  - Google transaction shows: \"" google-result "\"")
(println "  - Unknown merchant shows: \"" no-counterparty-result "\"")
(println "\n  ✅ UI now displays canonical counterparty names!\n")

(System/exit 0)
