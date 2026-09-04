# Spends — Live Progress

Live state pointer. Update this at every phase/release boundary. Read `CONTEXT.md` first
for how the project works.

## Current release
- **Tagged: v1.72.0** — versionCode **84**, versionName **"1.72.0"**. **Google Gemini joins the AI provider
  list**, so a Google AI Studio key now works for foreign-currency conversion. No schema change.
  - *Why it was asked for.* The owner holds a Google AI Studio key, not an Anthropic/OpenAI/Groq one. The
    BYOK promise in v1.70.0 was that the key is the *user's* — that only holds if the list contains the key
    the user actually has.
  - *Google's own endpoint, not its OpenAI-compatible one.* Google serves an OpenAI-shaped
    `chat/completions` too, and using it would have been a one-line enum entry with no new client code.
    It was rejected: that layer is documented as beta, documents only a handful of the fields it accepts,
    and **silently ignores the rest** — the output ceiling among them. A parameter quietly dropped rather
    than rejected is invisible until the day it matters, which is the opposite of how the rest of this
    feature is built. Gemini goes to `generateContent`, where what is sent is what applies.
  - *So there are three wire formats now.* `AiProvider.openAiCompatible: Boolean` became `AiWire` — a
    two-valued flag cannot pick between three payload builders and three parsers. Google is also the only
    provider that names the model in the URL rather than the body, so `endpoint` became `endpointFor(model)`.
    A pasted `models/gemini-…` prefix is stripped, because that is how Google's docs name a model and
    verbatim it would build `/v1beta/models/models/…` and 404 for no visible reason.
  - **No `temperature` in the Gemini payload, deliberately.** Sending 0 is the obvious choice for a question
    with one right answer, and it is the wrong one here: Google's own Gemini 3 guidance is to leave
    temperature at its default, because these models reason across the sampled tokens and pinning it can
    send them looping or degrade the answer. It was written as `temperature: 0` first and removed during
    verification. The first draft justified it with "the six-hour cache means determinism hardly matters",
    which the pre-tag review correctly took apart: the cache **repeats** one sample rather than averaging
    several, and it is in-memory, so a background capture in a cold process draws again. The real reason it
    is acceptable is what surrounds the number — `isSaneRate`, the rate printed on the face of the row, the
    "estimate" label, and a pinned rate that overrides it. A looping answer would clear none of those.
  - *Default model `gemini-3.5-flash-lite`.* This feature asks one one-line question, so the cheapest,
    fastest model that thinks only minimally is the right fit; on AI Studio's free tier with the 6h cache it
    should cost nothing. As with every provider it is only a default — any model id can be typed on the phone
    when Google rotates its lineup.
  - *The parser skips `thought` parts*, for the same reason the Anthropic one walks blocks: on a thinking
    model the answer is not reliably part zero. Every no-answer shape — a safety block, a turn that spent its
    whole budget thinking, an error envelope — falls through to the existing fail-closed path, so the amount
    stays in its own currency and flagged rather than becoming a guess.
  - *"Test key" now explains a 400.* Google answers a bad key with 400 where the others use 401, so it read
    as a bare `HTTP 400` with no advice.
  - *Tests.* `AiProviderTest` pins the URL building (the `models/` prefix, blank-falls-back-to-default, that
    no other provider's endpoint moved, that none is left holding an unreplaced placeholder, that all four
    are https). The three response parsers became `internal` and gained `AiClientParseTest` — they are where
    a reply becomes text a rate is read out of, and every interesting case there is a body shape, not a
    network condition, so a MockWebServer dependency would have bought nothing.
  - *Verified without a build, as usual.* The toolchain cannot compile this project, so `AiProvider.kt` and
    `AiClient.kt` were compiled standalone against the real okhttp/coroutines/org.json jars and **run**:
    all 11 `AiProviderTest` cases green, every parser assertion green, and the emitted request body checked
    field-by-field against Google's documented `generateContent` shape.
  - **The pre-tag review found a real one, and it was not in the new code.** Two adversarial reviewers ran
    before the tag. The compile/wiring one found nothing that breaks CI (it re-derived all 21 raw-string
    literals in the new parser test and confirmed 16/16 assertions, and confirmed unit tests here really do
    see `internal` members — three existing tests already rely on it). The logic/data-safety one found this:
    **there is one key slot, and changing provider did not clear it.** So picking Google with an Anthropic
    key saved sent `sk-ant-…` to Google as `x-goog-api-key` on the next foreign alert — silently, with the
    settings row still reading "Saved on this device", and the secret landing in another vendor's logs. It
    dates from v1.70.0, but this release is what makes it the FIRST thing every existing user does, and it
    contradicts the sentence on the provider dialog itself. `setProvider` now clears the key, the dialog
    says so before you choose, and re-picking the SAME provider is a no-op (the dialog's Done fires either
    way and must not cost anyone their key). **This fix has no automated test**: a ViewModel test for it
    would be timing-sensitive, this branch cannot be run locally, and the last commit before it was a
    flaky-ViewModel-test fix — so it is a ⭐⭐ box in the manual checklist instead, and that is the honest
    status rather than a comfortable one.
  - *Three smaller things the same review turned up, all fixed.* A thrown `JSONException` put the **whole
    response body** on the settings screen — a hotel captive portal's login page returns 200 and is not
    JSON, and `org.json` appends its entire input to the message; failures are now classified into short
    reasons and the body never escapes. The 404 advice said "try clearing the model field", which is a dead
    end when the field is already blank — it now names the default it falls back to. And the Model box, now
    that its contents go into the request URL on Google, refuses a pasted API key instead of putting it
    somewhere it gets written down.
  - *Disclosure re-swept*, per the rule that a new network call means redoing it: `README.md`,
    `play/DATA_SAFETY.md`, `docs/index.html` and `docs/FEATURE-INVENTORY.md` all name the fourth provider.
    **What is sent did not change** — still one rate question, no amount, no merchant, no message text.
