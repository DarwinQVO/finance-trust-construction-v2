# 🎯 Test Subject - PDF Real Extraído

**Fuente:** Bank of America Statement (April 10 - May 8, 2025)
**PDF:** `/Users/darwinborges/finance app/docs/literate-programming/examples/eStmt_20250508 1.pdf`
**Cuenta:** 3250 9372 5226
**Propietario:** Eugenio Castro Garza

---

## 📝 Resumen Ejecutivo

**Total transacciones:** 18
- ❌ **14 transacciones SIN merchant** (78%) - ACH deposits, credit card payments, wire transfers
- ✅ **4 transacciones CON merchant** (22%) - Card purchases que necesitan extracción

**Esto confirma tu punto:** La mayoría de transacciones NO tienen merchant!

---

## 🔍 Las 4 Transacciones que Necesitan Extracción

### 1. AFFIRM (Buy Now Pay Later)
```
Raw: AFFIRM INC DES:AFFIRM PAY ID:3191207 INDN:Eugenio C*Garza CO ID:0000317218 WEB
```
**Problema:** Metadata extra ("DES:", "ID:", códigos)
**Esperado:** `merchant-id: :affirm, category: :finance`

---

### 2. SLACK (SaaS Subscription)
```
Raw: CHECKCARD 0501 SLACK T04HFBU1QF4 DUBLIN 74609055121100020118218 RECURRING
```
**Problema:** Transaction codes, location, "RECURRING"
**Esperado:** `merchant-id: :slack, category: :software-subscriptions`

---

### 3. UBER ONE (Subscription - NOT ride/food)
```
Raw: CHECKCARD 0502 UBER* ONE UBER.COM/MX/E 74048925122100001169091
```
**Problema Crítico:** Disambiguation needed!
- `UBER ONE` → Subscription (:subscriptions)
- `UBER EATS` → Food delivery (:restaurants)
- `UBER TRIP` → Rides (:transportation)

**Esperado:** `merchant-id: :uber-one, category: :subscriptions` ✅

---

### 4. X CORP (Twitter Paid)
```
Raw: CHECKCARD 0507 X CORP. PAID FEATURES ABOUT.X.COM TX 24011345127100113701215 RECURRING
```
**Problema:** URL, transaction codes, "RECURRING"
**Esperado:** `merchant-id: :x-corp, category: :social-media-subscriptions`

---

## ❌ Las 14 Transacciones que NO Tienen Merchant

### ACH Deposits (3) - Merchant debe ser `nil`
```
✓ WISE US INC DES:Thera Pay ID:Thera Pay... → INGRESO (income)
✓ HubSpot Inc DES:Coupa Pay ID:14949541... → INGRESO (income)
```

### Credit Card Payments (5) - Merchant debe ser `nil`
```
✓ BANK OF AMERICA CREDIT CARD Bill Payment → PAGO_TARJETA
✓ APPLECARD GSBANK DES:PAYMENT... → PAGO_TARJETA
```

### Wire Transfers (5) - Merchant debe ser `nil`
```
✓ Wise Inc DES:WISE ID:TrnWise... → TRASPASO
✓ WISE US INC DES:WISE ID:Shin-April... → TRASPASO
```

### Bank Fees (1) - Merchant debe ser `nil`
```
✓ INTERNATIONAL TRANSACTION FEE → BANK FEE
```

---

## 🎯 Pipeline de 5 Etapas - Qué Resuelve Cada Una

### Stage 1: Transaction Type Detection
**Input:** Raw transaction
**Output:** Type + `merchant?` boolean

**Problemas que resuelve:**
- ❌ ACH deposits incorrectamente marcados como merchants
- ❌ Credit card payments incorrectamente procesados
- ❌ Wire transfers confundidos con purchases

**Ejemplo:**
```clojure
"BANK OF AMERICA CREDIT CARD Bill Payment"
→ {:type :credit-card-payment, :merchant? false} ✅
```

---

### Stage 2: Counterparty Detection
**Input:** Transaction with `merchant? true`
**Output:** Counterparty info (marketplace/processor)

**Problemas que resuelve:**
- ❌ "DOORDASH*CHIPOTLE" → merchant incorrectamente = "DOORDASH" (debería ser "CHIPOTLE")
- ❌ Payment processors (Affirm, Klarna) vs actual merchants

**Ejemplo:**
```clojure
"DOORDASH*CHIPOTLE..."
→ {:counterparty :doordash, :actual-merchant "CHIPOTLE"} ✅
```

---

### Stage 3: NER Extraction (Limpieza)
**Input:** Raw description
**Output:** Clean merchant string

