# Merchant Extraction Pipeline - Data Structures

**Badge:** ME-1
**Status:** In Progress
**Filosofía:** Data-driven design (Rich Hickey style)

---

## 🎯 Principio Central

> "It is better to have 100 functions operate on one data structure than 10 functions on 10 data structures."
> — Alan Perlis (quoted by Rich Hickey)

**Implicación:** Todas las stages operan sobre MAPAS, transformándolos incrementalmente.

---

## 📊 Data Flow Overview

```
Raw Transaction (from PDF)
    ↓
Stage 1: Type Detection
    ↓
Typed Transaction
    ↓
Stage 2: Counterparty Detection
    ↓
Counterparty Transaction
    ↓
Stage 3: NER Extraction
    ↓
Clean Transaction
    ↓
Stage 4: Disambiguation
    ↓
Disambiguated Transaction
    ↓
Stage 5: Entity Resolution
    ↓
Resolved Transaction (final)
```

**Key insight:** Cada stage AGREGA información, nunca remueve.

---

## 1️⃣ Raw Transaction (Input)

**Fuente:** PDF extraction (tal como viene)

```clojure
{:date "17-JUL-25"
 :description "SWEB TRANSF.INTERB SPEI 00000000000000170725 INBURSA TRANSFERENCIA A STORAGE /00550705 09:25:49 2025071740044B36L0000387125143 FECHA OPERACION: 17 JUL SOLUTION CANCUN STORAGE /036691500331752023"
 :retiro 4756.00
 :deposit nil
 :saldo 4571.73}
```

**Campos obligatorios:**
- `:date` - String (formato "DD-MMM-YY")
- `:description` - String (descripción completa, sin procesar)
- `:retiro` - Number (nil si no aplica)
- `:deposit` - Number (nil si no aplica)
- `:saldo` - Number (saldo después de transacción)

**Nota:** Este es el formato CRUDO. No asumimos nada sobre su contenido.

---

## 2️⃣ Typed Transaction (Output Stage 1)

**Stage 1 agrega:** `:type`, `:direction`, `:merchant?`

```clojure
{;; Original data (preserved)
 :date "17-JUL-25"
 :description "SWEB TRANSF.INTERB SPEI ..."
 :retiro 4756.00
 :deposit nil
 :saldo 4571.73

 ;; NEW: Type detection
 :type :spei-transfer-out
 :direction :transfer
 :merchant? false
 :confidence 0.98

 ;; Metadata
 :stage-1 {:detected-by :pattern-match
           :matched-rule :sweb-spei-transfer
           :timestamp "2025-11-10T10:30:00Z"}}
```

**Nuevos campos:**
- `:type` - Keyword (`:spei-transfer-out`, `:card-purchase`, `:domiciliacion`, etc.)
- `:direction` - Keyword (`:income`, `:expense`, `:transfer`)
- `:merchant?` - Boolean (¿necesita extracción de merchant?)
- `:confidence` - Number 0.0-1.0
- `:stage-1` - Map (metadata del stage)

**Decision point:** Si `:merchant?` es `false`, pipeline TERMINA aquí. Stages 2-5 solo procesan si `:merchant?` es `true`.

---

## 3️⃣ Counterparty Transaction (Output Stage 2)

**Stage 2 agrega:** `:counterparty-info` (si aplica)

```clojure
{;; Original + Stage 1 (preserved)
 :date "11-AGO-25"
 :description "CLIPMX AGREGADOR 00000000101008685717 CLIP MX REST HANAICHI REF. 0013732041 AUT. 742785 RFC BLI 120726UF6"
 :retiro 2236.00
 :type :card-purchase
 :direction :expense
 :merchant? true

 ;; NEW: Counterparty detection
 :counterparty-info {:detected? true
                     :counterparty-id :clip
                     :counterparty-type :payment-aggregator
                     :actual-merchant-hint "REST HANAICHI"
                     :extract-after "CLIP MX"}
 :confidence 0.95

 ;; Metadata
 :stage-2 {:detected-by :pattern-match
           :matched-rule :clip-aggregator
           :timestamp "2025-11-10T10:31:00Z"}}
```

**Nuevos campos:**
- `:counterparty-info` - Map (puede ser nil si no hay counterparty)
  - `:detected?` - Boolean
  - `:counterparty-id` - Keyword (`:clip`, `:zettle`, `:stripe`, etc.)
  - `:counterparty-type` - Keyword (`:payment-aggregator`, `:marketplace`, etc.)
  - `:actual-merchant-hint` - String (merchant después del counterparty)
  - `:extract-after` - String (pattern para saber dónde extraer)

**Si no hay counterparty:**
```clojure
:counterparty-info {:detected? false}
```

---

## 4️⃣ Clean Transaction (Output Stage 3)

**Stage 3 agrega:** `:clean-merchant`, `:removed-noise`