- Previous: **v1.71.1** — versionCode **83**, versionName **"1.71.1"**. **A foreign-currency card alert was
  being captured as the remaining CREDIT LIMIT.** Owner-reported, with two screenshots from his own phone.
  No schema change.
  - *What happened.* Yes Bank writes every card alert the same way: what you spent, then what is left
    — "MYR 87.48 spent on YES BANK Card X2664 @AIRASIA… **Avl Lmt INR 100,334.07**". On a rupee purchase
    that trailing figure is harmless, because the spend is written FIRST and `amountRegex.find` returns the
    first match. On a FOREIGN purchase there is no rupee spend amount at all — the only rupee figure in the
    message is the remaining limit. So the rupee-first pass matched it, returned happily, and the foreign
    pass never ran. His RM87.48 flight was queued as a ₹1,00,334.07 expense; his USD 19.50 eSIM as
    ₹94,018.55. Not a rounding error: a credit limit filed as a purchase.
  - *The fix.* Both passes now run over text with every balance/limit clause blanked out. One pattern
    anchored on the limit/balance WORD covers every spelling in the corpus — "Avl Lmt", "Avl Limit",
    "Avl Bal", "New Bal", "Available balance", "Balance Limit", "Total Lmt" — and only fires when a
    number directly follows, so a merchant whose name contains one of those words is untouched.
  - *Proven against the corpus before shipping, not after.* The parser cannot be compiled on this machine,
    so the two passes and the proposed mask were reimplemented and run over all **77** committed fixture
    strings lifted out of `SmsParserTest` and `SmsParserCurrencyTest`. Exactly **one** extracted amount
    moves: `sbi_limit_alert`, whose only figure IS a limit — and that message is rejected by `isLimitAlert`
    in step 1 of `parse`, long before any amount is read, so its asserted result does not move. Every one
    of the new tests' expected values was verified the same way first; one invented fixture was wrong and
    was corrected before it cost a build.
  - *Tests.* Both of the owner's real alerts are now committed fixtures, verbatim. Plus: a rupee spend
    still beats its own limit, all five leftover-figure spellings, a merchant named "BALANCE COFFEE",
    a limit-only message capturing nothing, and the mask's deliberate narrowness written down so nobody
    widens it into eating the amount beside the clause.
  - **Consequence the owner needs to know:** these alerts now arrive as MYR/USD and go through conversion.
    With no AI key set they land FLAGGED and unconverted, and the editor requires him to type the rupee
    figure before Save lights up. That is the v1.70.1 money guard doing its job — a correct manual number
    instead of a silent wrong one — but it is manual until a key is saved.
