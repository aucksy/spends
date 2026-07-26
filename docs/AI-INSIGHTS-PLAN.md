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
| 11 | **Needs vs Wants** | "55% needs, 25% wants, 20% saved" | a needs/wants classification — see below |
| 12 | **Commitments & savings rate** | fixed recurring load as a share of income; how much of this cycle you kept | recurring rules |
| 13 | **Savings opportunities** | "Food delivery came to ₹9,400 across 18 orders — four fewer a month is about ₹2,100" | see the advice note below |

Cards only appear when their finding clears a materiality threshold, so the carousel is never padded with
"nothing much happened" — which is the actual complaint being fixed.

---

## Three decisions that need calling out

### 1. Needs vs Wants needs a real classification, and that means a schema change

There is no honest way to split needs from wants without knowing which is which. Asking the model to guess
per cycle would make the same category flip sides between refreshes.

Proposal: a `needsWants` column on `categories` (`NEED` / `WANT` / `SAVING`), seeded with a sensible default
(Rent, Utilities, Groceries, Health, Loan/EMI, Transport → need; Food, Entertainment, Shopping, Travel,
Subscriptions, Gifts → want; Investments → saving), user-overridable in Settings → Categories.

That is **DB v16 → v17**, plus a migration, plus a backup-snapshot field. It is the only schema touch in the
whole plan and it lands in Phase C, so Phases A and B stay schema-free.

### 2. "Savings opportunities" is financial advice, and the current prompt forbids it

The shipped system prompt says *"Never give financial advice, warnings, or predictions."* Card 13 — and
arguably card 7 — cross that line deliberately. That is a reasonable call for a personal app, but it is a
guardrail being loosened on purpose, not overlooked.

Mitigations: suggestions must be **derived from a computed finding** (never generic money advice), phrased as
arithmetic rather than instruction, and the section carries a plain "not financial advice" line.

### 3. Savings opportunities at category level, not merchant level — to hold the privacy line

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

### Phase B — comparisons over time
Cards 7–10. Needs longer history windows and a `monthlyCategoryTotals` one-shot query.
- Year-on-year needs 13+ months; the card stays hidden until there is enough history to be honest.

### Phase C — judgement calls
Cards 11–13. Carries the DB v16→v17 change, the needs/wants editor in Settings → Categories, the prompt
change, and the full disclosure sweep listed above.

### Phase D — the dedicated AI section
- A full **Insights** screen (its own route, reachable from the Analytics carousel's "See all" and from
  Settings → Automatic Entries → AI helper): every card at full size, grouped, with the cycle selector.
- Per-card dismiss that sticks for that cycle, and pinning.
- Optional: a "what changed since you last looked" entry point.

---

## Open questions for the owner

1. Should the carousel **replace** the single insights card on Analytics, or sit alongside a "See all"
   entry into the dedicated screen? (Proposal: replace, with "See all" as the last page.)
2. How many cards on Analytics before it becomes a scroll of its own — cap at 5 and put the rest in the
   dedicated section? (Proposal: yes, cap at 5, ranked by materiality.)
3. Needs vs Wants: accept the default classification above, or set it during a short one-time setup?
