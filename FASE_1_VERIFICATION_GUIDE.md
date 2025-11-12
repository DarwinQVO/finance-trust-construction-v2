# FASE 1 Verification Guide

## Quick Verification (30 seconds)

### Step 1: Open the Page
```bash
# In your browser, navigate to:
http://localhost:3000/merchant-review
```

### Step 2: Open Browser Console
- **Chrome/Edge:** Press `F12` or `Cmd+Option+I` (Mac)
- **Firefox:** Press `F12` or `Cmd+Option+K` (Mac)
- **Safari:** Enable Develop menu → Show JavaScript Console

### Step 3: Look for Self-Audit Output

You should see this in the console:

```
🔍 FASE 1 Self-Audit: Transaction Type Badges
Testing renderTypeBadge():
  GASTO: ✅ <span class="type-badge badge-red">Expense</span>
  INGRESO: ✅ <span class="type-badge badge-green">Income</span>
  PAGO_TARJETA: ✅ <span class="type-badge badge-blue">CC Payment</span>
  TRASPASO: ✅ <span class="type-badge badge-orange">Transfer</span>
  RETIRO: ✅ <span class="type-badge badge-purple">Withdrawal</span>
  DEPOSITO: ✅ <span class="type-badge badge-green">Deposit</span>
  COMISION: ✅ <span class="type-badge badge-gray">Fee</span>
  INTERES: ✅ <span class="type-badge badge-yellow">Interest</span>
  AJUSTE: ✅ <span class="type-badge badge-gray">Adjustment</span>
  DESCONOCIDO: ✅ <span class="type-badge badge-unknown">Unknown</span>
  INVALID: ✅ <span class="type-badge badge-unknown">Unknown</span>

Type badges rendered: ✅ (N badges)
CSS styles loaded: ✅

✅ FASE 1 Self-Audit Complete
```

---

## Visual Verification Checklist

### ✅ Type Badges Visible
- [ ] Each pending merchant shows a colored badge
- [ ] Badge appears in the "pending-details" section
- [ ] Badge is to the left of the amount and date

### ✅ Badge Colors Correct
Look for these color combinations:

| Badge Text | Background | Text Color | Border |
|-----------|-----------|------------|--------|
| Expense | Light red (#fee) | Dark red (#c00) | Red (#fcc) |
| Income | Light green (#efe) | Dark green (#070) | Green (#cfc) |
| CC Payment | Light blue (#eef) | Dark blue (#007) | Blue (#ccf) |
| Transfer | Light yellow (#ffe) | Orange (#c60) | Yellow (#ffc) |
| Withdrawal | Light purple (#fef) | Purple (#707) | Purple (#fcf) |
| Fee | Light gray (#f5f5f5) | Gray (#666) | Gray (#ddd) |
| Interest | Light yellow (#ffc) | Brown (#660) | Yellow (#ff9) |
| Unknown | Light gray (#eee) | Gray (#999) | Gray (#ccc) |

### ✅ Badge Styling Correct
- [ ] Badges have rounded corners (3px radius)
- [ ] Text is uppercase
- [ ] Font size is small (11px)
- [ ] Padding looks balanced (2px vertical, 8px horizontal)
- [ ] Border is visible (1px solid)

### ✅ Layout Not Broken
- [ ] Badges don't overlap with merchant name
- [ ] Badges align horizontally with amount and date
- [ ] Delete button still works
- [ ] Click-to-select still works

---

## Expected Appearance

### Before (FASE 0)
```
┌─────────────────────────────────────────┐
│ WALMART MX                              │
│ GASTO  $125.50  2024-11-10             │ ← Plain text
└─────────────────────────────────────────┘
```

### After (FASE 1)
```
┌─────────────────────────────────────────┐
│ WALMART MX                              │
│ [Expense] $125.50  2024-11-10          │ ← Red badge with white text
└─────────────────────────────────────────┘
```

---

## Common Issues & Solutions

### Issue 1: Badges Not Visible
**Symptom:** No colored badges appear, only raw text like "GASTO"

**Solution:**
1. Check console for errors
2. Verify renderTypeBadge is defined (type `renderTypeBadge` in console)
3. Hard refresh page (Cmd+Shift+R or Ctrl+F5)

### Issue 2: Badges Have No Color
**Symptom:** Badges appear but with no background color

**Solution:**
1. Check if CSS loaded (in DevTools → Elements → Styles, search for `.type-badge`)
2. Verify no CSS conflicts
3. Clear browser cache

### Issue 3: Self-Audit Shows ❌
**Symptom:** Some tests show red ❌ instead of green ✅

**Solution:**
1. Read the error message in console
2. Check if specific transaction type is failing
3. Verify typeConfig in renderTypeBadge function

### Issue 4: Badges Overlap Text
**Symptom:** Badge overlaps merchant name or other elements

**Solution:**
1. Check CSS for `.pending-detail-item`
2. Verify badges are inside `.pending-details` div
3. Check for layout changes in renderPendingList

---

## Manual Testing Scenarios

### Scenario 1: Multiple Transaction Types
**Steps:**
1. Load page with pending merchants
2. Verify each merchant shows different badge colors
3. Confirm at least 3-4 different types visible

**Expected:** Different colored badges (red, green, blue, etc.)

### Scenario 2: Unknown Type Handling
**Steps:**
1. In console, type: `renderTypeBadge('INVALID_TYPE')`
2. Press Enter

**Expected:** Returns `<span class="type-badge badge-unknown">Unknown</span>`

### Scenario 3: Badge Click (Should NOT be clickable yet)
**Steps:**
1. Try clicking on a badge
2. Verify it selects the merchant (not the badge itself)

**Expected:** Clicking badge selects the entire pending item

---

## Screenshot Checklist

Take these screenshots for documentation:

1. **Full page view** - Shows all pending merchants with badges
2. **Console output** - Shows self-audit results
3. **DevTools Elements** - Shows one badge's HTML structure
4. **DevTools Styles** - Shows .type-badge CSS rules

---

## Performance Check

### Load Time
- Page should load in < 2 seconds
- Badges should render instantly (no delay)

### Console Errors
- Should show 0 errors
- Should show 0 warnings (except for expected ones)

### Memory Usage
- No memory leaks (check in DevTools → Memory)
- Badges should use minimal additional memory

---

## Acceptance Criteria

FASE 1 is considered COMPLETE if:

- [x] Self-audit shows all ✅ (no ❌)
- [x] Badges visible in pending list
- [x] Colors match specification
- [x] No layout issues
- [x] No console errors

---

## Reporting Issues

If you find issues, report with:

1. **Screenshot** of the problem
2. **Console output** (full text)
3. **Browser & version** (Chrome 120, Firefox 121, etc.)
4. **Steps to reproduce**

---

## Success Confirmation

When all checks pass, reply with:

```
✅ FASE 1 VERIFIED

- Self-audit: All ✅
- Visual: Badges visible with correct colors
- Layout: No issues
- Console: No errors

Ready for FASE 2.
```

---

**Expected verification time:** 2-3 minutes
**Last updated:** 2025-11-11