- Previous: **v1.71.0** — versionCode **82**, versionName **"1.71.0"**. The category drill-down now totals
  the SIDE OF THE LEDGER that was tapped. No schema change (Room v17, snapshot v6).
  - *The bug, which predates v1.70.0.* A category can hold both income and expense rows — the owner has
    two (Business, Interest) where money comes in under the same name it also goes out under. The Analytics
    donuts always split those correctly, because `observeCategorySpend` and `observeCategoryIncome` each
    filter on `kind`. The drill-down did not: `CategoryTransactionsViewModel` summed the category's rows
    regardless of direction, so tapping a ₹25,000 income wedge opened a screen headed with that income PLUS
    the same category's spending. The wedge and the page it opened disagreed, with nothing on either to say
    why. v1.70.0's income donut did not cause this — it added a second, much likelier way to meet it.
  - *The fix.* The lens travels with the tap: `Lens.kind` (which already existed and was dead code) is now
    carried on the route as an optional `kind` query argument, and the ViewModel filters `allItems` ONCE at
    the source. Everything downstream — the list, the cycle total, the monthly average, the year picker and
    the previous-year comparison — is derived from that list, so all five agree with the wedge without five
    separate places having to remember the lens.
  - *And the screen says which side it is.* A category with both directions now opens two screens under the
    same name showing different totals; unlabelled, that is a worse confusion than the original bug. The app
    bar gained a second line reading **"Money in"** or **"Money out"** (titleMedium + capped to one line each,
    so a long category name still fits a 64dp bar).
  - *Backward compatible.* The route argument defaults to EXPENSE, so a back-stack entry or deep link minted
    before it existed still resolves — and resolves to what it used to mean. An unreadable value falls back
    the same way rather than throwing.
  - *Tests.* `CategoryDrillDownKindTest` drives the real ViewModel against a real in-memory Room database
    with one category holding ₹30,000 of income and ₹5,000 of spending — the unfiltered sum (₹35,000) matches
    neither side, so a filter that silently did nothing cannot pass by coincidence. It pins both lenses, the
    missing-argument fallback, the unreadable-value fallback, and that the monthly average narrowed with the
    list. The period is pinned to ALL so the file can only fail for one reason.
- Previous: **v1.70.1** — versionCode **81**, versionName **"1.70.1"**. The pre-tag adversarial review of
  v1.70.0 found one money bug and five real defects; all are fixed here. No schema change (still Room v17,
  snapshot v6), so everything in the v1.70.0 entry below still describes what ships.
  - **The money bug — an unconvertible foreign alert could be saved as a base-currency amount.** Three of
    the four commit paths refused such a row; the full editor did not. It opened with the FOREIGN figure
    pre-filled, Save enabled, and on save discarded every column recording the row had ever been ringgit —
    so one tap on the most likely button on the screen filed RM250.00 as ₹250.00, indistinguishable
    afterwards from a genuine rupee entry. This is exactly the owner's stated use case (a card alert
    abroad) in exactly the conditions that make conversion fail (no signal). Now: Save is held until the
    amount is touched, the amount is headed with the FOREIGN symbol in the error colour while it is, and
    the origin (`fxCurrency`/`fxAmountMinor`) is stored unconditionally on **every** commit path — the rate
    is a receipt for the stored figure and still drops on an edit, but which currency it arrived in is a
    fact about the message and is kept. Pinned by `UnconvertedForeignGuardTest`.
  - **Every ordinary rupee capture notification had lost its ₹.** `CaptureNotifier` passed the capture's
    `currencyCode` to `Money.formatCode`, and that code is null for every rupee SMS — which the generic
    branch read as "an unknown currency whose symbol is the empty string", grouping Western. "Expense
    ₹12,34,567.89" shipped as "Expense 1,234,567.89" on the app's highest-traffic surface, with no test
    covering it. `formatCode` now treats a null/blank code as "no foreign currency involved" and renders in
    the ledger's own currency. Pinned by four cases in `AppCurrencyTest`.
  - **A failed rate lookup was never cached**, so an unreachable provider cost one HTTP attempt per foreign
    message at up to 20s each — inside the scan's capture mutex with the SMS cursor open. Failures are now
    remembered for 5 minutes (against 6 hours for a rate), so a brief signal drop cannot disable conversion
    for the day. Pinned by `CurrencyAiFailureCacheTest`, which counts calls against a stand-in client.
  - **A historical scan priced years-old alerts at today's rate.** The model is only ever asked "what is
    the rate RIGHT NOW", and a *converted* row is bulk-committable by "Add all" with no review. Anything
    older than two days is now queued flagged and unconverted instead. A live alert — the case the feature
    exists for — is unaffected. Pinned by `LiveRateFreshnessTest`.
  - **The spreadsheet export could contradict itself.** `ExcelExporter` is a `@Singleton` and its header
    was a stored `val`, freezing the currency at first construction while the split-details column read it
    per export: one file headed "Income (INR)" with "RM" inside it. Now a getter. Pinned by
    `ExcelHeaderCurrencyTest`.
  - **Backup threw away every conversion receipt.** Snapshot v6 added `baseCurrency` to the settings but
    never added the three `fx*` columns to `SnapshotExpense`. Amounts were always safe; the explanation was
    not. Added with null defaults (so every existing backup still restores) and mapped both ways. Pinned by
    `SnapshotFxTest`.
  - *Also:* two new render tests were racing an async DataStore read and could have gone red on a loaded CI
    runner — they now wait for the node rather than for composition to idle; a KDoc block was bound to the
    wrong function; two comments described behaviour the code did not have; `Money.RUPEE` was unreferenced.
  - *Reviewed but deliberately NOT changed:* the AI call timeout stays at 20s (the negative cache was the
    real fix, and a shorter timeout would make conversion fail on the slow roaming data this feature is for);
    a manual rate can still only be pinned for INR/MYR/USD, not for a detectable-but-not-base currency like
    SGD; `parseToMinor` still strips currency tokens unanchored; and restoring a pre-v6 backup still forces
    the base currency back to INR.