**Problemas que resuelve:**
- ❌ "SLACK T04HFBU1QF4 DUBLIN 74609..." → demasiada basura
- ❌ "UBER* ONE UBER.COM/MX/E 74048..." → URLs y códigos
- ❌ Locations, transaction IDs, metadata extra

**Ejemplo:**
```clojure
"CHECKCARD 0501 SLACK T04HFBU1QF4 DUBLIN 74609055121100020118218 RECURRING"
→ {:clean-merchant "SLACK", :context "RECURRING"} ✅
```

---

### Stage 4: Merchant Disambiguation
**Input:** Clean merchant string
**Output:** Specific entity ID

**Problemas que resuelve (EL MÁS CRÍTICO):**
- ❌ "UBER ONE" vs "UBER EATS" vs "UBER TRIP" → 3 entidades diferentes
- ❌ "STARBUCKS" vs "STARBUCKS RESERVE" → variantes
- ❌ Mismo merchant, diferentes servicios

**Ejemplo:**
```clojure
"UBER ONE"
→ {:merchant-id :uber-one, :category :subscriptions} ✅ NOT :uber-eats!
```

---

### Stage 5: Entity Resolution
**Input:** Merchant ID + history
**Output:** Final entity with lifecycle state

**Problemas que resuelve:**
- ❌ Nuevos merchants no reconocidos → auto-create provisional
- ❌ Typos ("STARBUCSK") → merge to canonical ("STARBUCKS")
- ❌ Confidence decay → upgrade after 10+ transactions

**Ejemplo:**
```clojure
{:merchant-id :slack, :transaction-count 45}
→ {:state :canonical, :confidence 0.98} ✅ Auto-promoted!
```

---

## 📊 Output Esperado del Pipeline

### Transaction 1: ACH Deposit ❌ NO merchant
```clojure
{:date "2025-04-15"
 :description "WISE US INC DES:Thera Pay..."
 :amount 2000.00
 :type :ach-deposit
 :merchant-id nil              ;; ✅ Correctamente vacío
 :category :income-transfer}
```

### Transaction 2: Card Purchase ✅ Merchant extraído
```clojure
{:date "2025-05-02"
 :description "CHECKCARD 0501 SLACK T04HFBU1QF4..."
 :amount -10.15
 :type :card-purchase
 :merchant-id :slack           ;; ✅ Limpiamente extraído
 :merchant-name "Slack"
 :category :software-subscriptions
 :subscription? true}
```

### Transaction 3: Uber One ✅ Disambiguado correctamente
```clojure
{:date "2025-05-02"
 :description "CHECKCARD 0502 UBER* ONE UBER.COM/MX/E..."
 :amount -3.57
 :type :card-purchase
 :merchant-id :uber-one        ;; ✅ NOT :uber-eats, NOT :uber-rides!
 :category :subscriptions      ;; ✅ NOT restaurants, NOT transportation!
 :subscription? true}
```

### Transaction 4: Credit Card Payment ❌ NO merchant
```clojure
{:date "2025-04-23"
 :description "BANK OF AMERICA CREDIT CARD Bill Payment"
 :amount -843.62
 :type :credit-card-payment
 :merchant-id nil              ;; ✅ Correctamente vacío
 :category :credit-card-payment}
```

---

## ✅ Validación del Approach

**Tu preocupación original:** "Merchants vienen sucios, con direcciones y basura"
→ **Confirmado:** 4/4 card purchases tienen metadata extra

**Tu preocupación:** "ACH deposits NO son merchants"
→ **Confirmado:** 3 ACH deposits, 0 merchants expected

**Tu preocupación:** "UBER vs UBER EATS son diferentes"
→ **Confirmado:** Disambiguation crítica en Stage 4

**Tu preocupación:** "Credit card payments NO son merchants"
→ **Confirmado:** 5 credit card payments, 0 merchants expected

---

## 🚀 Próximos Pasos

**Opción 1:** Implementar Stage 1 (Transaction Type Detection)
- Crear namespace `finance.merchant-extraction.type-detector`
- Rules en EDN: `resources/rules/transaction-types.edn`
- Tests con las 18 transacciones

**Opción 2:** Ver todos los stages diseñados antes de implementar
- Diseñar las 5 etapas completas (architecture)
- Luego implementar una por una

**Opción 3:** Implementar pipeline completo end-to-end
- Las 5 etapas de una vez
- Validar con las 18 transacciones

**¿Cuál prefieres?**

---

**Archivos creados:**
- ✅ `/Users/darwinborges/finance-clj/test-data/bofa_statement_raw.edn` - 18 transacciones
- ✅ `/Users/darwinborges/finance-clj/docs/MERCHANT_EXTRACTION_ANALYSIS.md` - Análisis completo
- ✅ Este resumen

**Test subject validado:** ✅ READY para implementación
