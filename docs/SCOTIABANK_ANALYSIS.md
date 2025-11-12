# Análisis Scotiabank México - Casos Nuevos vs BofA

**Fuente:** 3 PDFs Scotiabank (Julio, Agosto, Septiembre 2025)
**Cuenta:** 25604570372 (Eugenio Castro Garza)
**Total transacciones analizadas:** ~80 transacciones

---

## 🆕 Casos NUEVOS No Vistos en BofA

### 1. **Payment Aggregators** (CRÍTICO - Mexico specific)

#### CLIP (Mexican payment aggregator)
```
Raw: CLIPMX AGREGADOR 00000000101008685717 CLIP MX REST HANAICHI REF. 0013732041 AUT. 742785 RFC BLI 120726UF6
Amount: -$2,236.00
```

**Problema:**
- `CLIP MX` es el aggregator (como Square/Stripe)
- `REST HANAICHI` es el merchant REAL (restaurant)
- Necesita extracción **DESPUÉS** de "CLIP MX"

**Stage 2 (Counterparty Detection):**
```clojure
{:counterparty :clip
 :counterparty-type :payment-aggregator
 :actual-merchant "REST HANAICHI"
 :extract-after "CLIP MX"}
```

---

#### ZETTLE (by PayPal - Mexican market)
```
Raw: ZETTLE RESTS 00000000223957061759 ZTL PASIONPORLOSHELADO REF. 0013732041 AUT. 504844 RFC OPM 150323DI1
Amount: -$220.00
```

**Problema:**
- `ZETTLE RESTS` → Aggregator
- `PASIONPORLOSHELADO` → Merchant real (ice cream shop)
- Pattern: "ZTL" prefix

**Stage 2:**
```clojure
{:counterparty :zettle
 :actual-merchant "PASIONPORLOSHELADO"}
```

---

#### PAYU (Latin America payment processor)
```
Raw: PAYU GOOGLE CLOUDS CARG RECUR. 00000000000047418850 PAYU GOOGLE CLOUD M REF. 0013732041 AUT. 437902 RFC GCM 221031837
Amount: -$4.92
```

**Problema:**
- `PAYU` es processor
- `GOOGLE CLOUD` es merchant real
- Pattern: "PAYU" prefix

**CRÍTICO:** En este caso, ¿PAYU es counterparty o Google Cloud es merchant directo?
→ Decisión: Google Cloud es merchant, PAYU es payment method (como Stripe)

---

### 2. **RFC (Tax ID) - Mexico Specific**

Todos los merchants mexicanos tienen RFC (Registro Federal de Contribuyentes):

```
OXXO → RFC CCO 8605231N4
Farmacia Paris → RFC PIF 880519GH0
Chedraui → RFC CFC 110121742
SAT (Tax authority) → RFC SAT8410245V8
AT&T México → RFC CNM 980114PI2
```

**Uso en Pipeline:**
- RFC puede ayudar a identificar merchants
- RFC = identifier único (como EIN en USA)
- Útil para entity resolution (canonical names)

**Stage 5 (Entity Resolution):**
```clojure
{:merchant-id :oxxo
 :canonical-name "OXXO"
 :rfc "CCO 8605231N4"  ;; Unique identifier
 :aliases ["OXXO" "OXXO TAKH CUN" "OXXO SANDIA"]}
```

---

### 3. **Domiciliaciones (Automatic Payments)**

Pattern único de México:

```
03 POR COBRANZA DOMICILIADA 926249688 RFC/CURP: SAT8410245V8 IVA: 433.08
```

**Tipo:** `:automatic-payment` / `:domiciliacion`
**Problema:** ¿Es merchant o es bill payment (como credit card)?

**Caso 1: SAT (Tax authority)**
```
RFC/CURP: SAT8410245V8
→ Government tax payment
→ Merchant: `nil` (es un tax payment, no merchant)
```

**Caso 2: AT&T (Telecom)**
```
MI ATT A APP PS 1160533319 RFC CNM 980114PI2
→ Telecom bill payment
→ Merchant: `AT&T` (SÍ es merchant - recurring service)
```

**Stage 1 Decision:**
```clojure
;; SAT (tax)
{:type :tax-payment
 :merchant? false}

;; AT&T (service)
{:type :recurring-service-payment
 :merchant? true
 :merchant-id :att-mexico}
```

---

### 4. **Reversales (REV. prefix)**

Pattern de refund/cancellation:

