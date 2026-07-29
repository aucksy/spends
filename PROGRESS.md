# Spends — Live Progress

Live state pointer. Update this at every phase/release boundary. Read `CONTEXT.md` first
for how the project works.

## Current release
- **Shipped: v1.63.3** — versionCode **72**, versionName **"1.63.3"**. The home-screen widget's balance
  now applies carry-forward, via one rule shared with the app instead of a second copy.
  APK: https://github.com/aucksy/spends/releases/download/v1.63.3/Spends-v1.63.3.apk
- Previous: **v1.63.2** — versionCode 71. **The capture bug was found and
  fixed.** A Java-only regex flag, `(?U)`, made `SmsParser`'s object initialiser throw on any real
  Android device; every caller's `runCatching` swallowed it, so live SMS capture had been dead since
  v1.58.0. The same copied line crashed the v1.63.0 debug screen. No DB/schema/manifest change.
  APK: https://github.com/aucksy/spends/releases/download/v1.63.2/Spends-v1.63.2.apk
- Previous: **v1.63.1** — versionCode 70. On-device crash trace + Robolectric render tests. The trace it
  captured is what identified the root cause above.
  APK: https://github.com/aucksy/spends/releases/download/v1.63.1/Spends-v1.63.1.apk

## v1.63.4 — the widget's carry-forward now buckets by billing day

v1.63.3 made the widget apply carry-forward, but computed it **by date** while the app's Smart Cycle
buckets each spend by its card's **billing day**. So on Smart Cycle the two still disagreed — by however
much a single boundary-straddling card charge came to.

The fix is not a new abstraction. `SmartCardCycle.effectiveWindowStartMillis` was already the shared
rule, already used by Analytics, the timeline and the widget's own income/expense totals. Carry-forward
was the one number that wasn't calling it. Now it does, so it agrees with
`TransactionsViewModel.buildStateSmartCard` by construction.

One detail worth keeping: the carry fetch starts **one window before the anchor**. The billing shift only
moves a spend forward, and by at most one window, so a purchase dated just before the anchor can still
bill into a cycle at or after it. Starting the fetch at the anchor itself would silently drop those.

Deliberately NOT done: unifying the app's three summary paths behind one calculator. That would rewrite
working money code in the app to match the side that was wrong — the wrong direction of risk. The
sharing that mattered already existed; only one call site was missing.

## v1.63.3 — the widget's balance ignored carry-forward

The home-screen widget computed `income − expense` and never applied carry-forward, so anyone with the
setting on read **one balance on their home screen and a different one inside the app**, with nothing to
say which was right. The home screen is exactly where a balance gets glanced at without opening anything.

**The fix is one shared rule, not a second copy.** The rule had been written out separately at each place
that needed it — twice in `TransactionsViewModel`, and *not at all* in the widget. That is how the copies
drifted. [`CarryForward.resolve`](app/src/main/java/com/spends/app/core/calc/CarryForward.kt) is now the
single definition and all three call sites use it.

The guards are the load-bearing part, not the arithmetic: carry-forward requires an anchor (without one,
folding in an incomplete history produced the hugely-negative balance this app already hit once); a
window starting before the anchor gets no carry-in; and a caller can opt out entirely, which the
single-card view does because a running whole-account balance is meaningless over one card's statement.
The net is a lambda so each caller keeps its own notion of "before this window" — the plain window uses a
balance-before difference, the salary cycle uses its card-billing-aware bucketing — while the guards
around them cannot diverge.

`CarryForwardTest` pins every guard, that zero and null stay different answers (the UI shows a tile for
one and not the other), that the net is not computed when a guard rejects, and that the widget and app
figures agree *and* differ from the old `income − expense` — so the regression test cannot pass vacuously.

## v1.63.2 — the actual cause, after five releases

**One character class. `(?U)`.**

`(?U)` is a **Java-only** inline regex flag. Android's regex engine is ICU-backed and rejects it, so

```kotlin
private val NUMERAL = Regex("(?U)\\d[\\d,]*(?:\\.\\d+)?")
```

throws `PatternSyntaxException` **while `object SmsParser`'s initialiser runs**. The failure is therefore
an `ExceptionInInitializerError` on the *first touch* of `SmsParser` — not a parse miss — and every call
site wraps parsing in `runCatching`, which swallowed it. Bank texts simply stopped becoming
transactions, with nothing logged anywhere and no crash to notice.

**It entered in `c3ff6a3`, "v1.58.0: money-safety review fixes for the merchant/fuel round".** The owner
independently reported that capture last worked on **v1.57.0**. Those match exactly.

The same line was then copied verbatim into `SmsDebugLog` for v1.63.0 — deliberately, as a privacy
control that must not drift from the parser's — which is why the screen built to *find* this bug was
killed by it on open, before drawing a pixel.

**Why five releases and thirteen review rounds never saw it.** Unit tests and Robolectric both run on
the **JVM**, where `(?U)` is perfectly valid. 195 logic assertions, a full golden-fixture suite, two
adversarial review agents and a headless render pass all stayed green, because none of them ran on
Android's regex engine. The logic reviewer specifically *praised* the flag for covering
Devanagari/Arabic-Indic digits — it validated the semantics of a pattern that cannot compile on the
target platform. The earlier conclusion that "the cause is outside the app" was wrong; the cause was in
the app the whole time, in the one layer no JVM test could reach.

**The fix.** `\p{Nd}` — "any Unicode decimal digit" — which both engines understand and which
`JvmOnlyRegexTest` proves masks *exactly* what `(?U)\d` masked, including Devanagari and Arabic-Indic
samples asserted non-vacuously.

**The guard.** `JvmOnlyRegexTest` scans main source and fails on any `Regex(`/`Pattern.compile(` carrying
an inline flag group containing `U`. It validates its own scanner against both real defect lines before
trusting a clean result, and fails loud if the walk finds no sources. A source scan is the right
instrument precisely because the runtime under test is the one that cannot see the problem.

## v1.63.1 — make the crash speak

**What happened.** v1.63.0 shipped fully green: the signed release APK built, Hilt/KSP codegen ran, 195
logic assertions passed, and two adversarial review agents returned zero blockers. Opening the SMS debug
screen closed the app immediately.

**Why nothing caught it.** No check in this repo had ever *opened* a screen. The unit tests are pure
logic with no Android framework, and Robolectric was not on the classpath at all. Compiling a composable
proves almost nothing about running one — theme lookups, `remember` initialisers, permission reads,
ViewModel `init` and the first composition of every branch happen only when something composes it. A
successful APK build proves the code compiles and the DI graph is *legal*, not that a screen survives
being opened. Those are different claims, and v1.63.0 was shipped on the first while the second was
what mattered.

**What is now in place.**
1. **Robolectric render tests** (`SmsDebugScreenRenderTest`) — compose the real screen on a plain cloud
   runner, no emulator, in three states: ViewModel alone, empty log, and every `Outcome` recorded. Plus
   a **canary test that deliberately crashes a composition and asserts the harness sees it**, so a green
   render result is never trusted before the detector has been shown to work (lesson 4, applied).
2. **[`CrashLog`](app/src/main/java/com/spends/app/core/CrashLog.kt)** — an uncaught-exception handler
   installed first thing in `SpendsApp.onCreate`. It writes the trace to `filesDir` and then hands the
   crash straight on to the previous handler, so nothing is swallowed and the app still dies as Android
   intends. Surfaced on the **Automatic Entries** screen — deliberately the parent, because the screen
   being diagnosed closes the app as it opens.

**The honest part.** The render tests did *not* reproduce the crash: the screen composes cleanly
headlessly, so the fault needs the real app on a real device. That is precisely why the on-device trace
exists rather than another round of reading code. The rule that shipped v1.63.0 — *ship when no finding
touches behaviour, money or a privacy leak* — was the right rule and is unchanged; the gap was that
neither reviewer could see a whole class of defect, and "both agents clean" was reported with more
confidence than the evidence supported.

## v1.63.0 — the SMS capture diagnostic, and two fixes that were losing money

**Why this release exists.** Live SMS capture last worked ~21:00, Sun 26 Jul 2026, on v1.57.0, and died
by v1.62.0. Every code change in that window was traced and eliminated on evidence, so the cause is
outside the app — something on the phone stopped feeding the receiver — and the app had no way to say
which part. This release gives it one. Full reasoning: [`docs/SMS-CAPTURE-DEBUG.md`](docs/SMS-CAPTURE-DEBUG.md);
state and deferred items: [`docs/SMS-DEBUG-HANDOFF.md`](docs/SMS-DEBUG-HANDOFF.md).

**1. SMS debug screen** — Settings → Automatic Entries → Detect from SMS & notifications → SMS debug.
A verdict line, a row per precondition, a capped in-memory log, and a redacted copyable report. The
load-bearing number is **"SMS delivered (this app run)"**: if it stays 0 while texts arrive, Android is
not handing them to the app and nothing in Spends is the cause. Temporary — it is removed once answered.

**2. Two permanent capture fixes.** Neither is the owner's cause; both silently ate money.
- A parsed bank SMS was **discarded** when the review prompt could not be shown. It is now **queued for
  review** instead.
- Both live capture paths were **blind to the "Transaction detection" notification category being off
  on its own**. Now detected and reported.

**3. Honest scan messages.** `scanMessage` / `cardScanMessage` now distinguish "read nothing", "read
plenty, all already known", "couldn't read", and "refused in demo mode". The single old sentence for all
four cost a full round of this investigation.

**Process note — the thing worth remembering.** The previous session ran **thirteen** adversarial review
rounds on a temporary diagnostic and shipped nothing. The review process itself was working (it caught an
OTP leak, a UPI-ID leak and a build-breaking character literal); the **stopping rule** was wrong, because
each fix round can introduce a fresh wording defect, so "clean on both agents" has no bound. The rule
now: **ship when no finding touches behaviour, money, or a privacy leak.** That was true by round 8.
This release ran **one** round against that rule. Wording and test-coverage findings are recorded under
"Known, not fixed" in the handoff doc rather than fixed.

## v1.62.0 — Phase C: two judgement cards (commitments, and what you kept)

Cards that make a *judgement about the shape of your money*, not just a comparison of totals. Full detail,
every threshold, both dropped cards and all twenty-three review rounds:
[`docs/AI-INSIGHTS-PHASE-C.md`](docs/AI-INSIGHTS-PHASE-C.md).

**Shipped: two of the four cards planned.**
- `COMMITMENTS` — the monthly recurring payments already running, as a share of what a cycle usually brings in.
- `SAVINGS_RATE` — what you've kept so far, against the share you'd usually have kept by the same point.

**Owner decisions (AskUserQuestion):**
1. **Card 13 (`SAVINGS_OPPORTUNITY`) dropped** at the end of review round 3 — an advice card whose keyword
   allow-list matched exactly 3 of the 19 seeded expense categories, and which falsified the standing
   disclosure claim that the AI "only suggests a category, describes only".
