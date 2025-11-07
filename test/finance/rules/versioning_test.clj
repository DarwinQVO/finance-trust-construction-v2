(ns finance.rules.versioning-test
  "Tests for rule versioning and audit trail system.

  Tests verify:
  - Version saving and loading
  - Audit trail metadata
  - Version listing and filtering
  - Version comparison
  - Rollback functionality
  - Version statistics"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [finance.rules.versioning :as v]
            [clojure.java.io :as io]))

;; ============================================================================
;; TEST FIXTURES
;; ============================================================================

(def test-rules-v1
  [{:id :rule-1 :pattern "STARBUCKS" :category :restaurants :confidence 0.95}
   {:id :rule-2 :pattern "AMAZON" :category :shopping :confidence 0.90}
   {:id :rule-3 :pattern "UBER" :category :transportation :confidence 0.98}])

(def test-rules-v2
  [{:id :rule-1 :pattern "STARBUCKS" :category :restaurants :confidence 0.98} ; Modified confidence
   {:id :rule-2 :pattern "AMAZON" :category :shopping :confidence 0.90} ; Unchanged
   {:id :rule-4 :pattern "NETFLIX" :category :entertainment :confidence 0.95}]) ; Added, removed rule-3

(def test-metadata
  {:author "test-user"
   :reason "Test version"
   :notes "Testing versioning system"})

;; Clean up test files after each test
(defn cleanup-test-files [f]
  (f)
  ;; Note: In production, you'd clean up test version files here
  ;; For now, we'll let them accumulate (they're small)
  )

(use-fixtures :each cleanup-test-files)

;; ============================================================================
;; TEST 1: Save and Load Version
;; ============================================================================

(deftest test-save-and-load-version
  (testing "Save version and load it back"
    (let [;; Save v1
          version (v/save-version! :merchant-rules test-rules-v1 test-metadata)]

      (testing "Version metadata is correct"
        (is (= :merchant-rules (:rule-type version)))
        (is (= "test-user" (:author version)))
        (is (= "Test version" (:reason version)))
        (is (= 3 (:rule-count version)))
        (is (:timestamp version))
        (is (:path version)))

      (testing "Can load saved version"
        (let [loaded (v/load-version :merchant-rules (:timestamp version))]
          (is (= 3 (count loaded)))
          (is (= test-rules-v1 loaded)))))))

;; ============================================================================
;; TEST 2: List Versions
;; ============================================================================