```
Original: STR AGREGADOR CARG RECUR. ... -$919.60
Reversal: REV.STR AGREGADOR CARG RECUR. ... +$919.60
```

**Problema:**
- 2 transacciones (charge + reversal)
- Net effect = $0.00
- ¿Cómo manejar en entity resolution?

**Stage 1:**
```clojure
{:type :reversal
 :original-transaction-id "522310646921"  ;; Link to original
 :merchant-id :stripe  ;; Same merchant
 :reversal? true}
```

**Reconciliation:**
- Charge + Reversal = Net $0
- Merchant metrics: Don't count reversal as new purchase

---

### 5. **Multi-Currency con Exchange Rate**

```
OPENAI CHATGPT SUBSCR CARG RE
MONTO ORIGEN 200.00 USD
USD T/C 18.7840
MONTO MXN: 3756.80  (implícito en amount)
Amount: -$3,775.58
```

**Problema:**
- Original: 200 USD
- Exchange rate: 18.7840 MXN/USD
- Final MXN: 200 × 18.7840 = 3,756.80
- Pero amount = 3,775.58 (¿diferencia = fees?)

**Stage 3 (NER Extraction):**
```clojure
{:raw-merchant "OPENAI CHATGPT SUBSCR CARG RE MONTO ORIGEN 200.00 USD USD T/C 18.7840"
 :clean-merchant "OPENAI CHATGPT"
 :original-currency "USD"
 :original-amount 200.00
 :exchange-rate 18.7840
 :final-amount-mxn 3775.58
 :fee-amount 18.78}  ;; Difference
```

---

### 6. **Multiple Transactions Same Merchant Same Day**

```
15-SEP-25: CIRCUS PARK BLVB KUKUL -$150.00 AUT. 689767
15-SEP-25: CIRCUS PARK BLVB KUKUL -$50.00  AUT. 711869
15-SEP-25: CIRCUS PARK BLVB KUKUL -$100.00 AUT. 729875
```

**Problema:**
- 3 transacciones al mismo merchant
- Mismo día
- Diferentes authorization codes (AUT)
- ¿Son 3 compras separadas o 1 compra dividida?

**Stage 4 (Disambiguation):**
```clojure
;; Option 1: Keep separate (default)
[{:merchant-id :circus-park :amount -150.00 :auth "689767"}
 {:merchant-id :circus-park :amount -50.00  :auth "711869"}
 {:merchant-id :circus-park :amount -100.00 :auth "729875"}]

;; Option 2: Group as single visit (optional)
{:merchant-id :circus-park
 :total-amount -300.00
 :sub-transactions 3
 :auth-codes ["689767" "711869" "729875"]}
```

**Recomendación:** Keep separate (cada authorization = transacción única)

---

### 7. **Validation Charges ($1.00 + Reversal)**

```
STR VALIDATION 00000000161007670454 STR DELPHINUS WEB PASE -$1.00
REV.STR VALIDATION 00000000161007670454 STR DELPHINUS WEB PASE +$1.00
```

**Patrón:** Stripe validation
- Charge $1.00 (verify card)
- Immediate reversal +$1.00
- Net = $0.00

**Stage 1:**
```clojure
{:type :validation-charge
 :merchant-id :stripe
 :validation? true
 :expect-reversal true}
```

**Reconciliation:**
- Don't count validation charges in spending metrics
- Flag as "validation" not "purchase"

---

### 8. **SPEI Transfers (Mexican ACH equivalent)**

#### Pattern 1: SPEI IN (Deposits)
```
TRANSF INTERBANCARIA SPEI /S 250617011906042876I CAUICH BORGES DARWIN MANUEL
```

**Type:** `:spei-deposit` / `:income`
**Merchant:** `nil` (person-to-person transfer)

#### Pattern 2: SPEI OUT (Withdrawals)
```
SWEB TRANSF.INTERB SPEI BANAMEX TRANSFERENCIA A DIANA /53228561
```

**Type:** `:spei-transfer-out` / `:transfer`
**Merchant:** `nil` (bank transfer to person)

#### Pattern 3: International Wire (Wise via SPEI)
```
TRANSF INTERBANCARIA SPEI /WISE PAYMENT 16 1394345045 WISE PAYMENTS LIMITED
```

**Type:** `:international-wire` / `:income`
**Merchant:** `nil` (Wise is payment service, not merchant)

---

### 9. **Subscription Patterns - Mexican Context**

