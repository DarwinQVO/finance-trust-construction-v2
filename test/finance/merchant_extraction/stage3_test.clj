(ns finance.merchant-extraction.stage3-test
  (:require [clojure.test :refer :all]
            [finance.merchant-extraction.stage3 :as stage3]
            [finance.merchant-extraction.protocols :as proto]))

;; ============================================================================
;; Test Data (Counterparty transactions from Stage 2)
;; ============================================================================

(def test-transactions
  {:clip-with-noise
   {:date "11-AGO-25"
    :description "CLIPMX AGREGADOR 00000000101008685717 CLIP MX REST HANAICHI REF. 0013732041 AUT. 742785 RFC BLI 120726UF6"
    :retiro 2236.00
    :type :card-purchase
    :merchant? true
    :counterparty-info {:detected? true
                        :counterparty-id :clip
                        :actual-merchant-hint "REST HANAICHI REF. 0013732041 AUT. 742785 RFC BLI 120726UF6"}
    :confidence 0.95}

   :google-direct
   {:date "26-JUN-25"
    :description "GOOGLE YOUTUBEPREMIUM CARG RE 00000000517719716538 MONTO ORIGEN 159.00 REF. 0013732041 AUT. 305884"
    :retiro 159.00
    :type :card-purchase
    :merchant? true
    :counterparty-info {:detected? false}
    :confidence 0.95}

   :uber-eats-location
   {:date "05-SEP-25"
    :description "UBER *EATS MR TREUBLAAN 7 AMSTERDAM 1097 DP NH NLD"
    :retiro 171.19
    :type :card-purchase
    :merchant? true
    :counterparty-info {:detected? true
                        :counterparty-id :uber
                        :actual-merchant-hint "EATS MR TREUBLAAN 7 AMSTERDAM 1097 DP NH NLD"}
    :confidence 0.90}

   :payu-google-cloud
   {:date "01-AGO-25"
    :description "PAYU GOOGLE CLOUDS CARG RECUR. 00000000000047418850 PAYU GOOGLE CLOUD PLATFORM"
    :retiro 3756.80
    :type :card-purchase
    :merchant? true
    :counterparty-info {:detected? true
                        :counterparty-id :payu
                        :actual-merchant-hint "GOOGLE CLOUD PLATFORM"}
    :confidence 0.90}

   :no-merchant-spei
   {:date "17-JUN-25"
    :description "TRANSF INTERBANCARIA SPEI 00000000000003732041"
    :deposit 3140.00
    :type :spei-transfer-in
    :merchant? false
    :counterparty-info {:detected? false}
    :confidence 0.98}})

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn- extract-with-defaults [tx]
  (let [extractor (stage3/create-extractor)
        rules (stage3/load-rules)]
    (proto/extract-merchant extractor tx rules)))

;; ============================================================================
;; Noise Removal Tests
;; ============================================================================

(deftest test-clip-noise-removal
  (testing "Should remove transaction IDs, REF, AUT, RFC from CLIP transaction"
    (let [tx (:clip-with-noise test-transactions)
          result (extract-with-defaults tx)]

      (is (string? (:clean-merchant result)))
      (is (= "REST HANAICHI" (:clean-merchant result)))
      (is (seq (:removed-noise result)))
      ;; Should have removed: ID, REF, AUT, RFC
      (is (>= (count (:removed-noise result)) 3)))))

