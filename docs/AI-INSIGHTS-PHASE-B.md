# AI Insights — Phase B: comparisons over time

Phase B of [`AI-INSIGHTS-PLAN.md`](AI-INSIGHTS-PLAN.md). Builds directly on the Phase A engine shipped in
v1.60.0; read that plan's "one architectural rule" first, because nothing here relaxes it.

> **Every number is computed on the device by the pure `InsightEngine`. The AI only turns finished figures
> into English.** Phase B adds four detectors and one query. It adds no arithmetic to the model's job.

**No schema change** (still DB v16), no snapshot change, no manifest change, no new dependency.

---

## The four new cards

| Kind | Says | Silent unless |
|---|---|---|
| `PACE` | "Day 12 of the cycle and ₹24,100 spent. Your recent cycles were at ₹19,000 by this point — ₹5,100 ahead." | day ≥ 5, cycle unfinished, ≥3 prior cycles with data, ≥₹2,000 apart, ≥1.25× or ≤0.75× |
| `YEAR_ON_YEAR` | "₹30,000 so far this July, against ₹20,000 over the same stretch last July — ₹10,000 more." | records reach back past the year-ago window, that window holds ≥₹5,000, ≥₹3,000 apart, ≥1.15× or ≤0.85× |
| `CATEGORY_TREND` | "Dining ran about ₹4,500 a cycle earlier over the last 6 cycles, and about ₹6,300 a cycle lately." | present in ≥5 of 6 cycles, ≥₹1,500 and ≥20% apart, least-squares slope agrees with the halves |
| `HABIT_PAYDAY` | "About 40% of your spending lands in the seven days after payday, which is only 23% of the cycle." | cycle starts on the salary day, ≥4 cycles with data, ≥₹30,000 total, ≥25% share, ≥1.35× its share of days |

Card 1 (the v1.56.0 cycle summary) and the Phase A cards are unchanged.

### What is deliberately NOT here

- **Month-on-month.** Already said twice: the summary card on page 1 is given the previous cycle's total and
  mentions it, and the Phase A `MOVER_UP`/`MOVER_DOWN` cards are month-on-month per category. A third card
  restating it would be padding, and "a card must earn its place" is the rule that made Phase A worth
  shipping. Year-on-year is the genuinely new comparison, so that is what got built.
- **The weekend habit.** Across *all* categories the weekend signal is structurally diluted by rent, EMI,
  insurance and school fees, which land on fixed days and never at weekends. An all-categories weekend
  comparison therefore needs either a threshold so low it fires on noise, or one so high it never fires. The
  honest version looks at discretionary spending only — which is exactly the `needsWants` classification
  arriving in **Phase C**. Deferred there rather than shipped weak.

---

## Where each figure comes from

```
Room ─┬─ categorySlicesOnce(current window)          ─► current charges
      ├─ categorySlicesOnce(6 windows back)          ─┬► day-aligned buckets  (pace, unusual, quiet win)
      │                                               ├► FULL-cycle buckets   (category trend)
      │                                               └► dated charges        (payday habit)
      ├─ earliestExpenseOccurredAtOnce()              ─► "was SPENDING really being logged then?"  ← NEW
      └─ categorySlicesOnce(same stretch, −1 year)    ─► year-ago total                          ← NEW
                                    │
                          InsightEngine (pure) ──► findings ──► one Groq call ──► cards
```

The six-cycle history is still **one** read, now bucketed three ways in the same pass. Phase B's only new
reads are the two marked above, and `loadYearAgo` runs the second only when the first says the records
actually reach back. Everything stays behind the existing AI gate, so **G2 is intact: with the AI helper off,
not one of these queries runs.**

### Day-aligned vs full cycles — two bucketings, on purpose

Phase A's `InsightWindows.bucketIndex` compares the first N days of this cycle against the first N days of
each earlier one. That is right for *"how are we doing so far"* and wrong for *"is this category drifting"*:
a six-cycle trend measured on cycles all truncated to today's day-of-month would partly be measuring the
truncation. So `InsightWindows.fullBucketIndex` was added — the same boundaries without the day-alignment
gate — and the trend and habit detectors read complete cycles while pace reads day-aligned ones.

The full buckets are **not** filtered for empties: the trend detector reads them positionally, so a cycle
with no spending has to stay in place as a gap rather than close up and pretend the series is continuous.