- **Superseded: v1.70.0** — versionCode **80**, versionName **"1.70.0"**. Never tagged. Two features:
  **income analytics** and **multi-currency with optional AI conversion**. **Room schema v16 → v17**
  (`MIGRATION_16_17`: three nullable columns on each of `expenses` and `pending_captures`; additive, no
  stored figure changes). Backup snapshot **v5 → v6** (`baseCurrency`).
  - *Income analytics.* Analytics gains a **Spending / Income** toggle that drives BOTH the category donut
    and the over-time chart, so income has the same breakdown, the same drill-down and the same bars that
    spending has always had. New DAO query `observeCategoryIncome` mirrors `observeCategorySpend` exactly.
  - *Multi-currency.* The ledger can be kept in **INR / MYR / USD**. That is a rendering choice — symbol
    plus grouping convention (Indian vs Western) — and rewrites nothing: every stored `amountMinor` is
    untouched. `Money.displayCurrency` is a process-wide volatile because widgets, notifications and the
    exporter format outside any composition. Travels in the backup snapshot.
  - *AI conversion (BYOK, off by default).* A foreign-currency alert is converted on the way in and shows
    its receipt everywhere it appears: `RM 100.00 → ₹1,890.00 · 1 MYR = ₹18.90`. Provider is the user's
    choice (Anthropic / OpenAI / Groq), key encrypted on-device via `SecureKeyStore` under a **new**
    preference name — the orphaned v1.56–v1.64 key is still erased on launch, so a key the user was told
    was deleted is never adopted. Only an exchange-rate question is sent; no amount, merchant, card number
    or message text ever leaves the phone. Rates cached 6h; a pinned manual rate skips the network entirely.
  - *Money-safety stance.* A foreign amount that could NOT be converted is kept, flagged, and **refused by
    every commit path that has no editor** (quick-confirm, "Add all") rather than being logged as if it
    were base currency — and those rows are no longer swept away by "Add all"'s final delete. The rate is
    range-checked, and a model answering about the wrong currency pair is discarded.
  - *Parser.* Rupee matching runs FIRST and unchanged, so all 56 golden fixtures behave exactly as before;
    foreign detection only runs when a message names no rupee amount. 13 Malaysian senders added.
  - *Disclosure sweep done* (the app makes a third-party call again): README ×2, `docs/index.html` (new
    §3a), `play/DATA_SAFETY.md` (new §1a — why "Shared" is still No), `DATA_SAFETY_WALKTHROUGH.md`,
    `PERMISSIONS_DECLARATION.md`, `store-listing.md`.
  - *Known, PRE-EXISTING, not introduced here — the category drill-down mixes kinds.* A category can hold
    both income and expense rows (the reference export had two: money came in under the same name it also
    went out under). The Analytics donuts split those correctly, because both queries filter on the
    transaction's `kind`. The drill-down does not: `CategoryTransactionsViewModel` computes
    `total = rows.sumOf { it.amountMinor }` over the category's rows regardless of kind, so tapping a
    ₹25,000 income wedge opens a screen totalling that plus the same category's expenses. That line is
    unchanged since before v1.70.0 and the spending donut always had the same mismatch — but the income
    view adds a second, more likely way to meet it. Fixing it means threading the lens's kind through the
    drill-down route AND through its average/comparison maths, on a screen redesigned twice in v1.67–v1.68,
    so it is left as the owner's call rather than changed unasked.
  - *Tests.* 398 → 501 assertions across 13 new files, including Robolectric render tests that actually
    OPEN the new settings screen and BOTH sides of the Analytics toggle against a real in-memory Room
    database.
  - *Validated against a real export.* The owner's full seven-year ledger (4,063 transactions, Sept 2019 →
    Aug 2026) was replayed through the real DAO queries and the real ViewModel in a throwaway harness. The
    all-time income (₹49,92,290.00), expense (₹63,26,991.90) and net (−₹13,34,701.90) came out identical to
    an independently computed oracle — and the net matches the running-balance column the app itself
    exported, across all 4,063 rows with zero mismatches. Every donut centre reconciled with its own
    wedges on both lenses; format→parse round-tripped exactly for all 4,063 amounts in INR **and** MYR;
    converting all 4,063 at a ringgit rate produced no overflow. The harness was deleted; what it found is
    pinned by `AnalyticsIncomeAccuracyTest`, whose fixture is synthetic but shaped like that ledger.
    (The export itself is personal financial data and is deliberately NOT in the repo.)
