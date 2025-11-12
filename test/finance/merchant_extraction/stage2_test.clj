(ns finance.merchant-extraction.stage2-test
  (:require [clojure.test :refer :all]
            [finance.merchant-extraction.stage2 :as stage2]
            [finance.merchant-extraction.protocols :as proto]))

;; ============================================================================
;; Test Data (Typed transactions from Stage 1)
;; ============================================================================

(def test-transactions
  {:clip-aggregator
   {:date "11-AGO-25"
    :description "CLIPMX AGREGADOR 00000000101008685717 CLIP MX REST HANAICHI REF. 0013732041 AUT. 742785 RFC BLI 120726UF6"
    :retiro 2236.00
    :type :card-purchase
    :direction :expense
    :merchant? true
    :confidence 0.95}

   :zettle-aggregator
   {:date "05-SEP-25"
    :description "ZETTLE CIRCUS PARK CANCUN REF. 0013732041 AUT. 123456"
    :retiro 156.00
    :type :card-purchase
    :direction :expense
    :merchant? true
    :confidence 0.95}

   :stripe-aggregator
   {:date "11-AGO-25"
    :description "REV.STR AGREGADOR CARG RECUR. 00000000522310646921 STRIPE DELPHINUS WEB"
    :deposit 919.60
    :type :reversal
    :direction :income
    :merchant? true
    :confidence 0.92}

   :direct-merchant-google
   {:date "26-JUN-25"
    :description "GOOGLE YOUTUBEPREMIUM CARG RE 00000000517719716538 MONTO ORIGEN 159.00 REF. 0013732041 AUT. 305884"
    :retiro 159.00
    :type :card-purchase
    :direction :expense
    :merchant? true
    :confidence 0.95}

   :uber-marketplace
   {:date "05-SEP-25"
    :description "UBER *EATS MR TREUBLAAN 7 AMSTERDAM 1097 DP NH NLD UBER.COM/HELP NLD MONTO ORIGEN 7.95 EUR T/C 21.5280"
    :retiro 171.19
    :type :card-purchase
    :direction :expense
    :merchant? true
    :confidence 0.95}

   :no-merchant-spei
   {:date "17-JUN-25"
    :description "TRANSF INTERBANCARIA SPEI 00000000000003732041 TRANSF INTERBANCARIA SPEI /S 250617011906042876I"
    :deposit 3140.00
    :type :spei-transfer-in
    :direction :income
    :merchant? false
    :confidence 0.98}})

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn- detect-with-defaults [tx]
  (let [detector (stage2/create-detector)
        rules (stage2/load-rules)]
    (proto/detect-counterparty detector tx rules)))

;; ============================================================================
;; Counterparty Detection Tests
;; ============================================================================

(deftest test-clip-detection
  (testing "CLIP aggregator should be detected"
    (let [tx (:clip-aggregator test-transactions)
          result (detect-with-defaults tx)]

      (is (true? (get-in result [:counterparty-info :detected?])))
      (is (= :clip (get-in result [:counterparty-info :counterparty-id])))
      (is (= :payment-aggregator (get-in result [:counterparty-info :counterparty-type])))
      (is (= "CLIP MX" (get-in result [:counterparty-info :extract-after])))
      (is (string? (get-in result [:counterparty-info :actual-merchant-hint])))
      (is (>= (get-in result [:counterparty-info :confidence]) 0.90)))))

(deftest test-zettle-detection
  (testing "ZETTLE aggregator should be detected"
    (let [tx (:zettle-aggregator test-transactions)
          result (detect-with-defaults tx)]

      (is (true? (get-in result [:counterparty-info :detected?])))
      (is (= :zettle (get-in result [:counterparty-info :counterparty-id])))
      (is (= :payment-aggregator (get-in result [:counterparty-info :counterparty-type])))
      (is (>= (get-in result [:counterparty-info :confidence]) 0.90)))))

(deftest test-stripe-detection
  (testing "STRIPE aggregator should be detected"
    (let [tx (:stripe-aggregator test-transactions)
          result (detect-with-defaults tx)]

      (is (true? (get-in result [:counterparty-info :detected?])))
      (is (= :stripe (get-in result [:counterparty-info :counterparty-id])))
      (is (= :payment-aggregator (get-in result [:counterparty-info :counterparty-type])))
      (is (>= (get-in result [:counterparty-info :confidence]) 0.90)))))

(deftest test-uber-marketplace-detection
  (testing "UBER marketplace should be detected"
    (let [tx (:uber-marketplace test-transactions)
          result (detect-with-defaults tx)]

      (is (true? (get-in result [:counterparty-info :detected?])))
      (is (= :uber (get-in result [:counterparty-info :counterparty-id])))
      (is (= :marketplace (get-in result [:counterparty-info :counterparty-type])))
      (is (>= (get-in result [:counterparty-info :confidence]) 0.90)))))