---

## ⭐The blocker the review caught: prior cycles are not a fixed span apart

The first cut of Phase B inherited Phase A's way of walking back through history — subtract the **current
cycle's length** six times. That looks equivalent to "the last six cycles" and is not: real cycles are 28, 30
or 31 days, so each synthetic boundary slides further off the real one the further back you go. February
alone drags them days adrift.

The consequence is the Phase A lesson arriving through a different door. Salary day 1, rent ₹25,000 on the
1st, today is the 12th:

> "Day 12 of the cycle and ₹36,000 spent. Your recent cycles were at ₹11,000 by this point — ₹25,000 ahead."

They were at ₹36,000 too. The drifted windows put each earlier month's rent near the *end* of its window,
past the point this cycle has reached, so it dropped out of the baseline — while this cycle's rent was still
counted. An adversarial sweep of 96 anchor × month combinations found **39 that drift**. The payday habit
broke the same way: the "seven days after payday" measured over history were, for some months, a different
seven days entirely, while the card's sentence still named payday.

The fix is that the caller now passes the **real** cycle boundaries, produced by `CycleUtils` — the same
machinery that drew the window on screen — and `InsightWindows` has no fixed-span entry point left to reach
for. `InsightsProvider` **fails closed**, returning no cards at all, if `cycleBoundaries[0]` does not equal
the window it was asked about. One fix repaired pace, the trend and the habit together, and it makes the
Phase A cards more accurate too.

`AnalyticsViewModel.cycleBoundaries` anchors on **the displayed window's own start date**, not on today's
date replayed through the cycle offset. `windowFor(w.start, anchor).start == w.start`, so
`boundaries[0] == windowStartMillis` holds by construction rather than by agreement. That matters twice over:
it stops this having to mirror `PeriodResolver`'s anchor rules (a duplicated invariant that could drift), and
it closes a hole the delta review found — `resolvedFlow` recomputes its `today` only when the selection,
settings, earliest day or cards change, so an app left open overnight carries yesterday's window into the
next morning. A fresh clock here would have disagreed with it, the fail-closed guard would have fired, and
the user would have silently lost the **entire** carousel — Phase A's cards included — for one day every
month. Failing closed was right; the input was wrong.

---

## The traps, and what was done about them

**Never scale a comparison into a figure nobody spent.** The Phase A lesson holds throughout. Pace's baseline
is a median of real day-aligned totals. Year-on-year compares the same elapsed stretch, not a pro-rated year.
The trend reports the median of the older three cycles against the median of the recent three — both amounts
actually paid — and the least-squares slope is used **only as a gate on the sign**, never reported. The habit
card states two shares rather than a per-day average, because an average daily spend is a number nobody spent.

**Every reported figure must be one that was really spent — including the median.** The textbook median
averages the two middle values of an even-length list, and six cycles of history is the *ordinary* case, so
pace's baseline was routinely a figure no cycle ever reached while the card asserted "your recent cycles
**were at** ₹19,000 by this point". `median()` now takes the lower of the two middles: always a real
observation. Stated honestly rather than dressed up, the side effect is that a baseline at or just below the
true median makes the *rise* tests marginally easier to trip and the *fall* tests marginally harder. The
shift is bounded by the gap between the two middle values, is zero when they are equal (the common case),
and the absolute rupee floors are what stop it mattering.

**Every figure is named for what it actually is.** See the payload section below — this is the thread that
ran through four review rounds, and `putFigures()` is where it was finally cut.

**An empty year-ago window is not a frugal month.** If the app simply was not in use a year ago, *"you spent
₹24,100 more than last July"* is not unflattering, it is false. Three gates:
`earliestExpenseOccurredAtOnce()` must predate the window — the earliest **expense**, not the earliest row,
because an old income entry or opening balance says nothing about whether purchases were being logged; the
window must carry charges on at least 5 separate days, so one stray back-dated transaction cannot defeat the
first gate permanently and turn the card into "this month's spending against last year's *logging*"; and the
window must hold ≥₹5,000. It also waits until day 5, for the same reason pace does — on day 2 it compares a
two-day stretch against a two-day stretch and one rent payment on either side clears every floor.

