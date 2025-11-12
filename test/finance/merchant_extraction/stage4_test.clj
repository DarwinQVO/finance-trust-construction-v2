(ns finance.merchant-extraction.stage4-test
  (:require [clojure.test :refer :all]
            [finance.merchant-extraction.stage4 :as stage4]
            [finance.merchant-extraction.protocols :as proto]))

;; ============================================================================
;; Test Data (Clean transactions from Stage 3)
;; ============================================================================

(def test-transactions
  {:google-youtube
   {:date "26-JUN-25"
    :description "GOOGLE YOUTUBEPREMIUM CARG RE 00000000517719716538..."
    :clean-merchant "GOOGLE YOUTUBEPREMIUM"
    :type :card-purchase
    :merchant? true
    :counterparty-info {:detected? false}
    :confidence 0.92
    :stage-3 {:extraction-method :full-description}}

   :google-cloud
   {:date "01-AGO-25"
    :description "PAYU GOOGLE CLOUDS..."
    :clean-merchant "GOOGLE CLOUD PLATFORM"
    :type :card-purchase
    :merchant? true
    :counterparty-info {:detected? true
                        :counterparty-id :payu
                        :actual-merchant-hint "GOOGLE CLOUD PLATFORM"}
    :confidence 0.88
    :stage-3 {:extraction-method :post-counterparty}}

   :uber-eats
   {:date "05-SEP-25"
    :description "UBER *EATS MR TREUBLAAN 7..."
    :clean-merchant "UBER EATS"
    :type :card-purchase
    :merchant? true
    :counterparty-info {:detected? true
                        :counterparty-id :uber
                        :actual-merchant-hint "EATS"}
    :confidence 0.85
    :stage-3 {:extraction-method :post-counterparty}}

   :starbucks
   {:date "15-JUL-25"
    :description "STARBUCKS STORE..."
    :clean-merchant "STARBUCKS"
    :type :card-purchase
    :merchant? true
    :counterparty-info {:detected? false}
    :confidence 0.95
    :stage-3 {:extraction-method :full-description}}

   :netflix
   {:date "20-AUG-25"
    :description "CARG RECUR. NETFLIX..."
    :clean-merchant "NETFLIX"
    :type :card-purchase
    :merchant? true
    :counterparty-info {:detected? false}
    :confidence 0.93
    :stage-3 {:extraction-method :full-description}}

   :unknown-merchant
   {:date "10-SEP-25"
    :description "LOCAL TACO SHOP..."
    :clean-merchant "LOCAL TACO SHOP"
    :type :card-purchase
    :merchant? true
    :counterparty-info {:detected? false}
    :confidence 0.70
    :stage-3 {:extraction-method :full-description}}

   :no-clean-merchant
   {:date "17-JUN-25"
    :description "TRANSF INTERBANCARIA SPEI..."
    :clean-merchant nil  ;; Failed extraction
    :type :spei-transfer-in
    :merchant? false
    :counterparty-info {:detected? false}
    :confidence 0.10
    :stage-3 {:extraction-method :failed
              :error "Merchant name too short after cleaning"}}})

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn- disambiguate-with-defaults [tx]
  (let [disambiguator (stage4/create-disambiguator)
        rules (stage4/load-rules)]
    (proto/disambiguate-merchant disambiguator tx rules)))

;; ============================================================================
;; Rule Matching Tests
;; ============================================================================

(deftest test-google-youtube-disambiguation
  (testing "GOOGLE YOUTUBEPREMIUM should match google-youtube rule"
    (let [tx (:google-youtube test-transactions)
          result (disambiguate-with-defaults tx)]

      (is (= :google-youtube-premium (:merchant-id result)))
      (is (= "Google YouTube Premium" (:merchant-name result)))
      (is (= :entertainment-subscriptions (:merchant-category result)))
      (is (= :rule-match (get-in result [:stage-4 :disambiguation-method]))))))