```
GOOGLE YOUTUBEPREMIUM CARG RE → Recurring charge
GOOGLE ONE GOOGLE ONE CARG RE → Recurring charge
OPENAI CHATGPT SUBSCR CARG RE → Subscription recurring
```

**Pattern:** "CARG RE" = Cargo Recurrente (Recurring Charge)

**Stage 1:**
```clojure
{:pattern #"CARG RE"
 :subscription? true
 :recurring true}
```

---

## 📊 Transaction Type Distribution (Scotiabank)

**Total:** ~80 transacciones analizadas

| Tipo | Count | % | Merchant? |
|------|-------|---|-----------|
| SPEI Transfers (IN/OUT) | 25 | 31% | ❌ NO |
| Domiciliaciones (automatic) | 10 | 13% | Mixed |
| Card Purchases | 35 | 44% | ✅ YES |
| Reversals | 8 | 10% | Special |
| Bank Fees | 0 | 0% | ❌ NO |
| Validations ($1) | 2 | 2% | Special |

**Merchant extraction needed:** 35/80 (44%) - más que BofA (22%)!

---

## 🔄 Comparison: BofA vs Scotiabank

| Feature | BofA (USA) | Scotiabank (MX) |
|---------|------------|-----------------|
| Transfer type | ACH | SPEI |
| Tax ID | EIN (optional) | RFC (mandatory) |
| Aggregators | Rare | Common (CLIP, ZETTLE) |
| Reversals | Rare | Common (REV. prefix) |
| Multi-currency | Common | With exchange rate info |
| Automatic payments | "Bill Payment" | "Domiciliación" |
| Subscription pattern | "RECURRING" | "CARG RE" |

---

## 🎯 Pipeline Adjustments Needed

### Stage 1: Transaction Type Detection

**New patterns to add:**
```clojure
;; SPEI (Mexican ACH)
{:pattern #"TRANSF INTERBANCARIA SPEI"
 :type :spei-transfer
 :merchant? false}

;; SWEB (Online bank transfer)
{:pattern #"SWEB TRANSF.INTERB SPEI"
 :type :spei-transfer-out
 :merchant? false}

;; Domiciliación
{:pattern #"POR COBRANZA DOMICILIADA"
 :type :automatic-payment
 :merchant? true  ;; Depends on RFC (SAT = false, AT&T = true)}

;; Reversal
{:pattern #"^REV\."
 :type :reversal
 :merchant? true  ;; Link to original}

;; Validation
{:pattern #"VALIDATION"
 :type :validation-charge
 :merchant? true  ;; But special handling}
```

---

### Stage 2: Counterparty Detection

**New counterparties:**
```clojure
;; CLIP (Mexican aggregator)
{:pattern #"CLIPMX AGREGADOR"
 :counterparty :clip
 :type :payment-aggregator
 :extract-after "CLIP MX"}

;; ZETTLE (PayPal Mexican)
{:pattern #"ZETTLE RESTS"
 :counterparty :zettle
 :type :payment-aggregator
 :extract-after "ZTL"}

;; PAYU (Latin America)
{:pattern #"PAYU"
 :counterparty :payu
 :type :payment-processor
 :extract-after "PAYU"}

;; STRIPE (also in Mexico)
{:pattern #"STR AGREGADOR"
 :counterparty :stripe
 :type :payment-aggregator
 :extract-after "STRIPE"}
```

---

### Stage 3: NER Extraction

**New patterns:**
```clojure
;; Remove RFC (Mexican tax ID)
{:pattern #"RFC [A-Z0-9 ]{10,20}"
 :reason "Mexican tax ID"}

;; Remove authorization codes
{:pattern #"AUT\. \d+"
 :reason "Authorization code"}

;; Remove reference numbers
{:pattern #"REF\. \d+"
 :reason "Reference number"}

;; Remove "CARG RE" (recurring)
{:pattern #"CARG RE"
 :action :flag-as-recurring
 :reason "Recurring charge indicator"}

;; Extract exchange rate info
{:pattern #"MONTO ORIGEN [\d\.]+ [A-Z]{3}.*T/C [\d\.]+"
 :action :extract-currency-data
 :reason "Multi-currency transaction"}
```

---

### Stage 4: Disambiguation