```clojure
{;; Original + Stage 1 + Stage 2 (preserved)
 :date "11-AGO-25"
 :description "CLIPMX AGREGADOR ... CLIP MX REST HANAICHI REF. 0013732041 AUT. 742785 RFC BLI 120726UF6"
 :retiro 2236.00
 :type :card-purchase
 :counterparty-info {:detected? true
                     :counterparty-id :clip
                     :actual-merchant-hint "REST HANAICHI"}

 ;; NEW: NER extraction (cleaned)
 :clean-merchant "REST HANAICHI"
 :removed-noise ["REF. 0013732041"
                 "AUT. 742785"
                 "RFC BLI 120726UF6"
                 "00000000101008685717"]
 :kept-context ["CLIP MX"]  ;; Context útil mantenido por separado
 :confidence 0.92

 ;; Metadata
 :stage-3 {:extraction-method :post-counterparty
           :noise-patterns-applied 4
           :timestamp "2025-11-10T10:32:00Z"}}
```

**Nuevos campos:**
- `:clean-merchant` - String (merchant limpio, sin noise)
- `:removed-noise` - Vector of Strings (qué se removió)
- `:kept-context` - Vector of Strings (metadata útil mantenido separado)

**Ejemplo sin counterparty:**
```clojure
{:description "GOOGLE YOUTUBEPREMIUM CARG RE 00000000517719716538 MONTO ORIGEN 159.00 REF. 0013732041 AUT. 305884"
 :clean-merchant "GOOGLE YOUTUBEPREMIUM"
 :removed-noise ["CARG RE"
                 "00000000517719716538"
                 "MONTO ORIGEN 159.00"
                 "REF. 0013732041"
                 "AUT. 305884"]
 :kept-context ["CARG RE"]}  ;; Indica que es subscription
```

---

## 5️⃣ Disambiguated Transaction (Output Stage 4)

**Stage 4 agrega:** `:merchant-id`, `:merchant-name`, `:category`

```clojure
{;; Original + Stages 1-3 (preserved)
 :clean-merchant "REST HANAICHI"
 :counterparty-info {:counterparty-id :clip}

 ;; NEW: Disambiguation
 :merchant-id :restaurante-hanaichi
 :merchant-name "Restaurant Hanaichi"
 :category :restaurants
 :disambiguation-reason "Pattern match: 'REST' + name indicates restaurant"
 :confidence 0.90

 ;; Metadata
 :stage-4 {:matched-rule :restaurant-pattern
           :alternatives-considered []
           :timestamp "2025-11-10T10:33:00Z"}}
```

**Nuevos campos:**
- `:merchant-id` - Keyword (identifier único, canonico)
- `:merchant-name` - String (nombre limpio para display)
- `:category` - Keyword (`:restaurants`, `:groceries`, `:pharmacies`, etc.)
- `:disambiguation-reason` - String (por qué se eligió este merchant)

**Casos especiales:**

**Google variants:**
```clojure
;; Google YouTube Premium
{:clean-merchant "GOOGLE YOUTUBEPREMIUM"
 :merchant-id :google-youtube-premium
 :category :entertainment-subscriptions}

;; Google One
{:clean-merchant "GOOGLE ONE"
 :merchant-id :google-one
 :category :cloud-storage-subscriptions}

;; Google Cloud
{:clean-merchant "GOOGLE CLOUD"
 :merchant-id :google-cloud
 :category :cloud-services}
```

**Key:** Diferentes `:merchant-id` aunque todos sean "Google"

---

## 6️⃣ Resolved Transaction (Output Stage 5 - FINAL)

**Stage 5 agrega:** `:entity`, `:entity-state`

```clojure
{;; Original + Stages 1-4 (preserved)
 :merchant-id :restaurante-hanaichi
 :merchant-name "Restaurant Hanaichi"
 :category :restaurants

 ;; NEW: Entity resolution
 :entity {:entity-id :restaurante-hanaichi
          :canonical-name "Restaurant Hanaichi"
          :state :provisional  ;; or :canonical, :merged
          :rfc "BLI 120726UF6"  ;; Mexican tax ID
          :country "MX"
          :aliases ["REST HANAICHI" "HANAICHI" "Restaurant Hanaichi"]
          :transaction-count 1  ;; First time seeing this merchant
          :first-seen "2025-08-11"
          :last-seen "2025-08-11"
          :confidence 0.85}

 :entity-state :provisional
 :needs-verification true

 ;; Metadata
 :stage-5 {:resolution-method :auto-create-provisional
           :existing-entity? false
           :timestamp "2025-11-10T10:34:00Z"}}
```

**Nuevos campos:**
- `:entity` - Map (entidad resuelta con toda su info)
  - `:entity-id` - Keyword (mismo que `:merchant-id` normalmente)
  - `:canonical-name` - String (nombre oficial)
  - `:state` - Keyword (`:canonical`, `:provisional`, `:merged`)
  - `:rfc` - String (Mexican tax ID, si aplica)
  - `:country` - String ("MX", "US", etc.)
  - `:aliases` - Vector of Strings (todas las variantes vistas)
  - `:transaction-count` - Number (cuántas veces hemos visto esta entidad)
  - `:first-seen` - Date string
  - `:last-seen` - Date string
  - `:confidence` - Number 0.0-1.0
