# Spends — Complete Feature Inventory

**Version audited: v1.69.0** · **Date: 3 August 2026** · **Total features: 86**

> 📌 **Amended for v1.70.0 (not a re-audit).** v1.70.0 added three marketable things this inventory does
> not otherwise list — **income analytics**, **multi-currency (INR / MYR / USD)** and **optional AI
> currency conversion**. They are described at the end of Section A. Everything else below is still the
> v1.69.0 audit and has not been re-read against the code.

Built for marketing use (carousel slides, store listing, feature comparisons).

**How this was produced:** every screen, tab, settings page, dialog, notification, home-screen widget and
background job was read in the **shipping source code**, screen by screen. The documents in this repo were
read afterwards, only to spot where they disagreed with the code. **Where they disagreed, the code won** —
those disagreements are listed in Section C.

> ⚠️ **The AI helper and the carousel of "insight cards" do not exist.** They were removed entirely in
> **v1.65.0**. Five documents in this folder still describe them in detail (`AI-BUILD-PLAN.md`,
> `AI-INSIGHTS-PLAN.md`, `AI-INSIGHTS-PHASE-B.md`, `AI-INSIGHTS-PHASE-C.md`, `AI-RESEARCH.md`), as do older
> changelog entries. **Nothing in this inventory comes from those documents.** Do not market them.
>
> ⚠️ **This is NOT the same thing as v1.70.0's AI currency conversion.** That feature converts a
> foreign-currency bank alert and sends *only an exchange-rate question* — no amounts, no merchants, no
> message text. The removed helper sent masked transaction data and produced insight cards; none of that
> came back. When writing copy, never let the two blur together: the difference is exactly what makes the
> new one defensible on the Play data-safety form.

**Ranking basis:** how many users it helps × how much pain it removes × how rare it is in other expense
apps. Not by how hard it was to build.

- **Tier 1 — Hero** (12) · the reasons someone downloads it
- **Tier 2 — Strong** (29) · real, repeated daily value
- **Tier 3 — Supporting** (27) · good, but nobody switches apps for it
- **Tier 4 — Housekeeping** (12) · exists, not worth a slide
- **Section B — Present but not marketable** (6) · counted for honesty

---

## TIER 1 — HERO

| # | Carousel headline | What it does for me | Where I find it |
|---|---|---|---|
| 1 | **One balance. Every card.** | Bank, UPI and every credit card add up to a single "what's actually left" figure, instead of you guessing across five apps. | Settings → Money & Cycles → Smart Cycle; then the big balance card on Transactions |
| 2 | **Your bank texts, one tap** | The second your bank SMS lands, a notification pops up with the amount already filled in. No typing. | Automatic once switched on at Settings → Automatic Entries → Detect from SMS |
| 3 | **Nothing added behind your back** | Every detected spend waits for you to say yes. The app never silently writes to your ledger. | The "Review & Add" button on the notification, and the Review queue |
| 4 | **Your money never leaves your phone** | No account, no sign-up, no ads, no tracking. The one and only thing that ever goes online is your own Google Drive backup, if you switch it on. | Nothing to find — it's how the whole app is built |
| 5 | **Catches chat-style bank alerts** | Some banks send alerts through Google Messages or Truecaller instead of SMS. Almost no tracker can read those. This one can. | Settings → Automatic Entries → Detect from app notifications |
| 6 | **Backfill a year in seconds** | Just installed? Scan up to 12 months of old bank texts and get your history without typing a thing. | Settings → Automatic Entries → Detect from SMS → Scan past SMS |
| 7 | **Every card, its own cycle** | A swipe on the 20th lands in the statement that will actually bill it — not in today's calendar month. | Settings → Money & Cycles → Banks & Cards (set each card's billing day) |
| 8 | **It learns your shops** | Categorise a shop once and it remembers. The next alert from that shop arrives already sorted. | Happens automatically each time you confirm a detected spend |
| 9 | **Split one bill, many categories** | One ₹4,000 supermarket run becomes groceries + household + baby, each its own clean entry, with a running "₹ left to assign". | The + button → tap Category → "Split" |
| 10 | **Maths inside the amount box** | Type `1200+350` and it works it out. Splitting a dinner bill needs no second app. | The + button (and every amount field in the app) |
| 11 | **Budget by payday, not the 1st** | Your month starts on the day you're actually paid, so "what's left" means something. | Settings → Money & Cycles → Salary day |
| 12 | **Hide every figure instantly** | One tap blanks all amounts on screen, and they re-hide themselves after 15 seconds. For the metro, the office, anywhere. | The eye button, bottom-left of Transactions and Analytics |

