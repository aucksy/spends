# Spends — AI Research & Recommendation (Groq)

**Status:** Research + plan only. **No feature code until the owner approves a direction.**
Written 2026-07-24. Read `CONTEXT.md` first for how the app works.

This doc answers one question: *where does a Groq API key genuinely help Spends, for the
least effort and the least risk to my money and my privacy?* It ranks the options (Phase 1),
then lays out a concrete build plan for the top picks (Phase 2), and ends with the decisions
I need from the owner.

---

## TL;DR (plain English)

- Spends is **100% offline today.** Adding AI = the first time any of your data would leave
  your phone (other than the backup you already choose to send to Drive). So AI is treated as
  **strictly optional, off by default, and clearly labelled** — turn it off and the app is
  exactly what it is now.
- The **two best-value, safest** things AI can do for Spends:
  1. **Smarter category guessing** — when a bank SMS or a messy merchant name (e.g.
     `RAZ*FURLENCO BLR`) doesn't match the built-in rules, AI suggests the right category.
     It only ever *suggests* — you still confirm, exactly like today. Sends a short merchant
     name, nothing else.
  2. **Plain-English spending insights** — 2-4 sentences at the top of Analytics: *"You spent
     ₹X this cycle, ₹3k more than last, mostly Food. Subscriptions crept up ₹200."* Sends only
     **totals** (category sums), never individual transactions.
- **Money can never be touched by AI.** The rule stays: the deterministic parser owns the
  **amount**; AI only fills soft fields (category, wording). Everything still flows through the
  existing *review-before-it-counts* gate. AI literally cannot add, edit, or delete a
  transaction or move a balance.
- **The key:** because Spends is *your personal app*, the simplest and most private choice is
  **you paste your own Groq key once in Settings** (free tier is plenty for one person). A
  "one shared key for everyone" setup (like BragBuddy) is only worth it if Spends ever ships to
  other people — the code will be built so that can be added later without a rewrite.

The rest of this doc is the detail; the chat gives you the short version and the decisions.

---

## The hard rules this plan is built around (non-negotiable)

These come from the owner and `CONTEXT.md`. Every recommendation below is checked against them.

1. **Money safety.** AI must **never** silently create/edit a transaction or change a balance.
   Everything goes through the existing review-before-commit gate. **Amount extraction stays on
   the deterministic rules — never AI.** AI only does *soft* fields: category, merchant name,
   wording, insights.
2. **Privacy.** Spends is offline today. Any data leaving to Groq is the owner's decision:
   **default to sending aggregates/summaries, not raw transactions**; every AI feature is
   **opt-in and clearly labelled**; **degrade gracefully when offline** (behave exactly like
   today).
3. **Working style.** Plain English (owner isn't a developer); cloud-build only; never
   auto-build; **pause and ask on the privacy + key-model decisions before any code.**

---

## Why the app is already well-shaped for safe AI

Three things that already exist make the safe version cheap to build (see the appendix for
exact file names):

- **A review-only capture queue.** Captured SMS/notification transactions land in a
  `pending_captures` table and **never touch balance or analytics until you confirm them.**
  There's already a clean split in the code between *"review surfaces the user checks"*
  (`allowFuzzy = true`) and *"silent commit paths"* (`allowFuzzy = false`). AI plugs into the
  **review side only** — the exact boundary the money-safety rule wants.
- **Deterministic amount + a confidence score.** The rules parser (`SmsParser`, 36+ golden
  tests, validated against the real 14k-SMS export) owns the amount and marks each parse with a
  0-100 `parseConfidence`. AI never has to touch the amount — it slots in *after* the amount is
  fixed.
- **Ready-made aggregates.** `ExpenseDao` already computes per-category totals, income/expense
  totals, and balances by period — the exact "summaries not raw rows" payload insights need.

---

# PHASE 1 — The candidates, weighed and ranked

Each is scored on: **what it does for you** (plain words), **Value**, **Effort**,
**Privacy exposure** (what data leaves the phone), **Money-risk**, and whether it needs
**raw data or just aggregates**. Scale: Low / Medium / High.

### 1. Smarter auto-categorization (reason out messy/new merchant strings) ⭐ TOP PICK
- **What it does for you:** When a captured SMS or a manual entry has a merchant the built-in
  rules can't place (Indian merchant strings are messy — `RAZ*XYZ TECH`, `PAYTM*SWIGGY`,
  `UPI/9876/...`), AI reads it and suggests the best-fitting category from *your* list. It
  pre-fills the suggestion on the review row; **you still tap to accept.** Learns nothing new
  about the amount.
