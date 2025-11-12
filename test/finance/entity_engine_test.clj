(ns finance.entity-engine-test
  "Tests for data-driven entity resolution engine"
  (:require [clojure.test :refer :all]
            [finance.entity-engine :as engine]))

(deftest test-load-entity-definitions
  (testing "Entity definitions can be loaded from JSON"
    (let [definitions (engine/load-entity-definitions!)]
      (is (seq definitions) "Should load at least one entity definition")
      (is (every? #(contains? % :id) definitions) "All definitions should have :id")
      (is (every? #(contains? % :display-name) definitions) "All definitions should have :display-name")
      (is (every? #(contains? % :registry-file) definitions) "All definitions should have :registry-file"))))

(deftest test-list-enabled-entities
  (testing "Can list enabled entity types in priority order"
    (engine/load-entity-definitions!)
    (let [enabled (engine/list-enabled-entities)]
      (is (seq enabled) "Should have at least one enabled entity")
      (is (= 4 (count enabled)) "Should have 4 enabled entities (Merchant, Bank, Account, Category)")

      ;; Check they are in priority order
      (let [priorities (map :priority enabled)]
        (is (= priorities (sort priorities)) "Should be sorted by priority")))))

(deftest test-get-entity-definition
  (testing "Can retrieve specific entity definition by ID"
    (engine/load-entity-definitions!)

    ;; Test merchant
    (let [merchant-def (engine/get-entity-definition "merchant")]
      (is merchant-def "Should find merchant definition")
      (is (= "merchant" (:id merchant-def)))
      (is (= "Merchant" (:display-name merchant-def)))
      (is (= 1 (:priority merchant-def))))

    ;; Test bank
    (let [bank-def (engine/get-entity-definition "bank")]
      (is bank-def "Should find bank definition")
      (is (= "bank" (:id bank-def)))
      (is (= "Bank" (:display-name bank-def)))
      (is (= 2 (:priority bank-def))))

    ;; Test non-existent
    (is (nil? (engine/get-entity-definition "non-existent"))
        "Should return nil for non-existent entity")))

(deftest test-extract-text-simple
  (testing "Can extract text from transaction using source-field"
    (engine/load-entity-definitions!)
    (let [merchant-def (engine/get-entity-definition "merchant")
          tx {:clean-merchant "GOOGLE WORKSPACE"
              :bank "Scotiabank"}
          extracted (engine/extract-text-for-entity tx merchant-def)]
      (is (= "GOOGLE WORKSPACE" extracted)))))

(deftest test-extract-text-with-fallback
  (testing "Uses fallback field when source-field is nil"
    (let [entity-def {:extraction {:source-field :non-existent
                                   :fallback-field :bank}}
          tx {:bank "Scotiabank"}
          extracted (engine/extract-text-for-entity tx entity-def)]
      (is (= "Scotiabank" extracted)))))

(deftest test-resolve-entity-merchant
  (testing "Can resolve merchant entity"
    (engine/load-entity-definitions!)
    (let [tx {:clean-merchant "GOOGLE WORKSPACE"
              :amount 100.0
              :date "2025-01-15"}
          result (engine/resolve-all-entities tx)]

      ;; Check merchant fields
      (is (contains? result :merchant-entity) "Should have merchant-entity")
      (is (contains? result :merchant-resolved?) "Should have merchant-resolved?")
      (is (contains? result :merchant-canonical) "Should have merchant-canonical")

      ;; Original fields should be preserved
      (is (= "GOOGLE WORKSPACE" (:clean-merchant result)))
      (is (= 100.0 (:amount result)))
      (is (= "2025-01-15" (:date result))))))

(deftest test-resolve-entity-bank-from-pdf-source
  (testing "Can resolve bank entity from pdf-source"
    (engine/load-entity-definitions!)
    (let [tx {:pdf-source "scotiabank_edo_2025-07-14_0372.pdf"
              :clean-merchant "STARBUCKS"
              :amount 45.99}
          result (engine/resolve-all-entities tx)]

      ;; Check bank fields
      (is (contains? result :bank-entity) "Should have bank-entity")
      (is (contains? result :bank-resolved?) "Should have bank-resolved?")
      (is (contains? result :bank-canonical) "Should have bank-canonical")

      ;; Should extract "scotiabank" from PDF filename
      (when (:bank-resolved? result)
        (is (= "Scotiabank" (:bank-canonical result)))))))

(deftest test-resolve-all-entities
  (testing "Can resolve all 4 entities in one pass"
    (engine/load-entity-definitions!)
    (let [tx {:pdf-source "scotiabank_edo_2025-07-14_0372.pdf"
              :clean-merchant "GOOGLE WORKSPACE"
              :merchant-category :technology
              :account-name "Scotiabank Checking"
              :amount 100.0
              :date "2025-01-15"}
          result (engine/resolve-all-entities tx)]

      ;; Check all 4 entities have their keys
      (is (contains? result :merchant-entity) "Should have merchant-entity")
      (is (contains? result :bank-entity) "Should have bank-entity")
      (is (contains? result :account-entity) "Should have account-entity")
      (is (contains? result :category-entity) "Should have category-entity")

      ;; Check resolution status keys
      (is (contains? result :merchant-resolved?))
      (is (contains? result :bank-resolved?))
      (is (contains? result :account-resolved?))
      (is (contains? result :category-resolved?))

      ;; Original fields preserved
      (is (= 100.0 (:amount result)))
      (is (= "2025-01-15" (:date result))))))

(deftest test-resolve-batch
  (testing "Can resolve batch of transactions"
    (engine/load-entity-definitions!)
    (let [txs [{:clean-merchant "GOOGLE WORKSPACE" :amount 100.0}
               {:clean-merchant "AMAZON" :amount 50.0}
               {:clean-merchant "STARBUCKS" :amount 5.0}]
          results (engine/resolve-batch txs)]

      (is (= 3 (count results)) "Should return same number of transactions")

      ;; All should have entity resolution attempted
      (is (every? #(contains? % :merchant-entity) results))
      (is (every? #(contains? % :merchant-resolved?) results))

      ;; Original data preserved
      (is (= 100.0 (:amount (nth results 0))))
      (is (= 50.0 (:amount (nth results 1))))
      (is (= 5.0 (:amount (nth results 2)))))))

(deftest test-reload-entity-definitions
  (testing "Can reload entity definitions at runtime"
    (engine/load-entity-definitions!)
    (let [before-count (count (engine/list-enabled-entities))]

      ;; Reload
      (engine/reload-entity-definitions!)

      ;; Should still have same count
      (is (= before-count (count (engine/list-enabled-entities)))
          "Count should be same after reload"))))

(comment
  ;; Run all tests
  (run-tests)

  ;; Run individual test
  (test-load-entity-definitions)
  (test-resolve-all-entities)
  )
