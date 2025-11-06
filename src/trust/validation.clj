(ns trust.validation
  "Data validation primitives using spec and malli.

  Core concepts:
  - Schema as data (not code)
  - Validation returns detailed error explanations
  - Composable validators
  - Runtime validation with good error messages

  Provides:
  - spec-based validation
  - malli-based validation
  - Custom validators
  - Error reporting"
  (:require [clojure.spec.alpha :as s]
            [malli.core :as m]
            [malli.error :as me]))

;; ============================================================================
;; SPEC-BASED VALIDATION
;; ============================================================================

(defn validate-spec
  "Validate data against a spec (non-throwing).

  Use this when:
  - You want to handle errors gracefully
  - Building error reports or UI feedback
  - Batch validation where failures shouldn't stop processing

  Use validate-spec! when:
  - Invalid data is a bug (fast-fail)
  - In preconditions or assertions
  - When caller expects exceptions

  Args:
    spec - Spec keyword or spec definition
    data - Data to validate

  Returns:
    {:valid? true/false
     :data data
     :errors [...]}  ; only if invalid

  Example:
    (s/def ::positive-number (s/and number? pos?))
    (validate-spec ::positive-number 42)
    ; => {:valid? true :data 42}"
  [spec data]
  (if (s/valid? spec data)
    {:valid? true
     :data data}
    {:valid? false
     :data data
     :errors (s/explain-data spec data)}))

(defn validate-spec!
  "Validate data against a spec, throw exception if invalid.

  Example:
    (validate-spec! ::positive-number -5)
    ; => Throws ExceptionInfo with error details"
  [spec data]
  (if (s/valid? spec data)
    data
    (throw (ex-info "Validation failed"
                    {:spec spec
                     :data data
                     :errors (s/explain-data spec data)}))))

(defn conform-spec
  "Validate and conform data to a spec.

  Returns conformed value or ::s/invalid.

  Example:
    (s/def ::coords (s/tuple number? number?))
    (conform-spec ::coords [1 2])
    ; => [1 2]"
  [spec data]
  (s/conform spec data))

;; ============================================================================
;; MALLI-BASED VALIDATION (DEPRECATED - use Spec instead)
;; ============================================================================