**A completed cycle has no "so far".** Stepping back to a finished cycle leaves `now` far past its end. The
provider passes an explicit `cycleComplete` flag rather than testing `daysElapsed >= cycleDays`, because
`daysElapsed` is clamped and so cannot tell the **last day of a live cycle** from a cycle finished months ago
— the arithmetic version silently threw away day 30 of every cycle. Pace stays quiet on a finished cycle;
year-on-year still shows (the comparison holds) but drops the "so far" wording, and the payload carries
`cycleStillRunning` so the model does not reinstate it.

**Smart Cycle compares two definitions of the same cycle.** With the card-billing-aware Smart Cycle the
on-screen totals bucket a card purchase into the cycle its statement *bills*, while the history queries read
raw transaction dates. A per-category card needing a 2× swing tolerates that as an approximation; pace at
1.25× and year-on-year at 1.15× compare whole totals and would be quoting one definition at the other, so
`wholeCycleComparable = false` silences exactly those two there. The trend and habit cards read history on
both sides and are unaffected.

**A calendar year is not 365 days.** `oneYearEarlier` uses `minusYears(1)`, so a leap year does not slide the
comparison window a day out of step — enough drift to move one rent payment across the boundary.

**A cycle that straddles two months has one honest name.** 25 June → 24 July has 24 of its 30 days in July
and its owner calls it the July cycle, so `cycleMonthLabel` takes a midpoint rather than the start date. But
it is the midpoint of the **elapsed stretch**, not of the whole cycle: the full-cycle midpoint of 25 July –
25 August is 9 August, so on the 27th of July the card read *"₹4,000 so far this August"*. Measuring what has
actually happened names July on day 3 and rolls over only once most of the elapsed days really are in August.

**"The week after payday" has to actually be that.** Gated on the window *starting* on the user's salary day
(`AnalyticsViewModel.paydayAligned`), checked against the window itself rather than the cycle type. A Smart
Cycle pinned to a different reset day, a Month view for someone paid on the 25th, and a salary day of 31
clamped to the 28th in February all correctly answer "no" and lose the card, instead of describing the first
week of an arbitrary window as a payday habit. Habits are measured over **complete prior cycles only** — a
part-finished cycle contributes its whole payday week but only some of its ordinary days, which by itself
would manufacture a payday habit for every user from day 8 onwards.

**A cached card can go stale on the clock, not just on the data.** Pace says "day 12". Opening Analytics a
week later having added nothing would otherwise serve the cached card still claiming day 12, so the cycle-day
(clamped, so a finished cycle keeps one stable fingerprint) is now part of `insightFingerprint`.

**Comparing like with like.** Pace and year-on-year sum `current` — the categorised per-category totals the
history buckets are also built from — not the screen's headline expense figure. Using a total that can
include uncategorised spend against a baseline that cannot would put a permanent thumb on the scale.

---

## Sharing out the five slots

`MAX_FINDINGS` went 4 → 5 (owner decision: six carousel pages including the summary), and the ranking gained
a reservation: **up to 3 anomaly cards, up to 2 over-time cards**, then backfill in plain rank order.

Ranking purely by rupee impact sounds right and does the wrong thing here. Anomalies are measured in whole
category swings (tens of thousands); a habit is measured in percentages. Without a reservation the four new
card types would be built, tested, shipped — and never once seen by anyone whose cycle contains two
anomalies. The reservation is what makes the carousel actually vary, which was the original complaint.

Within the over-time slots, **at most one whole-cycle comparison** (pace *or* year-on-year). Both answer "how
does this cycle's total compare", so by materiality they would take both slots and the trend and habit cards
would never appear. That cap has to hold in the **back-fill** as well as in the slate — the first cut enforced
it only in the slate and then re-admitted the skipped card while filling spare slots, so a quiet cycle showed
"₹5,100 ahead" on one page and "₹19,470 less" on the next about the same total. `InsightEngineTest` pins all
three properties: that a cycle rich in anomalies still yields exactly 3 + 2, that the reservation does **not**
shrink the carousel when there is nothing over-time to put in it, and that the two whole-cycle cards can never
both survive.

The two **charge-level** cards — a duplicate, and one outsized charge — now rank above "a category is up".
With only three anomaly slots, the old order pushed the ₹12,500-charge-at-6×-normal card off the carousel
entirely whenever two categories ran hot, and that is the card most likely to surface an actual billing
mistake.