(deftest test-direct-merchant-no-counterparty
  (testing "Direct merchant should have no counterparty"
    (let [tx (:direct-merchant-google test-transactions)
          result (detect-with-defaults tx)]

      (is (false? (get-in result [:counterparty-info :detected?])))
      (is (nil? (get-in result [:counterparty-info :counterparty-id]))))))

(deftest test-no-merchant-skips-detection
  (testing "Transactions with merchant? false should skip counterparty detection"
    (let [tx (:no-merchant-spei test-transactions)
          result (detect-with-defaults tx)]

      ;; Should preserve merchant? false
      (is (false? (:merchant? result)))
      ;; Should have counterparty-info with detected? false
      (is (contains? result :counterparty-info))
      (is (false? (get-in result [:counterparty-info :detected?])))
      ;; Should have stage-2 metadata
      (is (contains? result :stage-2)))))

;; ============================================================================
;; Field Preservation Tests
;; ============================================================================

(deftest test-stage1-fields-preserved
  (testing "Stage 1 fields should be preserved"
    (let [tx (:clip-aggregator test-transactions)
          result (detect-with-defaults tx)]

      ;; Stage 1 fields should still exist
      (is (= (:type tx) (:type result)))
      (is (= (:direction tx) (:direction result)))
      (is (= (:merchant? tx) (:merchant? result))))))

;; ============================================================================
;; Merchant Hint Extraction Tests
;; ============================================================================

(deftest test-merchant-hint-extraction
  (testing "Merchant hint should be extracted after aggregator"
    (let [tx (:clip-aggregator test-transactions)
          result (detect-with-defaults tx)
          merchant-hint (get-in result [:counterparty-info :actual-merchant-hint])]

      ;; Should have extracted something after "CLIP MX"
      (is (string? merchant-hint))
      (is (pos? (count merchant-hint)))
      ;; Should contain "REST HANAICHI" somewhere
      (is (re-find #"REST HANAICHI" merchant-hint)))))

;; ============================================================================
;; Stage Metadata Tests
;; ============================================================================

(deftest test-stage2-metadata-present
  (testing "Stage 2 metadata should be present"
    (let [tx (:clip-aggregator test-transactions)
          result (detect-with-defaults tx)]

      (is (contains? result :stage-2))
      (is (contains? (:stage-2 result) :detected-by))
      (is (contains? (:stage-2 result) :matched-rule))
      (is (contains? (:stage-2 result) :timestamp)))))

;; ============================================================================
;; Batch Processing Tests
;; ============================================================================

(deftest test-batch-detection
  (testing "Batch processing should work correctly"
    (let [txs (vals test-transactions)
          results (stage2/detect-batch txs)]

      ;; Should process all transactions
      (is (= (count txs) (count results)))

      ;; All merchant transactions should have counterparty-info
      (let [merchant-txs (filter :merchant? results)]
        (is (every? #(contains? % :counterparty-info) merchant-txs))))))

;; ============================================================================
;; Statistics Tests
;; ============================================================================

(deftest test-counterparty-statistics
  (testing "Statistics calculation should work"
    (let [txs (vals test-transactions)
          results (stage2/detect-batch txs)
          stats (stage2/counterparty-statistics results)]

      (is (contains? stats :total-transactions))
      (is (contains? stats :merchant-transactions))
      (is (contains? stats :counterparty-detected))
      (is (contains? stats :direct-merchant))
      (is (contains? stats :by-counterparty))

      ;; Should have detected some counterparties
      (is (pos? (:counterparty-detected stats)))
      ;; Should have some direct merchants
      (is (pos? (:direct-merchant stats))))))

;; ============================================================================
;; Validation Tests
;; ============================================================================

(deftest test-validation
  (testing "Counterparty transactions should validate"
    (let [tx (:clip-aggregator test-transactions)
          result (detect-with-defaults tx)]

      (is (true? (stage2/validate-counterparty-transaction result))))))

(deftest test-batch-validation
  (testing "Batch validation should work"
    (let [txs (vals test-transactions)
          results (stage2/detect-batch txs)
          validation (stage2/validate-batch results)]

      ;; All should be valid
      (is (= (:valid validation) (count results)))
      (is (= (:invalid validation) 0)))))

;; ============================================================================
;; Confidence Adjustment Tests
;; ============================================================================

(deftest test-confidence-adjustment
  (testing "Confidence should be minimum of Stage 1 and Stage 2"
    (let [tx (assoc (:clip-aggregator test-transactions) :confidence 0.80)
          result (detect-with-defaults tx)]

      ;; Confidence should be min of Stage 1 (0.80) and Stage 2 (0.95) = 0.80
      (is (<= (:confidence result) 0.80)))))

;; ============================================================================
;; End of tests
;; ============================================================================
