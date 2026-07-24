# Spends — "AI helper" build plan (files + order)

**Status:** BUILT + adversarially reviewed 2026-07-24, **awaiting the owner's ship word** (no tag yet).
Implements the LOCKED spec in [`AI-RESEARCH.md`](AI-RESEARCH.md). Read `CONTEXT.md` first for how the app
works. See **Build status** at the bottom for the review outcome + accepted residuals.

Two features, one opt-in **AI helper**, master switch **OFF by default**:
- **#1 Smart category *suggestions*** on the SMS review queue (a `Suggested: Food ✨` chip;
  you still tap to accept, then still confirm — nothing auto-applied).
- **#2 Plain-English cycle *insights*** card on Analytics (category totals only; cached per cycle).

Key model = **BYOK** (you paste your own free Groq key; stored **encrypted**, device-local,
**never in the backup**). Fail-closed everywhere: no key / offline / timeout / bad answer →
the app behaves exactly like today.

---

## Money-safety checklist (mapped to exact code points — the whole point)

| Guardrail | How it's guaranteed in code |
|---|---|
| **Amount/kind/date are never AI** | Untouched `SmsParser`. AI only ever produces a *category name* or *insight text*. Golden parser tests unchanged. |
| **AI only on review/`allowFuzzy` surfaces** | AI runs only over `pending_captures` rows (the review queue) and the Analytics card. It is **never** called from `captureReturningId` (silent add, `allowFuzzy=false`, `SmsCaptureRepository.kt:110`), `confirmPending` (:513), `confirmAllPending` (:597), or `confirmPendingEdited` (:559) — those commit the **stored** `categoryId`, never re-resolve, never call AI. |
| **AI can't add/edit/delete a txn or move a balance** | #1 accepting the chip calls `setPendingCategory` (:544) = update the pending row + learn; **no ledger write**. You still hit "Review and Add"/"Add all" to commit. #2 is read-only text. |
| **G1 — learned map wins outright** | New public `SmsCaptureRepository.hasLearnedCategory(merchant)` (thin wrapper over the existing `learnedFor(allowFuzzy=true)`). A row is AI-eligible **only if `hasLearnedCategory == false`**. The learned branch in `resolveCategory` (:834) already returns before anything else — AI never competes with or re-ranks a learned pick. |
| **G2 — master OFF = today's app byte-for-byte** | Every AI entry point is gated on `settings.aiEnabled && sub-toggle && hasKey`. OFF (default) → zero Groq calls, no chip, no card, no new network. |
| **Fail closed** | Bad JSON / off-list category / null / 4xx-5xx / timeout / no network → drop the AI result, fall back to today's behaviour. Never crash, never guess an amount, never block UI. |
| **Privacy — only 2 things leave the phone** | #1 sends the **merchant string** + your **category-name list**. #2 sends **category totals + income/expense totals** (this + last cycle). NEVER: raw SMS bodies, amounts+balances, account/card numbers, last4, dates, or individual rows. |

---

## What's already in place (no new infra needed)
- `INTERNET` + `ACCESS_NETWORK_STATE` permissions — present.
- OkHttp + kotlinx-serialization — already dependencies.
- Encrypted key store pattern — `SecureKeyStore` (AndroidKeyStore AES-GCM).
- Review queue + the `allowFuzzy` boundary — `SmsCaptureRepository`.
- Reconciled per-cycle aggregates — `AnalyticsViewModel.state` (expense/income/`categories`).
- **No DB schema change. No new dependency. No manifest change.**

---

## Build order (file by file)

### Phase A — AI plumbing (no UI yet)
1. **`data/backup/SecureKeyStore.kt`** *(edit)* — add `setApiKey(String)` / `apiKey(): String?`
   / `hasApiKey(): Boolean` / `clearApiKey()`, reusing the existing hardware-wrapped `masterKey()`
   + a new pref key in the same `spends_secure` prefs (device-local; never in the snapshot).
2. **`data/settings/SettingsRepository.kt`** *(edit)* — add device-local prefs `aiEnabled`,
   `aiCategorize`, `aiInsights` (all default **false**) → `SettingsState` + `Keys` + setters.
   **Deliberately NOT added to `restore()` / the snapshot** (same pattern as `widgetEyeHidden`).
