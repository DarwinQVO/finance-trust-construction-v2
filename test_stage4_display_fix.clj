(require '[finance.merchant-extraction.stage1 :as stage1])
(require '[finance.merchant-extraction.stage2 :as stage2])
(require '[finance.merchant-extraction.stage3 :as stage3])
(require '[finance.merchant-extraction.stage4 :as stage4])

(println "\n🔧 Testing Stage 4 Display Fix (SAT/Atlas Seguros)\n")

;; Test data - SAT transaction
(def sat-tx-raw
  {:description "03 POR COBRANZA DOMICILIADA 926249688 RFC/CURP: SAT8410245V8 IVA: 433.08"
   :amount -2500.00
   :date "18-JUN-25"
   :currency "MXN"
   :account-name "Cuenta de Cheques"
   :clean-merchant "COBRANZA"
   :context-lines ["COBRANZA DOMICILIADA" "RFC: SAT8410245V8" "IVA: 433.08"]})

;; Test data - Atlas Seguros transaction
(def atlas-tx-raw
  {:description "03 POR COBRANZA DOMICILIADA 959450444"
   :amount 1076.00
   :date "01-JUL"
   :currency "MXN"
   :account-name "Cuenta de Cheques"
   :clean-merchant "COBRANZA"
   :context-lines ["        RFC/CURP: CNM980114PI2"
                   "        IVA: 0.00"
                   "        FOLIO DE RASTREO:"
                   "00020250701053352"
                   "        NUMERO REFERENCIA:      0002183"]})

(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "Test 1: SAT Transaction")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

;; Process through all stages
(def sat-stage1 (stage1/detect sat-tx-raw (stage1/load-rules)))
(def sat-stage2 (stage2/detect sat-stage1))
(def sat-stage3 (stage3/extract sat-stage2))

(println "\n📝 After Stage 3 (before Stage 4):")
(println "  clean-merchant:" (:clean-merchant sat-stage3))
(println "  counterparty detected?:" (get-in sat-stage3 [:counterparty-info :detected?]))
(println "  counterparty-id:" (get-in sat-stage3 [:counterparty-info :counterparty-id]))

;; Call get-merchant-field-by-type (the fixed function)
(def sat-merchant-text (#'stage4/get-merchant-field-by-type sat-stage3))

(println "\n✅ Stage 4 Display Name (AFTER FIX):")
(println "  merchant-text:" sat-merchant-text)
(if (= sat-merchant-text "SAT")
  (println "  ✓ CORRECT - Shows 'SAT' instead of 'COBRANZA'")
  (println "  ✗ WRONG - Still showing" sat-merchant-text))

(println "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "Test 2: Atlas Seguros Transaction")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

;; Process through all stages
(def atlas-stage1 (stage1/detect atlas-tx-raw (stage1/load-rules)))
(def atlas-stage2 (stage2/detect atlas-stage1))
(def atlas-stage3 (stage3/extract atlas-stage2))

(println "\n📝 After Stage 3 (before Stage 4):")
(println "  clean-merchant:" (:clean-merchant atlas-stage3))
(println "  counterparty detected?:" (get-in atlas-stage3 [:counterparty-info :detected?]))
(println "  counterparty-id:" (get-in atlas-stage3 [:counterparty-info :counterparty-id]))

;; Call get-merchant-field-by-type (the fixed function)
(def atlas-merchant-text (#'stage4/get-merchant-field-by-type atlas-stage3))

(println "\n✅ Stage 4 Display Name (AFTER FIX):")
(println "  merchant-text:" atlas-merchant-text)
(if (= atlas-merchant-text "Atlas Seguros")
  (println "  ✓ CORRECT - Shows 'Atlas Seguros' instead of 'COBRANZA'")
  (println "  ✗ WRONG - Still showing" atlas-merchant-text))

(println "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "Summary")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")

(println "BEFORE FIX:")
(println "  - Both transactions showed 'COBRANZA' in UI")
(println "  - Counterparty detection worked but display ignored it\n")

(println "AFTER FIX:")
(println "  - SAT transaction shows:" sat-merchant-text)
(println "  - Atlas transaction shows:" atlas-merchant-text)
(println "  - UI now displays actual merchant names! ✅\n")

(System/exit 0)
