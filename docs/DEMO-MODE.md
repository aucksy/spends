# Demo mode

A toggle in **Settings → Data & Trash** that swaps the whole app over to a fabricated sample account, so
every feature can be shown to someone without exposing real finances — and without putting them at risk.

---

## The safety model (read this first)

Demo mode does **not** hide, filter or tag your real rows. It swaps the stores that hold financial data or
data-derived state:

| | Live | Demo |
|---|---|---|
| Room database | `spends.db` | `spends-demo.db` |
| Settings DataStore | `settings.preferences_pb` | `settings_demo.preferences_pb` |
| Period selection | `period_selection.preferences_pb` | `period_selection_demo.preferences_pb` |

**While demo mode is on, the live database file is never opened** — not read, not written, not migrated.

Period selection is swapped because it stores `selectedCardId`, a `payment_methods` row id, and row ids in
the two databases are unrelated — sharing it would mean demoing the Single-Card view on the demo's card left
the *real* app opening on whichever real card had the same id. Demo mode must not change how the real app
behaves.

Deliberately **not** swapped, because none of them holds financial data: `spends_secure` (the Groq key — so
the AI helper still works in the demo), the widget mask store, and the backup-metadata store (its only
consumer, the Backup card, is hidden in demo mode anyway).

That is a categorically stronger promise than the obvious alternative (a `isDemo` column plus a filter on
every query), where demo rows and real money share the same tables and a single forgotten `WHERE` clause puts
invented transactions into a real balance. Here there is nothing to forget: the file isn't open.

The decision point is a single expression in [`DatabaseModule`](../app/src/main/java/com/spends/app/di/DatabaseModule.kt)
and one in [`SettingsModule`](../app/src/main/java/com/spends/app/di/SettingsModule.kt).

### Why the flag lives in SharedPreferences

`DemoMode` reads its flag from a `SharedPreferences` file, not DataStore, because it must be readable
**synchronously, before Hilt builds the object graph** — it decides which files Room and DataStore open.
DataStore is suspend-only and is itself one of the things being switched, so it cannot hold this.

That preferences file is never swapped, which is what lets the flag survive a mode change.

### Why flipping the toggle restarts the app

`SpendsDatabase` and `SettingsRepository` are `@Singleton`. Every repository, ViewModel and collected Flow in
the process holds the instance built at startup. There is no supported way to swap that out under a live
graph, and a partial swap would leave half the app reading one database and half the other.

So `DemoMode.restartInto()` writes the flag with `commit()` (not `apply()` — the process dies on the next
line and an async write would be a coin flip), starts the launcher intent **while still foreground** (a start
scheduled for after death would hit Android 10+ background-activity-start restrictions), and calls
`Runtime.getRuntime().exit(0)`. The new process reads the new flag and builds one consistent graph.

Both directions are behind a confirmation dialog, because an unexplained relaunch mid-demo reads as a crash.

### What is switched off in demo mode

| Blocked | Where | Why |
|---|---|---|
| Drive + local **backup** | `BackupRepository.buildSnapshot` | would upload fabricated data to the real Drive folder, where it sits in the restore picker looking genuine |
| Drive + local **restore** | `BackupRepository.applySnapshot` | would unpack real data into a sandbox that the next reset deletes |
| Daily auto-backup | `BackupWorker.doWork` | same as above, unattended |
| Live **SMS** capture | `SmsReceiver.onReceive` | a real bank alert captured into the sandbox is destroyed by the next reset — a genuinely lost transaction |
| Live **notification** capture | `CaptureNotificationListenerService.onNotificationPosted` | same |
| **"Scan past SMS"** | `SmsCaptureRepository.scanHistory` | reads the *real* inbox — would queue genuine bank alerts (raw bodies, balances, card digits) into the demo review queue, on screen, then delete them |
| **"Scan for cards"** | `SmsCaptureRepository.scanInboxForCards` | same inbox, would write real card last4 + institution into the sandbox |
| Notification **shade sweep** | `CaptureNotificationListenerService.onListenerConnected` | the only recovery path notifications have — an alert swept into the sandbox is lost permanently |
| Capture prompt **Add / Ignore / Edit** | `CaptureActionReceiver`, `MainViewModel.handleCaptureEdit` | a prompt posted *before* the switch stays in the tray; tapping Add would write a real transaction into the sandbox |

The last four were missed in the first pass and caught in review. They are the non-obvious half of the
problem: gating the two *live* capture entry points is the part that suggests itself, but the historical
scans, the reconnect sweep and the stale tray notification all reach the same place by other routes.

`buildSnapshot` / `applySnapshot` are the only two chokepoints for backup and restore — every public path
(Drive backup, Drive restore, local `.spsenc` export, local import, the worker) funnels through one of them,
so guarding the pair covers all of them without six checks a later edit could forget. The **Backup & Restore**
card is also hidden from the settings hub in demo mode, so the refusal is never something you discover by
tapping.

Spreadsheet import/export stays available — both operate entirely inside the sandbox and exporting the demo
to Excel is itself worth demonstrating.

### The permanent marker

`DemoModeWrapper` puts a non-dismissible **"DEMO MODE — sample data, not your money"** strip above every
screen. This is a safety control: every figure on screen is invented, and without a permanent marker it would
be possible to glance at the app, read a balance and act on a number that means nothing. When demo mode is
off the wrapper emits its content and nothing else — no extra layout node.

**The home-screen widget is the one surface the wrapper cannot reach**, and it is precisely where someone
glances at a balance without opening anything. So in demo mode `SummaryWidget` force-masks its figures and
its header reads "DEMO MODE — sample data". Real numbers return on the next refresh after switching back.

### The seeder's own guard