(deftest test-google-cloud-disambiguation
  (testing "GOOGLE CLOUD PLATFORM should match google-cloud rule"
    (let [tx (:google-cloud test-transactions)
          result (disambiguate-with-defaults tx)]

      (is (= :google-cloud-platform (:merchant-id result)))
      (is (= "Google Cloud Platform" (:merchant-name result)))
      (is (= :business-cloud-services (:merchant-category result)))
      (is (= :rule-match (get-in result [:stage-4 :disambiguation-method]))))))

(deftest test-uber-eats-disambiguation
  (testing "UBER EATS should match uber-eats rule (not generic uber)"
    (let [tx (:uber-eats test-transactions)
          result (disambiguate-with-defaults tx)]

      (is (= :uber-eats (:merchant-id result)))
      (is (= "Uber Eats" (:merchant-name result)))
      (is (= :food-delivery (:merchant-category result)))
      (is (= :rule-match (get-in result [:stage-4 :disambiguation-method]))))))

(deftest test-starbucks-disambiguation
  (testing "STARBUCKS should match starbucks rule"
    (let [tx (:starbucks test-transactions)
          result (disambiguate-with-defaults tx)]

      (is (= :starbucks (:merchant-id result)))
      (is (= "Starbucks" (:merchant-name result)))
      (is (= :cafes-coffee-shops (:merchant-category result)))
      (is (= :rule-match (get-in result [:stage-4 :disambiguation-method]))))))

(deftest test-netflix-disambiguation
  (testing "NETFLIX should match netflix rule"
    (let [tx (:netflix test-transactions)
          result (disambiguate-with-defaults tx)]

      (is (= :netflix (:merchant-id result)))
      (is (= "Netflix" (:merchant-name result)))
      (is (= :entertainment-subscriptions (:merchant-category result)))
      (is (= :rule-match (get-in result [:stage-4 :disambiguation-method]))))))

;; ============================================================================
;; Fallback Tests
;; ============================================================================

(deftest test-unknown-merchant-fallback
  (testing "Unknown merchant should use fallback"
    (let [tx (:unknown-merchant test-transactions)
          result (disambiguate-with-defaults tx)]

      ;; Should create kebab-case merchant-id
      (is (= :local-taco-shop (:merchant-id result)))
      ;; Should use original name as canonical
      (is (= "LOCAL TACO SHOP" (:merchant-name result)))
      ;; Should use fallback category
      (is (= :uncategorized (:merchant-category result)))
      ;; Should be marked as fallback
      (is (= :fallback (get-in result [:stage-4 :disambiguation-method])))
      (is (true? (get-in result [:stage-4 :fallback?])))
      ;; Should have low confidence
      (is (< (:confidence result) 0.50)))))

;; ============================================================================
;; No Clean Merchant Handling
;; ============================================================================

(deftest test-no-clean-merchant-skip
  (testing "Should skip disambiguation when clean-merchant is nil"
    (let [tx (:no-clean-merchant test-transactions)
          result (disambiguate-with-defaults tx)]

      ;; Should not have merchant fields
      (is (not (contains? result :merchant-id)))
      (is (not (contains? result :merchant-name)))
      (is (not (contains? result :merchant-category)))
      ;; Should have stage-4 with skipped method
      (is (= :skipped (get-in result [:stage-4 :disambiguation-method]))))))

;; ============================================================================
;; Field Preservation Tests
;; ============================================================================

(deftest test-previous-stages-preserved
  (testing "Previous stage fields should be preserved"
    (let [tx (:google-youtube test-transactions)
          result (disambiguate-with-defaults tx)]

      ;; Stage 3 fields preserved
      (is (= (:clean-merchant tx) (:clean-merchant result)))
      (is (= (:type tx) (:type result)))
      (is (= (:merchant? tx) (:merchant? result)))
      (is (= (:counterparty-info tx) (:counterparty-info result))))))

