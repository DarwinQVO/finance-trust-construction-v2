(ns finance.merchant-extraction.stage1-test
  (:require [clojure.test :refer :all]
            [finance.merchant-extraction.stage1 :as stage1]
            [finance.merchant-extraction.protocols :as proto]))

;; ============================================================================
;; Test Data (from Scotiabank real transactions)
;; ============================================================================

(def test-transactions
  {:spei-transfer-in
   {:date "17-JUN-25"
    :description "TRANSF INTERBANCARIA SPEI 00000000000003732041 TRANSF INTERBANCARIA SPEI /S 250617011906042876I FECHA DE ABONO: 17 JUN CAUICH BORGES DARWIN MANUEL //127691013510329220"
    :deposit 3140.00
    :retiro nil
    :saldo 3319.18}

   :card-purchase-google
   {:date "26-JUN-25"
    :description "GOOGLE YOUTUBEPREMIUM CARG RE 00000000517719716538 MONTO ORIGEN 159.00 REF. 0013732041 AUT. 305884"
    :retiro 159.00
    :deposit nil
    :saldo 20.36}

   :spei-transfer-out
   {:date "17-JUL-25"
    :description "SWEB TRANSF.INTERB SPEI 00000000000000170725 INBURSA TRANSFERENCIA A STORAGE /00550705 09:25:49 2025071740044B36L0000387125143 FECHA OPERACION: 17 JUL SOLUTION CANCUN STORAGE /036691500331752023"
    :retiro 4756.00
    :deposit nil
    :saldo 4571.73}

   :card-purchase-clip
   {:date "11-AGO-25"
    :description "CLIPMX AGREGADOR 00000000101008685717 CLIP MX REST HANAICHI REF. 0013732041 AUT. 742785 RFC BLI 120726UF6"
    :retiro 2236.00
    :deposit nil
    :saldo 13489.58}

   :reversal
   {:date "11-AGO-25"
    :description "REV.STR AGREGADOR CARG RECUR. 00000000522310646921 STRIPE DELPHINUS WEB"
    :deposit 919.60
    :retiro nil
    :saldo 5637.67}

   :domiciliacion
   {:date "19-AGO-25"
    :description "DOMICILIACION CFE SUMINISTRADOR COMISION FE ENERGIA B83A29FF-B1DC-4A05-B06B-3B8F7A1F01A3 11:08:12 025081 9B2K84LRXG000000015500011 MX CDMX CONTRATACION CFE"
    :retiro 1541.60
    :deposit nil
    :saldo 3775.58}

   :card-purchase-uber
   {:date "05-SEP-25"
    :description "UBER *EATS MR TREUBLAAN 7 AMSTERDAM 1097 DP NH NLD UBER.COM/HELP NLD MONTO ORIGEN 7.95 EUR T/C 21.5280"
    :retiro 171.19
    :deposit nil
    :saldo 2532.89}})

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn- detect-with-defaults [tx]
  (let [detector (stage1/create-detector)
        rules (stage1/load-rules)]
    (proto/detect-type detector tx rules)))

;; ============================================================================
;; Pattern Matching Tests
;; ============================================================================

(deftest test-spei-transfer-in-detection
  (testing "SPEI incoming transfer - should detect as transfer with NO merchant"
    (let [tx (:spei-transfer-in test-transactions)
          result (detect-with-defaults tx)]

      (is (= :spei-transfer-in (:type result)))
      (is (= :income (:direction result)))
      (is (false? (:merchant? result)))
      (is (>= (:confidence result) 0.95))
      (is (= :pattern-match (get-in result [:stage-1 :detected-by]))))))

