(require '[clojure.edn :as edn])

(def rules (edn/read-string (slurp "resources/rules/merchant-rules.edn.new")))
(println (format "Successfully loaded %d merchant rules" (count rules)))

;; Show first rule as verification
(println "\nFirst rule:")
(clojure.pprint/pprint (first rules))