- **Value: High.** Re-categorizing is the #1 recurring chore in any expense tracker. Today,
  anything the keyword rules miss falls into "Other." AI is genuinely good at this exact task.
- **Effort: Low–Medium.** The plug-in point already exists (`resolveCategory`, review-only
  `allowFuzzy` path). Batchable: one call handles all uncategorized rows after a scan.
- **Privacy exposure: Low–Medium.** Sends a short **merchant string** + your category-name
  list. No amounts, no balances, no dates, no full SMS. (Merchant names can hint where you
  shop — that's the one sensitivity, and it's opt-in.)
- **Money-risk: None.** Output is a category id chosen from your existing list, applied only to
  a *pending/review* row. Amount/kind stay from the parser.
- **Needs:** merchant string only (a small raw-ish field), not aggregates, not amounts.
- **Bonus that rides free:** the same call can also return a **clean merchant name** for
  display (candidate #6) at no extra cost.

### 2. Plain-English per-cycle insights (aggregates only) ⭐ SECOND PICK
- **What it does for you:** A short, friendly summary of the cycle you're looking at — how much,
  where it went, what changed vs last cycle, anything creeping up. Sits as a dismissible card at
  the top of Analytics.
- **Value: Medium–High.** Delightful and differentiating; turns the charts into a sentence you'd
  actually read. It's a *nice-to-have* on top of the analytics you already have, not a
  friction-remover.
- **Effort: Medium.** Build the aggregate payload (this-cycle vs last-cycle category totals +
  income/expense), a prompt, one Analytics card, and caching so it doesn't re-call on every open.
- **Privacy exposure: Low.** The textbook "aggregates only" case: per-category **totals** and
  income/expense totals for two cycles. **No individual transactions, no dates, no merchant
  names, no balances-over-time.**
- **Money-risk: None.** Read-only text; structurally can't touch data.
- **Needs:** aggregates only — the cleanest privacy story of any option.

### 3. "Ask your money" — natural-language questions over your data
- **What it does for you:** *"How much did I spend eating out last month?"* → an answer.
- **Value: Medium.** High wow, but overlaps heavily with insights (which delivers ~60% of the
  value for ~30% of the effort).
- **Effort: High.** To keep it private + money-safe, the right design is *AI turns your question
  into a structured filter → the app runs it locally on your data → AI phrases the answer.*
  That's two round-trips, a query-whitelist, and guardrails. The naive version (ship lots of raw
  rows to the model) is the privacy-bad shortcut we won't take.
- **Privacy exposure: Medium–High** unless the structured-query design is used (then only the
  *question* + a schema leave, not the data).
- **Money-risk: None** (read-only), provided AI can only ever produce read filters.
- **Verdict: Defer.** Best revisited *after* insights ships — insights reuses most of the same
  aggregate plumbing.

### 4. AI fallback for SMS/notification captures the rules miss (review-only)
- **What it does for you:** When the rules can't parse a financial-looking SMS/notification, AI
  tries to pull out {is-this-a-transaction, amount, merchant} → drops it into the **review queue
  only** (never auto-added).
- **Value: Medium** — *smaller than it sounds.* The rules parser is already strong and validated
  against the real message dump. Your actual "missed" transactions traced to **RCS / Business-
  Chat bank alerts, which no app (rules or AI) can read** — AI can't fix that. The genuine gap is
  new bank formats not yet in the allowlist.
