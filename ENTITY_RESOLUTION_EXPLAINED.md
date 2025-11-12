# 🎯 Entity Resolution: De Manual a Automático

## El Proceso Completo (5 Stages)

```
PDF/CSV → Stage 1 → Stage 2 → Stage 3 → Stage 4 → Stage 5 → Resultado Final
          Type      Extract    Normalize  Resolve   Categories
```

---

## 🔄 El Ciclo de Aprendizaje

### Primera Vez (100% Manual)
```
1. PDF entra: "TRANSF SPEI GOOGLE WORKSPACE LLC"
   ❌ No está en registry

2. Sistema pregunta: "¿Qué merchant es este?"
   👤 TÚ decides: "Es Google"
   👤 TÚ clasificas: Category = "utilities"

3. Sistema guarda en registry:
   ✅ merchant-id: "google"
   ✅ canonical-name: "GOOGLE"
   ✅ variations: ["GOOGLE WORKSPACE LLC"]
   ✅ category: "utilities"
```

### Segunda Vez (Automático)
```
1. Nuevo PDF: "GOOGLE WORKSPACE INC"

2. Stage 4 busca en registry:
   ✓ Encuentra variation similar: "GOOGLE WORKSPACE LLC"
   ✓ Match! → canonical-name: "GOOGLE"

3. ✅ Clasificado automáticamente como "utilities"
   ❌ NO pregunta nada
```

### Tercera Vez (Más Inteligente)
```
1. Otro formato: "GOOGLE LLC PAYMENT"

2. Stage 4:
   ✓ Fuzzy match con "GOOGLE" (90% similar)
   ✓ Auto-agrega "GOOGLE LLC PAYMENT" a variations

3. ✅ Futuras transacciones de "GOOGLE LLC PAYMENT"
      también automáticas
```

---

## 📊 Los 5 Stages Explicados

### Stage 1: Type Detection
**Qué hace:** Identifica el tipo de transacción
```
"TRANSF SPEI" → type: "spei-transfer-in"
"COMPRA TARJETA" → type: "card-purchase"
"RETIRO ATM" → type: "cash-withdrawal"
```
**Automatización:** 100% automática (reglas fijas)

---

### Stage 2: Counterparty Extraction
**Qué hace:** Extrae el nombre del merchant del texto
```
Input:  "TRANSF SPEI 00001234 GOOGLE WORKSPACE LLC"
Remove: "TRANSF SPEI", números, ruido
Output: "GOOGLE WORKSPACE LLC"
```
**Automatización:** 100% automática (patrones de ruido)

---

### Stage 3: Normalization
**Qué hace:** Limpia y normaliza el nombre
```
"GOOGLE WORKSPACE LLC" → "google workspace"
"SEGUROS ATLAS S.A." → "seguros atlas"
```
**Automatización:** 100% automática (reglas de normalización)

---

### Stage 4: Entity Resolution ⭐ (El Inteligente)
**Qué hace:** Busca en registry si ya conocemos este merchant

#### Primer Nivel: Exact Match
```
clean-merchant: "google workspace"
Registry tiene: "google workspace" en variations
✓ Match exacto → canonical: "GOOGLE"
```

#### Segundo Nivel: Fuzzy Match
```
clean-merchant: "google workspce" (typo)
Registry tiene: "google workspace" (90% similar)
✓ Match fuzzy → canonical: "GOOGLE"
✓ Auto-agrega "google workspce" a variations
```

#### Tercer Nivel: Manual (Primera Vez)
```
clean-merchant: "nueva empresa desconocida"
Registry NO tiene nada similar
❌ No match → pending classification
👤 Usuario clasifica manualmente
✅ Se agrega al registry
```

**Automatización:**
- Semana 1: 0% automático (todo nuevo)
- Semana 4: 60% automático (merchants comunes)
- Mes 3: 90% automático (casi todo conocido)

---

### Stage 5: Multi-Dimensional Categories ✨ (Nuevo!)
**Qué hace:** Agrega 6 dimensiones de categorización

```
Merchant resolved: "GOOGLE" (Stage 4)
↓
Stage 5 enriquece:
1. Budget Category: "Technology" (del MCC 5734)
2. Tax Category: "Business Deductible" (SAT: "Gastos de Software")
3. Payment Method: "Online Payment" (detectado del banco)
4. Accounting: "Expenses / Debit"
5. Merchant Category: "Computer Software Stores" (ISO 18245)
6. Flow Type: "GASTO" (expense)
```

**Automatización:**
- Si merchant tiene MCC en registry → 100% automático
- Si NO tiene MCC → usa fallbacks inteligentes (70% confiable)

---

## 🚀 Cómo Reduces Trabajo Manual

### Estrategia: Enriquecer Registry

#### Paso 1: Identifica Top Merchants (80/20)
```bash
# Ejecuta esto:
curl http://localhost:3000/api/registry/stats | jq '.by-entity-type'

# Resultado ejemplo:
Top 20 merchants = 80% del volumen de transacciones
```

#### Paso 2: Enriquece Manualmente (Una Vez)
```
Para cada top merchant:
1. Busca su MCC code (Google → 5734)
2. Agrega budget category ("Technology")
3. Agrega tax hints (deducible business)
```