- Previous: **v1.69.0** — versionCode **79**, versionName **"1.69.0"**. Learn-from-ignore actually works:
  the pattern key no longer carries the AMOUNT, which had made the feature dead code. No schema change.
  APK: https://github.com/aucksy/spends/releases/download/v1.69.0/Spends-v1.69.0.apk
- Previous: **v1.68.0** — versionCode **78**, versionName **"1.68.0"**. Two fixes to v1.67.0's category screen:
  stepping back a cycle no longer calls it "THIS CYCLE", and Yearly gets the same hero + comparison layout,
  compared against the previous year with data. No schema change.
  APK: https://github.com/aucksy/spends/releases/download/v1.68.0/Spends-v1.68.0.apk
- Previous: **v1.67.0** — versionCode **77**, versionName **"1.67.0"**. The category screen leads with ONE
  number (this cycle) and states the comparison in words — "About ₹1,500 more than your usual month" —
  instead of showing two equally loud figures over two different spans. No schema change.
  APK: https://github.com/aucksy/spends/releases/download/v1.67.0/Spends-v1.67.0.apk
- Previous: **v1.66.0** — versionCode **76**, versionName **"1.66.0"**. An accidental "Ignore" can now be
  undone: Settings → Automatic Entries → **Silenced alerts** lists every alert Spends has stopped asking
  about (and every one part-way there) and switches it back on. No schema change — DB stays at v16.
  APK: https://github.com/aucksy/spends/releases/download/v1.66.0/Spends-v1.66.0.apk
- Previous: **v1.65.0** — versionCode **75**, versionName **"1.65.0"**. The AI helper is gone. Recurring
  notifications now say what they added, a transaction can reach the rule that created it, and the category
  screen leads with the average (with a new Yearly view).
  APK: https://github.com/aucksy/spends/releases/download/v1.65.0/Spends-v1.65.0.apk
- Previous: **v1.64.0** — versionCode **74**. Insight cards now name the category
  they are about, enforced by a guard rather than by asking the model nicely. (The cards themselves were
  removed in v1.65.0.)
  APK: https://github.com/aucksy/spends/releases/download/v1.64.0/Spends-v1.64.0.apk
- Previous: **v1.63.4** — versionCode 73. Widget carry-forward buckets by card billing day.
  APK: https://github.com/aucksy/spends/releases/download/v1.63.4/Spends-v1.63.4.apk
- **v1.63.3** — versionCode 72. The home-screen widget's balance
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

## v1.69.0 — "Ignore" accumulates now; the amount is out of the pattern key

**Owner-reported, with screenshots that diagnosed it exactly:** "every ignore is registered as first ignore
— same merchant but each sms is probably considered different". Correct. `ignoreKey` was
`header|who|amountMinor|kind`, so the AMOUNT was part of a pattern's identity. A merchant almost never
charges the same figure three times, so every alert minted its own row and none could reach
`IGNORE_SUPPRESS_THRESHOLD`. His phone held eleven rows for two sources — ₹29,989 / ₹29,990 /
₹29,995 / ₹29,996 all from YES BANK · PHP*FINRELIABLE DIGITE — every one stuck at "ignored once".
**The feature had never once fired since it shipped.**

The key is now `header|who|kind`: source and direction. What a person means by "stop asking me about this"
is the source, not the sum.