- **Effort: Medium–High**, and it's the option that rubs against two hard rules at once.
- **Privacy exposure: Highest.** It must send **full raw bank SMS bodies** — the single most
  sensitive text in the app.
- **Money-risk: Medium (→ Low if strictly review-only).** This is the feature that flirts
  closest with the "amount stays on the rules, never AI" red line, because a fallback by
  definition extracts an amount the rules couldn't.
- **Needs:** raw SMS body. **Verdict: Defer.** Highest risk on *both* privacy and money axes,
  and the real gap (RCS) is unfixable. If ever built: opt-in per scan, review-only, amount
  clearly flagged "AI guess — check it," and walled off from the golden-tested rules path.

### 5. Talk / type-to-add an expense (draft → you confirm)
- **What it does for you:** *"spent 250 on lunch, cash"* → AI drafts amount/category/note → opens
  the editor for you to confirm.
- **Value: Medium.** Fun, but the app already has a fast keypad quick-add with most-used
  categories, so the time saved is modest. **Voice** adds real effort (speech-to-text).
- **Effort: Medium (typed) / High (voice).**
- **Privacy exposure: Low–Medium** (short self-written phrases, not bank data).
- **Money-risk: Low** (draft → confirm), though AI *does* read the amount out of your text here —
  acceptable only because you typed it and confirm it in the editor.
- **Verdict: Optional / later.** Nice polish, not a top-value win right now.

### 6. Merchant-name cleanup (`RAZ*FURLENCO BLR` → `Furlenco`)
- **Value: Low–Medium, cosmetic.** The timeline row now shows the **category**, not the
  merchant, so cleaned names are less visible than they used to be.
- **Effort: Low.** **Verdict: Don't build standalone — ship it as a free by-product of #1**
  (same input, same call, one extra output field).

### 7. Subscription price-hike / new-subscription detection
- **Value: Medium** (genuinely money-saving). **But this is not really an AI job** — detecting a
  recurring merchant whose amount jumped is *plain code* (group by merchant, spot periodic
  same-ish charges, flag deltas). AI adds almost nothing.
- **Verdict: Build later as local logic, not part of this AI round.** Noted so it isn't
  mistaken for an AI feature.

## The ranking (value-for-effort, money-safe)

| # | Feature | Value | Effort | Privacy exposure | Money-risk | Data sent | Call |
|---|---------|-------|--------|------------------|-----------|-----------|------|
| **1** | **Smarter auto-categorization** (review-only) | High | Low–Med | Low–Med (merchant string) | None | Merchant name + category list | **BUILD 1st** |
| **2** | **Plain-English cycle insights** | Med–High | Med | **Low (aggregates only)** | None | Category totals, 2 cycles | **BUILD 2nd** |
| 3 | Ask-your-money Q&A | Med | High | Med (or Low w/ query design) | None | Question + schema | Defer |
| 4 | AI capture fallback | Med | Med–High | **Highest (raw SMS)** | Med→Low | Full SMS body | Defer (care) |
| 5 | Talk/type-to-add | Med | Med/High | Low–Med | Low | Short phrase | Optional |
| 6 | Merchant-name cleanup | Low–Med | Low | Low–Med | None | Merchant name | Free with #1 |
| 7 | Subscription-hike detection | Med | Med | None (local) | None | — | Build local, no AI |

**Recommendation:** build **#1 (auto-categorization)** and **#2 (insights)** together as one
"AI helper" round. They're the highest value-for-effort, both **money-safe by construction**
(one is review-only, the other read-only), and they form a clean **privacy ladder** — you can
enable the aggregates-only insights without ever sending a merchant string, or enable both.
Everything else is deferred or built without AI.

---

# PHASE 2 — Build plan for the top 2

One opt-in **"AI helper"** feature, master-switch off by default, with two independent
sub-toggles. When off (or offline, or no key): the app behaves **exactly** as it does today.

## 2.1 Where it lives on screen (UX)

**Settings → new "AI helper" section** (inside *Automatic Entries*, next to SMS detection):
- Master **AI toggle** (off by default).
- **Groq key** field (see 2.5) + a **"Test key"** button that does one tiny call and reports
  "Working ✓" / a plain-English error.