- `:entity-state` - Keyword (shortcut al estado)
- `:needs-verification` - Boolean (si necesita review manual)

**Entity Lifecycle States:**

```clojure
;; PROVISIONAL (new, low confidence)
{:state :provisional
 :transaction-count 1
 :confidence 0.60
 :needs-verification true}

;; CANONICAL (verified, high confidence)
{:state :canonical
 :transaction-count 45
 :confidence 0.98
 :needs-verification false
 :verified-by "manual"  ;; or "auto-graduated"
 :verified-at "2025-08-20"}

;; MERGED (duplicate, points to canonical)
{:state :merged
 :merged-into :oxxo  ;; Points to canonical entity
 :merge-reason "Typo detected: OXOO -> OXXO"
 :merged-at "2025-09-01"}
```

---

## 🔄 Special Cases

### Case 1: No Merchant (Transfer)

```clojure
{:date "30-JUN-25"
 :description "SWEB TRANSF.INTERB SPEI BANAMEX TRANSFERENCIA A DIANA ..."
 :retiro 20000.00
 :type :spei-transfer-out
 :direction :transfer
 :merchant? false
 :confidence 0.99

 ;; Pipeline stops here - no stages 2-5
 :merchant-id nil
 :entity nil}
```

---

### Case 2: Reversal (Links to Original)

```clojure
{:date "11-AGO-25"
 :description "REV.STR AGREGADOR CARG RECUR. 00000000522310646921 STRIPE DELPHINUS WEB"
 :deposit 919.60
 :type :reversal
 :direction :income
 :merchant? true

 ;; NEW: Reversal-specific data
 :reversal-info {:is-reversal true
                 :original-transaction-ref "522310646921"
                 :original-amount -919.60
                 :net-effect 0.00}

 :merchant-id :stripe
 :entity {...}}
```

---

### Case 3: Multi-Currency

```clojure
{:date "19-AGO-25"
 :description "OPENAI CHATGPT SUBSCR CARG RE MONTO ORIGEN 200.00 USD USD T/C 18.7840"
 :retiro 3775.58
 :type :card-purchase
 :merchant? true

 ;; NEW: Currency conversion data
 :currency-info {:original-currency "USD"
                 :original-amount 200.00
                 :exchange-rate 18.7840
                 :expected-mxn 3756.80
                 :actual-mxn 3775.58
                 :fee-amount 18.78}

 :merchant-id :openai
 :category :software-subscriptions}
```

---

## 📋 Complete Example (End-to-End)

**Input:**
```clojure
{:date "11-AGO-25"
 :description "CLIPMX AGREGADOR 00000000101008685717 CLIP MX REST HANAICHI REF. 0013732041 AUT. 742785 RFC BLI 120726UF6"
 :retiro 2236.00
 :deposit nil
 :saldo 13489.58}
```

**Output (after all 5 stages):**
```clojure
{;; Original (preserved)
 :date "11-AGO-25"
 :description "CLIPMX AGREGADOR 00000000101008685717 CLIP MX REST HANAICHI REF. 0013732041 AUT. 742785 RFC BLI 120726UF6"
 :retiro 2236.00
 :deposit nil
 :saldo 13489.58

 ;; Stage 1: Type detection
 :type :card-purchase
 :direction :expense
 :merchant? true
 :confidence 0.95

 ;; Stage 2: Counterparty
 :counterparty-info {:detected? true
                     :counterparty-id :clip
                     :counterparty-type :payment-aggregator
                     :actual-merchant-hint "REST HANAICHI"}

 ;; Stage 3: NER extraction
 :clean-merchant "REST HANAICHI"
 :removed-noise ["REF. 0013732041" "AUT. 742785" "RFC BLI 120726UF6" "00000000101008685717"]
 :kept-context ["CLIP MX"]

 ;; Stage 4: Disambiguation
 :merchant-id :restaurante-hanaichi
 :merchant-name "Restaurant Hanaichi"
 :category :restaurants
 :disambiguation-reason "Pattern 'REST' indicates restaurant"

 ;; Stage 5: Entity resolution
 :entity {:entity-id :restaurante-hanaichi
          :canonical-name "Restaurant Hanaichi"
          :state :provisional
          :rfc "BLI 120726UF6"
          :country "MX"
          :aliases ["REST HANAICHI"]
          :transaction-count 1
          :first-seen "2025-08-11"
          :last-seen "2025-08-11"
          :confidence 0.85}
 :entity-state :provisional
 :needs-verification true

 ;; Pipeline metadata
 :pipeline {:stages-completed 5
            :total-time-ms 45
            :final-confidence 0.85}}
```

---

## ✅ Badge ME-1 Success Criteria

**DONE when:**
1. ✅ All 6 data structures documented
2. ✅ Field types specified
3. ✅ Special cases covered (reversal, multi-currency, no merchant)
4. ✅ Complete end-to-end example provided
5. ✅ Incremental transformation principle clear (each stage adds, never removes)

---

**Status:** ✅ COMPLETE
**Next:** Badge ME-2 (Design Protocols)
