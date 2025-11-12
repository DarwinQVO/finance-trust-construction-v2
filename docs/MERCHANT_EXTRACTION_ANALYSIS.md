# Análisis de Extracción de Merchants - Datos Reales

**Fuente:** Bank of America Statement PDF (April 10 - May 8, 2025)
**Account:** 3250 9372 5226
**Total Transacciones:** 18

---

## 📊 Clasificación por Tipo de Transacción

### ✅ Transacciones SIN Merchant (11 transacciones)

Estas transacciones **NO deben** tener merchant extraído:

#### 1. ACH Deposits (3 transacciones) - INGRESO
```
WISE US INC DES:Thera Pay ID:Thera Pay INDN:Eugenio Castro Garza CO ID:1453233521 CCD PMT INFO:From Bloom Financial Corp. Via WISE
HubSpot Inc DES:Coupa Pay ID:14949541 INDN:Eugenio Castro Garza CO ID:4272102091 PPD PMT INFO:NTE*OBI*Coupa Pay 286-31598 HubSpot Inc Tmate\
```

**Razón:** Son pagos ACH (depósitos directos), no compras a merchants.
**Merchant esperado:** `nil` o `""`
**Tipo:** `:income` / `:ach-deposit`

---

#### 2. Credit Card Payments (5 transacciones) - PAGO_TARJETA
```
BANK OF AMERICA CREDIT CARD Bill Payment
Bank of America Credit Card Bill Payment
APPLECARD GSBANK DES:PAYMENT ID:XXXXXXXXX INDN:Eugenio Castro Garza CO ID:9999999999 WEB
```

**Razón:** Son pagos de tarjetas de crédito (transfers entre cuentas propias).
**Merchant esperado:** `nil` o `""`
**Tipo:** `:credit-card-payment` / `:pago-tarjeta`

---

#### 3. Wire Transfers (5 transacciones) - TRASPASO
```
Wise Inc DES:WISE ID:TrnWise INDN:Eugenio Castro Garza CO ID:9453233521 WEB
WISE US INC DES:WISE ID:Shin-April INDN:Eugenio Castro Garza CO ID:1453233521 WEB
```

**Razón:** Son traspasos vía Wise (money transfers), no compras.
**Merchant esperado:** `nil` o `""`
**Tipo:** `:wire-transfer` / `:traspaso`

---

### ✅ Transacciones CON Merchant (4 transacciones)

Estas transacciones **SÍ necesitan** extracción de merchant:

#### 1. AFFIRM (Buy Now Pay Later)
```
Raw: AFFIRM INC DES:AFFIRM PAY ID:3191207 INDN:Eugenio C*Garza CO ID:0000317218 WEB
Amount: -$96.75
```

**Extraction Challenge:**
- Raw string tiene: "AFFIRM INC", "DES:AFFIRM PAY", metadata extra
- Merchant real: `AFFIRM`
- Category: `:finance` (payment installment)
- Tipo: `:expense`

**Esperado:**
```clojure
{:merchant-id :affirm
 :merchant-name "Affirm"
 :category :finance
 :confidence 0.95}
```

---

#### 2. SLACK (SaaS Subscription)
```
Raw: CHECKCARD 0501 SLACK T04HFBU1QF4 DUBLIN 74609055121100020118218 RECURRING
Amount: -$10.15
```

**Extraction Challenge:**
- Raw string tiene: "CHECKCARD 0501", "SLACK", location code, transaction ID, "RECURRING"
- Merchant real: `SLACK`
- Category: `:software` / `:subscriptions`
- Tipo: `:expense`
- Pattern: `RECURRING` indica subscription

**Esperado:**
```clojure
{:merchant-id :slack
 :merchant-name "Slack"
 :category :software-subscriptions
 :subscription? true
 :confidence 0.98}
```

---

#### 3. UBER ONE (Subscription - NOT a ride)
```
Raw: CHECKCARD 0502 UBER* ONE UBER.COM/MX/E 74048925122100001169091
Amount: -$3.57
```