---

## TIER 2 — STRONG

| # | Carousel headline | What it does for me | Where I find it |
|---|---|---|---|
| 13 | **A queue you can search** | Everything detected waits in one list you can search by amount, shop, bank, card digits — even the raw message text — and filter to just income or just spending. | Detect from SMS → Review queue |
| 14 | **See the original message** | Not sure about an entry? Read the exact bank text it came from, sender and time included. | "View SMS" on any card in the Review queue |
| 15 | **Stop nagging me about this** | Ignore the same alert three times and it stops interrupting you — but still files it in your review list, so nothing is lost. | Automatic, via "Ignore" on the notification |
| 16 | **Undo an accidental silence** | Everything you've silenced is listed, plus everything one tap away from being silenced, each with an "Ask me again" button. | Detect from SMS → Silenced alerts |
| 17 | **Finds your cards for you** | Scans your texts, spots your credit cards, and even works out each card's statement day. You just confirm. | Banks & Cards → Scan past SMS for cards |
| 18 | **Tags the right card itself** | A detected spend is matched to the card by its last four digits, so the per-card totals stay honest. | Automatic once your cards are added |
| 19 | **Never counts a spend twice** | The same purchase arriving as both a text and a chat alert, or a re-scan of the same month, is recognised as one thing. | Automatic |
| 20 | **Rent and EMIs log themselves** | Set rent, salary, EMIs and subscriptions once — daily, weekly, monthly or yearly, every N periods — and they appear on schedule. | Settings → Automatic Entries → Recurring transactions |
| 21 | **Tells you what it added** | The reminder names the actual transaction and amount, with an Edit button that opens that exact entry — not a vague "1 item added". | The notification, around 9 AM (time is yours to set) |
| 22 | **EMIs that stop themselves** | "End after 12 times" — a fixed run finishes on its own instead of billing you forever. | Recurring → new rule → End after a set number |
| 23 | **Why did this appear?** | Any auto-created transaction has a banner taking you straight to the rule that keeps creating it. | Tap any transaction created by a rule |
| 24 | **Your balance on the home screen** | Income, expense and balance for your cycle, hidden by default, revealed by a tap, re-hidden after 5 seconds. | Long-press home screen → Widgets → Spends |
| 25 | **Add a spend without opening the app** | A home-screen button that goes straight to the keypad. Shows no figures, so nothing is on display. | Long-press home screen → Widgets → Spends quick-add |
| 26 | **Where the money actually went** | A category donut with a tappable list showing each category's share and percentage. | Analytics tab |
| 27 | **"More than your usual month"** | Instead of two numbers to compare yourself, it says it in a sentence: about ₹1,500 more than usual. | Analytics → tap any category |
| 28 | **Compare year against year** | Switch to Yearly and see this year against the last year with data — per month on both sides, so a half-finished year still compares fairly. | Analytics → tap a category → Yearly |
| 29 | **Find any transaction** | Search your whole timeline by shop, note or category. | Magnifier icon at the top of Transactions |
| 30 | **Fix twenty rows at once** | Long-press to start selecting, then bulk delete or bulk re-categorise — with an Undo. | Long-press any transaction row |
| 31 | **Backup to your own Drive** | Everything — transactions, splits, categories, rules, cards, settings — into a visible "Spends Backup" folder in your Drive. Nobody else's server. | Settings → Backup & Restore → Back up now |
| 32 | **Backs itself up daily** | Once a day at a time you choose. If you're offline it simply catches up later. | Settings → Backup & Restore → Daily auto-backup |
| 33 | **Export like a bank passbook** | A real Excel file with Income, Expenses and a running Balance column, plus split details — for one cycle or the lot. | Settings → Data & Trash → Export Excel |
| 34 | **Bring your spreadsheet in** | Import Excel or CSV; new rows are added, duplicates skipped, all your categories preserved. | Settings → Data & Trash → Import Excel/CSV |
| 35 | **Leftover rolls into next month** | Underspent? It carries forward, from a start date and opening balance you set. | Settings → Money & Cycles → Carry forward |
| 36 | **Deleted isn't gone** | Everything deleted sits in a Trash bin you can restore from; it clears itself after 30 days. | Settings → Data & Trash → Trash |
| 37 | **Which card spent what** | This cycle's total broken down card by card, plus a bank/UPI bucket, all reconciling to the same figure. | Analytics → Per-instrument breakdown |
| 38 | **New spends pick your usual card** | Choose a default card and every new expense starts there. | Banks & Cards → Default for new expenses |
| 39 | **Manage banks and cards** | Add, edit or delete each card and account: name, last four, bank, billing day, due day — with each card's cycle spend and transaction count on the list. | Settings → Money & Cycles → Banks & Cards |
| 40 | **Any period you want** | Month, salary cycle or Smart Cycle, across current / last 3 / last 6 / all time / a custom range. | The date pill at the top of Transactions and Analytics |
| 41 | **Step back through cycles** | Arrows move you a cycle at a time, with a one-time dot when card spends have rolled into the next one. | The ‹ › arrows on the date pill |