**New cases:**
```clojure
;; OXXO (convenience store chain)
{:pattern #"OXXO"
 :merchant-id :oxxo
 :category :convenience-stores
 :rfc "CCO 8605231N4"}

;; Farmacia Paris (pharmacy chain)
{:pattern #"FARM(?:ACIA)? PARIS"
 :merchant-id :farmacia-paris
 :category :pharmacies
 :rfc "PIF 880519GH0"}

;; Chedraui (grocery chain)
{:pattern #"F AHORRO CNHU|CHEDRAUI"
 :merchant-id :chedraui
 :category :groceries
 :rfc "CFC 110121742"}

;; CIRCUS (entertainment)
{:pattern #"CIRCUS PARK"
 :merchant-id :circus-park-cancun
 :category :entertainment
 :rfc "CPA 1201121Z3"}
```

---

### Stage 5: Entity Resolution

**New attributes:**
```clojure
{:entity-id :oxxo
 :canonical-name "OXXO"
 :rfc "CCO 8605231N4"  ;; NEW: Mexican tax ID
 :country "MX"          ;; NEW: Country
 :category :convenience-stores
 :aliases ["OXXO" "OXXO TAKH CUN" "OXXO SANDIA"]
 :transaction-count 45
 :confidence 0.98}
```

---

## 🆕 New Test Cases from Scotiabank

### Test Case 1: CLIP Aggregator
```clojure
{:description "CLIPMX AGREGADOR CLIP MX REST HANAICHI REF. 0013732041 AUT. 742785"
 :expected {:counterparty :clip
            :merchant-id :restaurante-hanaichi
            :category :restaurants}}
```

### Test Case 2: Multi-Currency
```clojure
{:description "OPENAI CHATGPT SUBSCR CARG RE MONTO ORIGEN 200.00 USD USD T/C 18.7840"
 :expected {:merchant-id :openai
            :original-currency "USD"
            :original-amount 200.00
            :exchange-rate 18.7840
            :category :software-subscriptions}}
```

### Test Case 3: Validation + Reversal
```clojure
[{:description "STR VALIDATION ... -$1.00"
  :expected {:type :validation-charge
             :merchant-id :stripe
             :validation? true}}
 {:description "REV.STR VALIDATION ... +$1.00"
  :expected {:type :reversal
             :links-to-previous true
             :merchant-id :stripe}}]
```

### Test Case 4: SPEI Transfer
```clojure
{:description "SWEB TRANSF.INTERB SPEI BANAMEX TRANSFERENCIA A DIANA /53228561"
 :expected {:type :spei-transfer-out
            :merchant? false
            :merchant-id nil
            :category :transfer}}
```

### Test Case 5: Domiciliación - SAT vs AT&T
```clojure
;; SAT (tax)
{:description "03 POR COBRANZA DOMICILIADA RFC/CURP: SAT8410245V8"
 :expected {:type :tax-payment
            :merchant? false
            :merchant-id nil}}

;; AT&T (service)
{:description "MI ATT A APP PS RFC CNM 980114PI2"
 :expected {:type :recurring-service-payment
            :merchant? true
            :merchant-id :att-mexico
            :category :telecom}}
```

---

## 📈 Coverage Analysis

**BofA alone:** 22% transactions need merchant extraction
**Scotiabank:** 44% transactions need merchant extraction
**Combined coverage:** Covers USA + Mexico patterns

**New patterns found:**
1. ✅ Payment aggregators (CLIP, ZETTLE, PAYU)
2. ✅ RFC (Mexican tax IDs)
3. ✅ SPEI (Mexican transfers)
4. ✅ Domiciliaciones (automatic payments)
5. ✅ Reversals (REV. prefix)
6. ✅ Multi-currency with exchange rates
7. ✅ Validation charges
8. ✅ Multiple transactions same merchant

---

## ✅ Next Steps

1. **Update Stage 1 rules** - Add SPEI, domiciliación, reversal patterns
2. **Update Stage 2 rules** - Add CLIP, ZETTLE, PAYU counterparties
3. **Update Stage 3 rules** - Add RFC removal, exchange rate extraction
4. **Update Stage 4 rules** - Add Mexican merchant disambiguation
5. **Update Stage 5** - Add RFC attribute to entities

---

**Total test cases:** 18 (BofA) + 35 (Scotiabank) = **53 transacciones**
**Pipeline coverage:** USA + Mexico patterns ✅
**Ready for implementation:** YES 🚀

---

**Última actualización:** 2025-11-10
**Test data:** `/Users/darwinborges/finance-clj/test-data/scotiabank_raw_extraction.edn`
**BofA data:** `/Users/darwinborges/finance-clj/test-data/bofa_statement_raw.edn`
