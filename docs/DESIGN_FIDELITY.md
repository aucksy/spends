# Design-fidelity checklist

Source of truth: the Claude Design export at `D:\Apps\Spends\Design System\project\*.dc.html`
(Tokens · Foundations · Components · Screens · Screens 2). **Before shipping any UI, read the
relevant `.dc.html` and audit against this list — don't improvise.** Status keys:
✅ matches · 🟡 partial / close · ⬜ not built yet (its phase) · ❌ drift to fix.

## Tokens (`Spends Tokens` / `Foundations`)
- ✅ M3 color roles (primary #0F766E, surface, surfaceVariant #F2EFE8, background #FAFAF8, outline…) — `core/theme/Color.kt`.
- ✅ Money semantics (income/expense-ink/negative/transfer/nonConsumption/review) — `SemanticColors`.
- ✅ Type scale — `core/theme/Type.kt`: balanceHero 46/52, amountLg 28/34, amountRow 14/20 (Plex Mono tabular); headline/title/body/label (Manrope). *(Fixed in v0.7.0 — were undersized.)*
- ✅ Shape (sm8/md12/lg16/xl28/full999), spacing 8-pt, motion (counter 600ms).
- ❌ **Category palette** — design specifies a FIXED `cat/01…16` set with exact hexes; `core/category/ColorAssigner.kt` uses a different generated palette. Categories still get a distinct colour+icon, but hues don't match the spec. *Deferred — re-colouring existing user categories is invasive.*

## Components (`Spends Components`)
- ✅ Buttons (filled pill / outlined / tonal / text) & FAB (56dp r18) — used across screens.
- ✅ Period control (segmented pill, selected = primary fill) — `ui/components/DesignKit.kt` `PillSegmentedControl` (Analytics). 🟡 Home still uses the older header.
- ✅ Summary tiles (expense/income/balance) — Transactions header + Analytics summary card.
- ✅ Transaction rows (category-tint icon tile, merchant, meta, signed mono amount, kind) — `TransactionsScreen`. 🟡 no payment-method chip / source glyph yet (capture phases).
- ✅ Chips (category / filter / payment-method) — category picker + filters. 🟡 payment-method chips pending Cards phase.
- ✅ Switches, section labels, empty states.
- ✅ Charts: category donut + spend-over-time bars — `ui/components/Charts.kt` (Analytics).
- ✅ Card style: white surface + hairline outline + soft e1 — `DesignKit.SpendsCard`. 🟡 some older screens still use filled surfaceVariant cards.
- ⬜ Correction form (flagged-field "just confirm this") — capture phase (3/4).
- ⬜ Source/review badges, capture-health — capture phase.
- 🟡 Chrome: top bar ✅; bottom nav has 2 tabs (Transactions/Analytics) — design shows 4 (Home/Analytics/**Cards**/Settings); Cards needs payment methods (Phase 3). Snackbar ✅.

## Screens (`Spends Screens` / `Screens 2`)
1. Home / My Cycle — 🟡 built (Transactions tab) but layout differs from design hero (design = one teal hero card with inline expense/income/carry + white rounded list sheet). Revisit when payment methods land.
2. My Cycle per-instrument breakdown — ⬜ needs payment methods (Phase 3).
3. Needs review ("just confirm") — ⬜ capture phase.
4. **Analytics (donut)** — ✅ built v0.7.0 (`ui/analytics/AnalyticsScreen.kt`): period pill (Salary/Month — My Cycle/Card pending payment methods), donut + legend, spend-over-time bars, recurring summary, excludes-transfers note.
5. Onboarding — SMS rationale ⬜ (capture phase). **Data setup (Screens 2 #7)** — ✅ rebuilt v0.7.0 as a radio-select list + Continue (was tappable cards). Welcome step — 🟡 user reports it looks off; awaiting screenshots.
6. App-lock — ⬜ Phase 10.
8. OEM keep-running — ⬜ Phase 12.
9. Add transaction — 🟡 built; design uses a big centered amount hero + field rows with leading icons + "Paid with" (payment methods, Phase 3).

## Open drifts to fix in upcoming phases
- Home hero/list layout → design Screen 1 (with payment methods).
- Welcome onboarding step polish (awaiting user screenshots).
- Category palette → fixed cat/01…16 (needs a careful re-colour migration).
- Bottom nav → add Cards tab (Phase 3).