3. **`data/ai/GroqClient.kt`** *(new)* — OkHttp POST to Groq's OpenAI-compatible
   `chat/completions`; Bearer key from `SecureKeyStore`; `response_format` JSON mode; ~10s timeout;
   typed result (`Ok`/`Failed`), everything in `runCatching` → fail-closed. Model IDs as constants
   (**verify `llama-3.1-8b-instant` + `llama-3.3-70b-versatile` against Groq's live list at build
   time**). Exposes `testKey()` for the Settings "Test key" button.
4. **`data/ai/AiCategorizer.kt`** *(new)* — input a batch of `{id, merchant, kind}` + the
   category-name list → one 8B call (JSON, batched) → `Map<id, {categoryName, cleanName?}>`.
   Returns names only; off-list/null are dropped. Never sees amounts/dates/SMS.
5. **`data/ai/AiInsights.kt`** *(new)* — build the **aggregates-only** payload (`cycleLabel,
   income, expense, byCategory[name,total], lastCycle{expense, byCategory}`) → one 70B call →
   summary text or null. Small in-memory cache keyed by (window + data fingerprint).
6. **`data/db/dao/ExpenseDao.kt`** *(edit)* — add one-shot `categorySpendOnce(start,end)` for the
   previous-cycle insights payload (`kindSumsOnce` already exists). Query-only; no schema change.

### Phase B — Settings UI (the opt-in home)
7. **`ui/settings/AiSettingsViewModel.kt`** *(new)* — reads/writes the 3 toggles + key; runs
   `testKey()`; exposes `hasKey`, test status.
8. **`ui/settings/AiSettingsScreen.kt`** *(new)* — master **AI toggle**, **Groq key** field
   (reuse `PasswordField` reveal) + **Test key** button + plain-English result, the two sub-toggles
   (**Smart category suggestions** / **Spending insights**), and a one-time **"What leaves your
   phone"** explainer (mirrors the SMS-permission explainer). Built from the design-system kit.
9. **`ui/settings/AutomaticSettingsScreen.kt`** *(edit)* — add an `onOpenAi` `ClickableRow`
   ("AI helper — smarter categories & insights", `AutoAwesome` icon), next to SMS detection.
10. **`ui/navigation/Routes.kt`** *(edit)* — `SETTINGS_AI = "settings_ai"`.
11. **`ui/navigation/SpendsNavHost.kt`** *(edit)* — wire `onOpenAi` → navigate `SETTINGS_AI`;
    add the `composable(SETTINGS_AI){ AiSettingsScreen(...) }`.

### Phase C — Feature #1: the ✨ suggestion chip (review-only)
12. **`ui/review/ReviewViewModel.kt`** *(edit)* — inject `AiCategorizer` + `SettingsRepository`
    + `SmsCaptureRepository.hasLearnedCategory`. When enabled, batch-suggest for **eligible** rows
    only (merchant non-blank **AND** `!hasLearnedCategory` **AND** rules landed on "Other"); hold a
    `Map<id, suggestion>` StateFlow; fold `aiSuggestedCategoryId/Name` into `ReviewRowUi`; add
    `acceptSuggestion(id)` → `setPendingCategory(id, aiCatId)` (review-only + learn). Batch fires
    after a scan / when the queue is shown; one call, guarded, cached in-session.
13. **`ui/review/ReviewScreen.kt`** *(edit)* — in `ReviewCard`, when a suggestion exists render a
    subtle `Suggested: <name> ✨` chip by the category row; tap = accept (fills the category). Never
    auto-applied. No change to the "Review and Add" / "Add all" commit paths.

### Phase D — Feature #2: the ✨ Insights card (read-only)
14. **`ui/analytics/AnalyticsViewModel.kt`** *(edit)* — inject `AiInsights` + `SettingsRepository`;
    expose an insights StateFlow (text / loading / error) derived from the reconciled `state`
    (exact current-cycle numbers) + a one-shot previous-cycle read; `refreshInsights()`. Gated on
    `aiEnabled && aiInsights && hasKey`.
15. **`ui/analytics/AnalyticsScreen.kt`** *(edit)* — a dismissible `✨ Insights` card just under the
    period bar (before the summary), with a small refresh button. Absent when off/empty/failed.

### Phase E — Tests (`app/src/test`)
16. **`AiCategorizerTest.kt`** — name→id mapping; off-list/null/malformed → dropped (fail-closed);
    a learned merchant is never suggested (G1); empty input → no call.