- Two sub-toggles: **Smart category suggestions** and **Spending insights** (each independently
  on/off).
- A one-time, plain-English **"What leaves your phone"** explainer on first enable (mirrors the
  existing SMS-permission explainer pattern), spelling out exactly what each sub-feature sends
  and what it never sends.

**Smart category suggestions (feature #1):**
- In the **review queue**, any uncategorized / low-confidence row gets a subtle
  **`Suggested: Food ✨`** chip. Tap to accept (fills the category), ignore to leave it. Never
  auto-applied — it only pre-fills the suggestion the user still confirms.
- One **batched** call per scan (all uncategorized rows at once), not one per SMS.
- Optional secondary affordance: an **"✨ Suggest"** button in the category picker when the
  merchant string is messy, for manual entries too.

**Spending insights (feature #2):**
- A dismissible **`✨ Insights`** card at the top of Analytics for the selected cycle: 2-4
  sentences + a small **refresh** button. Cached per (cycle + data fingerprint) so opening
  Analytics repeatedly doesn't re-call or re-spend tokens.

## 2.2 The Groq model + why

Groq's current ladder (confirmed 2026-07, per-million-tokens): Llama 3.1 8B Instant
**$0.05/$0.08**, GPT-OSS 20B **$0.075/$0.30**, GPT-OSS 120B **$0.15/$0.60**, Llama 3.3 70B
Versatile **$0.59/$0.79**, Kimi K2 **$1/$3**. Free tier: **30 req/min, ~14,400 req/day, no
credit card** — effectively unlimited for one person's volume. *(Confirm exact model IDs
against Groq's live model list at build time — Groq rotates its lineup.)*

- **Categorization → `llama-3.1-8b-instant`.** The task (pick one category from a fixed list
  given a short string) is easy; the 8B is fast, dirt-cheap, and supports JSON output. Batch 20
  merchants per call.
- **Insights → `llama-3.3-70b-versatile`** (or **GPT-OSS 120B** as a cheaper middle option).
  Nicer prose and better at reasoning over the this-vs-last-cycle deltas. One call per cycle.
- **Structured output:** use Groq's JSON mode / `response_format` so the model returns strict
  JSON we can parse safely (and reject if malformed → fall back to rules). No free-text parsing.

## 2.3 Prompt design (shape, not final wording)

**Categorization (JSON in, JSON out, batched):**
- *System:* "You label Indian personal-finance transactions. Given merchant strings and a fixed
  list of category names, return the single best category **from the list only**. If unsure,
  return `null`. Never invent a category. Never output anything but the JSON."
- *User payload:* `{ categories: ["Food","Groceries",...], items: [{id, merchant, kind}] }`
  — **merchant strings only; no amounts, no dates, no SMS body.**
- *Output:* `[{id, category, cleanName?}]`. We map `category` back to a **real category id**;
  anything not on the list or `null` → leave it on the rules' guess. `cleanName` (optional) is
  the free merchant-name-cleanup by-product (#6), shown only, never used for money.

**Insights (JSON in, short text out):**
- *System:* "You are a calm, encouraging money assistant. In 2-4 short sentences, plain English,
  no jargon, ₹ amounts. Describe this cycle, the biggest categories, and notable changes vs last
  cycle. Never shame; never give financial advice or predictions."
- *User payload (aggregates only):* `{ cycleLabel, income, expense, byCategory:[{name,total}],
  lastCycle:{expense, byCategory:[...]} }` — **totals only.**
- *Output:* `{ summary: "..." }`. If empty/malformed → hide the card (no crash).

## 2.4 How it stays money-safe (the core guarantee)

- **AI is wired only into the review/read surfaces** — the same `allowFuzzy = true` boundary the
  code already uses. It is **never** called on the silent commit paths
  (`captureReturningId`, `confirmPending`, `confirmAllPending`). Amount/kind/date come **only**
  from `SmsParser` — untouched, golden tests untouched.
- **Categorization output = a category id from the existing list, applied to a `pending`/draft
  row's *suggestion*.** The user still confirms through the existing review gate. AI cannot
  insert, edit, or delete a transaction, and cannot change any amount or balance.
- **Insights = text only.** No write path exists.
- **Fail closed.** Malformed JSON, an off-list category, a timeout, a 4xx/5xx, or no network →
  **ignore the AI result and fall back to today's behaviour.** Never crash, never guess an
  amount, never block the UI.

## 2.5 Privacy model

- **Master toggle off by default.** Until it's on *and* a key is set, Spends is 100% offline —
  identical to now.
- **Two independent data tiers, each its own opt-in + label:**
  - *Categorization:* sends the **merchant string** + the **category-name list**. Nothing else.
  - *Insights:* sends **category totals + income/expense totals** for this + last cycle. No rows,
    no dates, no merchant names, no balances-over-time.
- **Never sent (this round):** full SMS bodies, account/card numbers, `last4`, raw transaction
  rows, running balances. (That's why the raw-SMS capture-fallback (#4) is out of scope.)
- **No background streaming.** Calls happen at clear moments — after a scan (categorization) or
  when you open Analytics for a cycle (insights, then cached). Nothing is sent while you're not
  looking.
- **Key at rest:** the Groq key is stored **encrypted** (reuse `SecureKeyStore` /
  AndroidKeyStore) and is **device-local — NOT in the backup snapshot** (like
  `defaultPaymentMethodId` / `widgetEyeHidden`), so a backup file never carries your key.

## 2.6 Offline & failure fallback

Already covered by "fail closed": no key, no network, timeout, or API error →
- *Categorization:* fall back to the keyword rules + learned-merchant map (today's behaviour);
  the ✨ chip simply doesn't appear.
- *Insights:* the card doesn't render. Analytics is unchanged.

AI is **strictly additive** — its absence is never a broken state.

## 2.7 Cost & rate-limit handling

- For one person, the **free tier covers it comfortably.** Rough sizes: a 20-merchant
  categorization batch ≈ a few hundred tokens; a cycle-insight call ≈ ~1k tokens. At 8B/70B
  prices that's **fractions of a paisa per call**, and batching + caching keep call counts tiny
  (≈1 per scan, ≈1 per cycle-view).
- **Batch** categorization (never per-SMS). **Cache** insights per (cycle + data fingerprint) so
  re-opening Analytics is free.
- Handle **429 / timeout** by failing closed (fall back), with a short (~8-10s) timeout. Optional
  tiny client-side throttle to respect 30 req/min — realistically never hit at this volume.

## 2.8 The key model — managed-for-all vs bring-your-own (BYOK)

- **BYOK (recommended for now):** the owner pastes **their own Groq key** once in Settings.
  - *Pros:* zero backend/infra/cost for us; the key never leaves the owner's device; the free
    tier is plenty for one user; ships today with no server.
  - *Cons:* a real person has to create a Groq key (fine for the owner; **bad** if Spends is ever
    handed to non-technical users).
- **Managed-for-all** (one shared key behind a proxy, like BragBuddy's plan): only worth the
  server + abuse-control + per-user cost **if Spends becomes a product for other people.**
- **Plan:** ship **BYOK now**, but structure the network layer so a **managed proxy can slot in
  later** without a rewrite (same seam BragBuddy uses). Confirm with the owner (this is a
  decision below).

## 2.9 Rough build shape (for future me — not a commitment)

New, all additive, no DB schema change needed:
- `data/ai/GroqClient.kt` — OkHttp POST to Groq's OpenAI-compatible `chat/completions`, JSON
  mode, short timeout, typed errors (reuse the app's existing OkHttp from the Drive layer).
- `data/ai/AiCategorizer.kt` — batch a list of `{id, merchant, kind}` → category ids, mapping
  names back to real ids; only ever called from the review/draft path.
- `data/ai/AiInsights.kt` — build the aggregate payload from `ExpenseDao`, call, cache per cycle.
- `data/ai/AiSettings` — new DataStore prefs: `aiEnabled`, `aiCategorize`, `aiInsights`; key via
  `SecureKeyStore` (device-local, **not** in the snapshot).
- UI: the Settings section, the review-row ✨ chip, the Analytics ✨ card.
- **Review ritual (per `CONTEXT.md`):** 2 adversarial agents (compile + logic/data-safety,
  scanning `app/src/test`) before any tag, with a specific check that **no AI call reaches a
  silent-commit path and no amount is ever AI-derived.**

---

## Decisions — LOCKED (owner, 2026-07-24)

Answered via the interactive question UI. These are now the spec:

1. **Scope: build BOTH** — #1 smart category suggestions **and** #2 spending insights, as one
   opt-in "AI helper" with two independent sub-toggles.
2. **Key model: BYOK** — the owner pastes their own free Groq key in Settings (encrypted,
   device-local, not in backup). Build the network layer so a managed proxy *could* be added
   later, but no server now.
3. **Privacy: both tiers approved** — OK to send **merchant strings** (for #1) and **category
   totals** (for #2). Nothing beyond those two tiers leaves the phone this round.

**Two owner guardrails added to the spec (non-negotiable):**

- **G1 — AI must NOT disturb learned-category pre-selection.** The existing merchant→category
  memory keeps winning outright. **AI is consulted only when a merchant has NO learned mapping
  (and no confident rule match).** It never overrides, re-ranks, or competes with a learned pick.
  Final order: **learned map → AI suggestion (unlearned only) → keyword rules → "Other".** In
  code: in `resolveCategory`, the learned-mapping branch returns *before* AI is ever called; AI
  slots in only on the "no learned entry" fall-through, and still only on review/`allowFuzzy`
  surfaces.
- **G2 — a master AI on/off switch in Settings; OFF = today's exact behaviour.** Turning AI off
  (the default) means zero Groq calls, no ✨ chips, no insights card — byte-for-byte the current
  app. The two sub-toggles and the key field live under this master switch.

**Next:** implement in a fresh build chat (this was research/plan only). Kick off from
`CONTEXT.md` → this doc; honour the release ritual (2 adversarial reviews, never auto-build,
pause between rounds).

---

## Appendix — the exact code seams this plugs into

- **Review-only capture:** `data/capture/SmsCaptureRepository.kt` — `pending_captures` queue,
  `CaptureDraft`, and the `resolveCategory(..., allowFuzzy)` split. **AI attaches only where
  `allowFuzzy = true`** (draft editor, pending queue, scan) — never `captureReturningId` /
  `confirmPending` / `confirmAllPending`.
- **Deterministic amount + confidence:** `data/capture/SmsParser.kt` (`Parsed.confidence`,
  golden-tested) — unchanged; AI runs *after* the amount is fixed.
- **Aggregates for insights:** `data/db/dao/ExpenseDao.kt` — `observeCategorySpend`,
  `kindSumsOnce`/`observeKindSums`, `observeBalanceBefore`. Add a small
  "previous cycle totals" read; no schema change.
- **Merchant learning (categorization complements, doesn't replace):**
  `merchant_categories` table + `data/capture/MerchantKeys.kt`. Order stays: learned map →
  (new) AI suggestion on review → keyword rules → "Other".
- **Opt-in prefs pattern:** `data/settings/SettingsRepository.kt` (`smsCaptureEnabled` /
  `smartCycleEnabled` are the templates). Key at rest: `data/backup/SecureKeyStore.kt`.
- **Existing network + JSON:** OkHttp (Drive layer) + kotlinx-serialization already in the app —
  no new heavy dependency.

**Sources (Groq facts, 2026-07):**
[Groq pricing 2026](https://klymentiev.com/blog/groq-pricing) ·
[CloudZero Groq pricing](https://www.cloudzero.com/blog/groq-pricing/) ·
[Groq free-tier limits](https://tokenmix.ai/blog/groq-free-tier-limits-2026)
