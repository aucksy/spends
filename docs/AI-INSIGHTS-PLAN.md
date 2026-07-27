# AI Insights v2 — carousel, a dedicated section, and insights worth reading

Owner ask (2026-07-26): *"more AI insights in carousel cards format, so I can see dots indicating there
are more cards to swipe… currently it's the same insight every single time… there should be more use for
it."* Plus a named list of insight types, and: *"you can plan in Phases and a dedicated AI section may be
required."*

This is the plan. Nothing here is built yet. Demo mode ships first
([`DEMO-MODE.md`](DEMO-MODE.md)); this follows in phases.

Builds on the v1.56.0 AI helper — see [`AI-RESEARCH.md`](AI-RESEARCH.md) (locked spec) and
[`AI-BUILD-PLAN.md`](AI-BUILD-PLAN.md) (what shipped).

---

## The one architectural rule

**Every insight is computed on the phone first. The AI only turns already-correct numbers into English.**

This is the same seam that makes the existing AI helper money-safe, extended. A "70% cheaper" or a "3× your
average" that the model *invented* would be worse than no insight at all, because it looks authoritative. So:

```
Room ──► InsightEngine (pure, deterministic, unit-tested) ──► List<InsightFinding>
                                                                     │
                                              one Groq call, aggregates only
                                                                     ▼
                                                          narrated card text
```

Consequences that fall out of this, all good:

- **Numbers cannot be hallucinated** — the model is handed them and told to phrase, not to calculate.
- **The payload stays small and aggregate** — the engine sends findings, not the ledger.
- **One call, not N** — all cards come back from a single request, so a carousel costs no more than today's
  single card.
- **A failed call degrades to a plain templated sentence** rather than an empty carousel, because the finding
  itself is the insight; the AI is only the writing.
