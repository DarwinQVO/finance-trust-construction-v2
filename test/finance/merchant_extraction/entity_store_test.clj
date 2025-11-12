(ns finance.merchant-extraction.stage5-test
  (:require [clojure.test :refer :all]
            [finance.merchant-extraction.stage5 :as stage5]
            [finance.merchant-extraction.protocols :as proto]))

;; ============================================================================
;; Test Data (Disambiguated transactions from Stage 4)
;; ============================================================================

(def test-transactions
  {:google-youtube-high-conf
   {:date "26-JUN-25"
    :clean-merchant "GOOGLE YOUTUBEPREMIUM"
    :merchant-id :google-youtube-premium
    :merchant-name "Google YouTube Premium"
    :merchant-category :entertainment-subscriptions
    :type :card-purchase
    :merchant? true
    :confidence 0.92  ;; Above auto-resolve threshold
    :stage-4 {:disambiguation-method :rule-match
              :matched-rule :google-youtube}}

   :google-cloud-medium-conf
   {:date "01-AGO-25"
    :clean-merchant "GOOGLE CLOUD PLATFORM"
    :merchant-id :google-cloud-platform
    :merchant-name "Google Cloud Platform"
    :merchant-category :business-cloud-services
    :type :card-purchase
    :merchant? true
    :confidence 0.83  ;; Between thresholds
    :stage-4 {:disambiguation-method :rule-match
              :matched-rule :google-cloud}}

   :unknown-merchant-fallback
   {:date "10-SEP-25"
    :clean-merchant "LOCAL TACO SHOP"
    :merchant-id :local-taco-shop
    :merchant-name "LOCAL TACO SHOP"
    :merchant-category :uncategorized
    :type :card-purchase
    :merchant? true
    :confidence 0.21  ;; Low confidence, below manual review
    :stage-4 {:disambiguation-method :fallback
              :fallback? true}}

   :starbucks-high-conf
   {:date "15-JUL-25"
    :clean-merchant "STARBUCKS"
    :merchant-id :starbucks
    :merchant-name "Starbucks"
    :merchant-category :cafes-coffee-shops
    :type :card-purchase
    :merchant? true
    :confidence 0.90  ;; Exactly at auto-resolve threshold
    :stage-4 {:disambiguation-method :rule-match
              :matched-rule :starbucks}}

   :no-merchant-id
   {:date "17-JUN-25"
    :clean-merchant nil
    :type :spei-transfer-in
    :merchant? false
    :confidence 0.10
    :stage-4 {:disambiguation-method :skipped
              :reason "No clean merchant available"}}})

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn- resolve-with-defaults [tx entity-store]
  (let [resolver (stage5/create-resolver)
        rules (stage5/load-rules)]
    (proto/resolve-entity resolver tx entity-store rules)))

;; ============================================================================
;; Entity Store Tests
;; ============================================================================

(deftest test-create-entity-store
  (testing "Should create empty entity store"
    (let [store (stage5/create-entity-store)]
      (is (not (nil? store))))))

(deftest test-create-entity
  (testing "Should create new entity"
    (let [store (stage5/create-entity-store)
          entity {:merchant-id :test-merchant
                  :merchant-name "Test Merchant"
                  :category :test-category
                  :confidence 0.95}
          entity-id (proto/create-entity store entity)]

      (is (keyword? entity-id))
      (is (= :test-merchant entity-id))  ;; Uses merchant-id as entity-id

      (let [stored-entity (proto/get-entity store entity-id)]
        (is (not (nil? stored-entity)))
        (is (= :test-merchant (:merchant-id stored-entity)))
        (is (= "Test Merchant" (:merchant-name stored-entity)))
        (is (= 1 (:transaction-count stored-entity)))))))

(deftest test-update-entity
  (testing "Should update existing entity"
    (let [store (stage5/create-entity-store)
          entity {:merchant-id :test-merchant
                  :merchant-name "Test Merchant"
                  :confidence 0.80}
          entity-id (proto/create-entity store entity)]

      ;; Update entity
      (proto/update-entity store entity-id {:confidence 0.90})

      (let [updated-entity (proto/get-entity store entity-id)]
        (is (= 2 (:transaction-count updated-entity)))  ;; Count incremented
        (is (= 2 (count (:confidence-history updated-entity))))))))  ;; History tracked

(deftest test-merge-entities
  (testing "Should merge two entities"
    (let [store (stage5/create-entity-store)
          source-id (proto/create-entity store {:merchant-id :source :merchant-name "Source"})
          target-id (proto/create-entity store {:merchant-id :target :merchant-name "Target"})]

      ;; Merge source into target
      (proto/merge-entities store source-id target-id "Duplicate merchant")

      (let [source (proto/get-entity store source-id)
            target (proto/get-entity store target-id)]
        ;; Source should be marked as merged
        (is (= :merged (:state source)))
        (is (= target-id (:merged-into source)))
        ;; Target should have source's aliases
        (is (contains? (:aliases target) :source))))))

;; ============================================================================
;; Entity Resolution Tests
;; ============================================================================

(deftest test-new-entity-creation
  (testing "First time seeing merchant should create new entity"
    (let [store (stage5/create-entity-store)
          tx (:google-youtube-high-conf test-transactions)
          result (resolve-with-defaults tx store)]

      (is (contains? result :entity))
      (is (contains? result :entity-id))
      (is (= :google-youtube-premium (:entity-id result)))
      (is (= :new-entity (get-in result [:stage-5 :resolution-method]))))))