---

## TIER 3 — SUPPORTING

| # | Carousel headline | What it does for me | Where I find it |
|---|---|---|---|
| 42 | **Lock your backup with a password** | Optional strong encryption. Off by default, so a forgotten password can never lock you out of your own data. | Backup & Restore → Backup encryption |
| 43 | **A safety copy before restoring** | Restoring saves your current data to Drive first, in case you picked the wrong file. | Automatic during a Drive restore |
| 44 | **Backup without any account** | Export a backup file to your phone or SD card and restore from it. No Google needed at all. | Backup & Restore → Export file / Restore file |
| 45 | **Recognises Monito exports** | Coming from Monito Expense Manager? Its export is detected and imported with every category intact. | Settings → Data & Trash → Import Excel/CSV |
| 46 | **Import on day one** | Bring your spreadsheet in during setup and land straight in the app with your history. | Onboarding → "How do you want to start?" → Import from Excel |
| 47 | **Restore on day one** | New phone? Pull your Drive backup during setup, and it even brings your salary day so you don't re-pick it. | Onboarding → "How do you want to start?" → Restore from Drive |
| 48 | **Categories, your way** | Add, rename, re-icon, archive or delete. | Settings → Categories |
| 49 | **Icons and colours, automatic** | Type "Petrol" and it picks a fuel icon and a distinct colour. No fiddly pickers unless you want them. | Whenever you create a category |
| 50 | **Or pick your own icon** | Around 90 icons in eight groups, if the automatic one isn't right. | Any category → tap the icon → Change |
| 51 | **27 categories ready to go** | 19 spending and 8 income categories exist from the first launch. | Automatic on first install |
| 52 | **Deleting can't lose history** | Delete a category that's in use and it's archived instead, so old transactions keep their name. | Settings → Categories → delete |
| 53 | **Spending over time** | A bar view of how the period's spending was spread out. | Analytics → Spend over time |
| 54 | **Your recurring bills at a glance** | Daily, weekly, monthly and yearly totals with a count of active rules. | Analytics → Recurring card |
| 55 | **Jump to any month** | In All-time view, tap the calendar and land on any month that has data, instead of scrolling to 2022. | Calendar icon on the date pill, All-time only |
| 56 | **Both sides of the day** | Each day header shows that day's spending and income separately, not one blended figure. | Transactions timeline |
| 57 | **Know where a row came from** | A tiny mark shows whether an entry came from SMS, a chat alert, a repeating rule, an import, or your own typing. | Transactions timeline |
| 58 | **This category's cycle total** | Every row shows what that whole category has cost you this cycle, right under the entry. | Transactions timeline |
| 59 | **Light, dark, or on a timer** | Follow the system, force one, or have dark switch on between two times you set. | Settings → Appearance → Theme |
| 60 | **Open where you want** | Start on the timeline or straight on Analytics. | Settings → Appearance → Open on |
| 61 | **An invisible reveal button** | Make the widget's eye invisible but still tappable, so nobody can tell the figures can be revealed at all. | Settings → Appearance → Hide the widget's reveal button |
| 62 | **Pick any date range** | Choose a start and end date with month, year and day pickers. | Date pill → Custom range |
| 63 | **"Billed this cycle" tag** | A row dated outside the cycle's printed dates says why it belongs there — your card's statement date. | Category drill-down |
| 64 | **Keep bulk scans out of the way** | Hide transactions from a past-SMS scan from the timeline while still counting them in your totals. | Detect from SMS → Hide bulk-scanned in timeline |
| 65 | **Undo a whole scan** | Clear the review queue, or clear it and delete everything a scan already added. Manual entries are never touched. | Detect from SMS → Delete scanned SMS data |
| 66 | **One statement day for all cards** | Several cards billing on the same day? Tick them and set it once. | Banks & Cards → Set common billing day |
| 67 | **Rejected cards can come back** | Marked something "not a card" by mistake? It's listed under Dismissed with a Restore button. | Banks & Cards → Dismissed |
| 68 | **Show the app safely** | Demo mode opens a made-up account with 14 months of data so you can show anyone the app. Your real data is hidden, not touched, and a permanent strip says it's a demo. | Settings → Data & Trash → Demo mode |