- **AI off ⇒ nothing runs.** G2 (master switch off = today's app, byte-for-byte) is preserved: the engine is
  only invoked from behind the existing gate.

`InsightEngine` being pure and deterministic also means every detector is unit-testable against fixtures —
and demo mode already ships a dataset with each pattern deliberately planted (see `DemoScript`), so there is
a ready-made end-to-end fixture.

---

## Card types

Card 1 stays the cycle summary that exists today. The rest are new.

| # | Card | What it says | Needs |
|---|---|---|---|
| 1 | **Cycle summary** | today's card, unchanged | — |
| 2 | **Unusual spending** | "Business Expenses are ₹38,050 this cycle — about 5× your usual ₹7,400" | 6 months of category history |
| 3 | **One-off outliers & duplicates** | "Today's fuel charge is 3× your normal average" / "Two identical ₹1,240 BookMyShow charges on the same day" | transaction-level scan |
| 4 | **Quiet wins** | "Entertainment is well below your usual — about ₹4,100 less" | 6 months of category history |
| 5 | **Biggest movers** | which categories rose and fell most vs last cycle | previous cycle |
| 6 | **Concentration** | "3 categories account for 68% of everything you spent" | current cycle |
| 7 | **Pace** | "12 days in and ₹24,100 spent — ahead of your last three cycles at this point" | prior cycles |
| 8 | **Month-on-month / year-on-year** | "You spent ₹6,200 less this July than last July" | 13+ months |
| 9 | **Category trends** | "Dining is up about 22% over six months" | 6 months, linear fit |
| 10 | **Habit discovery** | "You spend nearly 40% more in the week after payday" | dates + salary day |
| 11 | ~~**Needs vs Wants**~~ | ❌ **DROPPED** by the owner, 2026-07-27 — never built | would have needed a needs/wants classification — see below |
| 12 | **Commitments & savings rate** | already-started monthly rules as a share of what a cycle USUALLY brings in; how much of this cycle you kept | recurring rules |
| 13 | ~~**Savings opportunities**~~ | ❌ **DROPPED** by the owner, 2026-07-27 — built, reviewed three times, then removed | see the advice note below |

Cards only appear when their finding clears a materiality threshold, so the carousel is never padded with
"nothing much happened" — which is the actual complaint being fixed.

---

## Three decisions that need calling out

### 1. Needs vs Wants needs a real classification, and that means a schema change

> ❌ **Settled 2026-07-27: the owner dropped card 11 outright, so none of the schema work below was done.**
> The database stays at **v16**. There is no `needsWants` column, no `MIGRATION_16_17` and no snapshot
> field. The reasoning below is kept as the record of why it would have cost a migration.

There is no honest way to split needs from wants without knowing which is which. Asking the model to guess
per cycle would make the same category flip sides between refreshes.

Proposal: a `needsWants` column on `categories` (`NEED` / `WANT` / `SAVING`), seeded with a sensible default
(Rent, Utilities, Groceries, Health, Loan/EMI, Transport → need; Food, Entertainment, Shopping, Travel,
Subscriptions, Gifts → want; Investments → saving), user-overridable in Settings → Categories.

That is **DB v16 → v17**, plus a migration, plus a backup-snapshot field. It is the only schema touch in the
whole plan and it lands in Phase C, so Phases A and B stay schema-free.

### 2. "Savings opportunities" is financial advice, and the current prompt forbids it

> ❌ **Settled 2026-07-27, and the answer changed twice.** The owner first approved loosening the guardrail
> with the mitigations below; the card was built and survived three review rounds only by shrinking to an
> allow-list of category names, which meant it could fire on just **three of the app's nineteen seeded expense
> categories** (Entertainment, Shopping, Subscriptions) and never on `Food` — the very example this card was
> written for. Told that, the owner **dropped card 13**. The guardrail is therefore **still shut, with no
> exception**, and `InsightNarrator.SYSTEM` now forbids suggesting less spending outright. Two tests —
> `the narrator prompt still forbids advice outright, with no known carve-out or dropped symbol left in it` and its twin
> `the summary prompt carries every prohibition its disclosure claims` in `AiInsightsPayloadTest` — pin
> all three prohibitions on **both** system prompts, and fail if any of five known carve-out phrasings
> reappears. (Round 20: the narrator test pinned only two of the three until this sentence was checked
> against it. The prompt had always carried the third; nothing held it there.) That is a tripwire for the phrasings we know, not a proof that no carve-out can exist;
> review round 19 renamed the first test because its old name claimed the latter.
>
> **The durable lesson is worth more than the card was.** A price threshold is not an obligation test:
> Indian obligations are frequently small *and* repeated, so ₹2,500 caught dialysis and ₹800 still caught
> pharmacy runs, creche day-billing and a school van. A deny-list of obligation words then missed 26 of 28
> realistic category names — including `Fuel`, which round 1 had reported by name. When a false positive
> costs something no disclaimer covers, only an allow-list is safe, and an allow-list this narrow is not a
> feature.

The shipped system prompt says *"Never give financial advice, warnings, or predictions."* Card 13 — and
arguably card 7 — cross that line deliberately. That is a reasonable call for a personal app, but it is a
guardrail being loosened on purpose, not overlooked.

Mitigations (as proposed, and as built before the card was dropped): suggestions must be **derived from a
computed finding** (never generic money advice), phrased as arithmetic rather than instruction, and the
section carries a plain "not financial advice" line.

### 3. Savings opportunities at category level, not merchant level — to hold the privacy line

> ❌ **Moot: card 13 was dropped on 2026-07-27** (see §2). The constraint below is kept as the record of
> what any future version of this card must respect.

The user's example names a habit ("food delivery"). Doing that by merchant would mean **merchant names and
frequencies start leaving the phone from the insights path**, which today sends only category totals.

Proposal: express it at category level with counts — *"Food: ₹9,400 across 18 transactions"* — which supports
the same sentence without widening what leaves the device. If merchant-level detail is wanted later, that is
a deliberate, separately-disclosed change.

**Privacy delta of this plan as written:** the payload grows from *this cycle + last cycle* category totals to
*up to 13 months* of monthly category totals, plus transaction counts, plus recurring commitment amounts, plus
day-of-week aggregates. Still aggregates only — never SMS bodies, merchants, account or card numbers, last4,
individual rows or dates.

Per the standing rule, any change to what leaves the phone must also update **all** of: the in-app AI
explainer, the first-enable dialog, `docs/index.html` (the live privacy policy), `play/DATA_SAFETY.md`,
`play/PERMISSIONS_DECLARATION.md`, `play/listing/store-listing.md` and `README.md`. These went stale once
before and the Play SMS declaration was wrong as a result.

---

## Phases

Each phase is independently shippable and ends with the full release ritual.

### Phase A — the carousel and the engine
The structural work, plus the cards the owner asked for first.
- `data/ai/insights/`: `InsightFinding`, `InsightEngine`, `InsightNarrator`, detectors for cards 2–6.
- `HorizontalPager` carousel with page dots on Analytics, replacing the single card. Card 1 keeps today's
  behaviour so nothing is lost.
- One Groq call returning a JSON array; cached per cycle fingerprint; templated fallback on failure.
- Tests: each detector against fixtures + the demo dataset; payload asserted aggregates-only.

### Phase B — comparisons over time ✅ SHIPPED — see [`AI-INSIGHTS-PHASE-B.md`](AI-INSIGHTS-PHASE-B.md)
Cards 7–10. Needs longer history windows and a `monthlyCategoryTotals` one-shot query.
- Year-on-year needs 13+ months; the card stays hidden until there is enough history to be honest.

**As built:** `PACE`, `YEAR_ON_YEAR`, `CATEGORY_TREND`, `HABIT_PAYDAY`. Two deviations from the list above,
both deliberate and both explained in the Phase B doc:
- **Month-on-month was not built as its own card** — page 1's summary already carries the previous cycle's
  total, and the Phase A mover cards are month-on-month per category. A third telling would be padding.
- **The weekend habit moved to Phase C.** Across all categories the weekend signal is diluted by rent, EMI
  and insurance, which land on fixed days; the honest version was thought to need a discretionary/needs
  split. ❌ **Neither happened.** Phase C introduced no such split (card 11 was dropped, DB stays v16), and
  the weekend card itself was built and then dropped after review round 11 — per-category measurement did
  remove the dilution, but charge counts still cannot separate standing bills drifting onto Saturdays from a
  person who goes out at weekends. See [`AI-INSIGHTS-PHASE-C.md`](AI-INSIGHTS-PHASE-C.md).

The one query it added is the year-ago read, gated on the records genuinely reaching back that far — an
empty year-ago window means the app wasn't in use, and a card built on it would be false rather than
unflattering. Carousel grew to 6 pages with reserved slots so the over-time cards are actually seen.

### Phase C — judgement calls
Planned as cards 11–13 carrying a DB v16→v17 change. **As built (v1.62.0), it carries no schema change at
all**: the owner dropped card 11 and then card 13, and card 11 was the only schema touch in the entire plan.
DB stays **v16**; there is no needs/wants editor and no prompt loosening.

**As built:** `COMMITMENTS` and `SAVINGS_RATE` only — card 12, split into two cards. Phase B's weekend
carry-over was built, reviewed across eleven rounds and then **dropped**: measuring per category removed the
dilution, but not the thing underneath it, which is that charge counts cannot separate three standing bills
drifting onto Saturdays from a person who actually goes out at weekends. Full detail, and what a future
attempt would need: [`AI-INSIGHTS-PHASE-C.md`](AI-INSIGHTS-PHASE-C.md).

One additive read-only DAO query (`incomeChargesOnce`), the full disclosure sweep, and `MAX_FINDINGS` 5 → 6.

### Phase D — the dedicated AI section
- A full **Insights** screen (its own route, reachable from the Analytics carousel's "See all" and from
  Settings → Automatic Entries → AI helper): every card at full size, grouped, with the cycle selector.
- Per-card dismiss that sticks for that cycle, and pinning.
- Optional: a "what changed since you last looked" entry point.

---

## Open questions for the owner

1. Should the carousel **replace** the single insights card on Analytics, or sit alongside a "See all"
   entry into the dedicated screen? (Proposal: replace, with "See all" as the last page.) — *Phase A
   replaced it; the "See all" entry point is Phase D's to add.*
2. ~~How many cards on Analytics before it becomes a scroll of its own?~~ **Answered (Phase B, 2026-07-27):**
   seven pages — the summary plus six findings — with slots reserved so anomalies cannot take every one.
   (Phase B shipped six pages; Phase C raised `MAX_FINDINGS` 5 → 6 to make room for the judgement slots.)
   Ranking purely by materiality would have meant the over-time cards were never seen.
3. ~~Needs vs Wants: accept the default classification above, or set it during a short one-time setup?~~
   **Answered (Phase C, 2026-07-27): neither — card 11 is dropped**, and with it the only schema change in
   the plan. Card 13 was dropped in the same round once it became clear it needed the same classification.

**Also answered in the Phase B round (2026-07-27):** month names *are* sent (so year-on-year can say "this
July"), and the v1.60.0 carry-over is settled — the "large charge" / "charged twice?" cards keep sending
that one charge's amount and category.