**Extraction Challenge:**
- Raw string: "CHECKCARD 0502", "UBER* ONE", URL, transaction code
- Merchant real: `UBER ONE` (subscription service)
- **CRITICAL:** `UBER ONE` ≠ `UBER EATS` ≠ `UBER TRIP`
- Category: `:subscriptions` (NOT transportation, NOT food)
- Tipo: `:expense`

**Disambiguation Required:**
```clojure
;; UBER tiene 3+ entidades diferentes:
UBER* ONE     → :uber-one (subscriptions)     Category: :subscriptions
UBER* EATS    → :uber-eats (restaurants)      Category: :restaurants
UBER* TRIP    → :uber-rides (transportation)  Category: :transportation
UBER* FREIGHT → :uber-freight (logistics)     Category: :business-services
```

**Esperado:**
```clojure
{:merchant-id :uber-one
 :merchant-name "Uber One"
 :category :subscriptions
 :subscription? true
 :confidence 0.95}
```

---

#### 4. X CORP (Twitter Paid Features)
```
Raw: CHECKCARD 0507 X CORP. PAID FEATURES ABOUT.X.COM TX 24011345127100113701215 RECURRING
Amount: -$41.29
```

**Extraction Challenge:**
- Raw string: "CHECKCARD 0507", "X CORP. PAID FEATURES", URL, transaction code, "RECURRING"
- Merchant real: `X CORP` (formerly Twitter)
- Description extra: "PAID FEATURES" (context útil)
- Category: `:social-media` / `:subscriptions`
- Tipo: `:expense`

**Esperado:**
```clojure
{:merchant-id :x-corp
 :merchant-name "X Corp"
 :category :social-media-subscriptions
 :subscription? true
 :confidence 0.98}
```

---

## 🎯 Pipeline de 5 Etapas - Ejemplos Concretos

### Stage 1: Transaction Type Detection

**Input:** Raw transaction
**Output:** Transaction type + `merchant?` flag

```clojure
;; Ejemplo 1: ACH Deposit
{:description "WISE US INC DES:Thera Pay ID:Thera Pay..."
 :amount 2000.00}
→
{:type :ach-deposit
 :direction :income
 :merchant? false     ;; ❌ NO extraer merchant
 :confidence 0.98}

;; Ejemplo 2: Credit Card Payment
{:description "BANK OF AMERICA CREDIT CARD Bill Payment"
 :amount -843.62}
→
{:type :credit-card-payment
 :direction :transfer
 :merchant? false     ;; ❌ NO extraer merchant
 :confidence 0.99}

;; Ejemplo 3: Card Purchase
{:description "CHECKCARD 0501 SLACK T04HFBU1QF4 DUBLIN..."
 :amount -10.15}
→
{:type :card-purchase
 :direction :expense
 :merchant? true      ;; ✅ SÍ extraer merchant
 :confidence 0.95}
```

**Reglas (EDN):**
```clojure
[{:id :ach-deposit-pattern
  :pattern #"(?i)DES:(Thera|Coupa|ACH|DIRECT DEP)"
  :type :ach-deposit
  :merchant? false
  :priority 90}

 {:id :credit-card-payment
  :pattern #"(?i)CREDIT CARD.*Bill Payment"
  :type :credit-card-payment
  :merchant? false
  :priority 95}

 {:id :checkcard-purchase
  :pattern #"^CHECKCARD \d{4}"
  :type :card-purchase
  :merchant? true
  :priority 80}]
```

---

### Stage 2: Counterparty Detection

**Input:** Transaction with `merchant? true`
**Output:** Counterparty info (if marketplace)

```clojure
;; Ejemplo: AFFIRM (counterparty - payment processor)
{:description "AFFIRM INC DES:AFFIRM PAY ID:3191207..."
 :type :card-purchase}
→
{:counterparty? true
 :counterparty-id :affirm
 :counterparty-type :payment-processor
 :actual-merchant nil  ;; AFFIRM es el merchant final
 :confidence 0.90}

;; Ejemplo: DOORDASH (si existiera en este PDF)
{:description "DOORDASH*CHIPOTLE SAN FRANCISCO CA"
 :type :card-purchase}
→
{:counterparty? true
 :counterparty-id :doordash
 :counterparty-type :marketplace
 :actual-merchant "CHIPOTLE"  ;; Extraer después de *
 :confidence 0.92}
```