A `CATEGORY_TREND` for a category that already has an "unusual"/"quiet win" card is dropped, joining the
movers in `RESTATES_A_JUDGED_CATEGORY` — one big cycle drags the recent half of the series up, so an unusual
category is usually "trending" too, and the carousel would say the same thing three ways.

---

## What newly leaves the phone

Still aggregates, and still computed first. The findings payload gains:

| Field | Example | Note |
|---|---|---|
| `dayOfCycle` | `12` | a count, not a date |
| `month` | `"July"` | a month name — owner decision, see below |
| `cycleStillRunning` | `true` | so the model doesn't write "so far" about a finished cycle |
| `amountThisStretch` + `sameStretchLastYearAmount` + `timesLastYear` | `30000.0`, `20000.0`, `1.5` | one stretch, and the same stretch a year back |
| `recentPerCycleAmount` + `earlierPerCycleAmount` + `cyclesCompared` | `6300.0`, `4500.0`, `6` | per-cycle medians, neither from the viewed cycle |
| `paydayWeekShareOverEarlierCyclesPercent` + `shareOfCycleDaysPercent` + `cyclesMeasured` | `40`, `23`, `6` | describes *when in the month* the user spends |

Never: SMS bodies, merchants, account or card numbers, last4, individual rows, or transaction dates. The
month name and the day-of-cycle count are the **only** date-shaped values.

### ⭐Every figure is named for what it actually is

This took four review rounds to get right, and the lesson generalises. The payload started with generic keys —
`amount`, `usualAmount`, `timesUsual`, `count`, `sharePercent` — applied to whatever each card happened to
carry. Each round found another where the name was a claim the engine had never made, each was fixed
individually, and the next round found the next one:

- `timesUsual` on the payday card was one percentage divided by another (38% of the money ÷ 23% of the days).
  An obedient model writes *"about 1.7× your usual"* about a card with no amount in it.
- `usualAmount` on a **mover** was a single previous cycle. *"Well above your usual ₹9,000"* to someone whose
  usual was ₹18,000.
- `usualAmount` on the unusual/quiet/pace cards was measured **to the same day** of earlier cycles. On day 5
  that is five days' worth, offered as "your usual".
- `amount` on the trend card was a per-cycle median over months the user isn't looking at — it read as
  *"Dining is at ₹6,300 now"* on a screen whose donut showed Dining at ₹0.
- `count` meant charges on one card and categories on another; `amount` on the concentration card was the
  top-three subtotal, not the cycle total; `usualAmount` on the outlier card was a typical *charge*, not a
  typical month.

The generic `else` branch was the defect: it silently applied "usual" to whatever arrived. `putFigures()` is
now a single `when` over all twelve kinds with no fallthrough, so the complete payload is auditable in one
place. **The rule it encodes: the model is told to use only the figures it is given, so a key name is not a
label — it is an assertion the app makes about the user's money.** "Usual" means a median over six cycles and
nothing else; anything measured to today's day-of-cycle says `ByThisPoint`; a per-cycle average says
`PerCycle`; a typical charge is not a typical month. The system prompt gained a matching clause telling the
model to read those names literally.

**Page 1 also needed the two gates the finding cards already had.** Using real boundaries there means
deriving them from the **device timezone**, while `windowStartMillis` was resolved in whatever zone was in
force earlier and is not recomputed when the zone changes — so flying west made the two disagree, and page 1
would have compared against a cycle two months out (the arithmetic it replaced could not fail that way).
`buildInsightPayload` now makes the same `boundaries[0] == windowStartMillis` check `InsightsProvider` makes,
and drops the comparison rather than guessing. And `?: 0L` — which turned "no records" into "₹0 spent" — told
every brand-new user *"₹45,000 this cycle, well above last cycle"* about a month Spends did not exist for
them. That is the falsehood year-on-year carries three gates against; page 1 had none, since v1.56.0.

**And the same fixed-span defect was alive on page 1.** The cycle-summary card shipped in v1.56.0 has its own
payload builder, which never went through the Phase B fix: it took "last cycle" as `windowStart − span`. With
a salary day of 1, viewing February, that window began on 4 January — so January's rent fell outside it and
page 1 read *"you spent ₹25,000 more than last cycle"* when nothing had changed. It also had two guards the
finding cards were given and it was not: in **Single-Card** mode it compared one card's on-screen spend
against every instrument's previous window, and under **Smart Cycle** it compared billing-bucketed totals
against raw-date history. It now uses the real boundaries and drops the comparison entirely in both modes.
`AiInsights`'s prompt also gained the "use only the figures given — never subtract" clause the narrator
already had, because a card handed both cycles' totals and asked to note a change is an invitation to compute
the difference, which would break the on-device promise on the one card everybody sees.