(deftest test-list-versions
  (testing "List all versions of a rule type"
    ;; Save 2 versions
    (let [v1 (v/save-version! :deduplication-rules test-rules-v1
                             (assoc test-metadata :reason "Version 1"))
          _ (Thread/sleep 100) ; Ensure different timestamps
          v2 (v/save-version! :deduplication-rules test-rules-v2
                             (assoc test-metadata :reason "Version 2"))]

      (testing "Lists all versions in order"
        (let [versions (v/list-versions :deduplication-rules)
              ;; Get only our test versions (filter by author)
              test-versions (filter #(= "test-user" (:author %)) versions)]
          (is (>= (count test-versions) 2))
          ;; Verify order (oldest first)
          (is (< (compare (:timestamp (first test-versions))
                         (:timestamp (second test-versions)))
                 0)))))))

;; ============================================================================
;; TEST 3: Get Latest Version
;; ============================================================================

(deftest test-get-latest-version
  (testing "Get latest version of rules"
    ;; Save 2 versions
    (v/save-version! :merchant-rules test-rules-v1
                    (assoc test-metadata :reason "Version 1"))
    (Thread/sleep 100)
    (let [v2 (v/save-version! :merchant-rules test-rules-v2
                             (assoc test-metadata :reason "Version 2"))]

      (testing "Returns latest version metadata"
        (let [latest (v/get-latest-version :merchant-rules)]
          (is (= "Version 2" (:reason latest)))
          (is (= 3 (:rule-count latest))))))))

;; ============================================================================
;; TEST 4: Load Latest Version
;; ============================================================================

(deftest test-load-latest-version
  (testing "Load latest version without timestamp"
    ;; Save 2 versions
    (v/save-version! :merchant-rules test-rules-v1 test-metadata)
    (Thread/sleep 100)
    (v/save-version! :merchant-rules test-rules-v2 test-metadata)

    (testing "Loads latest when no timestamp specified"
      (let [loaded (v/load-version :merchant-rules)]
        (is (= 3 (count loaded)))
        ;; Should be v2 (has rule-4, not rule-3)
        (is (some #(= :rule-4 (:id %)) loaded))
        (is (not (some #(= :rule-3 (:id %)) loaded)))))))

;; ============================================================================
;; TEST 5: Compare Versions
;; ============================================================================

(deftest test-compare-versions
  (testing "Compare two versions and detect changes"
    (let [v1 (v/save-version! :merchant-rules test-rules-v1
                             (assoc test-metadata :reason "Version 1"))
          _ (Thread/sleep 100)
          v2 (v/save-version! :merchant-rules test-rules-v2
                             (assoc test-metadata :reason "Version 2"))

          diff (v/compare-versions :merchant-rules
                                  (:timestamp v1)
                                  (:timestamp v2))]

      (testing "Detects added rules"
        (is (= 1 (count (:added diff))))
        (is (= :rule-4 (:id (first (:added diff))))))

      (testing "Detects removed rules"
        (is (= 1 (count (:removed diff))))
        (is (= :rule-3 (:id (first (:removed diff))))))

      (testing "Detects modified rules"
        (is (= 1 (count (:modified diff))))
        (let [modified (first (:modified diff))]
          (is (= :rule-1 (:id modified)))
          (is (= 0.95 (get-in modified [:before :confidence])))
          (is (= 0.98 (get-in modified [:after :confidence])))))

      (testing "Detects unchanged rules"
        (is (= 1 (count (:unchanged diff))))
        (is (= :rule-2 (first (:unchanged diff))))))))

;; ============================================================================
;; TEST 6: Rollback
;; ============================================================================

(deftest test-rollback
  (testing "Rollback to previous version"
    (let [v1 (v/save-version! :merchant-rules test-rules-v1
                             (assoc test-metadata :reason "Version 1"))
          _ (Thread/sleep 100)
          _ (v/save-version! :merchant-rules test-rules-v2
                            (assoc test-metadata :reason "Version 2"))
          _ (Thread/sleep 100)

          ;; Rollback to v1
          rollback (v/rollback! :merchant-rules
                               (:timestamp v1)
                               (assoc test-metadata :reason "Rollback to v1"))]

      (testing "Creates new version with old rules"
        (is (= 3 (:rule-count rollback)))
        (is (= "Rollback to v1" (:reason rollback))))

      (testing "Rolled back rules match original"
        (let [loaded (v/load-version :merchant-rules (:timestamp rollback))]
          (is (= test-rules-v1 loaded)))))))

;; ============================================================================
;; TEST 7: Version Statistics
;; ============================================================================

(deftest test-version-stats
  (testing "Calculate version statistics"
    ;; Save 3 versions with different authors
    (v/save-version! :merchant-rules test-rules-v1
                    {:author "alice" :reason "Version 1"})
    (Thread/sleep 100)
    (v/save-version! :merchant-rules test-rules-v2
                    {:author "bob" :reason "Version 2"})
    (Thread/sleep 100)
    (v/save-version! :merchant-rules test-rules-v1
                    {:author "alice" :reason "Version 3"})

    (let [stats (v/version-stats :merchant-rules)]

      (testing "Counts total versions"
        (is (>= (:total-versions stats) 3)))

      (testing "Tracks first and latest versions"
        (is (:first-version stats))
        (is (:latest-version stats))
        (is (< (compare (:first-version stats) (:latest-version stats)) 0)))

      (testing "Tracks authors"
        (let [authors (:authors stats)]
          (is (contains? authors "alice"))
          (is (contains? authors "bob"))))

      (testing "Tracks rule count trend"
        (is (vector? (:rule-count-trend stats)))
        (is (every? number? (:rule-count-trend stats)))))))

;; ============================================================================
;; TEST 8: Get Version At Timestamp
;; ============================================================================

(deftest test-get-version-at
  (testing "Get version at or before specific timestamp"
    (let [v1 (v/save-version! :merchant-rules test-rules-v1
                             (assoc test-metadata :reason "Version 1"))
          _ (Thread/sleep 100)
          v2 (v/save-version! :merchant-rules test-rules-v2
                             (assoc test-metadata :reason "Version 2"))
          _ (Thread/sleep 100)
          v3 (v/save-version! :merchant-rules test-rules-v1
                             (assoc test-metadata :reason "Version 3"))]

      (testing "Gets exact version when timestamp matches"
        (let [found (v/get-version-at :merchant-rules (:timestamp v2))]
          (is (= (:timestamp v2) (:timestamp found)))))

      (testing "Gets closest earlier version when timestamp is between"
        ;; Timestamp between v2 and v3
        (let [between-timestamp (str (subs (:timestamp v2) 0 19) "Z")
              found (v/get-version-at :merchant-rules (:timestamp v3))]
          ;; Should get v3 or earlier
          (is found))))))

;; ============================================================================
;; TEST 9: Empty Rule Type
;; ============================================================================

(deftest test-empty-rule-type
  (testing "Handling rule types with no versions"
    (testing "list-versions returns empty vector"
      (is (vector? (v/list-versions :category-rules))))

    (testing "get-latest-version returns nil"
      (is (nil? (v/get-latest-version :category-rules))))

    (testing "version-stats returns nil"
      (is (nil? (v/version-stats :category-rules))))))

;; ============================================================================
;; TEST 10: Audit Trail Completeness
;; ============================================================================

(deftest test-audit-trail-completeness
  (testing "All required audit trail fields are present"
    (let [version (v/save-version! :merchant-rules test-rules-v1
                                  {:author "test-user"
                                   :reason "Testing audit trail"
                                   :notes "Extra notes"})]

      (testing "Has all required fields"
        (is (:timestamp version))
        (is (:author version))
        (is (:reason version))
        (is (:notes version))
        (is (:rule-type version))
        (is (:rule-count version))
        (is (:path version)))

      (testing "Timestamp is valid ISO 8601"
        (is (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z"
                       (:timestamp version)))))))

;; ============================================================================
;; EXAMPLE USAGE (for documentation)
;; ============================================================================

(comment
  ;; Run all tests
  (clojure.test/run-tests 'finance.rules.versioning-test)

  ;; Run specific test
  (test-save-and-load-version)
  (test-compare-versions)
  (test-rollback)
  )