(deftest test-google-direct-cleaning
  (testing "Should clean Google direct merchant"
    (let [tx (:google-direct test-transactions)
          result (extract-with-defaults tx)]

      (is (string? (:clean-merchant result)))
      ;; Should start with GOOGLE
      (is (re-find #"GOOGLE" (:clean-merchant result)))
      ;; Should have removed noise
      (is (seq (:removed-noise result))))))

(deftest test-uber-location-removal
  (testing "Should remove location codes from Uber Eats"
    (let [tx (:uber-eats-location test-transactions)
          result (extract-with-defaults tx)]

      (is (string? (:clean-merchant result)))
      ;; Should contain EATS
      (is (re-find #"EATS" (:clean-merchant result)))
      ;; Should NOT contain location codes like "1097 DP NH NLD"
      (is (not (re-find #"1097" (:clean-merchant result))))
      (is (not (re-find #"\bDP\b" (:clean-merchant result)))))))

(deftest test-payu-simple-clean
  (testing "Should extract clean merchant from PayU"
    (let [tx (:payu-google-cloud test-transactions)
          result (extract-with-defaults tx)]

      (is (string? (:clean-merchant result)))
      (is (= "GOOGLE CLOUD PLATFORM" (:clean-merchant result))))))

;; ============================================================================
;; No Merchant Handling
;; ============================================================================

(deftest test-no-merchant-skip
  (testing "Should skip extraction when merchant? is false"
    (let [tx (:no-merchant-spei test-transactions)
          result (extract-with-defaults tx)]

      (is (false? (:merchant? result)))
      ;; Should preserve original, no clean-merchant
      (is (= tx result)))))

;; ============================================================================
;; Field Preservation Tests
;; ============================================================================

(deftest test-previous-stages-preserved
  (testing "Previous stage fields should be preserved"
    (let [tx (:clip-with-noise test-transactions)
          result (extract-with-defaults tx)]

      ;; Stage 1 & 2 fields preserved
      (is (= (:type tx) (:type result)))
      (is (= (:merchant? tx) (:merchant? result)))
      (is (= (:counterparty-info tx) (:counterparty-info result))))))

;; ============================================================================
;; Stage Metadata Tests
;; ============================================================================

(deftest test-stage3-metadata
  (testing "Stage 3 metadata should be present"
    (let [tx (:clip-with-noise test-transactions)
          result (extract-with-defaults tx)]

      (is (contains? result :stage-3))
      (is (contains? (:stage-3 result) :extraction-method))
      (is (contains? (:stage-3 result) :noise-patterns-applied))
      (is (contains? (:stage-3 result) :timestamp))
      ;; Should be post-counterparty method
      (is (= :post-counterparty (get-in result [:stage-3 :extraction-method]))))))

;; ============================================================================
;; Confidence Adjustment Tests
;; ============================================================================

(deftest test-confidence-penalty-for-noise
  (testing "Confidence should have slight penalty when noise is removed"
    (let [tx (:clip-with-noise test-transactions)
          result (extract-with-defaults tx)]

      ;; Should have slightly lower confidence due to noise removal
      (is (<= (:confidence result) (:confidence tx))))))

;; ============================================================================
;; Batch Processing Tests
;; ============================================================================

(deftest test-batch-extraction
  (testing "Batch extraction should work correctly"
    (let [txs (vals test-transactions)
          results (stage3/extract-batch txs)]

      ;; Should process all transactions
      (is (= (count txs) (count results)))

      ;; All merchant transactions should have stage-3 or be skipped correctly
      (doseq [result results]
        (if (:merchant? result)
          (is (contains? result :stage-3))
          (is (not (contains? result :stage-3))))))))

;; ============================================================================
;; Statistics Tests
;; ============================================================================

(deftest test-extraction-statistics
  (testing "Statistics calculation should work"
    (let [txs (vals test-transactions)
          results (stage3/extract-batch txs)
          stats (stage3/extraction-statistics results)]

      (is (contains? stats :total-transactions))
      (is (contains? stats :merchant-transactions))
      (is (contains? stats :successfully-extracted))
      (is (contains? stats :success-rate))

      ;; Should have extracted merchants successfully
      (is (pos? (:successfully-extracted stats))))))

;; ============================================================================
;; Validation Tests
;; ============================================================================

(deftest test-validation
  (testing "Clean transactions should validate"
    (let [tx (:clip-with-noise test-transactions)
          result (extract-with-defaults tx)]

      (is (true? (stage3/validate-clean-transaction result))))))

(deftest test-batch-validation
  (testing "Batch validation should work"
    (let [txs (vals test-transactions)
          results (stage3/extract-batch txs)
          validation (stage3/validate-batch results)]

      ;; All should be valid
      (is (= (:valid validation) (count results)))
      (is (= (:invalid validation) 0)))))

;; ============================================================================
;; Edge Cases
;; ============================================================================

(deftest test-empty-merchant-after-cleaning
  (testing "Should handle case where merchant becomes empty after cleaning"
    (let [tx {:date "01-JAN-25"
              :description "REF. 123 AUT. 456"  ;; Only noise, no real merchant
              :type :card-purchase
              :merchant? true
              :counterparty-info {:detected? false}
              :confidence 0.95}
          result (extract-with-defaults tx)]

      ;; clean-merchant should be nil (nothing left after removing noise)
      (is (nil? (:clean-merchant result)))
      ;; Confidence should be very low
      (is (< (:confidence result) 0.50))
      ;; Should have failed extraction
      (is (= :failed (get-in result [:stage-3 :extraction-method]))))))

;; ============================================================================
;; Context Preservation Tests
;; ============================================================================

(deftest test-context-preservation
  (testing "Useful context should be kept separate from noise"
    (let [tx {:date "01-JAN-25"
              :description "CARG RECUR. NETFLIX 00000000123456789012 REF. 123 AUT. 456"
              :type :card-purchase
              :merchant? true
              :counterparty-info {:detected? false}
              :confidence 0.95}
          result (extract-with-defaults tx)]

      (is (string? (:clean-merchant result)))
      ;; Should have context
      (is (seq (:kept-context result))))))

;; ============================================================================
;; End of tests
;; ============================================================================