2. **The commitments denominator changed** after **seven** rounds of failed gates: compare against the
   **median income of COMPLETED cycles**, never this cycle's income-so-far. Narrowing the question deleted
   five gates and an entire defect class at once.
3. **The weekend card (`HABIT_WEEKEND`) dropped** after round 11 — charge counts cannot separate standing
   bills drifting onto a Saturday from a real weekend habit.

**No DB schema change (still v16)**, no snapshot change, no dependency change; one additive read-only DAO
query (`incomeChargesOnce`) returning a plain non-Room projection.

**⭐⭐⭐THE ROUND'S DURABLE LESSON — a floor that removes observations changes what the average MEANS.**
Both new cards shipped the *same* self-selecting-baseline defect, one each, five rounds apart. The prior
windows were selected with `>= ₹5,000`, so a cycle carrying ₹1,000–₹4,999 of genuinely logged income was
deleted before the median was taken — and "what you usually keep" became the median of the user's *good*
cycles only. A commission earner with real kept-shares of **[50, 40, 40, −733, −1000, −500]** was told
*"you've kept 20%, behind the 40% you'd usually have kept"* when their honest median is −500% and the card
should have said nothing. 47% of fired cards were affected. The rule, now written into the spec: **if a
threshold is protecting against ABSENCE of data, test for absence (`== 0`, `> 0`), never for smallness.**

**⭐⭐The second lesson — a sweep is only evidence if its generator can produce the failing shape.** Three
consecutive rounds returned "clean" on sweeps of hundreds of thousands of trials, and all three were
worthless: the generators used a mean-of-priors yardstick, then lump income arrival, then uniform ranges too
narrow to express the defect. A clean sweep is a statement about the generator until proven otherwise.

**⭐⭐The third lesson — deletion-testing a gate does not test its threshold.** Round 14 found
`SAVINGS_MIN_POINTS = 8` feeding two gates that both died when deleted, so thirteen rounds recorded them as
protected — yet changing the constant to 5, 7, 9 or 12 turned no test red while changing who gets a card.
A constant is only pinned when a fixture sits on *each side* of it. Fixtured; 8 is now the sole survivor.

**⭐⭐The fourth lesson, found in round 16 — a disclosure sweep scoped to the phase misses the claims that
are not.** README and `play/PERMISSIONS_DECLARATION.md` claim, of EVERY card, that the model is instructed
not to suggest what to do, tell the user to spend less, or give financial advice, *"with no exceptions"*.
That was literally true of `InsightNarrator.SYSTEM` (Phase A/B) and NOT of `AiInsights.SYSTEM` — the prompt
behind **page 1, the card every user sees**, shipped v1.56.0 — which said only "never give financial
advice" and which no test had asserted anything about in nine months. Fifteen rounds missed it because
every one was scoped to Phase C. The prompt was brought up to the claim rather than the claim down to the
prompt, and both prompts are now pinned by tests. **A public absolute is only as true as its weakest
surface, and the sweep must follow the CLAIM, not the diff.**

**⭐The fifth, round 18 — a prohibition test must assert the prohibition, not the topic.** Making the advice
assertion verb-agnostic (the two prompts word it "Never give" and "Do not give") dropped the negation with
the verb: `contains("give financial advice")` is satisfied by *"You may give financial advice"*. Inverting
the rule left both tests green. Now a verb alternation that keeps the negation, mutation-confirmed to kill
inversion, deletion and appended carve-outs while still tolerating harmonisation.

**Disclosure.** All six surfaces updated and re-checked for absolutes. Two errors in previously shipped
copy were corrected in the process: the published privacy policy said the AI helper "sends only a
number-masked extract" when it also sends the merchant name as the bank wrote it, and
`play/PLAY_SUBMISSION_CHECKLIST.md` §4 told the owner to answer **"No data collected/shared"** on the Play
Data Safety form — false since v1.56.0.

**Review: twenty-three rounds, two adversarial agents each.** The pure engine was ported to Python and executed
rather than reasoned about; every gate on both cards is mutation-tested by deletion, and both roles of
`SAVINGS_MIN_POINTS` by value. 117 unit tests across the three AI suites, twelve of them added during review because a
reviewer proved the existing ones could not catch something.

## v1.61.0 — Phase B: pace, year-on-year, category trends, payday habit
Four new carousel cards, all about *time* rather than this cycle alone. Full detail, every threshold and the
reasoning behind each: [`docs/AI-INSIGHTS-PHASE-B.md`](docs/AI-INSIGHTS-PHASE-B.md).

**Owner decisions (AskUserQuestion):** carousel grows to 6 pages with slots reserved so anomalies can't take
them all · month names ARE sent (so year-on-year reads "this July") · the v1.60.0 carry-over settled — the
"large charge"/"charged twice?" cards keep sending one charge's amount + category.