**Why this is safe to widen.** Two things must both hold, and now do: a suppressed alert still goes to the
review queue (nothing is lost, only the interruption stops), and since v1.66.0 every silenced pattern is
listable and reversible. Without that undo the amount was acting as an accidental safety catch on a door
with no handle on the inside — which is the real reason it survived so long.

**Legacy rows are purged, not migrated.** `IgnoredPatternDao.deleteLegacy()` drops the four-field keys, run
from `SilencedAlertsViewModel.init` and idempotent, so it needs no "have I run yet" flag. Nothing is lost:
*because* of the bug, not one legacy row had reached the threshold. `ignoreKey` strips the separator out of
the merchant so a current key always has exactly two of them, which is what makes the LIKE-based cleanup
exact.

**The row had to change too.** It used to read "₹450.00 at Swiggy"; a row now covers every amount from one
source, which is a bigger thing to switch off, so it shows the source as the heading and a `scopeLine()`
saying what it covers — "Money-out alerts · from YESBNK". `SilencedAlert.amountMinor` is gone.

`IGNORE_SUPPRESS_THRESHOLD` stays at 3. It is only now reachable at all, and the screen already warns with a
countdown before anything goes quiet.

---

## v1.68.0 — two fixes to the new category screen

**A wrong label, reported from the device.** Stepping back with the ‹ arrow left the headline reading
"THIS CYCLE" over a cycle that was months old. The heading was hardcoded in the composable, which cannot
know which window is selected. It now comes from the view model as `CategoryTxnsUiState.periodHeading`,
which distinguishes four cases the screen could not: the current cycle, an EARLIER cycle (the window has
ended), an UPCOMING one, and SELECTED PERIOD for All-time / Last-N / Custom ranges, which are not a single
cycle at all. The comparison wording was already correct for a past cycle — an ended window reports "less
than" rather than "under … so far" — because it keys off the same `now` boundary.

**Yearly now matches Monthly**, at the owner's request, for consistency. Same filled headline panel, same
comparison block. The baseline swaps: a year is compared against the newest EARLIER year that actually has
data — not `year - 1`, which would draw an empty bar for anyone with a gap. Both sides are per-month, so a
part-finished year still compares fairly, and the screen says so out loud because "2026 vs 2025" otherwise
invites the opposite reading. Yearly keeps its own headline meaning (average per month, year's total
beneath), which was confirmed correct on the device in the v1.65.0 round. It has no 3M/6M/All control,
because its baseline is picked by the year chips instead.

`CycleComparison.of` gained `reference` and `emptyText` parameters so both modes share one implementation
and can never drift into wording it differently; `monthlyAverageOver(start, end)` was extracted in the view
model for the same reason, so the two years are averaged by identical arithmetic.

---

## v1.67.0 — the category screen answers the question instead of posing it

**What the owner sees.** One headline (**THIS CYCLE**) on a filled panel, and directly under it the answer
in words: *"About ₹1,500 more than your usual month."* with two bars — this cycle against usual. The
6-month average is no longer a rival headline; it is the second bar.

**Why.** He reported the screen "has become confusing". Diagnosis against his real House Maintenance Jpr
figures: two headline numbers at identical weight (₹13,814 vs ₹15,339) covering different spans, two
adjacent controls driving different figures, and the heading "SPENT IN THIS CYCLE **ONLY**" — a warning
label compensating for an ambiguous layout rather than fixing it. The v1.65.0 reorder had fixed *which*
period owned the list, but still left the reader doing the subtraction.

**How.** New pure `CycleComparison.of(total, usual, cycleStillRunning)` returns the sentence plus two bar
fractions, or null when there is no history to average. `AvgWindow` (3M/6M/All) moved INSIDE the comparison
block — it only ever changed that figure, and sitting a few dp from the cycle stepper it read as though it
changed both. Yearly is untouched: its figure and its list already describe the same year.

**The honesty rule, and it is tested:** under-spending is only ever reported "**so far**" while the cycle is
still running, because a half-finished month has not finished spending and a flat "₹4,800 less than usual"
would congratulate a cycle that may still overshoot. Over-spending is stated flatly — once above a usual
month, remaining time cannot make that untrue. The sentence also carries **no figure for "usual"**: it is
rounded for readability while the bar beneath shows it to the paise, and printing both put two different
numbers for one quantity a centimetre apart.