`DemoDataSeeder` deletes every row in the database it is pointed at, so it refuses to run unless **both**
`DemoMode.isEnabled()` **and** `db.openHelper.databaseName == DemoMode.DEMO_DB_NAME`. The second check is the
one that matters: it inspects the file actually being written rather than trusting a flag, so a future
refactor that got the wiring wrong fails loudly instead of wiping real money.

---

## The sample account

Generated by [`DemoScript`](../app/src/main/java/com/spends/app/data/demo/DemoScript.kt) — pure, deterministic
and unit-tested, with no Room, Android or clock access (the caller passes `today` in).

**~14 months of history** (3 recent + 11 baseline), about 45 transactions a month, plus two credit cards with
different billing days, a bank account and a UPI handle, seven recurring rules, a seven-row review queue, ten
learned merchants, three items in the trash, four split transactions, two custom categories and one archived
one.

### Volume is flat on purpose

The obvious design — recent months dense, older months sparse — quietly ruins the data. If the last three
months carry ~1.8× the transactions of the baseline then *every* category shows an ~80% jump three months
ago, every trend reading fires at once, and the one genuine anomaly is lost in the noise. A baseline only
earns its keep if it is comparable.

So transaction volume is uniform across the whole span (`UNIFORM_MONTHLY_VOLUME`), and the current month is
scaled by how much of it has elapsed. Recency is expressed through *scripted texture* — splits, trash, the
review queue, mixed capture sources, the planted stories — not through more rows.

### The planted stories

Each exists so a specific feature (and each planned AI insight) has something real to say:

| Pattern | Demonstrates |
|---|---|
| A burst of **Business Expenses** charges in the last few days, ~5× that category's own norm | unusual-spending / anomaly detection |
| One **Fuel** charge at ~3× the usual | "today's fuel is 3× your average" |
| A same-day, same-amount pair at **BookMyShow** | duplicate-charge detection |
| **Food** drifting up ~50% across six months | category trend analysis |
| Last year's equivalent months ~15% pricier | year-on-year comparison |
| Spending ~32% heavier in the week after payday | habit discovery |
| Discretionary categories ~22% heavier at weekends | habit discovery |
| **Entertainment** well below its own norm recently | "quiet wins" |
| Rent, EMI, SIP, subscriptions as recurring rules | commitments / needs-vs-wants |

The anomaly is placed by fixed day offsets rather than a monthly multiplier, so it is present and recent
whatever day the demo runs — a multiplier has almost nothing to act on if you demo on the 3rd of a month.

### Recurring rules and `nextRunAt`

`DemoScript` writes every past occurrence as a real `RECURRING`-sourced transaction, and the seeder points
each rule's `nextRunAt` at the first occurrence **after today**. If it were left in the past, the app's
materialiser would run on next launch and generate that same history a second time — doubling rent, EMI and
SIP in the demo's balance. `stepRecurring` delegates to the app's own `RecurrenceMath` so the stored
`nextRunAt` agrees exactly with what the materialiser will compute from it.

### Demo settings

Written to the demo DataStore: salary day 25, Smart Cycle on (following the salary day), carry-forward on and
anchored at the start of the recent window with a ₹1,25,000 opening balance, trash retention 30 days. Capture
and auto-backup off. If a Groq key is already present the AI helper is switched on so its features are
demoable — the key is device-local and shared, not part of the swapped settings file.

`onboardingComplete = true` is the load-bearing one: a fresh preferences file defaults it to false, which
would drop the demo into the welcome flow instead of the app.

### Freshness

Demo data is dated relative to today, so it re-seeds once per calendar day (`DemoMode.needsSeed`) — data
seeded a week ago would open on a cycle that ended a week ago. Within a day it is left alone, so anything
added mid-demo survives an app restart. **Reset demo data** forces a rebuild at any time.

Seeding happens on the first launch after switching in, behind the splash screen (`MainViewModel` holds
`MainUiState.loading` until it finishes), so the demo never populates itself in front of an audience.

---

## Tests

`DemoScriptTest` runs every case over five fixed dates — including a 31st, an early-in-month date and a leap
day — so month-end clamping is exercised. `DemoScript` is deterministic, so these can never be flaky.

Two kinds of assertion, and the second is the interesting one:

1. **Structural** — money conserved across splits, nothing dated in the future, every category name real,
   review-queue rows distinguishable (they share a `UNIQUE` index, so a collision would be silently dropped).
2. **The scripted stories are actually present** — the anomaly really is anomalous, the quiet win really is
   quieter, the trend really climbs, volume really is flat. Without these, a drift in the generator would
   leave demo mode silently *claiming* to demonstrate anomaly detection while giving it nothing to find, and
   nobody would notice until it fell flat in front of an audience.

---

## Known limits

- **The restart is a real process kill.** If Android declines the launch intent, `restartInto` rolls the flag
  back and returns false rather than killing the process, and the user is told to reopen the app — but that
  path is unverified on a real device. The fallback would be ProcessPhoenix's two-process pattern, rejected
  here because a second process running `@HiltAndroidApp` alongside WorkManager and the notification listener
  is a larger risk than the one it solves.
- **Device-level schedules follow the demo settings during a demo.** `SpendsApp.onCreate` arms the recurring
  reminder alarm from whichever settings file is active, so a user with a 21:00 reminder gets the 09:00
  default while demoing. Self-heals on the next non-demo launch.
- Demo data re-seeds daily, so a demo left open across midnight shows data one day stale until reset.
- `widgetEyeHidden` lives in the swapped settings file, so a user who hid the widget eye sees it reappear
  inside the demo.
- The anomaly baseline is computed over 14-day windows and Business Expenses runs at ~2/month, so some
  baseline windows are legitimately empty. Fine today (the observed ratios are 8×–24× against a 2× bar), but
  it is the assertion most likely to need attention if the test's date list grows.