**Reglas (EDN):**
```clojure
[{:id :affirm-processor
  :pattern #"^AFFIRM INC"
  :counterparty :affirm
  :type :payment-processor
  :extract-merchant? false}  ;; Affirm ES el merchant

 {:id :doordash-marketplace
  :pattern #"^DOORDASH\*"
  :counterparty :doordash
  :type :marketplace
  :extract-after "*"}]  ;; Merchant está después de *
```

---

### Stage 3: NER Extraction (Limpiar Basura)

**Input:** Raw description
**Output:** Clean merchant string

```clojure
;; Ejemplo 1: SLACK
{:description "CHECKCARD 0501 SLACK T04HFBU1QF4 DUBLIN 74609055121100020118218 RECURRING"}
→
{:raw-merchant "SLACK T04HFBU1QF4 DUBLIN 74609055121100020118218 RECURRING"
 :clean-merchant "SLACK"
 :removed ["T04HFBU1QF4" "DUBLIN" "74609055121100020118218" "RECURRING"]
 :confidence 0.98}

;; Ejemplo 2: UBER ONE
{:description "CHECKCARD 0502 UBER* ONE UBER.COM/MX/E 74048925122100001169091"}
→
{:raw-merchant "UBER* ONE UBER.COM/MX/E 74048925122100001169091"
 :clean-merchant "UBER ONE"
 :removed ["UBER.COM/MX/E" "74048925122100001169091"]
 :confidence 0.95}

;; Ejemplo 3: X CORP
{:description "CHECKCARD 0507 X CORP. PAID FEATURES ABOUT.X.COM TX 24011345127100113701215 RECURRING"}
→
{:raw-merchant "X CORP. PAID FEATURES ABOUT.X.COM TX 24011345127100113701215 RECURRING"
 :clean-merchant "X CORP"
 :removed ["ABOUT.X.COM" "TX" "24011345127100113701215" "RECURRING"]
 :kept-context "PAID FEATURES"  ;; Útil para classification
 :confidence 0.92}
```

**Patterns de Limpieza (Regex):**
```clojure
[;; Remove transaction codes
 {:pattern #"\d{20,}"
  :reason "Transaction ID"}

 ;; Remove locations (city names)
 {:pattern #"\b(DUBLIN|AMSTERDAM|SAN FRANCISCO|MEXICO|TX|CA|NY)\b"
  :reason "Location"}

 ;; Remove URLs
 {:pattern #"\S+\.(COM|NET|ORG)/\S+"
  :reason "URL"}

 ;; Remove date codes
 {:pattern #"\d{8,10}"
  :reason "Date/timestamp"}

 ;; Keep these (context útil)
 {:pattern #"(RECURRING|PAID FEATURES|SUBSCRIPTION)"
  :action :keep-separate
  :reason "Transaction context"}]
```

---

### Stage 4: Merchant Disambiguation

**Input:** Clean merchant string
**Output:** Specific merchant entity ID

```clojure
;; Ejemplo crítico: UBER variants
{:clean-merchant "UBER ONE"
 :description "... UBER* ONE UBER.COM/MX/E ..."}
→
{:merchant-id :uber-one          ;; NOT :uber, NOT :uber-eats
 :merchant-name "Uber One"
 :category :subscriptions
 :disambiguation-reason "Keyword: ONE matches subscription service"
 :confidence 0.95}

;; Comparar con:
{:clean-merchant "UBER EATS"
 :description "... UBER *EATS MR TREUBLAAN 7 AMSTERDAM ..."}
→
{:merchant-id :uber-eats          ;; Diferente entidad
 :merchant-name "Uber Eats"
 :category :restaurants
 :disambiguation-reason "Keyword: EATS matches food delivery"
 :confidence 0.95}

;; Y también:
{:clean-merchant "UBER TRIP"
 :description "... UBER* TRIP RIO LERMA 232 ..."}
→
{:merchant-id :uber-rides         ;; Otra entidad más
 :merchant-name "Uber"
 :category :transportation
 :disambiguation-reason "Keyword: TRIP matches ride service"
 :confidence 0.95}
```