---

## TIER 4 — HOUSEKEEPING

| # | Carousel headline | What it does for me | Where I find it |
|---|---|---|---|
| 69 | **A five-step setup** | Welcome, SMS, battery, how to start, salary day. Skippable where it should be. | First launch |
| 70 | **Keeps detection alive** | Asks permission to sit outside battery optimisation so aggressive phones don't kill detection. | Onboarding, step 3 |
| 71 | **Half-typed entries survive** | Switch apps mid-entry and come back — your amount, category and splits are still there. | The + keypad |
| 72 | **Asks before discarding** | Closing a half-filled entry asks first instead of silently binning it. | The + keypad |
| 73 | **The keypad can't be swiped away** | No swipe-to-dismiss at all, so a stray thumb can never wipe what you typed. | The + keypad |
| 74 | **Back does the sensible thing** | Back closes search or clears a selection before it closes the app. | Transactions timeline |
| 75 | **Back to the top** | A button appears after about 25 rows. | Transactions timeline |
| 76 | **Indian number formatting** | ₹12,34,567.00, not 1,234,567. Money is held as exact paise, so rounding errors can't creep in. | Everywhere |
| 77 | **Month-end dates behave** | A salary day of 31 adjusts itself for February and 30-day months. | Automatic |
| 78 | **A small buzz on every tap** | Keypad, cycle arrows, date picks and the widget buttons all give a tick. | Everywhere |
| 79 | **A quiet brand splash** | A short branded screen on cold start. | Launch |
| 80 | **Trash empties itself** | Anything deleted more than 30 days ago is cleared on launch. | Automatic |

---

## A) UNIQUELY OURS

Genuinely rare or absent in typical expense apps. **These are the carousel slides.**

1. **Reading bank alerts that arrive as chat messages (RCS / Truecaller Business Chat)** — the single rarest
   thing in the app. Every SMS tracker breaks when a bank switches a customer to RCS; this one keeps working.
2. **One balance across bank, UPI and every card, bucketed by each card's billing day** — most apps show a
   calendar month. This answers "what's actually left of this salary", counting card spends against the
   statement that will bill them.