(defn validate-malli
  "DEPRECATED: Validate data against a malli schema.

  ⚠️  Use validate-spec instead. Malli has been removed from dependencies.
  See Bug #7 fix: deps.edn now only uses Spec.alpha.

  Args:
    schema - Malli schema (vector format)
    data - Data to validate

  Returns:
    {:valid? true/false
     :data data
     :errors [...]}  ; only if invalid, human-readable

  Example:
    (validate-malli
      [:map
       [:name string?]
       [:age [:int {:min 0 :max 150}]]]
      {:name \"Darwin\" :age 30})
    ; => {:valid? true :data {...}}"
  [schema data]
  (let [validator (m/validator schema)
        explainer (m/explainer schema)]
    (if (validator data)
      {:valid? true
       :data data}
      {:valid? false
       :data data
       :errors (me/humanize (explainer data))})))

(defn validate-malli!
  "DEPRECATED: Validate data against a malli schema, throw if invalid.

  ⚠️  Use validate-spec! instead. Malli has been removed from dependencies.

  Example (deprecated):
    (validate-malli!
      [:map [:name string?]]
      {:name 123})
    ; => Throws ExceptionInfo"
  [schema data]
  (let [result (validate-malli schema data)]
    (if (:valid? result)
      data
      (throw (ex-info "Validation failed"
                      {:schema schema
                       :data data
                       :errors (:errors result)})))))

(defn coerce-malli
  "Validate and coerce data using malli.

  Attempts type coercion (e.g., string -> number).

  Example:
    (coerce-malli
      [:map [:age int?]]
      {:age \"42\"})
    ; => {:age 42}"
  [schema data]
  (let [decoder (m/decoder schema)]
    (decoder data)))

;; ============================================================================
;; CUSTOM VALIDATORS
;; ============================================================================

(defn validator
  "Create a custom validator function.

  Args:
    pred - Predicate function (data -> boolean)
    error-msg - Error message (can be string or function)

  Returns a validator function.

  Example:
    (def positive? (validator pos? \"Must be positive\"))
    (positive? 42)
    ; => {:valid? true :data 42}"
  [pred error-msg]
  (fn [data]
    (if (pred data)
      {:valid? true
       :data data}
      {:valid? false
       :data data
       :errors [(if (fn? error-msg)
                  (error-msg data)
                  error-msg)]})))

(defn combine-validators
  "Combine multiple validators (all must pass).

  Example:
    (def validate-amount
      (combine-validators
        [(validator number? \"Must be a number\")
         (validator pos? \"Must be positive\")
         (validator #(< % 1000000) \"Must be under 1M\")]))

    (validate-amount 42)
    ; => {:valid? true :data 42}"
  [validators]
  (fn [data]
    (reduce
      (fn [result validator]
        (if (:valid? result)
          (let [v-result (validator data)]
            (if (:valid? v-result)
              result
              v-result))
          result))
      {:valid? true :data data}
      validators)))

;; ============================================================================
;; FIELD-LEVEL VALIDATION
;; ============================================================================

(defn validate-fields
  "Validate multiple fields in a map.

  Args:
    data - Map to validate
    field-validators - Map of field-name -> validator

  Returns:
    {:valid? true/false
     :data data
     :field-errors {...}}  ; map of field -> errors

  Example:
    (validate-fields
      {:name \"Darwin\" :age -5}
      {:name (validator string? \"Must be string\")
       :age (validator pos? \"Must be positive\")})
    ; => {:valid? false
    ;     :data {...}
    ;     :field-errors {:age [\"Must be positive\"]}}"
  [data field-validators]
  (let [results (into {}
                      (for [[field validator] field-validators]
                        [field (validator (get data field))]))]
    (let [errors (into {}
                       (keep (fn [[field result]]
                               (when-not (:valid? result)
                                 [field (:errors result)]))
                             results))]
      (if (empty? errors)
        {:valid? true
         :data data}
        {:valid? false
         :data data
         :field-errors errors}))))

;; ============================================================================
;; VALIDATION RULES (Data-driven)
;; ============================================================================

(defn rule-validator
  "Create validator from declarative rules.

  Rules format:
  [{:field :amount
    :type :number
    :min 0
    :max 1000000
    :required true
    :error \"Amount must be between 0 and 1M\"}
   ...]

  Example:
    (def validator
      (rule-validator
        [{:field :amount :type :number :min 0 :required true}
         {:field :merchant :type :string :required true}]))

    (validator {:amount 42 :merchant \"Starbucks\"})"
  [rules]
  (fn [data]
    (let [errors
          (keep
            (fn [rule]
              (let [{:keys [field type required min max pattern error]} rule
                    value (get data field)]
                (cond
                  ;; Required check
                  (and required (nil? value))
                  [field [(or error (str "Field " field " is required"))]]

                  ;; Type checks (if value exists)
                  (and value (= type :number) (not (number? value)))
                  [field [(or error (str "Field " field " must be a number"))]]

                  (and value (= type :string) (not (string? value)))
                  [field [(or error (str "Field " field " must be a string"))]]

                  ;; Range checks
                  (and value (number? value) min (< value min))
                  [field [(or error (str "Field " field " must be >= " min))]]

                  (and value (number? value) max (> value max))
                  [field [(or error (str "Field " field " must be <= " max))]]

                  ;; Pattern check
                  (and value (string? value) pattern (not (re-matches pattern value)))
                  [field [(or error (str "Field " field " does not match pattern"))]]

                  :else nil)))
            rules)]
      (if (empty? errors)
        {:valid? true
         :data data}
        {:valid? false
         :data data
         :field-errors (into {} errors)}))))

;; ============================================================================
;; VALIDATION PIPELINES
;; ============================================================================

(defn validate-pipeline
  "Run data through a pipeline of validators.

  Stops at first validation failure.

  Example:
    (validate-pipeline data
      [check-required-fields
       check-types
       check-business-rules])"
  [data validators]
  (reduce
    (fn [result validator]
      (if (:valid? result)
        (validator (:data result))
        (reduced result)))
    {:valid? true :data data}
    validators))

;; ============================================================================
;; COMMON VALIDATORS
;; ============================================================================

(def not-nil?
  "Validator: Value must not be nil."
  (validator some? "Value must not be nil"))

(def not-empty?
  "Validator: Collection/string must not be empty."
  (validator (fn [x]
               (and (some? x)
                    (or (not (coll? x))
                        (not (empty? x)))))
             "Value must not be empty"))

(def positive-number?
  "Validator: Value must be a positive number."
  (validator (fn [x] (and (number? x) (pos? x)))
             "Value must be a positive number"))

(def non-negative-number?
  "Validator: Value must be a non-negative number."
  (validator (fn [x] (and (number? x) (>= x 0)))
             "Value must be a non-negative number"))

(defn in-range?
  "Validator: Number must be in range [min, max]."
  [min max]
  (validator (fn [x] (and (number? x) (<= min x max)))
             (fn [x] (format "Value must be between %s and %s" min max))))

(defn matches-pattern?
  "Validator: String must match regex pattern."
  [pattern]
  (validator (fn [x] (and (string? x) (re-matches pattern x)))
             (fn [x] (format "Value must match pattern %s" pattern))))

(defn one-of?
  "Validator: Value must be one of the allowed values."
  [allowed-values]
  (validator (fn [x] (contains? (set allowed-values) x))
             (fn [x] (format "Value must be one of %s" allowed-values))))

;; ============================================================================
;; ERROR REPORTING
;; ============================================================================

(defn format-errors
  "Format validation errors as human-readable string.

  Example:
    (format-errors {:field-errors {:age [\"Must be positive\"]
                                   :name [\"Required\"]}})
    ; => \"age: Must be positive\\nname: Required\""
  [validation-result]
  (when-not (:valid? validation-result)
    (if-let [field-errors (:field-errors validation-result)]
      ;; Field-level errors
      (->> field-errors
           (map (fn [[field errors]]
                  (str (name field) ": " (clojure.string/join ", " errors))))
           (clojure.string/join "\n"))
      ;; General errors
      (clojure.string/join "\n" (:errors validation-result)))))

(defn validation-summary
  "Get a summary of validation results.

  Returns:
    {:valid-count N
     :invalid-count M
     :total N+M
     :invalid-items [...]}

  Example:
    (validation-summary results)"
  [validation-results]
  (let [valid (filter :valid? validation-results)
        invalid (remove :valid? validation-results)]
    {:valid-count (count valid)
     :invalid-count (count invalid)
     :total (count validation-results)
     :invalid-items invalid}))

;; ============================================================================
;; EXAMPLE USAGE (for documentation)
;; ============================================================================

(comment
  ;; Spec-based validation
  (s/def ::amount (s/and number? pos?))
  (s/def ::merchant string?)
  (s/def ::transaction
    (s/keys :req-un [::amount ::merchant]))

  (validate-spec ::transaction
    {:amount 42 :merchant "Starbucks"})
  ; => {:valid? true :data {...}}

  (validate-spec ::transaction
    {:amount -5 :merchant "Starbucks"})
  ; => {:valid? false :errors {...}}

  ;; Malli-based validation
  (def TransactionSchema
    [:map
     [:amount [:double {:min 0}]]
     [:merchant :string]
     [:date [:re #"\d{4}-\d{2}-\d{2}"]]])

  (validate-malli TransactionSchema
    {:amount 42.0
     :merchant "Starbucks"
     :date "2024-03-20"})
  ; => {:valid? true :data {...}}

  ;; Custom validators
  (def positive?
    (validator pos? "Must be positive"))

  (positive? 42)
  ; => {:valid? true :data 42}

  (positive? -5)
  ; => {:valid? false :errors ["Must be positive"]}

  ;; Field-level validation
  (validate-fields
    {:amount -5 :merchant "Starbucks"}
    {:amount positive-number?
     :merchant not-empty?})
  ; => {:valid? false
  ;     :field-errors {:amount ["Value must be a positive number"]}}

  ;; Rule-based validation (data-driven)
  (def validate-transaction
    (rule-validator
      [{:field :amount :type :number :min 0 :required true}
       {:field :merchant :type :string :required true}
       {:field :date :type :string :pattern #"\d{4}-\d{2}-\d{2}" :required true}]))

  (validate-transaction
    {:amount 42 :merchant "Starbucks" :date "2024-03-20"})
  ; => {:valid? true :data {...}}

  ;; Validation pipeline
  (validate-pipeline data
    [check-required-fields
     check-types
     check-ranges
     check-business-rules])

  ;; Error formatting
  (format-errors validation-result)
  ; => "amount: Must be positive\nmerchant: Required"
  )