**Deliberately NOT built, both explained in the doc:** a separate month-on-month card (page 1 and the Phase A
mover cards already say it twice) and the *weekend* half of habit discovery (across all categories the signal
is drowned by rent/EMI/insurance on fixed days; the honest version needs Phase C's needs/wants split).

**⭐⭐THE ROUND'S DURABLE LESSON — the Phase A lesson had a second door.** History was walked back by
subtracting a FIXED SPAN (the current cycle's length) six times. Real cycles are 28/30/31 days, so those
synthetic boundaries slide off the real ones the further back you go — a sweep found **84% of windows wrong**,
worst drift 3 days. Rent on a fixed day then falls outside a drifted window and drops out of the baseline
while THIS cycle's rent still counts: *"your recent cycles were at ₹11,000 by this point"* about cycles that
were at ₹36,000. Fixed by passing the **real** `CycleUtils` boundaries and deleting the fixed-span entry point
entirely. **And the same defect was alive on page 1** (`buildInsightPayload`, shipped v1.56.0, never audited)
— plus two guards the finding cards had and it didn't: Single-Card compared one card's spend against every
instrument's previous window, Smart Cycle compared billing-bucketed totals against raw-date history.

**⭐The other recurring defect: a key name is an assertion.** The model is told to use only the figures given,
so a payload key is not a label. Four rounds each found another over-claim — `timesUsual` on a ratio of two
percentages (40% ÷ 23% = "1.7× your usual" on a card with no amount); `usualAmount` on a MOVER, which is one
previous cycle ("well above your usual ₹9,000" when the usual was ₹18,000); `usualAmount` on the day-aligned
cards, which is five days' worth on day 5; `amount` on the trend card, a per-cycle median from months not on
screen. **The generic `else` branch was the bug.** Now one `when` over all 12 kinds, no fallthrough, auditable
in one place. Also: `median()` averaged the two middle values on an even list — the ordinary case — producing
a "usual" no cycle ever reached; now always a real observation.

**Reviews (ritual honored, and then some): 7 rounds × 2 adversarial agents = 14 passes. Every round found
something real.** Both agents ported the engine to Python and executed it rather than reasoning about it.
- **⭐3 of my own tests would have red-CI'd** (`assertTrue(cycleStillRunning)` on a fixture that never set
  `days`; the old `contains("₹")` assertion against a habit card that carries only percentages). Caught by
  hand-execution, not by review. **The ritual's value keeps coming from running the tests, not reading them.**
- **⭐A "regression guard" that guarded nothing** — the directional-invariant test's MOVER_UP/MOVER_DOWN
  branches never executed, because both fixtures gave the mover's category an anomaly card and
  `RESTATES_A_JUDGED_CATEGORY` stripped it first. Same tautology trap as the Phase A dedupe test. Fixed with
  an un-judged category (Rent) plus explicit liveness assertions.
- **⭐The reply-pairing guard didn't guard.** Cards were matched to findings by `kind` — but a normal carousel
  carries 2–4 `UNUSUAL_CATEGORY` findings, so a kind-only check passes for every permutation. The model now
  echoes `category` too, a *partial* echo is rejected, and a card is taken whole or not at all.
- **⭐New-user falsehood on page 1:** `?: 0L` turned "no records" into "₹0 spent", so a first cycle read *"well
  above last cycle"* against a month Spends didn't exist for. The year-on-year card carries three gates
  against exactly this; page 1 had none.
- **⭐I introduced a timezone bug and the review caught it.** Deriving real boundaries re-reads the device
  zone, while the window was resolved in whatever zone was in force earlier — fly west and they disagree,
  page 1 quotes a cycle two months out and the whole carousel silently vanishes. Now fail-closed on
  `boundaries[0] == windowStartMillis`, the same check the provider makes. Verified over **4.5M** cases:
  0 false passes, 0 false failures, DST-safe.
- Also fixed: a "Thinking…" hang I reintroduced via an unguarded DataStore read; the one-whole-cycle cap
  leaking through the backfill (pace *and* year-on-year about the same total, consecutive pages); the outlier
  card pushed off the carousel by the new slot reservation; ₹0 sent as "absent" so a card carried only its
  baseline; a cache that outlived the setting meant to suppress it.
- **Disclosure sweep, four times.** Six mandated files. Caught: four surfaces (incl. the first-enable consent
  dialog) claiming merchant names are never sent while the *categoriser* sends them verbatim; the Play
  permissions declaration never mentioning that up to 100 learned merchant shortcuts leave; `README`'s "every
  rupee stays on your device" and "100% offline"; `DATA_SAFETY.md` opening with "nothing is sent to any third
  party" and recommending a "No" its own table contradicts; and a paragraph I wrote that denied sending the
  month five lines after disclosing it.
- **Tests:** 78 across 4 files (~1,240 lines) — new `InsightCalendarTest`, rewritten `InsightWindowsTest`
  (real 28/30/31-day cycles) and `InsightNarratorTest` (payload key naming + 6 pairing tests), extended
  `InsightEngineTest`. Both agents confirmed the new guards **fail against the pre-fix code**, so they are
  regression guards rather than decoration.

**Accepted residuals (owner-told, all in the doc):** back-dated edits leave every card with a baseline stale
until refresh (page 1 included — this is bigger than the Phase A note implied); pace/concentration quote
categorised spend; Smart-Cycle-with-no-cards over-suppresses pace/YoY; a leap-day stretch shifts the year-ago
window by a day; a device-zone change can skew older boundaries 1–2 h; page 1 is the only card with no
structural check on the model's output; category names are user-chosen free text sent verbatim.

## v1.60.0 — AI insights carousel (owner-requested 2026-07-26)
Owner: *"more AI insights in carousel cards format… currently it's the same insight every single time."*
The single card becomes a `HorizontalPager` with dots: page 1 is the v1.56.0 summary unchanged, then
findings (unusual category · quiet win · outsized charge · duplicate charge · biggest mover · concentration).

**⭐The architecture rule:** every number is computed on-device by a pure `InsightEngine`; `InsightNarrator`
only phrases finished figures. One Groq call for all cards, run concurrently with the summary call. Any
failure falls back to the finding's own templated sentence — the finding IS the insight.

**Reviews (ritual honored):** 2 adversarial agents → **COMPILE: BLOCKED, LOGIC: NO-GO**; all fixed.
- **⭐BLOCKER** `val width by animateDpAsState(...)` missing the runtime `getValue` import — a hard compile error.
- **⭐BLOCKER ×2** two of my OWN tests would have failed CI. One exposed a real design flaw: the top 3 of 4
  categories are ≥75% of spend by arithmetic alone, so a 55% "concentration" bar fired unconditionally for
  anyone with few categories. Now needs 6+ categories and a 70% share.
- **⭐HIGH — the baseline was a figure nobody spent.** Pro-rating six complete cycles by elapsed time assumes
  uniform spending; rent/EMI/insurance are single fixed-day charges. Day 3 of a cycle → *"Rent is ₹20,000 this
  cycle, against ₹2,000 in a usual one — about 10× as much."* Replaced by **day-aligned windows**
  (`InsightWindows`): the first N days of this cycle vs the first N days of each previous one. The engine now
  does **no scaling at all**. **DURABLE LESSON: never scale a comparison figure into a number the user never
  spent — narrow the question instead.**
- **HIGH** Single-Card mode narrated other instruments' transactions (screen filters to one card, the history
  query didn't) → finding cards suppressed there and for non-cycle ranges. **HIGH** a navigable *future* cycle
  drove the elapsed fraction to its floor → "37× as much" → returns nothing before a cycle starts.
- **⭐HIGH — disclosures no longer matched the payload.** Two cards send an individual charge's amount while the
  policy said "never individual transactions". Corrected in **all six** required files including the three that
  had never mentioned insights at all (store-listing, README, PERMISSIONS_DECLARATION). Also corrected a
  **pre-existing** error: `play/DATA_SAFETY.md` claimed financial info was shared only via the user's own Drive
  backup — untrue since v1.56.0.
- **MED** the mover card restated the unusual card almost every time, and **the test meant to catch it was a
  tautology** (grouped by the key the engine already deduped on). **MED** Groq calls fired while the user was on
  another tab, breaking the shipped "nothing is sent while you're not looking" promise → gated on visibility.
  **MED** narrated cards were paired to findings by index with no check → the model now echoes `kind`.
  **MED** years of allocation rows were mapped + merchant-normalised on the main thread.
- Also: months with no spend counted as ₹0 when computing "usual" (halved the median → an ordinary
  intermittent purchase read as 2× unusual); an evenly-split transaction looked like a double charge; a
  cancelled call cached its own fallback text; same-named categories silently dropped one.

## Previous: v1.59.0 — versionCode **65**, versionName **"1.59.0"**
  (`app/build.gradle.kts` lines 41–42). Demo mode. **No DB schema change (still v16), no snapshot change,
  no dependency change**; manifest untouched, but `res/xml/backup_rules.xml` +
  `res/xml/data_extraction_rules.xml` now exclude the demo flag from device backup.
- Previous: **v1.58.0** — versionCode **64**, versionName **"1.58.0"**
  (`app/build.gradle.kts` lines 41–42). Shipped 2026-07-26 under the standing
  "ship after major fixes without asking" rule (see `CONTEXT.md` working agreement).
- **DB schema: v16** (UNCHANGED — no schema touch, no snapshot change, no manifest or dependency change).
- **Branch:** `main`, clean. Tag-driven CI.
- APK: https://github.com/aucksy/spends/releases/download/v1.58.0/Spends-v1.58.0.apk

## v1.59.0 — Demo mode (owner-requested 2026-07-26)
Owner: *"a toggle under data settings which hides all of current live data and replaces it with demo of
3 months enough data covering all scenarios which can help me demo every single feature."* Full technical
detail + the safety model: [`docs/DEMO-MODE.md`](docs/DEMO-MODE.md).

**Owner decisions (AskUserQuestion):** restart-on-toggle accepted in exchange for the hard safety guarantee ·
history extended 3 → ~14 months (Year-on-Year, which the owner asked for in the same round, is impossible to
demo with 3) · demo mode ships BEFORE the AI-insights round.

**The safety model — swap the storage layer, don't filter rows.** Demo mode points Room at `spends-demo.db`,
the settings DataStore at `settings_demo`, and the period-selection store at `period_selection_demo`. **The
live database file is never opened while demo mode is on.** The alternative (an `isDemo` column + a filter on
every query) puts invented money in the same tables as real money, where one missed `WHERE` is a wrong
balance. Decided in two expressions: `DatabaseModule` + `SettingsModule`. The flag lives in SharedPreferences
because it must be read **synchronously before Hilt builds the graph**; flipping it kills and relaunches the
process, because `SpendsDatabase`/`SettingsRepository` are `@Singleton`s that every repo and Flow already
holds. `DemoDataSeeder` refuses to run unless `DemoMode.isEnabled()` **and**
`db.openHelper.databaseName == DEMO_DB_NAME` — the second check inspects the file actually open, not a flag.

**~14 months of scripted data** (`DemoScript`, pure + deterministic + unit-tested): 2 cards on different
billing days, bank + UPI, 7 recurring rules, a 7-row review queue, splits, trash, learned merchants, custom +
archived categories. ⭐**Volume is uniform across all 14 months on purpose** — a denser recent window would
make *every* category read as "up 80% three months ago" and drown the one real anomaly; recency is expressed
as richer *kinds* of data, not more rows. Planted, findable stories for each future insight: an anomaly
burst, a one-off outlier, a duplicate pair, a 6-month trend, a YoY gap, payday/weekend habits, a quiet win.

**Reviews (ritual honored):** 2 parallel adversarial agents (compile/Hilt/Room + logic/money-safety, both
scanned `app/src/test`; both independently ported `DemoScript` **and** `kotlin.random.XorWowRandom` to Python
to actually execute the new tests) → **COMPILE: CLEAN, LOGIC: NO-GO**. All findings fixed:
- **⭐BLOCKER (mine, would have red-CI'd)** — the fuel-outlier test was a coin flip: the planted ₹9,850 charge
  sat only ~4× the Fuel plan's own median, and `typical` is a 34-sample order statistic. The two agents
  *disagreed* on whether it passed (one measured 3.96 worst-case, the other 2.76 and failing on 4 of 5 dates).
  Rather than pick a side, the guarantee was made **structural**: Fuel band narrowed to ₹900–2,600 so no
  organic charge can exceed ~₹3,950, and the outlier raised to ₹12,500 = ≥3× *every possible* organic charge,
  for every seed and date. **LESSON: a statistical assertion over generated data is a latent red build; size
  the fixture so the invariant is provable.**
- **⭐HIGH — four more paths reached real data.** Gating live SMS + notification capture is the obvious half;
  **"Scan past SMS"** (`scanHistory`), **"Scan for cards"** (`scanInboxForCards`), the **shade sweep** on
  `onListenerConnected`, and a **stale tray prompt's Add/Ignore/Edit** all reach the same place by other
  routes. In demo mode the scans would have queued the owner's genuine bank alerts — raw bodies, balances,
  card digits — into the demo review queue, rendered them on screen mid-demo, then destroyed them at the next
  reset. All four now gated.
- **HIGH — the home-screen widget** rendered fabricated Income/Expense/Balance with no marker, outside
  `DemoModeWrapper`'s reach, and is exactly where someone glances at a balance without opening the app. Now
  force-masked with a "DEMO MODE — sample data" header.
- **MED** — the demo flag rode Android cloud backup (restore a phone backed up mid-demo → boots into the
  sandbox with the real data invisible) → excluded from both backup rule files. **MED** — `period_selection`
  carried `selectedCardId` across the boundary, so demoing Single-Card left the *real* app opening on
  whichever real card shared that row id → store swapped. **MED** — the launch chores raced the seeder →
  `seedJob.join()`. **MED** — `DemoDataSeeder` dragged `GroqClient` (OkHttp + SecureKeyStore) onto the cold
  start of **every** user via `MainViewModel` → both now `Provider<>`, so non-demo launches build neither.
- **MED** — a failed seed left `onboardingComplete=false` inside demo, dropping into the welcome flow where
  "Restore from Drive" is a dead end → settings are now written *before* the data, so a failure still leaves
  a usable app. **MED** — `restartInto` killed the process even if the relaunch was refused → now rolls the
  flag back, returns false and explains. **LOW** — seeded recurring rows carried no `dedupeHash`, leaving
  `nextRunAt` as the single defence against double-generating rent/EMI → they now carry the same hash the
  materialiser would produce. Plus the `Science` icon (unverifiable, used nowhere else) swapped for `Info`,
  integer-division burst spacing made explicit, and `rememberSaveable` on the reset confirmation.
- **Tests:** `DemoScriptTest` — money conserved across splits, nothing future-dated, every category name real,
  review-queue rows distinguishable (they share a UNIQUE index), determinism, and — the valuable half — that
  **the scripted stories are actually present**: the anomaly is anomalous, the quiet win is quieter, the trend
  climbs, volume is flat. Without those, generator drift would leave demo mode silently claiming to
  demonstrate anomaly detection with nothing to find. Five fixed dates incl. a 31st and a leap day.

## v1.58.0 — merchant extraction fix + AI message context + privacy-disclosure corrections
Commits `6d74cfe` (fix) + `43fa248` (review fixes) + `00a99e1` (disclosure sweep) + `c3ff6a3` (money-safety
review fixes + bump). **This entry was backfilled 2026-07-26** — the release shipped without a PROGRESS.md
section, so for a while the live-state pointer read one version behind. Detail lives in the commit bodies.

- **⭐RCA (a PARSER bug, not an AI gap).** The owner's real Axis card fuel alert categorised as "Other".
  Indian bank alerts end with a fraud-report trailer ("Not you? SMS BLOCK 4094 to 919951860002"); with no
  "at <merchant>" in the message, `extractMerchant` fell through to the " to <X>" rule and recorded the
  merchant as **the phone number**. The real merchant ("Hello Fuels") sat on its own line and was never read.
  Worse than the missed category: the number was shown on the row AND learned into `merchant_categories` as
  a key that can never match again. With the merchant read correctly the seeded Fuel keyword rules categorise
  it deterministically, offline, **with the AI helper OFF** — fixing only the AI would have left the default
  path broken.
- **Parser (`extractMerchant` only** — amount/kind/last4/date/ref still read the full text, so no money field
  can shift): strip the report trailer before extraction; new pattern for the Axis shape
  `\bIST\s+([^.]{2,40}?)\s+(?:Ref\b|Avl\b)`; " to <X>" now stops at " on " like " at <X>" already did;
  `looksLikeMerchant()` rejects bare numbers and fragments.
- **⭐HIGH found in review — duplicate re-capture was genuinely possible.** The dedupe key is
  `last4 ?: merchant`, so for messages with NO last4 the merchant IS the hash; improving merchant extraction
  shifts those hashes, so `seenHashes` stops recognising a transaction a previous scan already added — and
  `manualKeys` deliberately excludes `source == SMS`, i.e. exactly the rows a previous scan-confirm created.
  Re-running "Scan past SMS" would re-queue them and one "Add all" would genuinely duplicate LEDGER rows.
  0 of 51 golden fixtures affected (all 6 whose merchant changed carry a last4) but 8 of 26 realistic
  last4-less shapes drifted. **Fix:** when `parsed.last4 == null` the scan also skips on the coarse
  day|amount|kind key against live rows from ANY source — the same conservative stance
  `relaxedNoRefDuplicate` already takes.
- **MED** — the merchant guard was eating real shops (`^(?:your|the|a)\b` killed "The Body Shop", "The Souled
  Store", "A One Sweets"); narrowed to `^your\b`, dropped bare "account" (was killing "Amazon Account
  Services"). **MED** — the new Axis rule over-captured without a sentence/Ref stop.
- **Privacy/disclosure:** the AI mask leaked shape and missed non-ASCII digits (Kotlin `\d` is ASCII-only, so
  a Devanagari digit survived; digit-by-digit masking turned `Rs.5,59,393.44` into `Rs.#,#,#.#`, leaking
  magnitude) → one whole numeral now masks to a single `#`. `kind` was being sent undisclosed, and "masking
  removes reference numbers" was false for alphanumeric refs. Both corrected in Settings **and** the privacy
  policy.

## v1.57.0 — notification-capture reconnect fix + temporary owner-facing diagnostic
Owner-reported 2026-07-26: **Truecaller alerts never become captures** — and on questioning, notification
capture has **never once** produced a capture from either watched app since it shipped in v1.53.0. Full
code trace found no single broken link but four silent failure modes and one real gap. Detail, hypothesis
table and the removal checklist: [`docs/NOTIFICATION-CAPTURE-DEBUG.md`](docs/NOTIFICATION-CAPTURE-DEBUG.md).

**A) The permanent fix — the listener now reconnects itself.** Android's notification-access GRANT and the
live service BINDING are different things: the grant survives forever, the binding is lost on every app
update (owner installed 5 releases since v1.53.0) and can be dropped by an OEM battery killer, without
reliably returning. The only proactive rebind was the Settings switch, and `onListenerDisconnected →
requestRebind` only runs while our process is alive. So the toggle and the grant both read "on" while
nothing is captured. New `service/NotificationListenerControl.kt` (`hasAccess` / `requestRebind: Boolean` /
`ensureBound` / `openAccessSettings` / the `connected` flag) — called from `SpendsApp.onCreate` (gated on
the setting, after a 5 s grace so a healthy install skips a pointless unbind/rebind) and from
`BootReceiver`. **Honest caveat:** `requestRebind` routes through `ManagedServices.setComponentState` and
does nothing for a component that was never *snoozed*, so for an update-lost binding it may be a no-op —
hence the screen also offers "Open Android settings", since toggling access off/on is the reliable remedy.

**B) The diagnostic (TEMPORARY — remove per the doc's checklist).** `data/capture/NotificationDebugLog.kt`
(@Singleton, **in memory only**, never persisted / never in the snapshot, 60-entry ring + 80-package cap),
`NotificationCapture.diagnose()` (pure), listener recording at every drop point, and
`ui/capture/NotificationDebug{Screen,ViewModel}.kt` at `Routes.NOTIFICATION_DEBUG` (Settings → Automatic
Entries → Detect from SMS & notifications → **Notification debug**, deliberately outside the enabled-only
block). Verdict line + access/connected/seen counters + Reconnect + every package that posted a
notification + per-alert detail + **Copy report**.

**Reviews (ritual honored):** 2 parallel adversarial agents (compile/Hilt/Room + logic/data-safety, both
scanned `app/src/test`) → **COMPILE: CLEAN, LOGIC: GO, 0 blockers**; all four claims CONFIRMED (capture
behaviour byte-identical, review-only intact, no DB/snapshot change, diagnostic memory-only + bounded).
Fixed everything else they found:
- **⭐The diagnostic would have closed the investigation on the one FIXABLE hypothesis.** Textless
  MessagingStyle messages make `candidates()` commit to the messages branch and never try `bigText`, so a
  readable alert in `bigText` reported as `NO_READABLE_TEXT` → rendered as "this is the RCS limit" → doc
  maps to "not fixable". That's H3b, **our** bug. New `MESSAGES_SHADOWED_BIG_TEXT` value separates them,
  with a regression test asserting it is never reported as `NO_READABLE_TEXT`.
- **Crash:** `shapeSkipEntry` read extras with no `runCatching`, on the main looper in a system callback —
  an unparcelable Bundle would kill the listener, the exact failure this build diagnoses. Both `record()`
  calls wrapped, extras null-guarded.
- **Self-erasing log:** `ALREADY_SEEN` no longer recorded — a conversation reposts up to 25 retained
  messages, so repost noise would evict the whole ring before the owner opened the screen.
- **Privacy:** `recordSeen` gated on the capture toggle; **Copy report withholds message BODIES** for
  entries whose sender didn't resolve to a tracked bank (a watched app is Google Messages, and the report
  is designed to be pasted off-device). Sender strings kept; recognised bank alerts export intact; the
  on-device screen still shows everything.
- **Structural:** `connected` moved off `NotificationDebugLog` onto `NotificationListenerControl` —
  `SpendsApp` read it off the log, so following the documented removal checklist would have broken the
  build. `CaptureSection`'s 3 duplicate helpers now delegate. `SKIPPED_SHAPE` reason order mirrors
  `looksReadable`; the `NotificationDecision` `when` is exhaustive; entries stamped with the MESSAGE's own
  time + body; cold-launch verdict no longer cries "bug" for the first few seconds.
- **⭐CI caught one the agents didn't:** an optimization skipping `publish()` when nothing was collecting
  left `state.value` stale for non-subscribing readers (the test read it with no collector). Removed the
  trap rather than patching the test — `publish()` always runs and no longer sorts; `Snapshot.packageCounts`
  is a `Map` that consumers sort at display time. Added a defensive-copy test.
- **Tests:** `NotificationDebugLogTest` — ring cap, newest-first, counters readable with no collector,
  defensive copies, `clear()` must not lie about the live connection, and the five `diagnose()` verdicts.

## v1.56.1 — AI helper: end-to-end-assessment fixes + "reproduce my learned category" enhancement
Owner asked for an end-to-end assessment of v1.56.0; 2 trace agents (regression + happy-path) + a Groq API contract
check found feature #1 sound and no existing regression, but real issues in feature #2 + owner added a G1 enhancement.
All fixed, delta-reviewed clean, shipped as v1.56.1. **No DB / snapshot / manifest / dependency change.** Detail +
review outcomes in [`docs/AI-BUILD-PLAN.md`](docs/AI-BUILD-PLAN.md).

**Assessment fixes:**
- **⭐HIGH — insights card could hang on "Thinking…" forever.** The collector claimed the cycle fingerprint BEFORE
  the network call under `collectLatest`, so unrelated churn (any DataStore write, a recurring-rule edit) cancelled
  the in-flight call and the same-fingerprint restart skipped the finish. Fix: drive it off a `distinctUntilChanged`
  fingerprint trigger (only a genuine cycle/data change cancels+reruns) + the ✕ dismiss is available during loading.
- **MED (regression, everyone) — analytics DB flows stayed hot in the background with AI off.** The always-on
  collector held a permanent `state` subscriber. Fix: `flatMapLatest` the gate so AI-off never subscribes to `state`
  → the analytics queries idle again.
- **MED — a cancelled Groq call didn't abort the HTTP request** (orphaned requests). Fix: `GroqClient` now uses
  `suspendCancellableCoroutine` + `call.cancel()`.
- **LOW — key wasn't reactive** (saving a key after enabling insights didn't show the card until the next data
  change). Fix: `GroqClient.hasKeyFlow` (updated by `setKey`/`clearKey`) combined into the gate. **LOW —** per-row
  learned-merchant lookup re-read the whole table → `learnedMerchantPredicate()` (one read). **LOW —** "Test key"
  now labels 400/404 correctly.