(deftest test-spei-transfer-out-detection
  (testing "SPEI outgoing transfer - should detect as transfer with NO merchant"
    (let [tx (:spei-transfer-out test-transactions)
          result (detect-with-defaults tx)]

      ;; Could be either spei-transfer-out or sweb-transfer-out (both valid)
      (is (contains? #{:spei-transfer-out :sweb-transfer-out} (:type result)))
      (is (= :transfer (:direction result)))
      (is (false? (:merchant? result)))
      (is (>= (:confidence result) 0.95)))))

(deftest test-card-purchase-detection
  (testing "Card purchase - should detect with merchant expected"
    (let [tx (:card-purchase-google test-transactions)
          result (detect-with-defaults tx)]

      (is (= :card-purchase (:type result)))
      (is (= :expense (:direction result)))
      (is (true? (:merchant? result)))
      (is (>= (:confidence result) 0.90))
      (is (contains? result :stage-1)))))

(deftest test-reversal-detection
  (testing "Reversal - should detect as income with merchant expected"
    (let [tx (:reversal test-transactions)
          result (detect-with-defaults tx)]

      (is (= :reversal (:type result)))
      (is (= :income (:direction result)))
      (is (true? (:merchant? result)))
      (is (>= (:confidence result) 0.90)))))

(deftest test-domiciliacion-detection
  (testing "Domiciliacion - should detect with merchant expected"
    (let [tx (:domiciliacion test-transactions)
          result (detect-with-defaults tx)]

      (is (= :domiciliacion (:type result)))
      (is (= :expense (:direction result)))
      (is (true? (:merchant? result)))
      (is (>= (:confidence result) 0.80)))))

;; ============================================================================
;; Field Preservation Tests
;; ============================================================================

(deftest test-original-fields-preserved
  (testing "Original transaction fields should be preserved"
    (let [tx (:card-purchase-google test-transactions)
          result (detect-with-defaults tx)]

      ;; Original fields should still exist
      (is (= (:date tx) (:date result)))
      (is (= (:description tx) (:description result)))
      (is (= (:retiro tx) (:retiro result)))
      (is (= (:deposit tx) (:deposit result)))
      (is (= (:saldo tx) (:saldo result))))))

;; ============================================================================
;; Stage Metadata Tests
;; ============================================================================

(deftest test-stage-metadata-present
  (testing "Stage 1 metadata should be present"
    (let [tx (:card-purchase-google test-transactions)
          result (detect-with-defaults tx)]

      (is (contains? result :stage-1))
      (is (contains? (:stage-1 result) :detected-by))
      (is (contains? (:stage-1 result) :matched-rule))
      (is (contains? (:stage-1 result) :timestamp)))))

;; ============================================================================
;; Early Termination Tests
;; ============================================================================

(deftest test-early-termination-logic
  (testing "Transactions with merchant? false should terminate pipeline"
    (let [tx (:spei-transfer-in test-transactions)
          result (detect-with-defaults tx)]

      (is (false? (:merchant? result)))
      ;; In real pipeline, this would terminate
      ;; No stages 2-5 should run
      (is (not (contains? result :stage-2)))
      (is (not (contains? result :stage-3))))))

;; ============================================================================
;; Batch Processing Tests
;; ============================================================================

(deftest test-batch-detection
  (testing "Batch processing should work correctly"
    (let [txs (vals test-transactions)
          results (stage1/detect-batch txs)]

      ;; Should process all transactions
      (is (= (count txs) (count results)))

      ;; All should have type
      (is (every? #(contains? % :type) results))

      ;; All should have stage-1 metadata
      (is (every? #(contains? % :stage-1) results)))))

;; ============================================================================
;; Statistics Tests
;; ============================================================================

(deftest test-type-statistics
  (testing "Statistics calculation should work"
    (let [txs (vals test-transactions)
          results (stage1/detect-batch txs)
          stats (stage1/type-statistics results)]

      (is (contains? stats :total-count))
      (is (contains? stats :by-type))
      (is (contains? stats :merchant-extraction-needed))
      (is (contains? stats :no-merchant-expected))

      ;; Verify counts
      (is (= (count results) (:total-count stats)))
      (is (> (:merchant-extraction-needed stats) 0))
      (is (> (:no-merchant-expected stats) 0)))))

;; ============================================================================
;; Validation Tests
;; ============================================================================

(deftest test-validation
  (testing "Typed transactions should validate"
    (let [tx (:card-purchase-google test-transactions)
          result (detect-with-defaults tx)]

      (is (true? (stage1/validate-typed-transaction result))))))

(deftest test-batch-validation
  (testing "Batch validation should work"
    (let [txs (vals test-transactions)
          results (stage1/detect-batch txs)
          validation (stage1/validate-batch results)]

      (is (= (:valid validation) (count results)))
      (is (= (:invalid validation) 0)))))

;; ============================================================================
;; Edge Cases
;; ============================================================================

(deftest test-unknown-transaction-type
  (testing "Unknown transaction should be handled gracefully"
    (let [tx {:date "01-JAN-25"
              :description "SOME WEIRD THING WE NEVER SEEN BEFORE"
              :retiro 100.00
              :deposit nil
              :saldo 1000.00}
          result (detect-with-defaults tx)]

      (is (= :unknown (:type result)))
      (is (= :unknown (:direction result)))
      (is (false? (:merchant? result)))
      (is (= 0.0 (:confidence result))))))

(deftest test-empty-description
  (testing "Empty description should be handled"
    (let [tx {:date "01-JAN-25"
              :description ""
              :retiro 100.00
              :deposit nil
              :saldo 1000.00}
          result (detect-with-defaults tx)]

      ;; Should return unknown, not crash
      (is (= :unknown (:type result))))))

;; ============================================================================
;; Priority Order Tests
;; ============================================================================

(deftest test-pattern-priority
  (testing "More specific patterns should match before general ones"
    ;; SPEI patterns should match before generic card-purchase
    (let [tx {:date "01-JAN-25"
              :description "TRANSF INTERBANCARIA SPEI REF. 123 AUT. 456"
              :deposit 1000.00
              :retiro nil
              :saldo 5000.00}
          result (detect-with-defaults tx)]

      ;; Should match SPEI, not card-purchase (even though both patterns present)
      (is (= :spei-transfer-in (:type result))))))

;; ============================================================================
;; End of tests
;; ============================================================================
