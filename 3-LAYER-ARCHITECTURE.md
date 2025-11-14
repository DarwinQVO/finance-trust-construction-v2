# 3-Layer Architecture

## Structure

```
src/finance/interface/
├── components.clj      # WHAT (L15-149: 12 components)
├── view_models.clj     # DATA (L24-148: transformations)
├── skins.clj          # HOW (L41-211: BlueprintSkin)
└── adapters.clj       # Entity → View Model
```

## Components (WHAT)

**File:** `src/finance/interface/components.clj`

**Registry (L15-149):**
- `:transaction/card` `:transaction/detail` `:transaction/table`
- `:merchant/card` `:merchant/detail`
- `:bank/card` `:category/card`
- `:stats/dashboard` `:source/list`
- `:page/transactions` `:page/pending-review` `:page/entities`

## View Models (DATA)

**File:** `src/finance/interface/view_models.clj`

**Functions:**
- `transaction->card-model` (L24-59)
- `transaction->detail-model` (L61-87)
- `transactions->table-model` (L89-105)
- `stats->dashboard-model` (L130-148)

## Skins (HOW)

**File:** `src/finance/interface/skins.clj`

**Protocol (L15-35):**
```clojure
(defprotocol ISkin
  (render-component [skin component-id view-model])
  (render-page [skin page-id view-model])
  (render-fragment [skin fragment-id view-model]))
```

**Implementations:**
- `BlueprintSkin` (L41-211) - Hiccup → HTML
- `TerminalSkin` (L266-326) - ANSI
- `ReactSkin` (L332-363) - JSX

## API Endpoints

**File:** `src/finance/web_server.clj`

```
GET /api/v2/components                           # L1097-1118
GET /api/v2/skins                                # L1120-1134
GET /api/v2/render/:skin/:component?params       # L902-1095
GET /api/v2/pages/transactions?skin=X&limit=N    # L1180-1220
```

## Frontend

**File:** `resources/public/components.html`

**Hiccup Converter (L250-326):**
```javascript
function hiccupToHtml(hiccup) {
  // null → ''
  // string → string
  // [tag attrs ...children] → <tag>children</tag>
}
```

## Data Flow

```
Browser
  ↓ fetch('/api/v2/render/blueprint/transaction/card?...')
web_server.clj:render-component-handler (L914)
  ↓ Build view-model from params (L936-1076)
skins.clj:BlueprintSkin (L44)
  ↓ Generate Hiccup
Return JSON: {success: true, result: [...hiccup...]}
  ↓
components.html:hiccupToHtml() (L250)
  ↓
DOM update
```

## Example

### View Model
```clojure
{:transaction/id "tx-001"
 :transaction/date "2024-03-20"
 :transaction/amount 45.99
 :transaction/merchant "STARBUCKS"
 :transaction/category "Restaurants"
 :transaction/type "GASTO"
 :transaction/confidence 0.95
 :transaction/bank "BofA"}
```

### Hiccup Output
```clojure
[:div.bp5-card.bp5-elevation-1
 {:style {:margin-bottom "1rem"}}
 [:h5.bp5-heading "STARBUCKS"]
 [:p.bp5-text-muted "2024-03-20 • Restaurants"]
 [:div {:style {:font-size "1.2rem"}} "$45.99"]]
```

### HTML Output
```html
<div class="bp5-card bp5-elevation-1" style="margin-bottom: 1rem">
  <h5 class="bp5-heading">STARBUCKS</h5>
  <p class="bp5-text-muted">2024-03-20 • Restaurants</p>
  <div style="font-size: 1.2rem">$45.99</div>
</div>
```

## Running

```bash
clojure -M -m finance.web-server
open http://localhost:3000/components.html
open http://localhost:3000/test-simple.html
```

## API Calls

```bash
# List components
curl http://localhost:3000/api/v2/components

# Render component
curl 'http://localhost:3000/api/v2/render/blueprint/transaction/card?merchant=STARBUCKS&amount=45.99&date=2024-03-20'

# Render page
curl 'http://localhost:3000/api/v2/pages/transactions?skin=blueprint&limit=5'
```