**⭐Owner enhancement — AI *reproduces* a learned category for a spelling variant (G1: enhance, never override).**
For the unrecognized merchants AI does get, it's now given the user's learned merchant→category shortcuts
(`SmsCaptureRepository.learnedCategoryPairs`, capped 100, names only, unarchived) and told to match a known merchant
FIRST (bridging variants the exact matcher missed) and REPRODUCE that category, else guess from the list. A match sets
`fromKnown` → chip reads **"Same as before: X ✨"** (vs "Suggested"); accepting learns the new spelling so the
deterministic matcher covers it next time. **G1 preserved:** the deterministic learned match still runs first, so AI is
only consulted for merchants it couldn't place — `known` lets it REPRODUCE, never replace. Privacy delta (learned
merchant names now also leave the phone — names only, no amounts/dates) disclosed in the explainer + first-enable dialog.

**Reviews:** delta agents on both the assessment fixes and the enhancement → **0 blockers**; compiles, G1 +
money-safety hold, privacy bounded + disclosed, cancellation safe (no double-resume). Tests: `AiCategorizerTest`
(+known-payload + fromKnown), `AiInsightsPayloadTest`, `ReviewEligibilityTest`. Files: `data/ai/{GroqClient,AiCategorizer}.kt`,
`ui/settings/AiSettings{ViewModel,Screen}.kt`, `data/capture/SmsCaptureRepository.kt`, `ui/review/{ReviewViewModel,ReviewScreen}.kt`,
`ui/analytics/{AnalyticsViewModel,AnalyticsScreen}.kt`.

## v1.56.0 — "AI helper" (Groq BYOK) — SHIPPED (owner said ship 2026-07-24)
Built + reviewed + shipped same day. **No DB / snapshot / manifest change; no runtime dependency** (one
test-only `org.json` dep). Two features in one opt-in "AI helper", **master switch OFF by default → today's app
byte-for-byte (G2)**. Full plan + review outcome + accepted residuals: [`docs/AI-BUILD-PLAN.md`](docs/AI-BUILD-PLAN.md);
locked spec: [`docs/AI-RESEARCH.md`](docs/AI-RESEARCH.md).

