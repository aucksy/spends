# Phase C — judgement calls, without the schema change

Phase C of [`AI-INSIGHTS-PLAN.md`](AI-INSIGHTS-PLAN.md). Builds on Phase A (the carousel + engine) and
Phase B ([`AI-INSIGHTS-PHASE-B.md`](AI-INSIGHTS-PHASE-B.md)).

**Owner decisions, 2026-07-27 (AskUserQuestion), in the order they were made:**

1. **Needs vs Wants (card 11) is DROPPED.** The owner chose to skip the feature outright.
2. **Savings opportunities (card 13) was approved with mitigations** — a deliberate, scoped loosening of the
   "never give financial advice" rule shipped in v1.56.0 — and *"savings suggestions stay at CATEGORY level,
   never merchant level"*.
3. ❌ **Card 13 was then DROPPED too**, at the end of review round 3, once the cost of making it safe was
   measurable. See [Why card 13 was dropped](#why-card-13-was-dropped) — the section that used to specify it.

**So v1.62.0 ships two new kinds, not four**, and the never-advise rule survives Phase C **intact and
without an exception**. That is a better outcome than the plan's, and it was not the plan's.

## ⭐ The headline consequence: no DB change in this phase

Card 11 was the *only* schema touch in the entire AI-Insights plan
([`AI-INSIGHTS-PLAN.md`](AI-INSIGHTS-PLAN.md#L84)). Dropping it means:

- **DB stays at v16.** No `MIGRATION_16_17`, no `needsWants` column, no backup-snapshot field.
- **No rollback trap.** The owner can install this build and drop back to v1.61.0 without clearing data —
  which matters, because v1.57.0–v1.61.0 have still never been on a device.
- `SpendsDatabase.version`, `DatabaseModule.addMigrations(...)` and `Snapshot.kt` are all untouched. A
  release check greps for exactly that.

---

## ❌ The weekend habit — built, reviewed eleven times, and dropped

**Owner decision after review round 11.** The card would have said *"About 46% of your Food spending lands
on weekends, which are 29% of the days."* It is not in v1.62.0, and the reasoning is worth keeping because
the same trap is waiting for anyone who tries again.

**The problem was never per-category measurement.** Phase B deferred this card believing it needed a
needs/wants split; that diagnosis was wrong, and measuring per category did fix the dilution. What it could
not fix is that **a category of fixed monthly bills is arithmetically indistinguishable from a weekend habit
using charge counts alone.** A charge anchored to a day of the month lands on a Saturday or Sunday about two
times in seven, so over six cycles a "Bills" category drifts onto weekends often enough to clear every share
and lift bar — and because rent and EMI are large, the false card **out-ranked the genuine one on
materiality**, so it was the card the user actually saw.

Four gates were added over four rounds, each one closing the previous counterexample:

| gate | closed | left open |
|---|---|---|
| per-category measurement | dilution by rent/EMI | rent firing on its own, 11.6% of anchor/start combinations |
| `weekendWindows ≥ 4` (recurrence, not presence) | one fixed date | **two** dates, 9.6% — each date is an independent chance |
| weekend **charge**-share lift | equal-money coincidences | the same two-date class, 4–6.9% |
| `charges ≥ weekendWindows × 3` (volume) | — | **arithmetically inert for N ≥ 3**: N bills produce exactly `6N` charges, and the requirement tops out at 18 |
| `weekendWindows ≥ 5` (round 10) | N = 2 completely (the volume bar becomes 15; two bills can only make 12) | **N ≥ 3 still fires** — 1.0% at three bills, 1.4% at four |

⭐⭐⭐**The decisive fact was not the residual — it was that the measurement kept being wrong.** Three
consecutive rounds, the sweep written to verify a fix reported **0.00%** and a reviewer enumerating the same
space properly found the card firing on real users' rent and EMI. Each sweep tested a handful of chosen
due-date shapes rather than enumerating them; the failing shape was always one that had not been generated.
That is the same methodological failure recorded twice already in this document for the commitments
denominator — *a sweep is only evidence if its generator can actually produce the failing shape* — and by the
third occurrence it stopped being a bug in a sweep and started being a signal about the card.

**What a future attempt needs.** Not another proxy gate. The app already knows which charges a recurring
rule produced (`InsightsProvider` loads the rule set for the commitments card), so the honest version
excludes rule-explained charges from the weekend split entirely rather than trying to out-guess them with
counts. Alternatively, gate on **distinct weekend days-of-month**: N fixed bills land on at most N, a real
habit lands on many. Either way, verify by **exhaustive enumeration of due-date sets across real calendars**,
never by a sample.

---

## What ships

Two new `InsightKind`s across two cards (12a and 12b).

| Kind | Card | Sentence |
|---|---|---|
| `COMMITMENTS` | 12a | "The monthly recurring payments already running come to ₹28,400 — about 32% of the ₹90,000 that usually comes in each cycle." |
| `SAVINGS_RATE` | 12b | "You've kept ₹54,000 of what came in so far — 57%, ahead of the 41% you'd usually have kept by this point." |

**Two of the four planned kinds were built and then removed**, and nothing of either remains in the code:
`SAVINGS_OPPORTUNITY` (the advice card, dropped by the owner on 2026-07-27) and `HABIT_WEEKEND` (dropped
after review round 11 — see below). No kind, no detector, no constants, no payload keys, no calendar
plumbing, no disclosure text.

### `COMMITMENTS` — real amounts only, and a denominator that cannot be partial

Sum of **active, EXPENSE, MONTHLY, `intervalCount == 1`, already-started** recurring rules, compared against
**the median income of the user's completed cycles**.

Weekly, daily and yearly rules are **deliberately excluded**. Folding a ₹12,000 annual premium in as
"₹1,000 a cycle" would put a figure on the card that the user has never paid, which is the one thing this
engine does not do. The payload key is `monthlyRulesAlreadyStartedTotalAmount` and the fallback text says
*"the monthly recurring payments already running"* — an assertion about the rules the user actually created
and has begun paying, not a claim to have found all their fixed costs. A commitment paid by SMS-captured
debit with no rule behind it is not counted, and the wording never implies otherwise.

#### ⭐⭐⭐ The denominator: seven rounds, and the lesson that ended them

**Owner decision, 2026-07-27.** The card originally divided by *income so far this cycle*. That is a full
month's commitments over a part month's income, and **no gate built from history can make it honest**,
because nothing in the past bounds what this cycle will finish at. Every round closed one hole and the next
found another:

| round | the gate | how it failed |
|---|---|---|
| 3 | none | a ₹10,000 side payment on day 3 → "commitments are 80% of your income" |
| 4 | day floor + 80% of the **day-aligned** usual | circular for anyone paid late: usual income by day 12 is near zero |
| 4 | whole-cycle yardstick, best-to-worst ≤ 3× | too weak (a third of gig/commission cards 10+ points out, worst **99% quoted vs a true 46%**) *and* too expensive (one month logged at ₹4,999 kept the card; ₹5,000 lost it) |
| 5 | the two **middle** cycles ≤ 25% apart | fixed the cliffs, still left 6–7% misleading |
| 5 | arrived bar 80 → 95 | bounds `income / prior median`, never `income / this cycle's final`. Worst case +15 → +3 **only when the priors resemble this cycle** |
| 6 | the user's own usual **arrival share** ≥ 95% | defeated in two lines by base-plus-late-supplement income — a tutor whose exam classes bill on day 27, a salaried user with a quarterly incentive: **+17 and +18 points**. It also removed up to **77%** of cards from anyone paid in instalments, and **94–100% of those cards had been honest** |

⭐⭐**Four sweeps were run across those rounds and every one was unsound, differently.** (1) compared the
quoted share against the *mean* of prior cycles — not what the card claims; (2) used a **lump** arrival
model, in which income-so-far is either near zero (rejected) or complete (no error), so it structurally
cannot exhibit the failure; (3) used smooth accrual but **narrow uniform** ranges, which cannot produce a
cycle far above the prior median, and reported 0.0% where the real rate was 20–63%; (4) used an arrival
model in which a cycle's arrival share cannot differ from the user's usual one, which is precisely the
assumption the gate under test was making. **A sweep is only evidence if its generator can produce the
failing shape** — and when the measurement keeps having to be rebuilt to see the next defect, the quantity
being measured is the problem.

**So the question was narrowed instead of gated**, the same move Phase A made when it stopped scaling
baselines. Both figures are now stable and real: the sum of the user's own already-started monthly rules,
and the median of their own completed cycles' income. Neither moves with the day of the month, so **four
gates were deleted with the old denominator** — the day floor, the arrived bar, the arrival gate and
`wholeCycleComparable` — and with them an entire class of defect. A test pins the property directly: the
card built on day 3 and on day 28 of a live cycle must be *identical*, and identical again with
`wholeCycleComparable` false.

`cycleComplete` was **restored** in round 8, and the distinction is worth stating because it looks like a
regression and is not. It is an **epoch** gate rather than a timing one: nothing here moves with the day
within a cycle, but the numerator is today's rule set while the denominator is the cycles preceding
whichever cycle is on screen — so browsing back would assert today's commitments against somebody else's
history, in the present tense. The same test asserts the card is absent there.

**The honest cost:** this is no longer news about *this* cycle. It reads the same until the user's rules or
their income change. That is the trade, and it is the right one — a card that says a true thing quietly
beats a card that says an interesting thing wrongly.

#### ⭐⭐ Round 8: the floor must TRIM, not FILTER

The first version of the rewrite filtered every sub-₹5,000 cycle out and took the median of what remained —
**the median of the user's *good* cycles.** That is the self-selecting-baseline defect this document already
records and fixes for `SAVINGS_RATE` ("Overspent cycles now stay in the median"), reintroduced on a different
card. It produced the worst sentence this card has ever been capable of:

> A user who lost their job three cycles ago, whose three most recent complete cycles logged **₹0**, was told
> their ₹28,400 of commitments was *"about 32% of the ₹90,000 that usually comes in each cycle."*

A seasonal earner whose lean months run ₹4,000 got the same sentence, while in half their cycles those
commitments are seven times that cycle's entire income. And the cliff was ₹1,000 wide: move the lean months
to ₹5,000 and the filtered version behaved correctly — the same shape of threshold cliff round 4 rejected,
inverted and pointing the dangerous way.

The list is newest-first, so **trailing ZERO** buckets are cycles from before the app was installed; those
are absence of data and are dropped (`dropLastWhile { it == 0L }`). Everything else stays, wherever it sits,
because a real lean or empty cycle is exactly what makes "usually" untrue. The median must then clear the
floor on its own. A stricter "every cycle must clear the floor" rule was tried and rejected: it kills the
ordinary case of one month where the salary was never logged.

⭐⭐**The trim is on `== 0L`, not on "below the floor", and round 9 is why.** Trimming everything sub-floor
asserts that a lean stretch 4–6 cycles ago is pre-install data — which the numbers cannot support, since a
₹0 bucket and a ₹4,000 cycle are indistinguishable only in the first case. Worse, shortening the list moved
the lower-middle median onto a good cycle, so whether a seasonal earner got a false card came down to a
**phase offset**: `LLLGGG` was silenced while `LGGLLL` — the same four lean cycles reordered — still announced
"32% of ₹90,000" to someone whose commitments are 710% of a lean cycle's income. Enumerating all 64
orderings: sub-floor trimming fires on 34 of 64 with a worst case of 4 lean cycles of 6; zero-only trimming
fires on 22 of 64, worst 2 of 6. **All the committed fixtures behaved identically under both rules** —
enumeration caught this, not the tests, which is why a fixture now pins the predicate itself.

Round 8 also found the card **completely dead in production under Smart Cycle**. Round 5 had made the income
query conditional on `wholeCycleComparable`, reasoning that both cards it fed bailed on that flag — true
then, and false the moment this rewrite dropped the flag. `priorFullCycleIncomeMinor` silently arrived as all
zeros and every Smart Cycle user lost the card, **with the engine tests still green, because none of them
runs through `InsightsProvider`.** An optimisation justified by a gate in another file is one edit away from
being a silent feature deletion.

⚠ **Accepted residual: income that STOPS is only caught after three cycles.** Three complete cycles at ₹0
put the median at ₹0 and the card falls silent (pinned by `a job loss is never described as a usual
income`). **Two** do not: someone unemployed for their last two complete cycles, with six cycles of history,
is still told ₹28,400 is *"about 32% of the ₹90,000 that usually comes in each cycle."* It is the same
median-lag mechanism as the pay cut below, but where a cut bounds the error at ~31 points, income going to
zero does not bound it at all. The obvious code fix — requiring the most recent surviving cycle to clear the
floor — re-breaks `one month with no logged income does not cost the card`, so this is documented rather
than gated.

⚠ **Accepted residual: a pay CUT lags by up to three cycles** (counting the cycle the cut lands in — the same convention used for the raise below). A median of six takes three cycles to move, and
the spike gate measures the *best* cycle against the median, so it cannot see a fall. One or two cycles after
a ₹90,000 → ₹45,000 cut the card still quotes "32% of ₹90,000" when the truth is 63% of ₹45,000 — about 31
points of **understatement**, the direction that makes commitments look more affordable than they are. The
third cycle still quotes it; the spike gate fires from the **fourth**, and the card stays quiet until the new
level becomes the median — which is what "up to three cycles" in the heading means. (This paragraph said
"from the third" until review round 15, disagreeing with `InsightEngine.kt` and with the enumeration.) A
recency gate was tried and rejected: it kills the "one odd low month must not cost the card" case bought in
round 4.

⚠ **And a pay RISE lags longer, by more.** Round 9 measured it: after a ₹90,000 → ₹1,35,000 rise (+50%,
exactly at the spike bar, so the gate never sees it) the card quotes 32% against a true 21% for **four**
cycles — and where commitments equal the old usual income, 100% against a true 67%, an **overstatement of 33
points**. That is longer and larger than the cut's three cycles at −31. It is the conservative direction
(commitments look *less* affordable than they are), which is why it is accepted rather than fixed — but the
earlier claim that "a raise is the safe direction" was doing more work than it had earned.

Gates: ≥1 qualifying rule · rule has already **started** (compared as calendar days, because the date picker
anchors a chosen day at local noon — an instant comparison drops a rule dated today until lunchtime) ·
commitments ≥ ₹2,000 · the viewed cycle is not a finished one (an epoch gate, not a timing one — the rules
are today's while the income is the cycles before whichever cycle is on screen) · ≥3 prior full cycles after
trimming trailing all-zero buckets · the median of those ≥
₹5,000 · best prior cycle ≤ 1.5× the median · commitments ≤ that median. Eight checks in all, each with a
fixture that pins it alone — plus two guards on the trim itself: one that goes red if it becomes a filter
again, and one that goes red if it starts dropping sub-floor cycles rather than only zeros.

### `SAVINGS_RATE` — day-aligned, for the same reason everything else is

Naively, "you've kept 57% of your income this cycle" is a trap identical to the rent-on-day-1 bug: salary
lands on day 1 and spending accumulates, so on day 3 every user has kept ~90% of their income and the
figure decays all cycle. Reporting it flat would flatter for three weeks and then quietly stop.

So it is measured the way `PACE` is: **this cycle's kept-so-far against the median kept-by-this-point of
prior cycles**, using the real `CycleUtils` boundaries. Both sides are real figures over equal-length,
day-aligned windows.

This needs income bucketed by prior window, which the existing queries do not provide (`categorySlicesOnce`
is `kind = 'EXPENSE'` only). Phase C therefore adds **one** additive read-only DAO query,
`incomeChargesOnce(start, end)` — dated income amounts over the history span, bucketed in memory by the same
`InsightWindows` machinery. It is called only from `InsightsProvider`, which is unreachable with the AI
helper off, so **G2 still holds: no extra query runs when the master switch is off.**

⭐**A review sweep found the baseline was self-selecting.** The prior windows were filtered on
`incomeMinor > expenseMinor`, which silently discarded every cycle the user overspent — so "the share you'd
usually have kept" was the median of only their good cycles. For real shares `[50, 45, 40, -20, -30, -10]`
the true median is −10%, but the filter produced 45%, and a cycle at **+35%** — far better than this user's
usual — was reported as *behind* usual. Overspent cycles now stay in the median; the card is simply
suppressed when the honest baseline is not a positive share, rather than being shown against a doctored one.

⭐⭐**Review round 3 replaced the spread gate, which failed in both directions.** A min-to-max bar of 40
points was meant to stop the lower-middle median from flipping the headline word. It did not: shares
[15, 17, 19, 40, 42, 44] span only 29 points, yet the two middles are 19 and 40, so the baseline reads 19%
when the real centre is near 30% and a cycle at 25% is announced as *ahead* of usual when it is behind. It was
also too expensive: [20, 35, 38, 41, 45, 70] spans 50 points and was silenced outright, though its middles are
three points apart and 38% is an entirely honest "usual" — one splurge and one windfall cost that user the
card for the other four cycles.

What misleads is not a wide range, it is an **ambiguous middle**. The gate is now: if the two middle values
differ by more than `SAVINGS_MIN_POINTS`, the choice of which one to take — not the data — is deciding what
the card says, so say nothing. An odd-length list has one true middle, so it can never fire there.

⭐**And the order matters.** The old spread gate returned *before* the negative-baseline rule, so round 1's
regression guard for that rule stopped executing its own branch — a guard that guards nothing. The
negative-baseline check now runs first, and a test pins each rule on a fixture the other cannot reach.

⭐⭐⭐**Review round 12: the same trim-vs-filter defect as the commitments card, on this one.** The prior
windows were selected with `.filter { it.incomeMinor >= ₹5,000 }`. The comment above it justified dropping a
window because *"a cycle with no recorded income says the salary wasn't logged"* — which covers an **empty**
window and nothing else. A window carrying ₹1,000–₹4,999 of genuinely logged income was silently deleted
before the median was taken, so "the share you'd usually have kept" was measured over the user's good windows
only.

A commission earner whose real day-aligned kept-shares ran **[50, 40, 40, −733, −1000, −500]** had the three
lean windows removed and was told *"you've kept ₹9,000 — 20%, behind the 40% you'd usually have kept by this
point."* Their honest median is −500%, and the `baselineShare <= 0` rule below would have silenced the card
outright — except the baseline was doctored before that rule could see it. Measured two ways — an
enumeration of all 32,768 six-window configurations, and 200,000 random trials with a generator built to
carry the ₹1,000–₹4,999 income band — **47% of fired cards** were speaking against a baseline drawn only
from the user's good windows.

> This paragraph said **29%** until review round 14, while `InsightEngine.kt` said 47% for the same claim.
> An independent re-run of the 200,000-trial sweep returned 47.4% (15,660 cards fired under the old
> predicate, 8,239 under the shipped one), so the code comment was right and this file was wrong. Fourth
> time this file has carried a figure nobody could reproduce — **state the generator next to the number, or
> do not state the number.**

The predicate is now `> 0L`, matching what the comment always claimed it did. **This is the second time this
exact defect has shipped into review** — round 8 on the commitments denominator, round 12 here — so it is
worth naming as a rule rather than an incident: *a floor that removes observations changes what the average
of those observations MEANS. If a threshold is protecting against absence of data, test for absence
(`== 0`, `> 0`), never for smallness.*

⭐⭐**Review round 14: `SAVINGS_MIN_POINTS = 8` was pinned against deletion but not against its VALUE.**
Both gates it feeds — the ambiguity bar and the difference bar — went red when deleted, so every round up to
13 recorded them as protected. But changing the constant to 5, 7, 9 or 12 turned **no committed test red**,
while changing who gets a card and what it says. Twelve lines of KDoc justify exactly 8 and the
`differenceBar >= ambiguityBar / 2` invariant, and nothing held it there.

This is the same defect round 12 found on the commitments spike bar — which is now fully pinned (25 / 49 /
51 / 100 / 200 all go red). The fix was applied to one constant and not to its twin, in the same file, in
the same phase. Two fixtures now bracket each role from both sides:

| role | fires | silent | kills |
|---|---|---|---|
| ambiguity | middles 8 apart (30/38) | middles 9 apart (30/39) | 5, 7 · 9, 12 |
| difference | 8 points off usual (48 vs 40) | 7 points off (47 vs 40) | 9, 12 · 5, 7 |

Ported and executed before CI: 8 is the sole survivor. **Deletion-testing a gate does not test its
threshold — a constant is only pinned when a fixture sits on each side of it.**

⭐**Review round 14 also found `an empty cycle produces nothing` was tautological.** `detect()` returns early
on `expenseMinor <= 0`, which silences COMMITMENTS too — a card that reads no expense figure at all. That is
deliberate and commented, but the test named for it passed with the gate deleted, so nothing pinned it: a
user paid on day 1 with four already-started rules and a ₹90,000 usual income would have read *"…about 32%
of the ₹90,000 that usually comes in"* on a morning with no spending on the screen at all. Now fixtured.

⭐⭐**Review round 4 found the card could tell the user they had more money than they do.** `kept` is
`income − expense`, but the two sides were measured on different bases: income is the screen's headline
total (every income row), while the expense side is the **categorised** total, because that is the only basis
the prior windows can be built from. Pace survives the same gap because it compares expense against expense,
so the bias cancels. This card does not merely compare — it **states `kept` as a rupee amount** and sends it
as `keptSoFarAmount`. With ₹10,000 of uncategorised spend it read *"You've kept ₹60,000 — 67%"* when the
true figures were ₹50,000 and 56%.

The share would still have been defensible; the amount would not, and the amount is what the sentence leads
with. So the card now refuses to speak unless the headline and categorised totals agree **exactly**. In
practice they always do — every write path creates allocations summing to the full amount, and split entry is
gated on a zero remainder — so this costs nothing today and fails closed if that ever changes.

⚠ Accepted asymmetry: the equality is checked on the current window only.

⚠ **Accepted residual (round 10): a FUTURE-DATED entry inflates `kept`.** `savings.current` comes from the
whole-window on-screen totals while the prior windows are truncated to the elapsed stretch, and the entry
screens place no upper bound on the date. Log a ₹40,000 invoice dated the 25th on day 12 and the card reads
*"you've kept ₹1,00,000 — 77%"* against an honest ₹60,000 and 67% — with the word "so far" in the sentence.
Inherited from `pace`, but this is the first card to state it as a rupee amount. **It cuts both ways** — a
future-dated *expense* deflates `kept`: the same day 12, logging ₹40,000 of rent dated the 25th reads
*"you've kept ₹20,000 — 22%, behind the 40% you'd usually have kept"* when the honest figures are ₹60,000
and 67%, i.e. comfortably ahead. The honest fix is a `selectableDates` bound on the entry screens; that
changes the whole app's input rules and belongs in its own round, not smuggled into an insights release. The prior windows are assumed to
match, because the provider never queries a headline total per historical window.

Gates: `wholeCycleComparable` · day ≥ 5 · cycle not complete · headline expense total **equals** the
categorised total · current income ≥ ₹5,000 · kept > 0 · ≥3 prior windows with income **> ₹0** · honest
baseline share > 0 · the baseline's two middle values differ by ≤ 8 points · kept-share differs from baseline
by ≥ 8 points.

<a id="why-card-13-was-dropped"></a>
### ~~`SAVINGS_OPPORTUNITY`~~ — why card 13 was dropped

The one place the "never give advice" rule was to be deliberately loosened. It was built, reviewed three
times, and then removed. This section is the record of why, because the reasoning cost more than the card
was worth and the next person to propose this feature should read it first.

**Three mitigations were designed in, and all three worked.** The suggestion was arithmetic over figures the
engine had already produced; the typical charge was the **median**, a real observation, never the mean; and
the projection was computed on device and phrased as arithmetic rather than instruction. None of those were
the problem.

**The problem was targeting.** Without card 11's needs/wants flag, the card had to guess which categories a
person *chooses* rather than *owes*, and every mechanism tried was wrong in a way that mattered:

1. ⭐**A ratio gate is free.** "The typical charge is a small share of the total" sounds like a test for
   little-and-often spending. With eight similar charges the median is 12.5% of the total *by arithmetic
   alone*, so a 15% bar rejected nothing. Eight ₹20,000 rent instalments sailed through.
2. ⭐**A price ceiling is not an obligation test.** At ₹2,500 the card fired on rent paid in instalments,
   school fees, fuel, childcare and **dialysis** — *"3 fewer sessions would be about ₹6,000"*. Dropping the
   bar to ₹800 did not fix it, because **Indian obligations are frequently small *and* repeated**: pharmacy
   runs for a chronic illness (10 × ₹550), subsidised dialysis (10 × ₹800), per-day creche billing
   (20 × ₹500), per-class tuition (16 × ₹400), a school van (22 × ₹150), daily-billed PG rent (30 × ₹300).
   No threshold separates those from a coffee habit, because on the numbers they *are* a coffee habit.
3. ⭐**A representativeness gate was needed too**, and found a mirror failure: twelve ₹500 charges plus one
   ₹1,00,000 charge gave a median of ₹500 (0.5% of the total, so the ratio waved it through) and the card
   read *"₹1,06,000 across 13 transactions, typically ₹500 each"*. Both figures true; together a lie.
4. ⭐⭐⭐**A deny-list of obligation words failed in round 3.** Asking "is this an obligation?" means firing
   on everything the list has not thought of — and for a user base naming categories in Hindi, in
   abbreviations and by brand, that is almost everything. A sweep found it still firing on `Meds`, `Physio`,
   `Lab tests`, `Dr Sharma`, `Ambulance`, `Dawai`, `Auto`, `Metro`, `Kirana`, `Sabzi`, `Doodh`, `Tiffin`,
   `Hostel mess`, `Labour`, `Cattle feed`, `Seeds`, `Fertiliser`, `Petrol` — and `Fuel`, which round 1 had
   named explicitly. **26 of 28 realistic categories got through.** A farmer's inputs, a commuter's fare, a
   patient's medicine.

**The last version standing was an ALLOW-list**, which inverts the failure mode so an unrecognised category
means silence rather than advice. It was safe. It was also, measurably, not a feature:

> ⭐**Spends seeds nineteen expense categories. The allow-list could match exactly three of them** —
> `Entertainment`, `Shopping`, `Subscriptions`. Not `Food`, not `Groceries`, not `Travel`, not
> `Personal Care`, not `Fitness`. And `Food` is the app's own default category for eating out, which is the
> case the plan's illustration was written about (*"Food delivery came to ₹9,400 across 18 orders"*). A user
> who never renamed their categories would have got this card only for shopping, streaming and nights out.

Shown that number, the owner dropped the card. The judgement is defensible either way, but the *cost side*
of it — a permanent loosening of a shipped privacy-and-conduct promise, a "not financial advice" line in the
Play listing, and an allow-list nobody maintains — was being paid for three categories.

**What was learned, and is worth keeping:**

- A price threshold is not an obligation test. Where a false positive costs something no disclaimer covers,
  the default must be silence, which means an allow-list, not a deny-list.
- An allow-list narrow enough to be safe may be too narrow to be worth shipping. Measure it against the
  actual category names the app creates before deciding — that check took two minutes and settled the
  question after three review rounds had failed to.
- **The proper fix was always card 11's needs/wants flag**, which would have made this the user's judgement
  rather than a keyword list's. If this card is ever revisited, it should arrive with that flag, or with a
  device-local set of user-ticked categories — which needs no migration at all.

### ~~`HABIT_WEEKEND`~~ — dropped after review round 11

Specified and built; **not shipped**. The full record of why, and what a future attempt would need, is in
[❌ The weekend habit](#-the-weekend-habit--built-reviewed-eleven-times-and-dropped) above. Nothing of it
remains in the code — no kind, no detector, no constants, no calendar plumbing, no payload keys.

---

## Carousel capacity

Phase B settled on six pages (summary + five findings) with slots reserved so anomalies could not take them
all. Two new kinds into five slots would mean half of Phase C is built and never seen — the exact failure
the Phase B slot reservation was introduced to prevent.

So: **`MAX_FINDINGS` 5 → 6** (seven pages), and a third family.

| Family | Slots | Kinds |
|---|---|---|
| `ANOMALY` | 3 | unusual · quiet win · outlier · duplicate |
| `OVER_TIME` | 2 | pace · year-on-year · trend · payday |
| `JUDGEMENT` | 2 | **commitments · savings rate** |

One cap inside the slates, for variety rather than correctness:

- **At most one whole-cycle card** (pace / year-on-year / savings rate) — all three answer "how does this
  cycle's total compare", so by materiality they would take every over-time and judgement slot between them.
  Phase B introduced this for pace vs year-on-year; savings rate joins the same cap.

Reserved slots total 7 against `MAX_FINDINGS` 6, so the lowest-ranked reserved card is trimmed by the final
`take`. That is deliberate: it lets a cycle with nothing to say in one family give its slot to another.

Two slots for a family with only two members is generous, and knowingly so: the savings rate is additionally
capped against pace and year-on-year by the whole-cycle rule, so `JUDGEMENT` usually delivers **one** card.
The reservation exists so that the one it does deliver cannot be crowded out by three anomalies, which by
rupee impact would otherwise always win.

---

## Privacy delta, and the six-file sweep

Two new classes of figure leave the phone, both aggregates:

| New in the payload | Why it is needed |
|---|---|
| Monthly recurring commitment total + rule count | `COMMITMENTS` |
| Income kept so far, as an amount and a share, against the **share** usually kept by this point | `SAVINGS_RATE` |
| The **median income of the user's completed cycles** — the commitments denominator | `COMMITMENTS` |

⚠ Phase C does **not** send this cycle's income total. That has left the phone since v1.56.0 via the summary
card; what is new here is a median over *completed* cycles, which is a different figure and is disclosed as
one. An earlier version of this table and of `InsightNarrator`'s KDoc said "income totals", and that
imprecision propagated into all six surfaces before round 13 caught it.


⭐**Dropping card 13 shrank this delta.** Per-category **transaction counts** and the **median transaction
amount** were its payload and no longer leave the phone at all. The disclosure sweep therefore had to *remove*
those two lines from all six files after they had already been added — a disclosure that over-claims is still
a wrong disclosure.

Still never sent: SMS bodies, merchant names, account or card numbers, last4, individual transaction rows,
transaction dates, balances.

⚠ **Income is a new *class* of figure on the insights path.** v1.56.0's summary card already sent
income/expense totals, so this is not a first — but commitments and the savings rate make income
load-bearing, and the disclosure must say so plainly rather than relying on the old wording.

Per the standing rule, all six must be updated and re-checked for absolutes: the in-app AI explainer **+**
first-enable dialog (`ui/settings/AiSettingsScreen.kt`), `docs/index.html`, `play/DATA_SAFETY.md`,
`play/PERMISSIONS_DECLARATION.md`, `play/listing/store-listing.md`, `README.md`. (Six files, seven surfaces
— the explainer and the consent dialog live in one file and must be checked separately.)

**This sweep also had to check a second class of claim.** Every previous sweep hunted privacy absolutes
("never", "only", "entirely on-device"). Card 13 would have falsified a different kind of promise — any text
saying Spends does not suggest what to do with your money, gives no advice, or only describes. Both greps
were run, twice: once to add the advice disclosures while the card existed, and once to take them out again
when it was dropped. With card 13 gone **every "no advice" claim in the six files is true**, and the app now
says so positively rather than by omission.

---

## Test plan

`InsightEngineTest` gets a detector block per kind, plus:

- **Liveness assertions on every new branch.** `assertTrue(all.any { it.kind == COMMITMENTS })` before
  asserting anything *about* the commitments card. Phase B found two "regression guards" whose branches
  never executed; a test that cannot fail guards nothing.
- **Directional invariants.** A savings rate above baseline must never render "behind".
- **Normalisation refusal.** A yearly ₹12,000 rule must produce **no** commitments contribution, proving
  the engine never divides it into a monthly figure.
- **No carve-out survives.** `InsightNarrator.SYSTEM` must forbid giving advice, suggesting less spending
  and proposing an action — all three, because that is what the disclosure claims of every card — must not
  contain any of five known carve-out phrasings ("only exception", "one exception", "except for",
  "except when", "unless the"), and must not name the dropped kind or its payload key. The same three
  prohibitions and the same five phrasings are pinned on `AiInsights.SYSTEM`, the page-1 prompt, which
  had no test of any kind until review round 16. A dangling permission
  for a kind that no longer exists would fail nothing and would be inherited by the next card added.
- **Every gate is mutation-tested.** For each threshold, a fixture sized so that gate is the *only* thing
  that can reject it — then the gate is deleted and the test must go red. Round 4 found three fixtures that
  were shadowed by a neighbouring gate and reshaped them; the whole judgement block was ported to Python and
  executed, gate by gate, before CI ever saw it.
- **Payload naming.** `InsightNarratorTest` asserts each new kind's keys by name, and that `putFigures`
  still has no `else` branch — the generic fallthrough was the original over-claim bug.
- **Aggregates-only.** The existing payload test is extended to the new kinds.

Every new assertion is hand-executed before CI. Phase B's round found three of mine that would have turned
CI red, and a Python port of the pure engine caught what reading the code did not.

### ⭐⭐ Rounds 20–21: a test that cannot fail is worse than no test

Round 20 asked, for the first time, not *"is every gate mutation-tested?"* but *"does every test actually
fail on the defect its own name describes?"* — and the answer was no, in three places. Round 21 widened
that sweep to all 113 AI-insights tests and found more. The worst example had been green for nineteen
rounds:

`months with no spend in a category do not drag its usual figure down` guards a real rule — a month with
no Travel spending must be DROPPED from the baseline, not counted as ₹0. Its fixture was four steady
₹10,000 windows plus two empty ones. Because `median` takes the LOWER middle, the honest rule reads
`sorted[1]` of `[10k,10k,10k,10k]` = ₹10,000 and the zero-substituting bug reads `sorted[2]` of
`[0,0,10k,10k,10k,10k]` = **also ₹10,000**. Identical under both. The comment even claimed the bug "would
put the median at ₹5,000", which no median rule can produce. It looked right, so nobody executed it.

**The rule: mutation-test the DEFECT THE NAME DESCRIBES, not only the gate the code contains.** A fixture
whose two branches agree is a green light wired to nothing.

Also fixed in these rounds: `a card is taken whole or not at all` fixtured only one operand of a
two-operand `&&` (the blank-BODY half could be deleted with every test green, after which a heading
renders over an empty card); two prohibitions in `InsightNarrator.SYSTEM` were pinned by nothing at all;
`OUTLIER_CHARGE` — one of only TWO kinds that send a single charge's amount off the device — had a single
positive test and ALL FOUR of its gates deletable — round 21 said three, and round 22 found the fourth
survived too, rendering "about 2147483647× your usual ₹0" once removed; and the payload boundary had one
unpinned level of nesting on
each side (a new ROOT key on the narrator payload, a new field on a `byCategory` object in the summary
payload).

### ⚠ Known test-durability backlog, deliberately NOT fixed in v1.62.0

All Phase A/B detector coverage, all predating this release, none affecting a number v1.62.0 shows — the
shipped engine is correct and every ported fixture reproduces it. Recorded here so they are not lost:

| gate | what a user would be told if it were deleted |
|---|---|
| `PACE_HIGH` / `PACE_LOW` (1.25 / 0.75) | *"Day 12 and ₹52,000 spent. Your recent cycles were at ₹50,000 by this point"* — a 4% difference gets a card |
| `YOY_MIN_MINOR` (₹3,000) | *"₹8,000 so far this July, against ₹5,500 last July"* on a ₹2,500 gap |
| duplicate `merchantKey != null` | two hand-entered cash rows become *"2 charges of ₹600 landed on the same day"* |
| `MIN_DUPLICATE_MINOR` (₹200) | two ₹5 metro taps become a card |
| movers' `MIN_MATERIAL_MINOR` | *"Stationery is ₹40 ahead of where last cycle stood"* |
| `now <= 0` on pace / year-on-year | *"Day 12 and ₹0 spent"* under a donut showing ₹50,000 |
| `TREND_MIN_MINOR` / `TREND_MIN_FRACTION` | mutually shadowing — the concept is covered, neither constant is |

Plus one name/body mismatch (`the whole-cycle comparisons stay silent when the totals are not comparable`
names three kinds and tests two) and one decorative assertion (`InsightNarratorTest`'s per-index kind
check cannot fail, since `pair` copies the kind from the finding being indexed).
