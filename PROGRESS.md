# Spends — Live Progress

Live state pointer. Update this at every phase/release boundary. Read `CONTEXT.md` first
for how the project works.

## Current release
- **Shipped: v1.51.0** — versionCode **55**, versionName **"1.51.0"**
  (`app/build.gradle.kts` lines 41–42). Release CI green (signed APK + AAB).
- **DB schema: v15.** (MIGRATION_14_15 = additive `merchant_categories.note` TEXT column.)
- **Branch:** `main`, clean. Tag-driven CI.
- APK: https://github.com/aucksy/spends/releases/download/v1.51.0/Spends-v1.51.0.apk

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