**A real display defect fixed on the way.** In Smart Cycle the rows are bucketed by the paying card's
BILLING day (`SmartCardCycle.filterToWindow`), so his 23 Jul transaction correctly sat under a
"25 Jul – 24 Aug" heading — but nothing said so, and it read as a miscount. Such rows now carry a
**"billed this cycle"** tag (`CategoryTxnRow.billedIntoCycle`). The maths was always right; the screen was
not admitting what it had done.

Three options were mocked up in `docs/category-screen-options.html` and the owner chose A ("Answer first").

---

## v1.66.0 — an accidental "Ignore" can be undone

**What the owner sees.** A new **Silenced alerts** row in Settings → Automatic Entries. It lists every
bank alert Spends has stopped asking about, in plain words ("₹450.00 at Swiggy"), with **Ask me again**
next to each. Alerts only part-way to the threshold are listed too, with a countdown — that warning is
the last moment the decision is still reversible by doing nothing.

**Why.** Ignoring the same alert three times set `ignored_patterns.ignoreCount` past
`IGNORE_SUPPRESS_THRESHOLD` and suppressed that alert **permanently**. The table was write-only: no DAO
read it back, no screen showed it, and it is device-local (deliberately not in the Drive backup), so a
reinstall was the only exit. Three mistaken taps could silence a genuine recurring alert for good.

**How.** `IgnoredPatternDao` gained `observeAll` / `observeSilencedCount` / `deleteByKey` / `deleteAll`;
`SmsCaptureRepository` exposes `observeSilencedAlerts`, `observeSilencedCount`, `unsilenceAlert`,
`unsilenceAllAlerts`. Un-silencing **deletes** the row rather than decrementing it — decrementing to
threshold-minus-one would re-silence on the very next ignore, which is the opposite of "ask me again".

`SilencedAlert.decode` is the new pure half: it reads the opaque `header|who|amount|kind` key back into
display fields, splitting from the **right** because `kind` and `amountMinor` are machine-written and
can never contain a `|`, whereas the merchant is a verbatim slice of the bank's text and one day will.
It never throws and never drops a row — a key this code cannot parse is exactly the one that would
otherwise stay stuck silencing an alert with no way out. Covered by `SilencedAlertTest`.

**Known, not fixed (wording only, per the review stopping rule):** the "Silenced alerts" subtitle in
`CaptureSection` hardcodes "3" rather than reading `SmsCaptureRepository.IGNORE_SUPPRESS_THRESHOLD`, which
the screen itself uses. Correct today; it would drift if the threshold ever changed.

**Not fixed, deliberately:** the twin guard can still drop a genuine second payment. Owner's call — rare
on his setup, he will report it if money goes missing. Full write-up, cause, and the shape of the fix:
`docs/KNOWN-ISSUE-TWIN-GUARD.md`.

---

## v1.65.0 — the AI helper is removed; recurring notifications say what they added

### 1. The "recurring added" notification names the transaction
It used to say "1 scheduled transaction was added" and nothing else — you had to open the app and hunt for
it to find out whether it was rent or Netflix, and whether the amount was still right. Each added
transaction now gets its own notification carrying **the name, the note and the amount**, with **Edit**
(opens that exact transaction) and **Dismiss** (clears the notification; the transaction stays, because
everywhere else in Android "dismiss" means "hide", not "delete").

`materializeDue` now returns the occurrences it created instead of a count, collected **per rule and merged
only after that rule's transaction commits** — a rolled-back rule must not produce a notification whose Edit
button opens an empty editor. Above five occurrences in one pass (the back-fill case: three months away from
the app can create a hundred) the batch collapses to a single roll-up, because at that size there is no one
transaction the user meant to edit. No explicit notification group: Android bundles them itself, and an
app-managed summary outlives its children on some OEM builds.

### 2. A transaction can reach the rule behind it
Rows the scheduler created already carried `recurringRuleId`; nothing surfaced it. The editor now shows
"Added by a repeating rule → tap to open the rule that creates it", which routes to `Routes.recurring(id)`
and opens that rule's editor on arrival. Deliberately a **route, not a binding**: correcting this month's
rent still edits only this month. Completes the flow *notification → Edit → transaction → the rule*.

### 3. Truecaller capture: the cause was never in this app
The debug screen's report showed `text: Sensitive notification content hidden`. That string is not in this
codebase — it is Android's own placeholder. Since Android 15, notifications that Android System Intelligence
classifies as carrying a one-time code are **redacted before any notification listener sees them**, unless
the listener holds `RECEIVE_SENSITIVE_NOTIFICATIONS` (system/role only). So the parser was being handed a
placeholder, and the old verdict — "the sender isn't a bank Spends knows" — pointed the next investigation
at the sender allowlist, which cannot be the cause when the body never arrived.

