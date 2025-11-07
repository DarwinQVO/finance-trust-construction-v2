(ns finance.rules.versioning
  "Rule versioning and audit trail system.

  Implements Badge 30.2 & 30.3:
  - Version tracking for rule files
  - Audit trail (who, when, why changed)
  - Rollback capability
  - Version comparison

  Version Storage:
    resources/rules/versions/{rule-type}-v{timestamp}.edn
    resources/rules/versions/manifest.edn

  Example:
    (save-version! :merchant-rules rules {:author \"darwin\" :reason \"Added new cafe\"})
    (load-version :merchant-rules \"2025-11-07T14:30:00Z\")
    (list-versions :merchant-rules)
    (rollback! :merchant-rules \"2025-11-07T14:00:00Z\")"
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.set :as set])
  (:import [java.time Instant ZoneId]
           [java.time.format DateTimeFormatter]
           [java.io File]))

;; ============================================================================
;; CONFIGURATION
;; ============================================================================

(def ^:private versions-dir "resources/rules/versions")
(def ^:private manifest-file "resources/rules/versions/manifest.edn")

(def ^:private rule-types
  "Supported rule types"
  #{:merchant-rules :deduplication-rules :category-rules :classification-rules})

;; ============================================================================
;; TIMESTAMP UTILITIES
;; ============================================================================

(defn- iso-timestamp
  "Generate ISO 8601 timestamp.

  Returns: \"2025-11-07T14:30:00Z\""
  []
  (-> (Instant/now)
      (.atZone (ZoneId/of "UTC"))
      (.format (DateTimeFormatter/ISO_INSTANT))))

(defn- timestamp->filename
  "Convert timestamp to safe filename.

  \"2025-11-07T14:30:00Z\" => \"2025-11-07T14-30-00Z\""
  [timestamp]
  (str/replace timestamp #":" "-"))

(defn- filename->timestamp
  "Convert filename back to timestamp.

  \"2025-11-07T14-30-00Z\" => \"2025-11-07T14:30:00Z\""
  [filename]
  (str/replace filename #"-" ":"))

;; ============================================================================
;; FILE OPERATIONS
;; ============================================================================

(defn- ensure-versions-dir!
  "Create versions directory if it doesn't exist."
  []
  (let [dir (io/file versions-dir)]
    (when-not (.exists dir)
      (.mkdirs dir))))

(defn- version-path
  "Get path for a versioned rule file.

  Args:
    rule-type - :merchant-rules, :deduplication-rules, etc.
    timestamp - ISO 8601 timestamp

  Returns: \"resources/rules/versions/merchant-rules-v2025-11-07T14-30-00Z.edn\""
  [rule-type timestamp]
  (let [safe-timestamp (timestamp->filename timestamp)
        filename (format "%s-v%s.edn" (name rule-type) safe-timestamp)]
    (str versions-dir "/" filename)))

(defn- load-manifest
  "Load version manifest.

  Returns manifest map or empty map if doesn't exist."
  []
  (let [file (io/file manifest-file)]
    (if (.exists file)
      ;; Use Clojure reader (supports regex), not EDN reader
      (read-string (slurp file))
      {:versions {}})))

(defn- save-manifest!
  "Save version manifest.

  Args:
    manifest - Manifest map"
  [manifest]
  (ensure-versions-dir!)
  (spit manifest-file (with-out-str (pp/pprint manifest))))

;; ============================================================================
;; VERSION OPERATIONS
;; ============================================================================

(defn save-version!
  "Save a new version of rules with metadata.

  Args:
    rule-type - :merchant-rules, :deduplication-rules, etc.
    rules - Vector of rule maps
    metadata - Map with:
               :author - Who made the change
               :reason - Why the change was made
               :notes - (optional) Additional notes

  Returns: Version metadata map

  Example:
    (save-version! :merchant-rules rules
      {:author \"darwin\"
       :reason \"Added new Starbucks location\"
       :notes \"Store #5678 opened this week\"})"
  [rule-type rules metadata]
  {:pre [(contains? rule-types rule-type)
         (vector? rules)
         (map? metadata)
         (:author metadata)
         (:reason metadata)]}

  (let [timestamp (iso-timestamp)
        path (version-path rule-type timestamp)

        ;; Create version record
        version-record {:rule-type rule-type
                        :timestamp timestamp
                        :author (:author metadata)
                        :reason (:reason metadata)
                        :notes (:notes metadata)
                        :rule-count (count rules)
                        :path path}

        ;; Update manifest
        manifest (load-manifest)
        updated-manifest (update-in manifest [:versions rule-type]
                                   (fnil conj [])
                                   version-record)]

    ;; Save rules to versioned file
    (ensure-versions-dir!)
    (spit path (with-out-str (pp/pprint rules)))

    ;; Save updated manifest
    (save-manifest! updated-manifest)

    ;; Return version record
    version-record))

(defn load-version
  "Load a specific version of rules.

  Args:
    rule-type - :merchant-rules, :deduplication-rules, etc.
    timestamp - ISO 8601 timestamp (or nil for latest)

  Returns: Vector of rules

  Example:
    (load-version :merchant-rules \"2025-11-07T14:30:00Z\")
    (load-version :merchant-rules nil)  ; Latest version"
  ([rule-type]
   (load-version rule-type nil))
  ([rule-type timestamp]
   {:pre [(contains? rule-types rule-type)]}

   (let [manifest (load-manifest)
         versions (get-in manifest [:versions rule-type])

         version (if timestamp
                  ;; Find specific version
                  (first (filter #(= (:timestamp %) timestamp) versions))
                  ;; Get latest version
                  (last versions))]

     (when version
       (let [file (io/file (:path version))]
         (when (.exists file)
           (read-string (slurp file))))))))

(defn list-versions
  "List all versions of a rule type.

  Args:
    rule-type - :merchant-rules, :deduplication-rules, etc.

  Returns: Vector of version metadata (sorted by timestamp, oldest first)

  Example:
    (list-versions :merchant-rules)
    ; => [{:rule-type :merchant-rules
    ;      :timestamp \"2025-11-07T14:00:00Z\"
    ;      :author \"darwin\"
    ;      :reason \"Initial rules\"
    ;      :rule-count 33}
    ;     {:rule-type :merchant-rules
    ;      :timestamp \"2025-11-07T14:30:00Z\"
    ;      :author \"darwin\"
    ;      :reason \"Added new cafe\"
    ;      :rule-count 34}]"
  [rule-type]
  {:pre [(contains? rule-types rule-type)]}

  (let [manifest (load-manifest)]
    (get-in manifest [:versions rule-type] [])))

(defn get-latest-version
  "Get metadata for latest version.

  Args:
    rule-type - :merchant-rules, :deduplication-rules, etc.

  Returns: Version metadata map or nil

  Example:
    (get-latest-version :merchant-rules)
    ; => {:rule-type :merchant-rules
    ;     :timestamp \"2025-11-07T14:30:00Z\"
    ;     :author \"darwin\"
    ;     :reason \"Added new cafe\"
    ;     :rule-count 34}"
  [rule-type]
  {:pre [(contains? rule-types rule-type)]}

  (last (list-versions rule-type)))

(defn get-version-at
  "Get version metadata at or before a specific timestamp.

  Args:
    rule-type - :merchant-rules, :deduplication-rules, etc.
    timestamp - ISO 8601 timestamp

  Returns: Version metadata map or nil

  Example:
    (get-version-at :merchant-rules \"2025-11-07T14:15:00Z\")
    ; Returns version saved at 14:00:00 (closest before 14:15:00)"
  [rule-type timestamp]
  {:pre [(contains? rule-types rule-type)
         (string? timestamp)]}

  (->> (list-versions rule-type)
       (filter #(<= (compare (:timestamp %) timestamp) 0))
       last))

;; ============================================================================
;; COMPARISON
;; ============================================================================

(defn compare-versions
  "Compare two versions and show differences.

  Args:
    rule-type - :merchant-rules, :deduplication-rules, etc.
    timestamp1 - First version timestamp
    timestamp2 - Second version timestamp

  Returns: Map with:
           {:added [...] - Rules in v2 but not v1
            :removed [...] - Rules in v1 but not v2
            :modified [...] - Rules changed between v1 and v2
            :unchanged [...] - Rules same in both}

  Example:
    (compare-versions :merchant-rules
      \"2025-11-07T14:00:00Z\"
      \"2025-11-07T14:30:00Z\")"
  [rule-type timestamp1 timestamp2]
  {:pre [(contains? rule-types rule-type)
         (string? timestamp1)
         (string? timestamp2)]}

  (let [rules1 (load-version rule-type timestamp1)
        rules2 (load-version rule-type timestamp2)

        ;; Index by :id
        rules1-by-id (into {} (map (juxt :id identity) rules1))
        rules2-by-id (into {} (map (juxt :id identity) rules2))

        ids1 (set (keys rules1-by-id))
        ids2 (set (keys rules2-by-id))

        ;; Compute differences
        added-ids (set/difference ids2 ids1)
        removed-ids (set/difference ids1 ids2)
        common-ids (set/intersection ids1 ids2)

        ;; Find modified rules
        modified (for [id common-ids
                      :let [r1 (get rules1-by-id id)
                            r2 (get rules2-by-id id)]
                      :when (not= r1 r2)]
                  {:id id
                   :before r1
                   :after r2})

        unchanged (for [id common-ids
                       :let [r1 (get rules1-by-id id)
                             r2 (get rules2-by-id id)]
                       :when (= r1 r2)]
                   id)]

    {:added (mapv rules2-by-id added-ids)
     :removed (mapv rules1-by-id removed-ids)
     :modified modified
     :unchanged unchanged}))

;; ============================================================================
;; ROLLBACK
;; ============================================================================

(defn rollback!
  "Rollback rules to a previous version.

  Creates a new version with rules from the specified timestamp.
  This preserves audit trail (doesn't delete newer versions).

  Args:
    rule-type - :merchant-rules, :deduplication-rules, etc.
    timestamp - ISO 8601 timestamp to rollback to
    metadata - Map with :author, :reason

  Returns: New version metadata

  Example:
    (rollback! :merchant-rules
      \"2025-11-07T14:00:00Z\"
      {:author \"darwin\"
       :reason \"Reverting bad rule changes\"})"
  [rule-type timestamp metadata]
  {:pre [(contains? rule-types rule-type)
         (string? timestamp)
         (map? metadata)
         (:author metadata)
         (:reason metadata)]}

  (let [rules (load-version rule-type timestamp)
        rollback-metadata (assoc metadata
                            :notes (format "Rolled back to version %s" timestamp))]

    (when-not rules
      (throw (ex-info "Version not found"
                      {:rule-type rule-type
                       :timestamp timestamp})))

    (save-version! rule-type rules rollback-metadata)))

;; ============================================================================
;; STATISTICS
;; ============================================================================

(defn version-stats
  "Get statistics for all versions of a rule type.

  Args:
    rule-type - :merchant-rules, :deduplication-rules, etc.

  Returns: Map with stats

  Example:
    (version-stats :merchant-rules)
    ; => {:total-versions 5
    ;     :first-version \"2025-11-01T10:00:00Z\"
    ;     :latest-version \"2025-11-07T14:30:00Z\"
    ;     :total-changes 12
    ;     :authors #{\"darwin\" \"eugenio\"}
    ;     :rule-count-trend [30 32 33 34 34]}"
  [rule-type]
  {:pre [(contains? rule-types rule-type)]}

  (let [versions (list-versions rule-type)]
    (when (seq versions)
      {:total-versions (count versions)
       :first-version (:timestamp (first versions))
       :latest-version (:timestamp (last versions))
       :total-changes (count versions)
       :authors (set (map :author versions))
       :rule-count-trend (mapv :rule-count versions)})))

;; ============================================================================
;; INITIALIZATION
;; ============================================================================

(defn init-versioning!
  "Initialize versioning system by creating initial versions of existing rules.

  Args:
    metadata - Map with :author, :reason

  Example:
    (init-versioning!
      {:author \"system\"
       :reason \"Initial versioning setup\"})"
  [metadata]
  {:pre [(map? metadata)
         (:author metadata)
         (:reason metadata)]}

  (ensure-versions-dir!)

  ;; Save current rules as v1.0
  (let [results (atom [])]

    ;; Merchant rules
    (when-let [rules (try
                      (-> "rules/merchant-rules.edn"
                          io/resource
                          slurp
                          read-string)
                      (catch Exception _ nil))]
      (swap! results conj
             (save-version! :merchant-rules rules
                           (assoc metadata :notes "Merchant classification rules"))))

    ;; Deduplication rules
    (when-let [rules (try
                      (-> "rules/deduplication-rules.edn"
                          io/resource
                          slurp
                          read-string)
                      (catch Exception _ nil))]
      (swap! results conj
             (save-version! :deduplication-rules rules
                           (assoc metadata :notes "Deduplication detection rules"))))

    @results))

;; ============================================================================
;; EXAMPLE USAGE (for documentation)
;; ============================================================================

(comment
  ;; Initialize versioning system
  (init-versioning!
    {:author "darwin"
     :reason "Initial versioning setup"})

  ;; Save a new version
  (def rules (-> "rules/merchant-rules.edn"
                 io/resource
                 slurp
                 read-string))

  (save-version! :merchant-rules
                 (conj rules {:id :new-cafe
                             :pattern "NEW CAFE"
                             :merchant :new-cafe
                             :category :restaurants})
                 {:author "darwin"
                  :reason "Added new cafe location"
                  :notes "Store opened this week"})

  ;; List versions
  (list-versions :merchant-rules)

  ;; Load specific version
  (load-version :merchant-rules "2025-11-07T14:00:00Z")

  ;; Load latest version
  (load-version :merchant-rules)

  ;; Get latest version metadata
  (get-latest-version :merchant-rules)

  ;; Compare versions
  (compare-versions :merchant-rules
    "2025-11-07T14:00:00Z"
    "2025-11-07T14:30:00Z")

  ;; Rollback
  (rollback! :merchant-rules
    "2025-11-07T14:00:00Z"
    {:author "darwin"
     :reason "Reverting bad changes"})

  ;; Stats
  (version-stats :merchant-rules)
  )