**Tiempo:** 2-3 minutos por merchant × 20 = ~1 hora
**Beneficio:** 80% de futuras transacciones auto-categorizadas

#### Paso 3: El Sistema Aprende
```
Transaction 1: "GOOGLE WORKSPACE"
  → Manual: MCC 5734, Budget: Technology
  → Guardado en registry

Transactions 2-100: "GOOGLE WORKSPACE"
  → Automático: Lee MCC del registry
  → Stage 5 categoriza automáticamente
  → 0 trabajo manual
```

---

## 📈 Progresión de Automatización

### Mes 1
```
100 transacciones nuevas
├─ 80 merchants nuevos → 80% manual
├─ 20 merchants conocidos → 20% automático
└─ Total trabajo manual: ~2-3 horas/semana
```

### Mes 2
```
100 transacciones nuevas
├─ 20 merchants nuevos → 20% manual
├─ 80 merchants conocidos → 80% automático
└─ Total trabajo manual: ~30 minutos/semana
```

### Mes 3+
```
100 transacciones nuevas
├─ 5 merchants nuevos → 5% manual
├─ 95 merchants conocidos → 95% automático
└─ Total trabajo manual: ~10 minutos/semana
```

---

## 🎯 Ejemplo Real: Google

### Primera Transacción (Manual)
```
PDF: "GOOGLE WORKSPACE LLC PAYMENT"

Stage 1: ✓ Automático → type: "card-purchase"
Stage 2: ✓ Automático → extract: "GOOGLE WORKSPACE LLC"
Stage 3: ✓ Automático → normalize: "google workspace"
Stage 4: ❌ NO match → pending classification
   👤 TÚ: Clasificas como "google", category: "utilities"
   👤 TÚ: Agregas MCC: 5734, Budget: "Technology"
Stage 5: ❌ Esperando Stage 4
```
**Tiempo:** ~2 minutos de trabajo manual

---

### Transacciones 2-10 (Automático Total)
```
PDFs:
- "GOOGLE LLC PAYMENT"
- "GOOGLE WORKSPACE INC"
- "GOOGLE SERVICES"

Stage 1: ✓ Automático
Stage 2: ✓ Automático
Stage 3: ✓ Automático
Stage 4: ✓ Automático → Match con "google" (fuzzy)
          ✓ Auto-agrega variations
Stage 5: ✓ Automático → Lee MCC 5734 del registry
          ✓ Budget: "Technology"
          ✓ Tax: "Business Deductible"
          ✓ Payment: "Online Payment"
```
**Tiempo:** 0 segundos de trabajo manual ✨

---

## 🔑 Keys to Success

### 1. Registry es el Cerebro
```
Merchant Registry = Tu base de conocimiento
- Más merchants → Más automático
- Más variations → Mejor fuzzy matching
- Más MCC codes → Mejor Stage 5
```

### 2. Focus en Top Merchants (80/20)
```
✅ Enriquece: Top 20 merchants (80% volumen)
⏸️ Ignora: Long tail (20% volumen)

Resultado: 80% automático con ~1 hora de trabajo
```

### 3. El Sistema Auto-Aprende Variations
```
Primera vez: "GOOGLE WORKSPACE"
Segunda vez: "GOOGLE LLC"
Tercera vez: "GOOGLE SERVICES"

Stage 4 auto-agrega estas variations al registry
Futuro: Cualquier variación de "GOOGLE" → automático
```

---

## 📊 Tu Situación Actual

### Datos Actuales
```
✅ 20 merchants en registry
✅ 27 variations conocidas
✅ 71 transacciones históricas
✅ 15 merchants enriquecidos con MCC
⏳ 9 pending clasificación
```

### Qué Significa
```
Automatización actual: ~60%
- 40 transacciones: Auto-resolved ✓
- 31 transacciones: Need classification ⏳

Con 1 hora de trabajo:
1. Clasifica los 9 pending
2. Enriquece top 5 merchants restantes
3. Resultado: 85% automatización
```

---

## 🎯 Próximos Pasos Recomendados

### Opción 1: Clasificar Pending (20 min)
```
Tab Merchants → 9 pending
Para cada uno:
1. Click → Ver detalles
2. Clasificar (2 min cada uno)
3. Total: 20 minutos
→ Beneficio: Esos merchants automáticos forever
```

### Opción 2: Batch Re-process (Avanzado)
```
Re-procesar las 71 transacciones existentes con Stage 5
→ Todas tendrán Budget/Tax/Payment categories
→ Requiere: Crear script batch (1 hora desarrollo)
```

### Opción 3: Esperar y Acumular
```
Procesar nuevas transacciones normalmente
→ Stage 5 funcionará automáticamente
→ Históricas quedan sin Stage 5 (no afecta futuro)
```

---

## 💡 Resumen en Una Frase

**"El sistema aprende cada merchant que clasificas una vez, y luego reconoce automáticamente todas sus variaciones futuras, reduciendo tu trabajo manual de 100% → 5% en 3 meses."**

---

**Última actualización:** 2025-11-11
**Estado:** Sistema funcionando, 60% automatización actual
**Próximo objetivo:** 85% automatización (1 hora de clasificación)