The same defect existed in the reply-pairing guard. Cards were matched to findings by `kind`, but a normal
carousel carries two to four `UNUSUAL_CATEGORY` findings, so a kind-only check passes for *every* permutation
of them — a dropped or reordered card would put one category's heading over another's numbers, the exact
failure the echo was added to prevent. The model now echoes `category` too, matching is on both, and a card
is taken whole or not at all rather than field by field.

**Owner decisions (AskUserQuestion, 2026-07-27):**
1. **Carousel size:** 6 pages, with the guaranteed mix above.
2. **Month names:** send them. Year-on-year reads "less this July than last July" rather than "than the same
   month last year". Groq can infer the month from the request timestamp anyway; the point is that it is now
   *disclosed* rather than incidental.
3. **The v1.60.0 carry-over** ("a large charge" / "charged twice?" sending one charge's amount + category):
   **left as is**. The rupee figure is what makes the card actionable, and it was already disclosed.

Disclosure sweep done in all six mandated places: the in-app explainer + first-enable dialog
(`ui/settings/AiSettingsScreen.kt`), `docs/index.html`, `play/DATA_SAFETY.md`,
`play/PERMISSIONS_DECLARATION.md`, `play/listing/store-listing.md`, `README.md`. The share-of-spending-after-
payday figure is called out explicitly as being about habits rather than totals — it is the most personal
thing in the payload and burying it would be the wrong call.

---

## The advice guardrail is still shut

The shipped system prompt still forbids financial advice, warnings and predictions. Phase B adds one clause —
*never project a figure forward or say what a total will reach* — because pace is precisely the card that
invites "at this rate you'll spend ₹X". Loosening the guardrail is **Phase C's** decision, and it is the
owner's to make.

---

## Files

**New:** `data/ai/insights/InsightCalendar.kt` (pure; `DatedCharge`, `HabitBuckets`, `YearAgoWindow`).
**Changed:** `InsightFinding.kt` (4 kinds, 4 fields, fallback wording), `InsightEngine.kt` (4 detectors, slot
allocation, `slope`), `InsightWindows.kt` (`fullBucketIndex`), `InsightsProvider.kt` (bucketing, year-ago
read, habit buckets), `InsightNarrator.kt` (payload fields, baseline key naming, one prompt clause),
`ExpenseDao.kt` + `ExpenseRepository.kt` (`earliestExpenseOccurredAtOnce` — the `kind = 'EXPENSE'` filter is
load-bearing; the unfiltered `observeEarliestOccurredAt` would silently defeat the gate), `AnalyticsViewModel.kt`
(`paydayAligned`, cycle-day in the fingerprint).
**Tests:** new `InsightCalendarTest`; extended `InsightEngineTest`, `InsightWindowsTest`, `InsightNarratorTest`.

## Accepted residuals

- **Daylight saving.** Window starts are derived by fixed-millisecond subtraction (inherited from Phase A's
  windowing) and then converted to dates in the device zone. In a DST zone the one-hour shift could move a
  window boundary across a calendar date, making one window measure 29 or 31 days and moving a charge in or
  out of the payday week. The effect is a percentage point or two on the habit card's day share, never a
  wrong rupee figure — and it is zero in `Asia/Kolkata`, which has no DST. Fixing it properly means moving
  the whole windowing system onto calendar arithmetic, which is a much larger blast radius than the defect.
- **The year-ago gate costs one aggregate per build**, for the users who can reach it: `MIN(occurredAt)` runs
  on every carousel build where a year-on-year card is possible at all — so not under Smart Cycle, where the
  whole `loadYearAgo` call is skipped. Only the range scan is further conditional. Cheap and indexed, and it
  is the only honest gate: nothing about the calendar tells you whether records exist.
- **Page 1 is the one card with no structural check on what the model wrote.** The finding cards cannot state
  an uncomputed figure — the pairing guard binds each card to its finding, and a failure falls back to a
  template built from the engine's own numbers. The summary card has no echo, no pairing and no template; it
  rests on its prompt alone. The "use only the figures given — never subtract" clause narrows that and cannot
  close it. Worth knowing when reading page 1 against the rest of the carousel.
- **A device-zone change can skew the older boundaries by an hour or two.** The fail-closed guard compares
  `boundaries[0]` against the window, so a move between two zones that agree on the window start but differ
  further back (London↔Reykjavik, New York↔Santiago) passes the check while `boundaries[1..6]` sit 1–2 hours
  out. Worst case, a sliver at a cycle edge lands in the adjacent baseline window — immaterial against floors
  of ₹1,500 and up.
- **⚠ Back-dated edits go stale, on every card that has a baseline — page 1 included.** Stated properly,
  because the first draft of this bullet understated it twice. **Every card except concentration and
  duplicate-charge** — and page 1's "vs last cycle", which goes stale the same way, since a previous-cycle
  edit doesn't move `state` and so never changes the fingerprint — draws
  its baseline from rows *outside* the viewed window — the unusual/quiet medians, the outlier's typical
  charge, the movers' last-cycle bucket (all Phase A), plus all four new cards. The cache fingerprint covers
  only the viewed window's totals, categories, day and settings. So a back-dated edit — a late receipt, a
  spreadsheet import, a restore from Drive, a trash restore — changes what those cards *should* say without
  changing the fingerprint, and the cached carousel survives until the refresh button is pressed. This is not
  new in Phase B and it is not "one clause on one card", which is what the Phase A note implied. Folding a
  history signal into the fingerprint needs a query, and the fingerprint is computed in a flow transform that
  cannot suspend; the honest options were "do it properly later" or "overstate the guarantee", and this is
  the first.
- **PACE, YEAR_ON_YEAR and CONCENTRATION quote categorised spend**, summed from the same basis the history
  buckets are built from, while the screen's headline expense total is a separate sum. They are equal
  whenever every transaction carries a category — which every entry path enforces — but they are not the
  *same field*, so a transaction that somehow reached the ledger with no allocation would put a slightly
  smaller figure on the card than the tile above it (and would overstate the concentration percentage).
  Comparability was the right thing to preserve; this is the price.
- **A category rename** now moves the fingerprint (the name is part of it), but a rename that *merges* two
  categories still leaves the previous grouping cached until a total changes or the user refreshes.
- **A stretch straddling 29 February** gets a year-ago window one day longer or shorter, because *both* ends
  take `minusYears(1)` and a leap day exists on only one side. Calendar alignment is the defensible reading
  of "the same stretch last July" — the alternative reintroduces the fixed-span drift — and the difference is
  one ordinary day's spend against a ₹3,000 / 1.15× gate.
- **Page 1's "vs last cycle" is dropped in Single-Card and Smart-Cycle views**, so the disclosures' "and the
  one before it" now reads "and, in most views, the one before it". The Play files deliberately keep the
  unqualified wording — over-declaring is the correct posture in a compliance document.
- **A Smart-Cycle user with no cards** (or no card billing days) has on-screen totals identical to raw-date
  history, so pace and year-on-year are suppressed there for nothing. The error direction is safe — a card is
  lost, never a wrong figure — and the alternative is a condition that has to stay in step with
  `SmartCardCycle`'s shift rule forever.
- **`refreshInsights` reads settings twice** — once for the fingerprint, once inside `buildCards`. A DataStore
  write landing between them would cache under a fingerprint that doesn't quite describe the settings that
  produced the cards. Microsecond window, cosmetic consequence, left alone rather than churn a hot path at
  the end of a review round.
- **Category names are user-chosen free text and are sent verbatim** on the insight cards, exactly as
  category totals always have been. Someone who names a category after something private is naming it to the
  AI helper too. The disclosures say category names are sent; they do not warn about this specifically, and
  the sub-toggle means insights can be on with suggestions off.
- Phase A's Smart Cycle residual still stands for the per-category cards: the on-screen figures bucket a card
  purchase into the cycle its statement bills while the history query reads raw date windows, so a historical
  comparison there is a close approximation. Current-cycle figures are always exact, and the two whole-cycle
  cards now stay silent there entirely rather than approximate.

---

No test asserts that the **demo dataset** produces any particular card. Demo volume is generated from weighted
random day-picking, so such an assertion would be a coin flip and a latent red build — the Phase A lesson.
Detector behaviour is pinned against hand-built fixtures whose arithmetic is provable instead.