**What it does:** (#1) a `Suggested: X ✨` chip on **review-queue** rows the rules left on "Other" — tap to
accept (fills the pending row's category + learns it; you STILL confirm via Review-and-Add/Add-all; never
auto-applied). (#2) a dismissible **✨ Insights** card on Analytics: a 2–4 sentence plain-English summary of the
viewed cycle, with refresh. Key = **BYOK**: owner pastes their own free Groq key in Settings → Automatic Entries
→ AI helper (a first-enable privacy dialog + Test-key button).

**Money-safe by construction (traced + review-confirmed):** amount/kind/date stay 100% on `SmsParser` (golden
tests untouched); AI only produces a category NAME (#1) or summary TEXT (#2). AI is called ONLY from
`ReviewViewModel`/`AnalyticsViewModel` (review + read-only surfaces) — NEVER from `captureReturningId` /
`confirmPending` / `confirmAllPending` / `confirmPendingEdited` / `commitDraft`. Accepting a chip →
`setPendingCategory` (pending-row UPDATE, no ledger write). **G1:** eligibility requires no learned mapping
(`SmsCaptureRepository.hasLearnedCategory`) AND a rules-fallback category → AI never overrides a learned/confident
pick. **Fail-closed:** no key / offline / timeout / non-2xx / bad JSON / off-list category → today's behaviour,
no crash, no UI block.

**Privacy — only two things leave the phone:** (#1) merchant string + category names; (#2) category totals +
income/expense totals (cycle label is the descriptive name, e.g. "Current Salary Cycle" — no dates). NEVER: SMS
bodies, amounts+balances, account/card numbers, last4, dates, individual rows. Groq key stored ENCRYPTED via
`SecureKeyStore` (AndroidKeyStore-wrapped), device-local, **not in the backup snapshot** (like `widgetEyeHidden`);
AI toggles are device-local too (not in `restore()`).

**New:** `data/ai/{GroqClient,AiCategorizer,AiInsights}.kt`, `ui/settings/AiSettings{Screen,ViewModel}.kt`.
**Edited:** `SecureKeyStore` (encrypted key), `SettingsRepository` (3 device-local AI prefs), `SmsCaptureRepository`
(`hasLearnedCategory`), `ExpenseDao`+`ExpenseRepository` (one-shot `categorySpendOnce`), `ReviewViewModel`+`ReviewScreen`
(chip), `AnalyticsViewModel`+`AnalyticsScreen` (card), `AutomaticSettingsScreen`+`Routes`+`SpendsNavHost` (SETTINGS_AI).
Models: `llama-3.1-8b-instant` (categorize, batched JSON) + `llama-3.3-70b-versatile` (insights, cached per cycle)
— both verified as current Groq production models.

**Reviews (ritual honored):** 2 parallel adversarial agents (compile/Hilt/Room + logic/data-safety/privacy, both
scanned `app/src/test`, explicit money-safety check) → **0 blockers, 0 HIGH/MED; all guarantees CONFIRMED.** Fixed
4 LOW/NIT: (1) `GroqClient` no longer swallows `CancellationException`; (2) review collector un-marks a batch on
mid-scan cancellation so chips retry; (3) `AiInsights` cache bounded (64); (4) added `ReviewEligibilityTest`.
**Tests:** `AiCategorizerTest` (name→id map, off-list/null/hallucinated-id/malformed → dropped), `AiInsightsPayloadTest`
(aggregates-only payload, rupee conversion, `parseSummary` fail-closed), `ReviewEligibilityTest` (fallback-only rule).

**Accepted residuals (owner-told, read-only prose, no money/privacy impact):** insights "vs last cycle" can read
stale after editing a *previous*-cycle txn (refresh button fixes it); for a card-heavy Smart Cycle the "vs last"
number uses the plain previous window (current-cycle figures always exact); G2 master-off gating is review-verified
but not unit-tested (fail-closed + privacy + eligibility cores ARE).

## v1.55.0 — Settings hub (categories to tap into) + one-time "moved to next" dot
Owner-requested 2026-07-24; built + reviewed + shipped same day. **No DB / snapshot change** (one new
device-local DataStore pref + a UI restructure).

**A) "Moved to next cycle" dot is now a one-time nudge (owner: "should go away after first use").**
- New device-local pref `smartShiftBadgeSeen` (`SettingsRepository`/`SettingsState`, key `smart_shift_badge_seen`,
  default false) — like `widgetEyeHidden`, deliberately NOT in the backup snapshot/`restore()`.
- `PeriodSelectorBar` gains `shiftBadgeSeen` + `onShiftBadgeSeen`; `showShiftBadge` now also requires
  `!shiftBadgeSeen`, and the forward-arrow onClick fires `onShiftBadgeSeen()` (only when the dot is actually
  showing: offset 0, cards shifted). `TransactionsUiState.shiftBadgeSeen` sourced from settings in
  `buildStateSmartCard`; `TransactionsViewModel.markShiftBadgeSeen()` persists true. Owner decision (asked):
  **gone for good after the first forward-tap** — a teaching nudge, does NOT re-arm for a new card's shift next
  cycle. The forward ARROW stays enabled (`forwardEnabled` independent of the flag) — only the dot goes away.
  Other `PeriodSelectorBar` callers (Analytics, category drill-down) pass neither shift field → dot never shows
  there → new defaults are inert.

**B) Settings split from one long scroll into a 6-category HUB (owner: "different categories to tap into…
it's intimidating").** Owner picked the 6-group layout interactively.
- `SettingsScreen.kt` is now the HUB: 6 tappable cards (Money & Cycles / Automatic Entries / Categories /
  Appearance / Backup & Restore / Data & Trash), each an icon-chip + title + one-line subtitle + chevron.
- Shared look extracted to **`SettingsCommon.kt`** (public, same package): `SettingsSubScaffold` (titled
  back-arrow scaffold), `SectionHeader`, `SettingsSection`, `RowDivider`, `SwitchRow`, `ClickableRow`,
  `SettingsHubRow`, `ordinal`, `formatMinuteOfDay`. Old private copies removed from `SettingsScreen.kt`.
- 5 new sub-screens (each `hiltViewModel<SettingsViewModel>()` → same singleton repo/DataStore, no split-brain):
  `MoneySettingsScreen` (salary day, carry-forward +anchor+opening, Smart Cycle +reset day +Banks&Cards;
  owns the Salary/Reset dialogs, anchor picker, opening dialog), `AutomaticSettingsScreen` (SMS/notif capture,
  Recurring), `AppearanceSettingsScreen` (theme +auto-dark window +dialog, Open-on, widget eye),
  `BackupSettingsScreen` (`BackupSection`), `DataSettingsScreen` (`SpreadsheetSection` + Trash). Categories =
  the hub card jumps straight to the existing `CATEGORIES` route.
- Routes `SETTINGS_MONEY/AUTOMATIC/APPEARANCE/BACKUP/DATA` + `SpendsNavHost` wiring; every moved control keeps
  its exact side effects (widget refresh AFTER the write; Smart-Cycle-ON opens reset dialog; carry-forward
  auto-anchor). **Feature-complete parity** with the old page — nothing dropped or duplicated.
- **Reviews (ritual honored):** 2 parallel adversarial agents (compile/Hilt + logic/UX, both scanned
  `app/src/test`+`androidTest`) → **compile CLEAN, logic GO, 0 findings.** Verified: no duplicate top-level
  decls, all `SettingsViewModel` calls exist, all icons in `material-icons-extended`, dot persists/never
  re-arms, forward arrow stays enabled, full old→new control mapping. No tests reference the old structure.

## v1.54.1 — Make the carry-over box discoverable (timeline summary strip)
Owner-requested 2026-07-24; built + reviewed + shipped same day. **Pure layout; no logic/DB/money change.**
- **Problem:** The timeline summary strip (`ui/transactions/SummaryHeader.kt`) sizes tiles so exactly TWO
  (Expense + Income) fill the row width; when a THIRD tile (Carry forward, shown only when
  `state.carryForward != null` = carry-forward setting ON) is present it sat fully off-screen to the right
  with no hint it existed. Owner didn't know there was a carry-over box.
- **Fix:** In the `BoxWithConstraints`, when `tiles.size > 2` the tile width becomes
  `(maxWidth - gap*2 - peek) / 2` with `peek = 40.dp`, so the third tile PEEKS in from the right edge — the
  standard "scroll for more →" affordance. The two-tile default path (carry-forward OFF, the common case)
  is byte-identical (`(maxWidth - gap) / 2`). Peek is a fixed 40dp sliver independent of screen width
  (`2*tileW + 2*gap = maxWidth - peek`). Shared font style still measured from the one `tileW` → all tiles
  stay one matched size (just slightly smaller when 3 show).
- **Reviews (ritual honored):** 2 parallel adversarial agents (compile + logic/layout, both told to scan
  `app/src/test`) → **compile CLEAN, logic GO, 0 findings.** No test asserts tile sizing; only consumer
  `TransactionsScreen.kt` uses the unchanged public signature. RTL: peek mirrors to the left edge (correct).
- **Ship glitch (fixed):** first `git commit` here-string broke on inner quotes → the commit silently didn't
  happen and the tag landed on the OLD v1.54.0 commit. Caught it (main push said "up-to-date"), deleted the
  tag local+remote, committed via a `-F` message file (commit `decd47b`), re-tagged. LESSON: commit multi-line
  messages via `git commit -F <file>`, not PowerShell here-strings with embedded quotes.

## v1.54.0 — Card-billing-aware Smart Cycle ("billed card spends roll to the next cycle")
Owner-requested 2026-07-24; built + reviewed + shipped same day. **No DB / snapshot change** (pure
period-bucketing logic + UI). Refines the v1.52.0 Smart-Cycle rework.

- **RCA (owner):** After v1.52.0, Smart Cycle = ONE reset-day window; a credit card's billing day was
  ignored, so a card purchase that had already *billed* (its statement closed) still counted against the
  current cycle's balance until the reset day. Owner wants: a card purchase counts in the cycle where its
  **statement bills** — on/after the card's billing day it rolls into the **NEXT** Smart Cycle (navigable
  via the ›-forward arrow + a one-per-card "moved to next" dot). HARD constraint: must NOT reintroduce the
  v1.52.0-era "spend vanishes when the billing day passes" bug.
- **The rule (single source of truth):** `core/period/SmartCardCycle.kt` — `effectiveWindowStartMillis
  (occurredAt, billingDay, resetDay)` = the reset-window containing `statementCloseDate` (the next
  billing-day occurrence strictly after the purchase; month-end clamped). Bank/UPI + null/out-of-range
  billingDay → no shift. **Partition:** every txn → exactly ONE window (never zero = vanish, never two =
  double-count). **⭐Shift is CAPPED at one window forward** (`cap = nextWindow(rawWindow)`) — the month-end
  double-clamp case (e.g. bills 30 / resets 31) could otherwise push a spend TWO windows ahead, past every
  consumer's one-window-back fetch → vanish. Cap keeps the ≤1-window invariant TRUE so the spend lands in
  the adjacent, always-fetched, navigable cycle. Owner decision (asked interactively): **shift them all** —
  a card billing mid-cycle moves its whole post-billing tail forward (not only near-reset billers).
- **Consumers (all 5 reconcile via the shared `belongsToWindow`/`filterToWindow`):** timeline
  (`TransactionsViewModel.buildStateSmartCard`: list + totals + carry-forward + next-cycle badge, computed
  in memory from ONE wide fetch = prev cycle → next cycle end, plus carry history), Analytics
  (`AnalyticsViewModel.buildStateSmartCard`), per-instrument breakdown (`CycleBreakdownViewModel`), category
  drill-down (`CategoryTransactionsViewModel` — also coerces range=CURRENT for Smart), home widget
  (`SummaryWidget` smart-composite branch). Salary / Month / Single-Card paths UNCHANGED; all shift logic
  gated on `smartCycleEnabled && type==SMART_CYCLE && selectedCardId==null`.
- **UI:** `PeriodSelectorBar` forward arrow now enabled when the next cycle holds rolled-forward spends
  (`canGoForwardToNext`; normally forward is capped at the present) + a small primary-color dot on › at the
  current cycle when cards have shifted (`shiftedCardNames`, one per card). `TransactionsUiState` gained
  `canGoForwardToNext` + `shiftedCardNames`.
- **Reviews (ritual honored):** 2 parallel adversarial agents (compile CLEAN; logic found **1 BLOCKER**) →
  ⭐BLOCKER = the 2-window month-end-double-clamp vanish (bills 30 / resets 31: a 30 Mar purchase → +2
  windows → below the fetch bound → dropped from the balance on 4/5 surfaces). Fixed = the cap. Also fixed:
  widened the test sweep into the clamp zone {resets 29/30/31 × billings 28/29/30/31} + explicit regression
  tests (they were structurally unable to catch it), and the CategoryTransactions range=CURRENT coercion.
  → delta-verification agent re-ran a 2024–2028 × 31×31 sweep: **0 shifts ≥2, 0 backward, no double-count,
  no new vanish** (`previousWindow(nextWindow(W))==W` round-trip proven), reconciliation intact → safe to ship.
- **Tests:** `SmartCardCycleTest` — owner's 23rd/25th example, open-statement pull-forward, mid-cycle card,
  billing≥reset, month-end clamp + leap year, the ≤1-window sweep (now incl. the clamp zone), the
  double-clamp regression guard, and a partition/conservation check.
- **Accepted residuals (owner told):** (1) carry-forward (OFF by default) with a NON-cycle-boundary anchor
  can be off-by-a-hair on the first cycle for card spends in the last day or two before the anchor — no
  double-count, no vanish; (2) the rare month-end double-clamp purchase counts in the *adjacent* cycle (the
  cap) — a minor attribution approximation chosen over vanishing; (3) the "moved to next" indicator is a
  plain dot, not the card name — refinement candidate. **This roll-to-next approach also largely addresses
  the concern behind the ⏸PARKED Step-2 card-dues idea (different mechanism).**

## v1.52.0 — Smart Cycle Step 1 ("balance improves on billing day" fix)
Feature commit `32cca4f` + bump `eff095a`.
No DB change; no snapshot schema bump (additive settings field only).

- **RCA (owner-reported, export-verified):** Smart Cycle was a per-instrument composite —
  each card's window anchored on its own billingDay. The moment a billing day passed, that
  card's previous-cycle spends silently left the current-cycle balance (no carry-forward,
  no dues bucket, bill payments deliberately unlogged), so a negative balance "improved"
  with nothing paid. Owner's data: true Jun-25→Jul-21 cycle net **−₹13,357** while the app
  showed a shrinking ~−5k. Salary cycles run 25th→24th; +₹10,000 income on Jul 17 was the
  one *real* part of the movement.
- **Fix:** Smart Cycle (all instruments) = ONE contiguous window via `PeriodResolver`,
  anchored on new setting `smartCycleResetDay` (**0 = follow salary day**, the default;
  `SettingsState.effectiveSmartResetDay`). Timeline, Analytics, breakdown screen, category
  drill-down and the widget all resolve the SAME window → all numbers reconcile.
  Carry-forward rules now apply to Smart exactly like Salary (it's a plain window).
  **Single-Card mode unchanged** (that card's own billing cycle via
  `CompositeCycleResolver.resolveSingleCard`) with the reset-window headline balance.
  Cards tab keeps per-card statement windows (correct — it's a statement view).
- **Settings flow (owner decision):** toggling Smart Cycle ON opens `SmartResetDayDialog`
  — plain-words copy, wheel 1..31 preset to the salary day; picking exactly the salary day
  stores 0 (follows later salary-day changes); any other day is pinned. Editable later via
  the "Cycle reset day" row. Toggle subtitle rewritten to match the new promise.
- **Backup:** additive `SnapshotSettings.smartCycleResetDay` (default 0) + restore write.
  Old backups restore fine (default = follow salary).
- **Reviews:** 2 full adversarial agents (compile CLEAN-TO-BUILD; logic GO, 1 MED + 3 LOW)
  → 4 fixes (drill-down stale-SMART→Salary coercion; widget vanished-card label; breakdown
  feature-flag anchor guard; drill-down sheet hint text) → combined delta agent VERIFIED all.
- **Tests:** `PeriodResolverTest` smart-anchor cases (distinct reset day, equality with
  salary when 0, offset stepping) + new `SettingsStateTest` (effective-day fallback rules).
- **Known nits (accepted):** in-app pill says "Single Card" over the whole-cycle fallback
  when a picked card was deleted (rare, numbers correct); dead composite multi-instrument
  code (`resolveSmartCycle`, `isComposite` flags) kept — Step 2 reuses the machinery.
- **⏸ PARKED (owner 2026-07-22): Step 2 card dues.** When a card's billing day passes, the
  closed statement becomes a visible "Bill generated — ₹X unpaid" persisting until paid
  (manual "mark paid" + auto-detect from the bill-payment SMS the parser currently
  IGNORES — use as a signal, not a transaction). "Total unpaid on cards" = closed unpaid
  bills + current open statements. Likely a statements/dues table (DB v15→v16) +
  Cards-tab/breakdown surfacing. Do NOT start without the owner un-parking it.

## v1.53.0 — Notification capture (Phase 4) — SHIPPED (owner said ship 2026-07-22)
Owner-chosen 2026-07-22; built + shipped same day. Commits `85ca2f2` (feature) +
`56e98c0`/`c70d843`/`d285b74` (compile fixes) + `eb9094a` (review-fix round) + `7b4c945`
(delta fix + docs) + the vc57 bump. **DB v15→v16** (`pending_captures.sourceApp`, additive
nullable TEXT). CI green on the full chain (compile + all unit tests incl. the SmsParser
golden gate).

**What it does:** `CaptureNotificationListenerService` reads notifications from apps the
user ticks (launch set: **Google Messages + Truecaller** — owner-chosen; GPay/PhonePe
deferred until they get their own parsing rules, so no checkbox that captures nothing).
Closes the RCS gap: RCS bank alerts look like SMS in notification form, so the untouched
`SmsParser` + allowlist handle them. Review-only, same hard rule as SMS — a capture either
shows the standard "Review & Add / Ignore" prompt or lands in `pending_captures`; NEVER
the ledger without explicit user action.

**Key design points:**
- `SenderAllowlist.canonicalSenderFor`: RCS friendly names ("Axis Bank", "HDFC Bank
  Cards") → the canonical DLT header, so parse + hashes are identical to the SMS twin;
  suffix stripping (Ltd/Limited/India/Cards/Card/Bank/Official), exact-match-after-strip.
- MessagingStyle-aware extraction (per-message sender/text/timestamp; bigText fallback);
  group summaries / ongoing / FGS skipped; repost guard (7d TTL) + 72h live age gate
  (sweep gets the full 7d); `requestRebind` self-heal; **no keep-alive service**
  (owner-chosen); shade catch-up on connect queues SILENTLY (owner said yes to sweep).
- **⭐Twin collapse (the hard part):** the same real payment can arrive as SMS + notification
  with the notification text missing the ref number → different dedupe hashes. Solution =
  the **relaxed hash** (hash with ref blanked): a ref-less capture's stored hash IS its
  relaxed hash, so twins are exactly detectable. Guards at every layer: `claimPrompt`
  (atomic, both live paths, refs-provably-differ escape), queue insert (with-ref insert
  deletes its queued ref-less twin; exact relaxed check queue-side), and EVERY commit path
  (`twinAlreadyCommitted` on confirm/confirm-all, relaxedHash+fromNotification on
  commitDraft). The coarse day|amount|kind branch is confined to notification-sourced
  rows so the pure-SMS flow's blast radius is nil.
- Blocked-notifications fallback: a PROMPT that can't show (POST_NOTIFICATIONS denied)
  queues silently instead of evaporating.
- Settings: "Detect from app notifications" toggle + notification-access deep-link
  (`ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` — note the full constant name) + app
  checklist; defaults seeded ONCE via a seeded-flag (un-ticking everything sticks).
  Device-local prefs, deliberately NOT in the backup snapshot.
- Review UI: "DETECTED FROM NOTIFICATION" badge + "via <app>" in the detail sheet.

**Reviews (ritual honored):** round 1 = 2 full adversarial agents (compile: 1 blocker
found+fixed; logic: 1 BLOCKER + 1 HIGH + 5 MED) → fix round `eb9094a` → delta verification
agent (traced the twin matrix, hash byte-compat, races): found 1 residual HIGH (ref-less
notification DRAFT could commit after its SMS twin, needed origin threading into
CaptureDraft) → fixed. ⭐LESSON: `*/` inside a KDoc sentence ("*prompts*/re-parses") ends
the comment and produces bizarre parse errors — CI now uploads the full Gradle output as a
`build-output` artifact (fetchable anonymously via nightly.link) because job logs need
admin auth.

**Accepted residuals (owner may revisit):** rejected captures can resurrect after process
death + repost (pre-existing re-scan semantics, no tombstone table); ledger-side relaxed
check is coarse day|amount|kind → an RCS-only no-ref alert is silently dropped when ANY
same-day same-amount row exists (conservative by design); a swipe-dismissed prompt for an
RCS-only alert is unrecoverable (no history source); unknown RBM agent names silently
no-match (no debug counter); GPay/PhonePe/Paytm parsing = future round.

**Next candidates (owner picks):** GPay/PhonePe/Paytm notification parsing rules round ·
card dues (Step 2, still ⏸ PARKED) · category budgets · exact-alarm backup · Play prep.

## Recent: v1.51.0 (DB v14→v15)
**Merchant self-learning rework + recency-ranked category picker** — commits `7842f4a`
(feature) + `f09ffb2` (review-fix round 1) + `123ea4a` (review-fix round 2) + `2a67b62` (bump).
- **MerchantKeys** (`data/capture/MerchantKeys.kt`, pure + 25 golden unit tests):
  normalized merchant keys (gateway prefixes incl. glued "RAZFurlenco", company suffixes,
  order numbers, UPI VPA suffixes stripped; STOP_TOKENS refuses all-generic/letter-less
  keys) + conservative `sameMerchant` fuzzy matching (word containment / glued prefix).
- **Learning policy:** fuzzy matches pre-fill ONLY editor-reviewed surfaces (draft, pending
  editor seed, queue guesses at scan time); silent Add / quick confirm / Confirm-all use
  exact normalized matches only. Learning records only DELIBERATE choices: Confirm-all
  never learns; editor save teaches only what changed (`categoryDeliberate`/`noteShown`);
  newest entry wins across spellings; note propagation on replaceNote touches only siblings
  carrying the previous note. `merchant_categories.note` = **MIGRATION_14_15** (DB v15).
- **Notes remembered per merchant** and applied everywhere a capture becomes a transaction
  (owner's choice); pre-filled editable in review editors; a deliberate clear clears + propagates.
- **Backup Snapshot v5** carries the learned memory (restore: v5+ full-replace filtered to
  restored categories; pre-v5 keeps device memory + pruneOrphans — schemaVersion default
  hardened to 1, buildSnapshot stamps CURRENT_SCHEMA).
- **Confirm-all is now transactional** (one db.withTransaction + per-row dedupe-hash guard —
  mid-loop crash can no longer double-add on retry; pre-existing hole closed).
- **Category pickers** rank by last-90-days usage (owner chose 3 months), all-time as
  tie-break; cutoff computed at collect; archived categories rejected in resolveCategory.
- **Reviews:** full 2-agent adversarial pass on 7842f4a (1 compile/Room CLEAN-to-build;
  1 logic found 3 HIGH + mediums) → all fixed in f09ffb2 → 2-agent delta re-review found
  3 MED residuals (guess re-learning via note-only saves; note smearing across fuzzy
  siblings) → fixed in 123ea4a → final combined verification agent on the last delta.
  Owner decisions honored: review queue NOT back-filled when a mapping is learned;
  Confirm-all commits queue-time categories as displayed.
- Debug CI green on 7842f4a and f09ffb2 (compile + all unit tests incl. SmsParser gate).

## Recent tags
- **v1.50.0** — Excel export re-columned + **the TRANSFER kind removed from the whole app** (DB v13→v14).
  **(A) Excel export** (`data/export/ExcelExporter.kt`): the confusing single "Amount" + "Balance impact"
  columns are replaced by three — **Income (₹)**, **Expenses (₹)**, and a **running Balance (₹)** (passbook
  style). Balance is accumulated OLDEST→NEWEST (opening = income−expense of everything before the window
  start, so a windowed export starts from the carried-in balance; all-time opens at 0), then rows are
  displayed NEWEST→oldest each carrying the balance it settled at. Accumulation sort `compareBy(occurredAt,
  id)` is the exact reverse of the display sort `compareByDescending(occurredAt).thenByDescending(id)` so
  same-timestamp rows show the right balance. Dropped the now-redundant "Type" column and `prettyKind()`;
  added `impactMinor(ExpenseEntity)`. **(B) Transfer removal** (owner: "this concept should not exist"):
  `TxnKind` is now `{ INCOME, EXPENSE }`. Transfers were always balance-neutral (never in balance = income −
  expense, never in spend charts), so removal changes **no** totals for income/expense-only data. Touched:
  `Enums.kt` (enum), `Converters.kt` (stringToKind now defaults unknown→EXPENSE instead of crashing),
  `SpendsDatabase.kt` (**v14 + MIGRATION_13_14** deletes `kind='TRANSFER'` from expenses+allocations+
  pending_captures+recurring_rules; data-only, schema identity unchanged from v13), `DatabaseModule.kt`
  (registered), `SummaryHeader.kt` (Transfers tile gone), `TransactionsModels.kt` (SummaryTotals.transfer
  gone), `TransactionsViewModel.kt` / `AnalyticsViewModel.kt` (transfer sums + `transferMinor` gone),
  `AnalyticsScreen.kt` ("Excludes transfers" row gone; weekly note → "Shows spending only"),
  `TransactionsScreen.kt` / `CategoryTransactionsScreen.kt` / `ReviewScreen.kt` / `TrashViewModel.kt` /
  `CaptureNotifier.kt` / `SmsCaptureRepository.kt` (TRANSFER `when` branches removed — 2-branch whens are
  exhaustive), `GenericAdapter.kt` / `MonitoAdapter.kt` (imported "transfer" rows → expense), `SmsParser.kt`
  (credit-card bill payments + unexplained card credits now **not logged** = IGNORED, were TRANSFER),
  `SmsParserTest.kt` (4 golden tests → expect IGNORED). NOTE the Add/Edit + Quick-add kind toggles already
  only offered Income/Expense — no UI toggle change needed. `semantic.transfer` COLOR token is kept (the
  Carry-forward tile still uses it). **Reviews:** 2 parallel agents (compile/Room + logic/data-safety) →
  **2 findings, both fixed pre-tag:** (1) BLOCKER — `ExcelExporter.kt` referenced `ExpenseEntity` without
  importing it → added the import; (2) HIGH data-safety — restoring an OLD backup (made before this release)
  would coerce its `kind="TRANSFER"` rows into EXPENSES via the `toEntity` fallback, wrongly subtracting them
  from the balance AND re-creating transfer recurring rules as phantom expense generators. **Fix:**
  `BackupRepository.applySnapshot` now DROPS legacy transfer rows on restore (transfer expenses + their
  allocations + transfer recurring rules) via a new `isKnownKind()` (`TxnKind.entries`), instead of coercing
  them → balance stays correct. Delta re-review (both agents) confirmed both fixes correct + complete, no new
  problems. LESSON: removing an enum value that's persisted needs BOTH a DB migration AND a restore-path
  filter — the migration only cleans the LIVE db, old backups re-introduce the value.
- **v1.49.0** — 5-fix round (no DB change). (1) **Swipe removed** from the transactions list — deleted
  `SwipeableRow`/`SwipeBg` + the swipe-only delete-confirm dialog and recategorise sheet; rows render
  `TransactionRow` directly (too many accidental swipes). Single delete/recategorise stay reachable via
  row-tap→editor, and multi-select (long-press) still does bulk delete + change-category. (2) **Widget
  quick-add keeps in-progress work** — removed `android:noHistory` from `QuickAddActivity` so switching apps
  no longer finishes it and wipes the entry (QuickAddSheet fields are already `rememberSaveable`;
  `singleInstance` re-tap resumes the same instance). (3) **Keypad haptic firmer** — `KeypadKey`
  KEYBOARD_TAP → VIRTUAL_KEY, still fired on finger-down (no lag returns). (4) **Export a chosen cycle to
  Excel** — `ExcelExporter.build(start, end)` filtered overload (no-arg `build()` delegates to MIN..MAX = all,
  byte-identical to before); new `ui/backup/ExportCycleSheet.kt` (Month/Salary × All-time/This-cycle/Last-3/
  Last-6/Custom, default All time; reuses `PillSegmentedControl` + the now-public `CustomRangeDialog` +
  `PeriodResolver`); `BackupViewModel.exportExcel(uri, start, end)` + `excelFileNameFor(label)` + `salaryDay`/
  `earliestDay` flows (injects `ExpenseRepository`); `SpreadsheetSection` holds the window in
  `rememberSaveable` so a rotation/process-death mid-SAF can't strand a 0-byte file. (5) **Compact cycle
  stepper on the per-category drill-down** — `CategoryTransactionsViewModel` gains a LOCAL `PeriodSelection`
  (seeded from the shared store's current value so the drill-down matches the Analytics slice you tapped,
  Smart→Salary; NEVER writes back), resolved via `PeriodResolver` exactly like Analytics; `CategoryTransactions
  Screen` shows a compact `PeriodSelectorBar` (`label=""` → single line; concrete dates kept on the count
  line) above "Monthly average", with the empty state inline so the selector stays reachable. ‹ › arrows show
  for a single cycle; All-time/Last-N show a tappable name. **Reviews:** full round (2 agents: compile +
  logic) clean, 2 fixed pre-tag (export `rememberSaveable` window; `QuickAddActivity` KDoc); delta re-review
  (compile + logic) clean; then a regression audit — 0 blocker/high, fixed the 1 MEDIUM = the stepper had
  been forced to CURRENT (mismatched the tapped Analytics slice) → reverted to seed-from-store so it opens on
  the viewed cycle, + 3 comment/import nits. Files: `TransactionsScreen.kt`, `AndroidManifest.xml`,
  `CalculatorKeypad.kt`, `CategoryTransactions{ViewModel,Screen}.kt`, `PeriodSelectorBar.kt` (CustomRangeDialog
  public), `ExcelExporter.kt`, `BackupViewModel.kt`, `ExportCycleSheet.kt` (new), `BackupSection.kt`,
  `QuickAddActivity.kt`.
- **v1.48.2** — keypad haptic = Gboard feel. The key haptic fired `LONG_PRESS` from `clickable`'s onClick
  (RELEASE) — a heavier effect a frame late = the "slight delay". Now `KeypadKey` fires
  `performHapticFeedback(KEYBOARD_TAP, FLAG_IGNORE_VIEW_SETTING)` on the finger-DOWN via
  `pointerInput{ awaitEachGesture{ awaitFirstDown(requireUnconsumed=false); … } }` placed before `.clickable`
  (no consume → tap still commits, scroll not broken). Researched: <30 ms after touch a buzz reads as
  "didn't register", and KEYBOARD_TAP is the same crisp keyboard effect Gboard uses. SaveKey unchanged. Both
  reviews compile-CLEAN + behavior-GO. Files: CalculatorKeypad.kt. (Firmness is a 1-line constant swap if wanted.)
- **v1.48.1** — UI fixes to the v1.48.0 round (3 items, all layout, no logic): (1) the "Jump to month" sheet
  overflowed → content Column now `verticalScroll` so all years/months are reachable. (2) Dropped the ugly
  separate "Jump to month" pill; instead the period pill's existing **calendar icon "pops"** (primary-
  container chip) and is tappable in All-time mode to open the jumper (`PeriodSelectorBar.onJumpToMonth`
  callback, non-null only in All-time). (3) The "Paid with" picker overflowed and hid the new Add-card/bank
  rows → its Column now `verticalScroll`. Both reviews compile-CLEAN + logic-GO (isNavigable==range CURRENT
  so All-time reaches the chip branch; two distinct tap targets; Analytics unaffected). Files: JumpToMonth.kt,
  PeriodSelectorBar.kt, TransactionsScreen.kt, PaidWith.kt.
- **v1.48.0** — 3-feature round. (1) **"Jump to month" in All-time**: a pill (only in All-time mode) opens a
  picker of every month that has data, grouped by year; picking one scrolls the timeline straight to that
  month's first day-header (`ui/transactions/JumpToMonth.kt`; scroll math in TransactionsScreen — summary
  item + prior groups' header+rows). (2) **Year in day-headers**: `DateUtils.dayMonthFormatter` →
  "EEE, d MMM yyyy"; label hardened with weight+maxLines+ellipsis so the wider header never squeezes the
  per-day amounts. (3) **Add a bank/card from "Paid with"** without leaving the entry: PaidWithPickerSheet
  gained optional `onAddNew`; QuickAddSheet opens the existing `CardEditorSheet` over the still-mounted
  Dialog (rememberSaveable entry preserved) and auto-selects the new instrument via
  `QuickAddViewModel.addInstrument` (→ `PaymentMethodRepository.addManual`, returns the new id). Reviews:
  compile CLEAN + logic GO on all three (index math exact, entry preserved, other picker callers unaffected).
  No DB change.
- **v0.47.0** — keypad-clip fix take 5 (final tuning). v0.46 fixed the clip — the 0·Save row became fully
  visible — but on the user's device it sat too close to the gesture pill. Bumped the clearance floor+margin
  from `maxOf(inset,24)+8` (min 32dp) to `maxOf(inset,32)+16` (min 48dp) in `DraglessBottomSheet` so the keys
  have comfortable breathing room above the pill on any device/nav mode. Two-constant change; both adversarial
  agents GO (Save still always reachable — keypad rows are fixed 54dp, padding is below them inside the scroll).
- **v0.46.0** — keypad-clip fix take 4. v0.45's activity-read inset was the RIGHT mechanism (Save did move
  up — user confirmed "a bit better") but landed a few dp short: gesture-nav skins report a thinner
  `navigationBars` strip than they visually occupy. Fix in `DraglessBottomSheet`: floor the inset and add a
  small always-on margin — `bottomClearance = maxOf(LocalSheetBottomInset.current, 24.dp) + 8.dp` — so the
  0·Save row clears the bar on any device/nav mode. Padding stays INSIDE the scroll, BELOW the last row, so
  Save is always reachable (verified: content ~82% of a 94%-capped sheet → fits, no scroll needed). Pure
  layout; no swipe/discard/validation/money change. Tradeoff: a tidy fixed bottom gap on thin/no-nav-bar devices.
- **v0.45.0** — keypad-clip fix take 3 (the raw in-Dialog listener in v0.44 also read 0). Insets are
  UNREADABLE inside a plain Dialog, full stop. So read the nav-bar inset in the ACTIVITY (SpendsTheme,
  where edge-to-edge insets work) and pass the value into the Dialog via a new `LocalSheetBottomInset`
  CompositionLocal; `DraglessBottomSheet` pads the keypad up by it. This reads the real value, not zero.
- **v0.44.0** — REAL keypad-clip fix (v0.43's revert didn't work). Root cause: a plain Compose Dialog
  never feeds WindowInsets into its composition, so BOTH `navigationBarsPadding()` and the
  `decorFitsSystemWindows` flag are no-ops inside it. Fix: `DraglessBottomSheet` now reads the RAW window
  insets off the dialog's view (`ViewCompat.setOnApplyWindowInsetsListener`), and pads the content by
  max(nav bar, keyboard). API + soundness verified. The keypad's 0·Save row now clears the gesture bar,
  and a focused Note field clears the keyboard.
- **v0.43.0** — fix keypad bottom row (0 · Save) clipped under the gesture bar. v0.42 set
  DraglessBottomSheet's Dialog `decorFitsSystemWindows = false`, but a plain Dialog doesn't dispatch
  window insets, so `navigationBarsPadding` read 0 and the panel ran under the nav/gesture bar.
  Reverted to the default (`decorFitsSystemWindows` true) — the decor fits system windows, keeping the
  keypad clear of the gesture bar (the v0.41 display behavior). Swipe-proof + discard-confirm unchanged.
- **v0.42.0** — main quick-add sheet ALSO moved to the swipe-proof Dialog (DraglessBottomSheet), so
  the home + button AND the widget quick-add can no longer be swiped away (closes only via ✕/back;
  back confirms if there's unsaved work). Fixed the popup panel color: it was tinted teal by a tonal-
  elevation overlay — now surfaceContainerLow + tonalElevation 0, matching the app's other sheets.
  DraglessBottomSheet now caps at 94% height + scrolls internally + lifts above the keyboard
  (decorFitsSystemWindows=false + imePadding). ALL keypad surfaces are now swipe-proof. No DB change.
- **v0.41.0** — REAL swipe fix, new mechanism: the confirm-on-swipe (v0.39/v0.40) never fired on
  device. So the popup keypad `AmountKeypadSheet` (the "Split amount" popup + the AddEdit & Recurring
  amount keypads) was moved OFF `ModalBottomSheet` onto a plain `Dialog` (new `DraglessBottomSheet`) —
  a Dialog has NO swipe-to-dismiss gesture at all, so a stray swipe can never discard the amount; it
  closes only via ✕ or back. No confirmValueChange veto → no freeze. **STILL TODO:** the main
  quick-add sheet (QuickAddSheet) is still a ModalBottomSheet — convert it the same way (it's tall +
  scrollable + has a note text field, so needs heightIn + decorFitsSystemWindows=false care). No DB change.
- **v0.40.0** — discard-confirmation now on ALL half-screen keypads: v0.39 only guarded the quick-add
  sheet; the shared AmountKeypadSheet (AddEdit editor, Recurring editor, split-slice amount) had no
  guard, so swiping those still lost work with no prompt. Added the same "Discard this amount?" guard
  there (hasWork = amount changed from what it opened with). "Keep editing" now also re-shows the sheet
  (recovers even if the onDismiss re-show didn't take). Still no confirmValueChange veto → no freeze.
- **v0.39.0** — accidental-swipe protection done the freeze-free way: a DISCARD CONFIRMATION on the
  quick-add sheet. Swipe-down / tap-outside / back / ✕ with unsaved work (amount, category, note, or a
  split in progress) now asks "Discard this entry?" (Keep editing / Discard) instead of silently losing
  it — covers every exit route, no confirmValueChange veto (that froze the app). No DB change.
- **v0.38.0** — FREEZE FIX (final): removed the LAST swipe-dismiss veto (on the main quick-add
  sheet). v0.37 removed it only from the nested keypad, but the freeze recurred on the MAIN
  home-screen keypad — a confirmValueChange veto on a skipPartiallyExpanded sheet freezes on drag
  even without nesting. Now NO ModalBottomSheet in the app vetoes dismissal (v0.35-equivalent);
  the swipe-block feature is gone (it was fundamentally causing the freeze). ✕ / back / swipe all
  close the sheet. No DB change.
- **v0.37.0** — split fixes: (#3, SEVERE) fixed a touch-freeze in the split flow — the per-slice
  amount keypad was a ModalBottomSheet nested inside the quick-add sheet, and BOTH vetoed swipe-
  dismiss; two stacked vetoing sheets deadlocked touch handling. Removed the veto from the inner
  keypad (kept it on the outer sheet), matching the working category-picker pattern. (#2) The slice
  amount keypad now opens blank (default 0) with the remaining shown as "₹X left" instead of pre-
  loading the amount. FUTURE cleanup logged: make the split-slice keypad inline/non-modal to drop
  nested dialogs entirely. No DB change.
- **v0.36.0** — split hardening (5 items): fixed the split-picker header wrap (#1); quick-add +
  amount keypad sheets now resist accidental swipe-dismiss and have a dedicated X — only X/back close
  (#2); live "₹X left to assign" shown beside the Split Amount title (#3); a slice can't over-assign —
  entering more than remaining disables Done + shakes the figure red (#4); each split slice has its own
  note (#5). No DB change.
- **v0.35.0** — 3-item UX round: split entry reworked — tap a category → "Split" → multi-select
  categories → each gets a keypad amount with a live remainder; Save requires every slice > 0 and
  sum = total (#1). Settings decluttered: subtle divider lines between logical rows, shortened
  descriptions, rewritten Smart Cycle copy (remaining-salary framing), removed the cards paragraph
  (#2/#3). No DB change.
- **v0.34.0** — 4-item round: removed the "Analytics" screen heading (#1); split-one-amount
  across categories in quick-add (total-first, each slice saved as its own BAU transaction via
  `ExpenseRepository.createAll`, #2); bank-name → instrument auto-match in the SMS **review
  editor** (last4 first, then a *unique* institution match; silent one-tap-Add / Confirm-all stay
  last4-only), "Paid with" now shown + pre-filled in capture review (#3); recurring 9 AM reminder
  moved off inexact WorkManager onto an **exact AlarmManager alarm** (#4 — `RecurringAlarmScheduler`
  + `RecurringAlarmReceiver` + `BootReceiver`; deleted `RecurringScheduler`/`RecurringWorker`; added
  USE_EXACT_ALARM/SCHEDULE_EXACT_ALARM/RECEIVE_BOOT_COMPLETED). No DB change.
- **v0.33.0** — Batch 2: single-card = remaining SALARY balance (#7), card
  "Review & Add" pre-fill flow (#9), Settings reorg 11→6 groups (#3). No DB change.
- **v0.32.0** — Batch 1: backup reschedule fix (CANCEL_AND_REENQUEUE), search back in top
  bar, PaidWith grouped Banks/Cards, cycle in category drill-down, recurring "for the next
  N months" note. No DB change.
- **v0.31.0** — #13: statement-SMS billing-day auto-detect (DB v12→v13).
- **v0.30.0** — Round 3 (minus #13): card capture. No DB change.
- **v0.29.0** — Round 4: category monthly-average window (#8).

## Status of the two feedback batches
- **16-item batch (v0.27.0–v0.31.0): COMPLETE.**
- **9-item batch (v0.32.0–v0.33.0): COMPLETE, shipped green.**
- No feature work is in progress.

## Open decisions (do NOT act without the user)
- **1.0 version label** — whether to stamp the next release `1.0.0` or stay on `0.x`.
  User deferred ("hold on, I'll get back"). versionName is cosmetic; only versionCode
  affects installs. Before a 1.0 stamp the user wants an on-device soak of the recent
  DB-touching (v0.28/v0.31) + money-logic (v0.33) releases.

## Roadmap candidates (not requested — mention, don't start)
- On-device soak of recent releases (the main pre-1.0 gate).
- App-lock / privacy screen.
- Notification-based capture (GPay/PhonePe) — Phase 4.
- Category budgets round.
- Splitting UI.
- Exact-alarm backup if Doze drift on the daily backup persists.
- Play Store setup (Spends is sideload-APK only today: no Play listing / privacy policy;
  READ_SMS is Play-restricted).

## Next action
Wait for the user's next batch of changes, or their decision on the 1.0 label. Follow the
release ritual in `CONTEXT.md` for anything that ships.