**Reglas de Disambiguación (EDN):**
```clojure
[{:id :uber-one
  :pattern #"UBER.*ONE"
  :merchant-id :uber-one
  :category :subscriptions
  :keywords ["ONE" "SUBSCRIPTION"]
  :priority 95}

 {:id :uber-eats
  :pattern #"UBER.*EATS"
  :merchant-id :uber-eats
  :category :restaurants
  :keywords ["EATS" "FOOD"]
  :priority 95}

 {:id :uber-rides
  :pattern #"UBER.*(TRIP|RIDE)"
  :merchant-id :uber-rides
  :category :transportation
  :keywords ["TRIP" "RIDE"]
  :priority 95}

 {:id :uber-generic
  :pattern #"^UBER$"
  :merchant-id :uber-rides  ;; Default a rides
  :category :transportation
  :priority 50}]  ;; Lower priority (default fallback)
```

---

### Stage 5: Entity Resolution

**Input:** Merchant ID + context
**Output:** Final entity with lifecycle state

```clojure
;; Ejemplo 1: SLACK (canonical - bien conocido)
{:merchant-id :slack
 :merchant-name "Slack"
 :category :software-subscriptions}
→
{:entity-id :slack
 :canonical-name "Slack"
 :state :canonical           ;; Alta confianza, verificado
 :category :software-subscriptions
 :aliases ["SLACK" "Slack Technologies"]
 :transaction-count 45       ;; Visto 45 veces
 :first-seen "2023-01-15"
 :last-seen "2025-05-02"
 :confidence 0.98}

;; Ejemplo 2: Nueva tienda local (provisional)
{:merchant-id :cafe-local-nuevo
 :merchant-name "CAFE LOCAL NUEVO"
 :category :restaurants}
→
{:entity-id :cafe-local-nuevo
 :canonical-name "Cafe Local Nuevo"
 :state :provisional         ;; Primera vez, baja confianza
 :category :restaurants
 :aliases ["CAFE LOCAL NUEVO"]
 :transaction-count 1        ;; Primera aparición
 :first-seen "2025-05-08"
 :needs-verification true
 :confidence 0.60}

;; Ejemplo 3: Auto-graduate después de 10 transacciones
{:entity-id :cafe-local-nuevo
 :transaction-count 12}      ;; Ahora tiene 12 txs
→
{:entity-id :cafe-local-nuevo
 :state :canonical           ;; Auto-promoted!
 :promotion-reason "Threshold reached (12 > 10 transactions)"
 :confidence 0.85}           ;; Upgraded de 0.60 → 0.85
```

**3-Tier Entity Lifecycle:**
```clojure
;; Tier 1: CANONICAL (verified, high confidence)
{:state :canonical
 :min-transactions 10
 :min-confidence 0.85
 :verified? true}

;; Tier 2: PROVISIONAL (new, auto-created)
{:state :provisional
 :min-transactions 1
 :min-confidence 0.60
 :verified? false
 :needs-review true}

;; Tier 3: MERGED (duplicate resolved)
{:state :merged
 :merged-into :slack        ;; Points to canonical
 :reason "Duplicate detected (Levenshtein distance < 2)"}
```

---

## 🔍 Casos Edge Críticos de Este PDF

### ❌ Caso 1: Service Fees NO son merchants
```
CHECKCARD 0502 UBER* ONE UBER.COM/MX/E 74048925122100001169091 INTERNATIONAL TRANSACTION FEE
Amount: -$0.11
```

**Problema:** Es un FEE del banco, NO una transacción a merchant.
**Solución Stage 1:** Detectar "INTERNATIONAL TRANSACTION FEE" → tipo `:bank-fee`, `merchant? false`

---

