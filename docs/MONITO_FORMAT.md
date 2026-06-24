# Monito Expense Manager — export format (for the import adapter)

Derived by inspecting the user's real export (kept out of the repo; `.gitignore` blocks `*.xls`).
**No raw personal data is recorded here** — only the structure.

## Workbook shape
- Legacy **BIFF8 `.xls`** (OLE2 / Excel 97-2003 binary) — *not* `.xlsx`. Needs an Android `.xls`
  reader (the generic path uses fastexcel for `.xlsx`, but Monito needs a BIFF reader).
- **One sheet per month**, named like `September 2019` … `June 2026` (gaps are possible).
- Each sheet has a fixed preamble then a table:

| row | content |
|---|---|
| r0 | (blank) |
| r1 | `Monito Expense Manager` |
| r2 | `Version 8.3` |
| r3 | `Created on <date>` |
| r4 | (blank) |
| r5 | the month label (col E) |
| r6 | (blank) |
| r7 | **header**: cols C..G = `Date`, `Category type`, `Category name`, `Note`, `Amount` |
| r8+ | data rows |

Columns A,B are always empty (data starts at column C / index 2).

## Cell semantics
- **Date** (col C): text, format `d MMM yyyy` (e.g. `25 Sep 2019`, `1 Feb 2020` — no leading zero). Parse with an English `d MMM yyyy` formatter; store at IST noon.
- **Category type** (col D): only **`Income`** or **`Expense`** in this export (no `Transfer`). Maps to `kind`.
- **Category name** (col E): the category. **Preserve exactly**; create one per distinct name.
- **Note** (col F): free text, optional.
- **Amount** (col G): a **positive** number; the sign/direction comes from Category type. Convert to paise.

## Adapter rules
- Detect the header row by matching `Date` + `Category type` + `Category name` (don't hard-code r7).
- `Income` → `kind = INCOME`; `Expense` → `kind = EXPENSE`. No transfer rows here, but the adapter
  still recognises a `Transfer` type → `kind = TRANSFER` for forward compatibility.
- **Category usage** is inferred from the rows a name appears in: only-income → `INCOME`,
  only-expense → `EXPENSE`, both → `BOTH`.
- **excludeFromSpend** auto-flagged for non-consumption names (keyword match: invest, emi, loan,
  sip) — e.g. `Investment`, `Shopping EMIs` — shown in the mapping confirm step so the user can fix.
- `paymentMethodId` is null (no account column in the export).
- Source = `IMPORT`. A `dedupeHash` over (occurredAt-day + amount + categoryName + note) skips rows
  already present, so re-importing the same file is idempotent.

## Scale (this export)
~3,800 transactions across ~66 monthly sheets, ~27 distinct categories. Large enough to run the
import on a WorkManager job with progress, off the main thread.