;; ============================================================================
;; Stage Metadata Tests
;; ============================================================================

(deftest test-stage4-metadata
  (testing "Stage 4 metadata should be present"
    (let [tx (:google-youtube test-transactions)
          result (disambiguate-with-defaults tx)]

      (is (contains? result :stage-4))
      (is (contains? (:stage-4 result) :disambiguation-method))
      (is (contains? (:stage-4 result) :timestamp))
      (is (contains? (:stage-4 result) :matched-rule))
      (is (contains? (:stage-4 result) :matched-pattern))
      ;; Should be rule-match method
      (is (= :rule-match (get-in result [:stage-4 :disambiguation-method]))))))

;; ============================================================================
;; Confidence Adjustment Tests
;; ============================================================================

(deftest test-confidence-multiplication
  (testing "Confidence should be product of previous and disambiguation confidence"
    (let [tx (:google-youtube test-transactions)
          result (disambiguate-with-defaults tx)
          original-confidence (:confidence tx)
          rules (stage4/load-rules)
          rule-confidence (get-in rules [:disambiguation-rules :google-youtube :confidence])]

      ;; Should be approximately original * rule confidence
      (is (<= (:confidence result) original-confidence))
      ;; For google-youtube: 0.92 * 0.95 = 0.874
      (is (> (:confidence result) 0.85))
      (is (< (:confidence result) 0.90)))))

;; ============================================================================
;; Batch Processing Tests
;; ============================================================================

(deftest test-batch-disambiguation
  (testing "Batch disambiguation should work correctly"
    (let [txs (vals test-transactions)
          results (stage4/disambiguate-batch txs)]

      ;; Should process all transactions
      (is (= (count txs) (count results)))

      ;; All transactions with clean-merchant should have stage-4
      (doseq [result results]
        (if (:clean-merchant result)
          (is (contains? result :stage-4))
          (is (= :skipped (get-in result [:stage-4 :disambiguation-method]))))))))

;; ============================================================================
;; Statistics Tests
;; ============================================================================

(deftest test-disambiguation-statistics
  (testing "Statistics calculation should work"
    (let [txs (vals test-transactions)
          results (stage4/disambiguate-batch txs)
          stats (stage4/disambiguation-statistics results)]

      (is (contains? stats :total-transactions))
      (is (contains? stats :merchants-disambiguated))
      (is (contains? stats :rule-matched))
      (is (contains? stats :fallback))
      (is (contains? stats :match-rate))
      (is (contains? stats :categories))
      (is (contains? stats :unique-merchants))

      ;; Should have matched some rules
      (is (pos? (:rule-matched stats)))
      ;; Should have at least one fallback (unknown merchant)
      (is (pos? (:fallback stats))))))

;; ============================================================================
;; Validation Tests
;; ============================================================================

(deftest test-validation
  (testing "Disambiguated transactions should validate"
    (let [tx (:google-youtube test-transactions)
          result (disambiguate-with-defaults tx)]

      (is (true? (stage4/validate-disambiguated-transaction result))))))

(deftest test-batch-validation
  (testing "Batch validation should work"
    (let [txs (vals test-transactions)
          results (stage4/disambiguate-batch txs)
          validation (stage4/validate-batch results)]

      ;; All should be valid
      (is (= (:valid validation) (count results)))
      (is (= (:invalid validation) 0)))))

;; ============================================================================
;; Case Insensitivity Tests
;; ============================================================================

(deftest test-case-insensitive-matching
  (testing "Pattern matching should be case insensitive"
    (let [tx {:clean-merchant "google youtubepremium"  ;; lowercase
              :type :card-purchase
              :merchant? true
              :confidence 0.90
              :stage-3 {:extraction-method :full-description}}
          result (disambiguate-with-defaults tx)]

      ;; Should still match despite different case
      (is (= :google-youtube-premium (:merchant-id result))))))

;; ============================================================================
;; End of tests
;; ============================================================================