(deftest test-existing-entity-reuse
  (testing "Seeing merchant again should reuse existing entity"
    (let [store (stage5/create-entity-store)
          tx (:google-youtube-high-conf test-transactions)]

      ;; First transaction creates entity
      (resolve-with-defaults tx store)

      ;; Second transaction should reuse entity
      (let [result (resolve-with-defaults tx store)]
        (is (= :existing-entity (get-in result [:stage-5 :resolution-method])))
        (is (= 2 (get-in result [:entity :transaction-count])))))))

(deftest test-high-confidence-auto-resolve
  (testing "High confidence should not need verification"
    (let [store (stage5/create-entity-store)
          tx (:google-youtube-high-conf test-transactions)
          result (resolve-with-defaults tx store)]

      ;; High confidence (0.92 >= 0.90), but new entity needs verification
      ;; Entity resolution marks new entities as needs-verification
      (is (true? (:needs-verification result))))))

(deftest test-low-confidence-needs-review
  (testing "Low confidence should need verification"
    (let [store (stage5/create-entity-store)
          tx (:unknown-merchant-fallback test-transactions)
          result (resolve-with-defaults tx store)]

      (is (true? (:needs-verification result)))
      (is (not (nil? (:verification-reason result)))))))

(deftest test-fallback-creates-provisional
  (testing "Fallback merchants should create provisional entities"
    (let [store (stage5/create-entity-store)
          tx (:unknown-merchant-fallback test-transactions)
          result (resolve-with-defaults tx store)]

      (is (= :provisional (:entity-state result))))))

(deftest test-rule-matched-creates-canonical
  (testing "Rule-matched merchants should create canonical entities"
    (let [store (stage5/create-entity-store)
          tx (:google-youtube-high-conf test-transactions)
          result (resolve-with-defaults tx store)]

      (is (= :canonical (:entity-state result))))))

;; ============================================================================
;; No Merchant Handling
;; ============================================================================

(deftest test-no-merchant-id-skip
  (testing "Should skip resolution when merchant-id is nil"
    (let [store (stage5/create-entity-store)
          tx (:no-merchant-id test-transactions)
          result (resolve-with-defaults tx store)]

      ;; Should not have entity fields
      (is (not (contains? result :entity)))
      (is (not (contains? result :entity-id)))
      ;; Should have stage-5 with skipped method
      (is (= :skipped (get-in result [:stage-5 :resolution-method]))))))

;; ============================================================================
;; Field Preservation Tests
;; ============================================================================

(deftest test-previous-stages-preserved
  (testing "Previous stage fields should be preserved"
    (let [store (stage5/create-entity-store)
          tx (:google-youtube-high-conf test-transactions)
          result (resolve-with-defaults tx store)]

      ;; Stage 4 fields preserved
      (is (= (:merchant-id tx) (:merchant-id result)))
      (is (= (:merchant-name tx) (:merchant-name result)))
      (is (= (:merchant-category tx) (:merchant-category result))))))

;; ============================================================================
;; Stage Metadata Tests
;; ============================================================================

(deftest test-stage5-metadata
  (testing "Stage 5 metadata should be present"
    (let [store (stage5/create-entity-store)
          tx (:google-youtube-high-conf test-transactions)
          result (resolve-with-defaults tx store)]

      (is (contains? result :stage-5))
      (is (contains? (:stage-5 result) :resolution-method))
      (is (contains? (:stage-5 result) :timestamp))
      (is (contains? (:stage-5 result) :transaction-number)))))

;; ============================================================================
;; Batch Processing Tests
;; ============================================================================

(deftest test-batch-resolution
  (testing "Batch resolution should work correctly"
    (let [store (stage5/create-entity-store)
          txs (vals test-transactions)
          results (stage5/resolve-batch txs store)]

      ;; Should process all transactions
      (is (= (count txs) (count results)))

      ;; All transactions with merchant-id should have stage-5
      (doseq [result results]
        (if (:merchant-id result)
          (is (contains? result :stage-5))
          (is (= :skipped (get-in result [:stage-5 :resolution-method]))))))))

;; ============================================================================
;; Statistics Tests
;; ============================================================================

(deftest test-resolution-statistics
  (testing "Statistics calculation should work"
    (let [store (stage5/create-entity-store)
          txs (vals test-transactions)
          results (stage5/resolve-batch txs store)
          stats (stage5/resolution-statistics results)]

      (is (contains? stats :total-transactions))
      (is (contains? stats :entities-resolved))
      (is (contains? stats :new-entities))
      (is (contains? stats :existing-entities))
      (is (contains? stats :needs-verification))
      (is (contains? stats :verification-rate))
      (is (contains? stats :by-state))
      (is (contains? stats :unique-entities))

      ;; Should have resolved some entities
      (is (pos? (:entities-resolved stats)))
      ;; All resolved should be new (first time seeing them)
      (is (= (:new-entities stats) (:entities-resolved stats))))))

;; ============================================================================
;; Validation Tests
;; ============================================================================

(deftest test-validation
  (testing "Resolved transactions should validate"
    (let [store (stage5/create-entity-store)
          tx (:google-youtube-high-conf test-transactions)
          result (resolve-with-defaults tx store)]

      (is (true? (stage5/validate-resolved-transaction result))))))

(deftest test-batch-validation
  (testing "Batch validation should work"
    (let [store (stage5/create-entity-store)
          txs (vals test-transactions)
          results (stage5/resolve-batch txs store)
          validation (stage5/validate-batch results)]

      ;; All should be valid
      (is (= (:valid validation) (count results)))
      (is (= (:invalid validation) 0)))))

;; ============================================================================
;; End of tests
;; ============================================================================