3. **Nothing is ever added without you confirming it** — most SMS trackers auto-add and leave you cleaning
   up. This is a deliberate, marketable stance, not a limitation.
4. **Learn-from-ignore, with a visible undo** — it stops nagging after three ignores, and there is a screen
   listing everything it silenced with an "Ask me again" button. The undo is the rare half; plenty of apps
   suppress, almost none let you see and reverse it.
5. **Finding your credit cards in your own SMS history, statement day included** — you confirm; it digs.
6. **Splitting one payment across categories with a live "₹ left to assign"** — and each slice becomes its
   own clean transaction rather than a nested oddity.
7. **A calculator inside the amount box** — rare outside Monito, and it removes a real daily annoyance.
8. **Privacy that is actually visible** — an eye that blanks every figure and re-hides itself, a widget
   masked by default, and an option to make the widget's reveal button invisible-but-still-tappable.
9. **Truly one network call** — no account, no analytics, no ad SDK, no crash-reporting service. Backup goes
   to *your* Drive folder, which you can open and see.
10. **"About ₹1,500 more than your usual month"** — the comparison is written as a sentence, and it says
    "so far" while a cycle is still running rather than congratulating you at the halfway mark.

---

## B) PRESENT, BUT NOT MARKETABLE

Counted so the total is honest. **Keep off the carousel.**

| # | What it is | Why it's here |
|---|---|---|
| 81 | **SMS debug screen** | Owner-facing diagnostic showing whether bank texts are reaching the app and where each one stopped. Marked "Temporary" in the code, but **reachable by any user** via Detect from SMS. |
| 82 | **Notification debug screen** | Same, for the chat-alert reader. Also temporary, also user-reachable. |
| 83 | **Crash report copy button** | Appears after a crash, offering to copy a technical note. Contains no amounts or message text. |
| 84 | **Salary-day auto-detector** | Code that guesses your salary day from your income history. **Fully written and unit-tested, but nothing in the app calls it — it is dead code.** Do not market it. |
| 85 | **Old-format ignore-record cleanup** | A one-time tidy-up of records left by an earlier version of the ignore feature. Invisible. |
| 86 | **Leftover API-key erasure** | Every launch checks for and deletes the stored key the **removed v1.56–v1.64 helper** used. Invisible, runs once. Still runs in v1.70.0: the new conversion feature stores its key under a different name, so a key the user was told had been deleted is never quietly adopted. |

---

### Added in v1.70.0 (amendment — see the note at the top of this file)