17. **`AiInsightsPayloadTest.kt`** — payload contains **only** aggregates (asserts no merchant, no
    dates, no last4, no raw rows); malformed response → null (no crash).
18. **`AiGatingTest.kt`** — with `aiEnabled=false` the eligibility/collectors yield nothing (G2);
    eligibility excludes learned + non-"Other" rows.

### Phase F — Ship ritual (per `CONTEXT.md`) — only after you say "ship"
- 2 parallel adversarial review agents (compile/Hilt/Room + logic/data-safety), told to scan
  `app/src/test`, **with an explicit check that no AI call reaches a silent-commit path and no
  amount is ever AI-derived.** Fix all real findings.
- Then (on your word): bump **versionCode 60→61, versionName 1.55.0→1.56.0**, commit (`-F`), tag,
  push, poll CI green, post the direct APK link, give a manual-test checklist.

---

## Decisions I made (so you don't have to) — all reversible
- **AI settings = their own sub-page** under *Automatic Entries* (matches the v1.55.0 hub), not a
  crammed inline block.
- **Accepting a ✨ chip also *teaches* that merchant** → next time it's pre-filled with no AI call
  (reinforces G1). Same as tapping a category in the picker.
- **Insights "vs last cycle"** shows only for a normal navigable cycle; for All-time/Last-N/Custom
  it summarizes the current window without the comparison. Current-cycle ₹ figures are exact
  (sourced from the on-screen reconciled totals, so the card never contradicts the charts).
- **Not in this round** (kept tight): the optional "✨ Suggest" button inside the manual category
  picker; the deferred ideas (ask-your-money, type-to-add, capture-fallback).

## Not touched
Parser + golden tests · silent-commit paths · balances/amounts · DB schema · backup snapshot ·
manifest · dependencies (only a **test-only** `org.json` dep was added).

---

## Build status (2026-07-24)

**Built** exactly as above (Phases A–E). **No DB schema change, no manifest change, no runtime dependency
added** (test-only `org.json`). **Adversarial review ritual honoured:** 2 parallel agents —
compile/Hilt/Room and logic/data-safety/privacy, both scanning `app/src/test`.

- **Compile/Hilt/Room agent: NO BLOCKERS.** Verified every symbol/import, Hilt bindings, no Room migration
  needed (`categorySpendOnce` is a query on an existing projection), the tests' `internal` access + real
  `org.json` override, and that the one changed signature (`AutomaticSettingsScreen.onOpenAi`) has its single
  caller updated.
- **Logic/data-safety/privacy agent: NO BLOCKER / HIGH / MED.** Every guarantee CONFIRMED by tracing real
  code: (1) AI never on a silent-commit path — only `ReviewViewModel`/`AnalyticsViewModel` call it; (2)
  amount/kind/date never AI-derived, accepting a chip only updates a *pending* row; (3) "Add all"/confirm
  commit the stored id, AI suggestion lives in an in-memory map until an explicit tap; **G1** learned-wins;
  **G2** master-off = zero calls; **fail-closed** everywhere; **privacy** payloads are merchant-string / cat
  totals only, cycle label is the descriptive name (no dates), key encrypted + never in the snapshot.

**Fixes applied after review (all LOW/NIT — done, not deferred):**
1. `GroqClient` no longer lets `runCatching` swallow `CancellationException` (structured-concurrency correct).
2. `ReviewViewModel` suggestion collector un-marks a batch on mid-call cancellation (a scan burst) so its
   chips retry instead of being lost for the session.
3. `AiInsights` fingerprint cache is bounded (64 entries) so it can't grow unbounded in a very long session.
4. Added `ReviewEligibilityTest` (the "AI only for rules-fallback rows" rule) to close the Phase-E gate gap.

**Accepted residuals (owner-visible, no money/privacy impact — all read-only prose):**
- Insights "vs last cycle" can read slightly stale if you edit a transaction in the *previous* cycle (the card
  tracks the current cycle); the **refresh** button regenerates it. 
- For a card-heavy **Smart Cycle**, the "vs last cycle" number uses the plain previous window, not the
  billing-bucketed one — a minor approximation on that one comparison. Current-cycle figures are always exact
  (sourced from the on-screen totals).
- **G2 master-off gating** is verified by the review (it lives in the flow collectors) but not unit-tested;
  the fail-closed + privacy + eligibility cores ARE unit-tested.