### ❌ Caso 2: WISE aparece en AMBOS deposits y withdrawals
```
WISE US INC DES:Thera Pay... → +$2,000.00 (DEPOSIT - income)
Wise Inc DES:WISE ID:TrnWise... → -$2,000.00 (WITHDRAWAL - transfer out)
```

**Problema:** Mismo "merchant" (WISE), pero diferentes tipos.
**Solución Stage 1:** Direction (+ vs -) + pattern detection → income vs transfer

---

### ❌ Caso 3: RECURRING puede ser engañoso
```
CHECKCARD 0501 SLACK... RECURRING → Es merchant (subscription)
CHECKCARD 0507 X CORP... RECURRING → Es merchant (subscription)
```

**Problema:** "RECURRING" NO significa "no merchant".
**Solución Stage 3:** `RECURRING` es metadata, mantener separada pero NO eliminar merchant.

---

## 📊 Resumen de Test Cases

**Total transacciones:** 18

| Tipo | Count | Merchant? | Ejemplo |
|------|-------|-----------|---------|
| ACH Deposits | 3 | ❌ NO | WISE US INC DES:Thera Pay |
| Credit Card Payments | 5 | ❌ NO | BANK OF AMERICA CREDIT CARD Bill Payment |
| Wire Transfers | 5 | ❌ NO | Wise Inc DES:WISE ID:TrnWise |
| Card Purchases | 4 | ✅ YES | CHECKCARD 0501 SLACK... |
| Bank Fees | 1 | ❌ NO | INTERNATIONAL TRANSACTION FEE |

**Merchant extraction needed:** 4/18 (22%)
**No merchant needed:** 14/18 (78%)

---

## ✅ Expected Output del Pipeline Completo

```clojure
[;; Transaction 1: ACH Deposit - NO merchant
 {:date "2025-04-15"
  :description "WISE US INC DES:Thera Pay..."
  :amount 2000.00
  :type :ach-deposit
  :direction :income
  :merchant-id nil              ;; ✅ Correctamente vacío
  :category :income-transfer
  :confidence 0.98}

 ;; Transaction 2: Card Purchase - SÍ merchant
 {:date "2025-05-02"
  :description "CHECKCARD 0501 SLACK T04HFBU1QF4..."
  :amount -10.15
  :type :card-purchase
  :direction :expense
  :merchant-id :slack           ;; ✅ Extraído correctamente
  :merchant-name "Slack"
  :category :software-subscriptions
  :subscription? true
  :confidence 0.98}

 ;; Transaction 3: Uber One - Disambiguado correctamente
 {:date "2025-05-02"
  :description "CHECKCARD 0502 UBER* ONE UBER.COM/MX/E..."
  :amount -3.57
  :type :card-purchase
  :direction :expense
  :merchant-id :uber-one        ;; ✅ NOT :uber-eats, NOT :uber-rides
  :merchant-name "Uber One"
  :category :subscriptions      ;; ✅ NOT restaurants, NOT transportation
  :subscription? true
  :confidence 0.95}

 ;; Transaction 4: Credit Card Payment - NO merchant
 {:date "2025-04-23"
  :description "BANK OF AMERICA CREDIT CARD Bill Payment"
  :amount -843.62
  :type :credit-card-payment
  :direction :transfer
  :merchant-id nil              ;; ✅ Correctamente vacío
  :category :credit-card-payment
  :confidence 0.99}]
```

---

## 🎯 Próximos Pasos

1. ✅ **Test subject creado** - 18 transacciones reales del PDF
2. ⏭️ **Implementar Stage 1** - Transaction Type Detection
3. ⏭️ **Implementar Stage 2** - Counterparty Detection
4. ⏭️ **Implementar Stage 3** - NER Extraction
5. ⏭️ **Implementar Stage 4** - Merchant Disambiguation
6. ⏭️ **Implementar Stage 5** - Entity Resolution
7. ⏭️ **Integración completa** - Pipeline end-to-end

---

**Última actualización:** 2025-11-10
**Test data:** `/Users/darwinborges/finance-clj/test-data/bofa_statement_raw.edn`
**Pipeline:** 5 stages, data-driven, Rich Hickey aligned