Fixed the *diagnosis*, not the capture (there is nothing to fix in capture): a new
`REDACTED_BY_ANDROID` outcome, matched narrowly on the platform placeholder, plus a verdict line that names
the phone setting to try. `looksRedacted` is deliberately narrow — a false positive would blame the phone
for a real parser bug, which is the one mistake here that hides something fixable. It also joins
`REDACTED_OUTCOMES`, because it is reached **before** any sender resolves to a bank and the report must not
export a body that never passed that check.

### 4/6. The category screen leads with the average, and gains a Yearly view
The monthly average sat directly above the transaction list, so a 6-month average read as a label for a list
holding **one cycle**. The average is now the headline; the cycle total is a separate block, ruled off, headed
"SPENT IN THIS CYCLE ONLY" and carrying its own dates.

A **Monthly / Yearly** toggle joins it. Monthly is unchanged (3M / 6M / All). Yearly replaces the window with
a calendar year — every year that has data, newest first, no cap — shows the **average per month in that
year** with the **year's total underneath**, and the list below shows **that whole year**, so figure and list
finally describe the same stretch. Both ends of the divisor are clamped: at the category's first transaction,
and at *now* — without the second clamp the current year would divide by twelve and read as a spending
collapse until December.

### 5. The AI helper is removed
Deleted: `data/ai/**` (Groq client, categoriser, the insight engine/narrator/provider), the insights
carousel, the AI settings screen, three DataStore switches, and the four DAO queries nothing else called.
The **13 on-device insight cards went with it** — the owner chose full removal with that trade-off stated.
What survives: the learned merchant→category memory (deterministic, on-device) still auto-fills categories.

Any stored API key is **erased on the next launch**: it is a personal credential the user can no longer see
or delete themselves, since the screen that managed it is gone. `SecureKeyStore`'s read and write paths for
it are deleted outright — only the check and the erase remain, so nothing in the app can put a key back.

**The app now sends nothing to any third party.** The full disclosure sweep was redone in the strengthening
direction: `README.md` (two places), `docs/index.html` (section 3 deleted, sections renumbered),
`play/DATA_SAFETY.md` (both "Shared" answers now No; SMS collects nothing), `play/PERMISSIONS_DECLARATION.md`,
`play/listing/store-listing.md` and `play/PLAY_SUBMISSION_CHECKLIST.md`. The five `docs/AI-*.md` plans carry a
HISTORICAL banner. ⚠ **The Play Data safety form must be re-submitted** — the filed declaration says the app
shares financial data and SMS content with a third party, and that is no longer true.

## v1.64.0 — insight cards now say what they are about

The cards were arithmetically perfect and useless: *"You had a ₹10000 charge, which is 15.4 times the
typical ₹650 charge."* Which charge? Worse, three cards about three different categories read as three
contradictory claims about the same money — ₹6,070, ₹2,017 and ₹0 "so far this cycle", all true, none of
them saying of what.

**The numbers were never wrong.** Every figure is computed on-device by [`InsightEngine`]; the model does
no arithmetic. This was purely a wording defect.

**The category name was in the payload the whole time.** The prompt told the model to echo it back as a
*field* so the pairing could be checked, and never told it to *say* it. The offline templates always
named it — so the model's prose was strictly worse than having no model at all, which is the detail that
makes failing closed the obviously right call here.

**Three changes.**
1. **The prompt** now requires the body to name the finding's category, spelled exactly as given.
2. **`InsightEngine.concentration`** kept only the amounts — it sorted `values` and discarded the keys —
   so "Top Categories" *could not* name them however the prompt was worded. It now carries
   `topCategories`, the one genuinely new field in the payload.
3. **The guard is the part that matters.** `InsightNarrator.pair` now rejects a card whose title and body
   between them fail to name its subject, and falls back to that finding's template. An instruction is a
   request; this makes it a property of the output. A paraphrased name ("Food and Drink" for "Food &
   Drink") is rejected too — deliberately, because a renamed category cannot be checked against the donut
   on the same screen.

**Cost: about 4% more per call** (~1,570 → ~1,635 tokens), one call for the whole carousel, cached per
cycle. The only new data leaving the device is three category names on one card — the same class of value
every other card's `category` already carried.

`InsightNarratorTest`'s standing key-set guard was updated too: its "fully populated" finding did not set
`topCategories`, so the new key would have escaped the exact-key-set assertion entirely while the test
still claimed the set was maximal.

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