| # | Feature | What it is |
|---|---|---|
| 87 | **Income analytics** | *Tier 1.* Analytics gains a **Spending / Income** toggle that drives the whole page: the category donut, its tappable legend and drill-down, and the over-time bars all switch sides of the ledger. Income finally has the same breakdown spending has always had — the answer to "where did it come from?", not just "where did it go?". |
| 88 | **Multi-currency (INR / MYR / USD)** | *Tier 1.* Keep the books in rupees, ringgit or dollars. Symbol and digit grouping follow the choice (Indian `12,34,567` for rupees, `1,234,567` otherwise), across the app, the widget, notifications and spreadsheet exports. **No stored amount is ever rewritten.** Travels in the backup. |
| 89 | **AI currency conversion (BYOK, off by default)** | *Tier 2.* A bank alert in another currency is converted on the way in and **shows its working**: `RM 100.00 → ₹1,890.00 · 1 MYR = ₹18.90`, on the review card, in the editor and on the saved transaction. Your own key (Anthropic / OpenAI / Google Gemini / Groq), encrypted on-device, never in a backup, never shown back. Only a rate question is sent. Market it as *"shows you the rate it used"*, and say plainly that the rate is an estimate. |
| 90 | **Pinned exchange rates** | *Tier 3.* Set your own rate for a currency pair and Spends uses it instead of asking the AI — no key, no network call. The escape hatch when an estimate has drifted. |
| 91 | **Unconverted-amount guard** | *Tier 4 (invisible until it matters).* If a foreign amount can't be converted, it is kept in the review queue **flagged, in its original currency**, and refused by every path that commits without an editor — including "Add all". It is never logged as though it were base currency. |
| 92 | **Malaysian sender recognition** | *Tier 3.* 13 Malaysian banks and wallets (Maybank, CIMB, Public Bank, RHB, Hong Leong, AmBank, Bank Islam, BSN, OCBC, UOB, HSBC, Touch 'n Go, Boost, GrabPay) join the 55 Indian sender IDs. |

---

## C) DOCUMENT vs CODE MISMATCHES — found, and corrected

All of the following were claims the documents made that the app does **not** do. **All were corrected on
3 August 2026** in the same change that added this file. Recorded here so they don't creep back.

| Claim | Where it was | What the code actually does |
|---|---|---|
| **"Swipe-to-delete with instant Undo"** | README **and the Play Store listing draft** | **There is no swipe gesture anywhere.** It was deliberately removed — too many accidental deletes. Deleting is tap-a-row, or long-press → multi-select. Undo exists, on the bulk delete. ⚠️ This was in copy about to be published to Play. |
| **"Material You colours"** | Play Store listing draft | **Material You was removed in v0.12.0.** The app always uses its own brand palette. The old setting survives only so pre-v0.12 backups still load; the theme ignores it. |
| **"Add it in one tap" / "one-tap Add / Edit / Ignore"** | README, store listing, SMS declaration, privacy policy | The notification has **two** buttons: **Review & Add** and **Ignore**. Both it and tapping the notification open a pre-filled editor you then Save. The silent one-tap Add was deliberately removed. Honest wording: *"one tap to review, one to save."* |
| **"~25 Indian banks, cards and wallets"** | README | The list holds **55 sender IDs covering 16 named institutions**: HDFC, ICICI, SBI, SBI Card, Axis, IDFC First, IndusInd, Yes, RBL, PNB, Amex, OneCard, CRED, Paytm, MobiKwik, L&T Finance. *(v1.70.0 adds 13 Malaysian institutions alongside these.)* |
| **AI helper and insight cards** | Five documents in this folder, plus older changelog entries and the demo-mode notes | **Confirmed absent from the code** as of v1.69.0, and still absent in v1.70.0. Those five documents remain the main trap for a future session — and v1.70.0 adds a second trap next to it: the app now has a *different* AI feature (currency conversion), so "the AI helper is gone" and "the app makes no third-party call" are no longer interchangeable statements. The first is still true; the second is not. |
| **"App lock"** | **The app's own Settings screen** says *"App lock arrives in an upcoming update"* | There is no app lock, fingerprint or PIN anywhere in the code. **This promise is on-screen for users today** — still outstanding: either build it or remove the line. |
| **Data-safety files written as a re-submission** | Play data-safety answers, the click-by-click walkthrough, the submission checklist | All three told the reader to *correct a declaration already filed with Play*. **Nothing has ever been filed.** Reframed as a first submission. |
| **Category drill-down "shows a monthly average"** | README | Out of date rather than wrong: since v1.67 the screen **leads with this cycle's total** and states the comparison in words. The 3M / 6M / all-time average is still there, as the reference bar underneath. |

### Still outstanding after this pass

- **The app's own onboarding screen** still says a bank alert lets you *"add it in one tap"*, while the
  button really says **Review & Add**. Every document now describes the real two-step flow; only the app is
  out of step. The SMS permissions declaration quotes that on-screen line verbatim for Play reviewers, so
  the two must be changed together.
- **"App lock arrives in an upcoming update"** is still shown in Settings, with no app lock in the code.

### Notes on merged features

To avoid double-counting: "detect from SMS" and "detect from notifications" are counted as **two** features
(different permissions, different sources, different limits — the chat-alert one is the rare one). "Auto
icon" and "auto colour" are merged into one line (#49). The Smart Cycle reset day is folded into #1 and the
salary day into #11, rather than each being listed separately.
