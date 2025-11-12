(ns scripts.enrich-top-merchants
  "Enriches top merchants with MCC codes, budget categories, and tax hints"
  (:require [finance.entity-registry :as registry]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

;; ============================================================================
;; Enrichment Data - Top Merchants with Full Context
;; ============================================================================

(def enrichment-data
  "Enrichment data for top merchants based on existing registry"
  [{:merchant-id "seguros-atlas"
    :canonical-name "Seguros Atlas"
    :category "insurance"
    :entity-type "business"
    :mcc 6300
    :mcc-description "Insurance Sales, Underwriting, and Premiums"
    :budget-category "Insurance"
    :budget-subcategory "Health & Life"
    :typical-flow-type "GASTO"
    :tax-hints {:business-deductible true
                :personal-deductible true
                :sat-category "Gastos Médicos"
                :irs-deductible "Medical expenses (subject to AGI threshold)"
                :notes "Health insurance premiums are deductible in Mexico and US"}}

   {:merchant-id "google"
    :canonical-name "GOOGLE"
    :category "utilities"
    :entity-type "business"
    :mcc 5734
    :mcc-description "Computer Software Stores"
    :budget-category "Technology"
    :budget-subcategory "Software & Services"
    :typical-flow-type "GASTO"
    :tax-hints {:business-deductible true
                :personal-deductible false
                :sat-category "Gastos de Software"
                :irs-deductible "Business software expense"
                :notes "Google Workspace and other SaaS subscriptions"}}

   {:merchant-id "oxxo"
    :canonical-name "OXXO"
    :category "groceries"
    :entity-type "business"
    :mcc 5411
    :mcc-description "Grocery Stores, Supermarkets"
    :budget-category "Living"
    :budget-subcategory "Groceries"
    :typical-flow-type "GASTO"
    :tax-hints {:business-deductible false
                :personal-deductible false
                :notes "Personal groceries - not tax deductible"}}

   {:merchant-id "walmart"
    :canonical-name "WALMART"
    :category "groceries"
    :entity-type "business"
    :mcc 5411
    :mcc-description "Grocery Stores, Supermarkets"
    :budget-category "Living"
    :budget-subcategory "Groceries"
    :typical-flow-type "GASTO"
    :tax-hints {:business-deductible false
                :personal-deductible false
                :notes "Personal groceries - not tax deductible"}}

   {:merchant-id "att"
    :canonical-name "AT&T"
    :category "utilities"
    :entity-type "business"
    :mcc 4814
    :mcc-description "Telecommunication Services"
    :budget-category "Technology"
    :budget-subcategory "Internet & Phone"
    :typical-flow-type "GASTO"
    :tax-hints {:business-deductible true
                :personal-deductible false
                :sat-category "Gastos de Telecomunicaciones"
                :irs-deductible "Business phone/internet expense"
                :notes "Mobile and internet services for business use"}}

   {:merchant-id "bp-derma-colombiana"
    :canonical-name "BP DERMA COLOMBIANA"
    :category "healthcare"
    :entity-type "business"
    :mcc 8011
    :mcc-description "Doctors and Physicians (not elsewhere classified)"
    :budget-category "Healthcare"
    :budget-subcategory "Medical Services"
    :typical-flow-type "GASTO"
    :tax-hints {:business-deductible false
                :personal-deductible true
                :sat-category "Gastos Médicos"
                :irs-deductible "Medical expenses (subject to AGI threshold)"
                :notes "Dermatology and medical services"}}

   {:merchant-id "farmacia-del-ahorro"
    :canonical-name "FARMACIA DEL AHORRO"
    :category "healthcare"
    :entity-type "business"
    :mcc 5912
    :mcc-description "Drug Stores and Pharmacies"
    :budget-category "Healthcare"
    :budget-subcategory "Pharmacy"
    :typical-flow-type "GASTO"
    :tax-hints {:business-deductible false
                :personal-deductible true
                :sat-category "Gastos Médicos"
                :irs-deductible "Medical expenses (subject to AGI threshold)"
                :notes "Prescription and OTC medications"}}

   {:merchant-id "farmacia-paris"
    :canonical-name "FARMACIA PARIS"
    :category "healthcare"
    :entity-type "business"
    :mcc 5912
    :mcc-description "Drug Stores and Pharmacies"
    :budget-category "Healthcare"
    :budget-subcategory "Pharmacy"
    :typical-flow-type "GASTO"
    :tax-hints {:business-deductible false
                :personal-deductible true
                :sat-category "Gastos Médicos"
                :irs-deductible "Medical expenses (subject to AGI threshold)"
                :notes "Prescription and OTC medications"}}

   {:merchant-id "hanaichi"
    :canonical-name "HANAICHI"
    :category "restaurants"
    :entity-type "business"
    :mcc 5812
    :mcc-description "Eating Places, Restaurants"
    :budget-category "Living"
    :budget-subcategory "Dining"
    :typical-flow-type "GASTO"
    :tax-hints {:business-deductible true
                :personal-deductible false
                :condition "business meals only"
                :sat-category "Gastos de Alimentación (Negocios)"
                :irs-deductible "50% deductible for business meals"
                :notes "Restaurant meals - only business meals are deductible"}}

   {:merchant-id "tere-cazola"
    :canonical-name "TERE CAZOLA"
    :category "restaurants"
    :entity-type "business"
    :mcc 5812
    :mcc-description "Eating Places, Restaurants"
    :budget-category "Living"
    :budget-subcategory "Dining"
    :typical-flow-type "GASTO"
    :tax-hints {:business-deductible true
                :personal-deductible false
                :condition "business meals only"
                :sat-category "Gastos de Alimentación (Negocios)"
                :irs-deductible "50% deductible for business meals"
                :notes "Restaurant meals - only business meals are deductible"}}

   {:merchant-id "xj-puerto-cancun"
    :canonical-name "XJ PUERTO CANCUN"
    :category "restaurants"
    :entity-type "business"
    :mcc 5812
    :mcc-description "Eating Places, Restaurants"
    :budget-category "Living"
    :budget-subcategory "Dining"
    :typical-flow-type "GASTO"
    :tax-hints {:business-deductible true
                :personal-deductible false
                :condition "business meals only"
                :sat-category "Gastos de Alimentación (Negocios)"
                :irs-deductible "50% deductible for business meals"
                :notes "Restaurant meals - only business meals are deductible"}}

   {:merchant-id "delphinus"
    :canonical-name "DELPHINUS"
    :category "entertainment"
    :entity-type "business"
    :mcc 7911
    :mcc-description "Dance Halls, Studios and Schools"
    :budget-category "Entertainment"
    :budget-subcategory "Recreation"
    :typical-flow-type "GASTO"
    :tax-hints {:business-deductible false
                :personal-deductible false
                :notes "Entertainment/recreation - not tax deductible"}}

   {:merchant-id "acuario-cancun"
    :canonical-name "ACUARIO CANCUN"
    :category "entertainment"
    :entity-type "business"
    :mcc 7911
    :mcc-description "Dance Halls, Studios and Schools"
    :budget-category "Entertainment"
    :budget-subcategory "Recreation"
    :typical-flow-type "GASTO"
    :tax-hints {:business-deductible false
                :personal-deductible false
                :notes "Entertainment/recreation - not tax deductible"}}

   {:merchant-id "solution-cancun-storage"
    :canonical-name "SOLUTION CANCUN STORAGE"
    :category "home"
    :entity-type "business"
    :mcc 6537
    :mcc-description "Storage Services"
    :budget-category "Home"
    :budget-subcategory "Household"
    :typical-flow-type "GASTO"
    :tax-hints {:business-deductible false
                :personal-deductible false
                :notes "Personal storage - not tax deductible"}}

   {:merchant-id "pasion-por-los-helados"
    :canonical-name "PASION POR LOS HELADOS"
    :category "groceries"
    :entity-type "business"
    :mcc 5411
    :mcc-description "Grocery Stores, Supermarkets"
    :budget-category "Living"
    :budget-subcategory "Groceries"
    :typical-flow-type "GASTO"
    :tax-hints {:business-deductible false
                :personal-deductible false
                :notes "Personal groceries - not tax deductible"}}])

;; ============================================================================
;; Enrichment Functions
;; ============================================================================

(defn enrich-merchant!
  "Enriches a single merchant with MCC, budget, and tax data"
  [merchant-data]
  (let [merchant-id (:merchant-id merchant-data)
        result (registry/update-merchant merchant-id merchant-data)]
    (if (:success result)
      (println (format "✅ Enriched: %s (MCC: %s, Budget: %s)"
                      (:canonical-name merchant-data)
                      (:mcc merchant-data)
                      (:budget-category merchant-data)))
      (println (format "❌ Failed to enrich %s: %s"
                      merchant-id
                      (:error result "Unknown error"))))))

(defn enrich-merchants!
  "Enriches all top merchants with MCC, budget, and tax data"
  []
  (println "\n🔧 FASE 2: Enriching top merchants with MCC, budget, and tax data...")
  (println "=" (apply str (repeat 70 "=")))
  (println)

  (doseq [merchant enrichment-data]
    (enrich-merchant! merchant))

  (println)
  (println "=" (apply str (repeat 70 "=")))
  (println "✅ Enrichment complete!")
  (println (format "   Total merchants enriched: %d" (count enrichment-data)))
  (println))

;; ============================================================================
;; Main Entry Point
;; ============================================================================

(defn -main
  "Main entry point for enrichment script"
  [& args]
  (enrich-merchants!))

;; Run if executed directly
(when (some #{"enrich"} *command-line-args*)
  (-main))
