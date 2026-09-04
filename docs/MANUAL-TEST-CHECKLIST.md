# Manual test checklist — things only a phone can confirm

A **running** list. Every release appends a section; nothing is deleted until it has actually been
checked on a device. Cloud CI proves the code compiles and the maths is right; it cannot see a screen,
a notification, or a real bank SMS arriving.

**How to use it:** tick as you go, in any order. If something is wrong, note what you saw next to the
box — "said 3× but it was more like 1.5×" is enough to act on. Anything left unticked is simply still
unknown, which is fine; it just isn't confirmed.

**Priority when time is short:** do the ⭐ items first. They are the ones where being wrong means a
wrong number about real money, rather than something looking untidy.

**Currently untested: v1.57.0 → v1.72.0.** Install **v1.72.0**; it contains every one of them.

> ⚠️ **Four sections below are now DEAD and must not be worked through.** v1.60.0, v1.61.0, v1.62.0 and
> v1.64.0 test the AI insight cards, which were **removed entirely in v1.65.0**. There is no carousel and no
> AI helper in the app any more, so those boxes can never be ticked. They are kept only so it is obvious they
> were superseded rather than forgotten. The same applies to the "summary card's vs last cycle" item in
> *Highest value* — that was an insight card.

> **The capture bug is found and fixed in v1.63.2.** A Java-only regex flag made the SMS parser throw on
> any real Android phone the first time it was touched, and the error was being swallowed — so bank texts
> silently stopped becoming transactions from **v1.58.0** onward, which matches exactly when capture
> stopped working. The same copied line is what crashed the v1.63.0 debug screen. **The single most
> valuable thing to check now is whether a real bank text captures again.**

---

## v1.72.0 — Google Gemini is now one of the AI providers

You have a Google AI Studio key. Until now the provider list held Anthropic, OpenAI and Groq only, so that
key had nowhere to go and every foreign alert stayed flagged and unconverted. Nothing else about the
feature changed — same switch, same one rate question sent, same rate line on the transaction.

- [ ] ⭐ **Settings → Currency & AI → Convert with AI → Provider** now lists **Google (Gemini)**. Pick it.
      The Model row should immediately read **gemini-3.5-flash-lite (default)** — switching provider clears
      whatever model was there, so it should never show a leftover Claude or Llama name.
- [ ] ⭐ Paste your AI Studio key and press **Test key**. It should say **Working** within a few seconds.
      This is the single most important box on the page: it is the only proof the new provider actually
      talks to Google.
- [ ] Try **Test before saving** inside the key sheet too, with the key typed but not yet saved.
- [ ] ⭐ Paste a deliberately wrong key (change a few characters) and press Test. You should get a readable
      sentence — *"That key or model was rejected…"* — **not** a bare `HTTP 400`. Google reports a bad key
      differently from the others, and that translation is new in this release.
- [ ] Type a nonsense model name (e.g. `gemini-does-not-exist`) and Test. Expect a readable failure, then
      clear the field and confirm it goes back to working.
- [ ] Paste the model with Google's own prefix — `models/gemini-3.5-flash-lite` — and Test. It should
      still work; the prefix is stripped for you rather than 404ing.
- [ ] ⭐⭐ **The real thing.** With Gemini selected and the key saved, let a genuine foreign card alert
      arrive (or forward yourself the exact text of one). The review card should show the converted rupee
      amount **with the rate line under it**, e.g. `RM 87.48 → ₹1,650.00 · 1 MYR = ₹18.86`. Sanity-check
      that rate against what Google says today — it is an estimate, but it should not be wildly off.
- [ ] ⭐ Save that transaction and confirm the saved row still carries the original amount and the rate.
- [ ] Check a second foreign alert in the same currency arrives quickly and quotes the **same** rate —
      that means the six-hour cache is working and you are not paying for a call per message.
- [ ] ⭐ **Nothing broke for the other three.** If you have an Anthropic, OpenAI or Groq key handy, switch
      to it, Test, and confirm it still works exactly as it did in v1.71.1.
- [ ] Turn **Convert with AI** off and confirm a foreign alert goes back to arriving flagged and
      unconverted, with Save greyed out until you type the rupee figure.
- [ ] Turn it back on, then use **Remove the saved key** and confirm the key is gone (the row reads
      *Not set*) and conversion stops.
- [ ] A **pinned rate** still wins over the AI: set one for MYR in *Your own rates*, and confirm the next
      alert uses your figure with no call at all.

---

## v1.71.1 — ⭐⭐ the credit limit is no longer captured as the amount

Your Yes Bank card alert ends with what is LEFT on the card. On a foreign purchase that was the only
rupee figure in the message, so the app grabbed it. RM87.48 was queued as ₹1,00,334.07.

- [ ] ⭐ Make a real card purchase in Malaysia (or forward yourself the exact text of one). The review
      card shows **RM87.48**, not the Avl Lmt figure.
- [ ] ⭐ Same for a **USD** charge — shows the dollar amount, not the limit.
- [ ] ⭐ An ordinary **rupee** purchase on the same card is unchanged: it shows what you spent, and the
      Avl Lmt figure is ignored exactly as before.
- [ ] Check the queue for any rows already captured wrongly at the old limit figure and delete them.
- [ ] ⭐ **With no AI key saved**, the foreign alert arrives flagged: the amount shows in **RM/USD in red**
      and Save stays greyed out until you type the rupee figure. That is deliberate. Save one and check
      the saved transaction still records what it arrived as.
- [ ] Once you save an AI key, a new foreign alert converts on its own and shows the rate line.

---

## v1.71.0 — the category drill-down now matches the wedge you tapped

Your **Business** and **Interest** categories hold both income and expenses. Tapping either used to open a
screen that added both together, so its total never matched the wedge.

- [ ] ⭐ Analytics → **Income** → tap **Business**. The big number equals the wedge you tapped. It does NOT
      include Business spending.
- [ ] ⭐ Go back → switch to **Spending** → tap **Business**. Now the number is the spending only, and it is
      a different figure.
- [ ] ⭐ The app bar says **Money in** on the first and **Money out** on the second, under the category name.
      Without that the two screens look identical while showing different numbers.
- [ ] The list under each shows only that direction — no expense rows on the income screen.
- [ ] The "usual month" sentence and the bar beneath the headline also changed between the two. They are
      computed from the same list, so they must move together with it.
- [ ] Do the same for **Interest**.
- [ ] An ordinary one-direction category (Food, Salary) behaves exactly as it did before.
- [ ] Change the cycle with the arrows, and switch Monthly / Yearly. Both keep the same side of the ledger.
- [ ] Go into a category and press Back — Analytics is still on the toggle you left it on.
- [ ] A long category name still fits the app bar on your phone without pushing the "Money in" line out.

---

## v1.70.1 — what the pre-tag review fixed

Six defects found by the two adversarial reviews of v1.70.0, which was never released. Everything in the
v1.70.0 section below still applies — this section is only the fixes on top.

### ⭐⭐ The money bug — test this one first, it is your actual use case

The situation: a ringgit or dollar alert arrives while the phone has **no usable data**, so Spends cannot
get a rate. Before this fix, opening it and tapping Save filed RM250.00 as ₹250.00 and kept no record it
had ever been ringgit.

- [ ] ⭐ Turn OFF mobile data and wifi. Send yourself a ringgit SMS (`RM250.00 spent at TESCO on card
      ending 1234`). Open it from the notification.
- [ ] ⭐ The big amount reads **RM250.00** — with the ringgit symbol, **in red**. It must NOT say ₹250.00.
- [ ] ⭐ **Save is greyed out.** Tapping it does nothing.
- [ ] ⭐ The line under the amount says the alert was for RM250.00, no rate was available, and to set the
      amount yourself.
- [ ] ⭐ Type the rupee figure (e.g. 4725). Save lights up. Save it.
- [ ] ⭐ Reopen that transaction. It still says it arrived as **RM250.00**, and now says **you** set the
      amount — not that it needs converting. The stored figure is ₹4,725.00.
- [ ] Clear the amount box completely: Save stays greyed out (a blank amount was never saveable), and the
      screen is not stuck — typing any figure releases it.

### ⭐ The notification lost its rupee sign

- [ ] ⭐ With data back ON, get an ordinary **rupee** SMS. The notification title reads
      **₹1,25,000.00** — with the ₹, and Indian grouping. Not `125,000.00`.
- [ ] A small rupee amount (₹450.00) also shows the ₹.
- [ ] A **ringgit** alert's notification still shows **RM**, not ₹.

### Conversion on a bad connection

- [ ] ⭐ With AI conversion on and a key saved, turn data OFF and let several foreign alerts arrive. The
      app must stay responsive — no long freeze. (Before: each message waited 20 seconds on its own.)
- [ ] ⭐ Turn data back ON and wait ~5 minutes. A new foreign alert converts again. (The app remembers a
      failure only briefly, so signal coming back is enough — you should not have to restart it.)
- [ ] Run **Scan past SMS** with data off and a few foreign messages in range. It finishes rather than
      hanging.

### Old messages are no longer priced at today's rate

- [ ] ⭐ Run **Scan past SMS** over a range that includes foreign alerts **older than two days**. They
      arrive in the review queue **flagged and unconverted**, showing the original RM/USD figure — they are
      NOT silently converted at this week's rate.
- [ ] ⭐ Tap **Add all**. Those old foreign rows are **skipped and left in the queue**. Everything else is
      added.
- [ ] An alert from **today** still converts normally. This is the case that matters while travelling.

### Spreadsheet export

- [ ] ⭐ Export a sheet in ₹. Switch the app to RM. Export again **without restarting the app**. The second
      sheet's headings say **(MYR)**, and any split-detail cells inside it also say RM. The two must agree.

### Backup keeps the conversion receipt

- [ ] ⭐ Add a converted transaction (one showing `RM250.00 → ₹4,725.00 · 1 MYR = ₹18.90`). Back up, wipe,
      restore. Open that transaction: the same conversion line is still there.
- [ ] ⭐ Restore a backup made by **v1.69.0 or earlier**. It restores cleanly — the new fields are optional.
- [ ] An unconverted foreign row also survives a backup still marked as foreign.

### Known and NOT fixed in this release — confirm you are happy to live with these

- [ ] You can pin your own rate only for **INR / MYR / USD**. A currency Spends can *detect* but does not
      keep books in (SGD, THB, GBP…) has no manual-rate row, so with no network those alerts can only be
      set by hand each time.
- [ ] Restoring a backup made **before** this version sets your currency back to ₹. If you are running a
      ringgit ledger, re-set the currency after any old restore.
- [ ] The AI call still waits up to 20 seconds on a slow connection. Deliberate — a shorter wait would make
      conversion fail on exactly the patchy roaming data this feature is for.
- [ ] Speculative, not reproduced: "RM" is also Indian banking shorthand for *Relationship Manager*. A
      message with no rupee amount that reads "...contact your RM 9876543210" could in theory be read as a
      ringgit amount. It needs a spend/credit word to become a capture at all, and no realistic example was
      found — but if a nonsense RM figure ever appears in your review queue, that is why.

---

## v1.70.0 — income analytics, and multi-currency with AI conversion

**⭐⭐ Read first — the one thing that must not be wrong.** This release changes the database (v16 → v17)
and adds a path where an amount can be *rewritten* on the way in. Before anything else: open the app on
a phone that already has real data and confirm **every existing figure is exactly what it was**. The
migration only adds empty columns, so a changed balance would mean something is badly wrong.

### Upgrading (do this before touching any new feature)

- [ ] ⭐ Upgrade **in place** from v1.69.0 (do not uninstall). The app opens without crashing.
- [ ] ⭐ The cycle balance, every transaction amount and the Analytics totals are **identical** to before.
- [ ] ⭐ Restore a backup made by v1.69.0 (schema v5). It restores cleanly, and the currency stays ₹.
- [ ] Make a fresh backup, then restore it. Your currency choice comes back with it.

### Income analytics

- [ ] Analytics shows a **Spending / Income** toggle under the summary card, starting on **Spending**.
- [ ] The Spending view is unchanged from v1.69.0 — same donut, same legend, same bars.
- [ ] ⭐ Tap **Income**: the donut, its centre figure ("EARNED"), the legend and the bar chart all switch
      to income together. Nothing on screen still says "spending".
- [ ] ⭐ The income donut's centre figure equals the sum of its legend rows.
- [ ] Tapping an income category (e.g. Salary) drills into that category's transactions for the cycle.
- [ ] A cycle with income but **no** spending still charts, rather than saying "Nothing to chart yet".
- [ ] ⭐ A month with **no income at all** (there are 16 such months in your history — e.g. Jul–Oct 2021,
      Feb 2022, May 2024) still opens on the Income view: an empty ring, "₹0.00" in the middle, and the
      wording "No categorised income this period." — not a crash and not "Nothing to chart yet".
- [ ] A month whose only income is one salary credit shows a **single full ring at 100%**.
- [ ] ⭐ **Business** and **Interest** hold both income and expenses in your ledger. Check each appears in
      **both** donuts with its own side's figure (income and expense totals differ for both).
- [ ] Known, and pre-existing — not new in this release: tapping one of those two categories opens a
      drill-down whose total is income **plus** expenses for that category, so it won't equal the wedge you
      tapped. Confirm it looks the way the note in `PROGRESS.md` describes, then decide whether you want it
      changed.
- [ ] The choice survives rotating the phone and coming back from a drill-down.

### Currency

- [ ] Settings has a new **Currency & AI** row; it opens.
- [ ] ⭐ Switch to **Malaysian Ringgit**. Every figure becomes `RM…` and regroups Western-style
      (`1,234,567.89`, not `12,34,567.89`) — and **no amount changes value**. On your data the all-time
      expense total should read `₹63,26,991.90` before and `RM6,326,991.90` after: the same digits,
      regrouped. If any digit changes, stop and report it.
- [ ] ⭐ Note what this does NOT do: switching currency **re-labels** your history, it does not convert it.
      Seven years of rupee spending will read as ringgit. That is deliberate (converting 4,000 stored
      amounts is not something to do silently) — confirm you are happy with it before relying on the
      setting.
- [ ] The home-screen **widget** shows the new symbol too (it may need a tap or a minute).
- [ ] The add/edit screen's big amount, the keypad, and the recurring editor all show the new symbol.
- [ ] A spreadsheet **export** has the new currency code in its column headings.
- [ ] Switch back to ₹. Everything returns exactly as it was.

### AI currency conversion (needs your own API key)

- [ ] With the switch **off**, the app behaves exactly as before and makes no AI call.
- [ ] Turn it on, pick a provider, paste a key, tap **Test key** → "Working".
- [ ] Paste a deliberately wrong key → it says the key was rejected, in plain words.
- [ ] The saved key is **never shown back** to you; "Remove the saved key" works.
- [ ] ⭐ With the ledger in ₹, get a **ringgit** SMS (or forward yourself one that reads like
      `RM250.00 spent at ... on card ending 1234`). The review card shows the converted rupee figure
      **and** the line `RM250.00 → ₹… · 1 MYR = ₹…`.
- [ ] ⭐ Open it, save it, then reopen the saved transaction: the same conversion line is still shown.
- [ ] ⭐ Edit the amount by hand and save → the conversion line disappears (it no longer explains the
      figure). This is deliberate.
- [ ] ⭐ **With no key set / aeroplane mode**, get a ringgit SMS. The review card shows it in **RM**, in
      red, saying it was not converted — it must **not** appear as a rupee amount.
- [ ] ⭐ With that unconverted row in the queue, tap **Add all**. It must be **skipped and left in the
      queue**, not added and not deleted. Everything else in the queue is added.
- [ ] Pin a manual rate (1 MYR = your number). A new ringgit SMS uses **your** rate, with no network call
      (works in aeroplane mode).
- [ ] An ordinary **rupee** SMS still captures exactly as before, with no conversion line anywhere.
- [ ] ⭐ The live notification for a ringgit alert shows **RM**, not ₹.

### Malaysian senders

- [ ] An SMS from Maybank / CIMB / Public Bank / RHB / Touch 'n Go is recognised as a transaction.
- [ ] A promotional or OTP message from those same senders is still ignored.

---

## v1.69.0 — "Ignore" actually accumulates now

**The bug you found.** Every ignore was recorded as a first ignore, so nothing could ever go quiet. The
amount was part of what Spends treated as "the same alert", and a shop almost never charges you the
identical figure three times — so ₹29,989 and ₹29,990 from the same card counted as two different
things. It is now the **source and the direction**, not the amount.

**Your existing rows are wiped on first open.** None of them had reached the threshold (that was the bug),
so nothing is lost, and your counts start clean rather than silencing things instantly.

- [ ] ⭐ Open Settings → Automatic Entries → **Silenced alerts**. The long list of near-identical
      ₹29,xxx rows should be **gone**.
- [ ] ⭐ Ignore **three** alerts from the same shop or bank, each a **different amount**. After the
      third, that alert should stop prompting — this never worked before.
- [ ] Watch the countdown as you go: "2 more Ignores" → "1 more Ignore" → it moves to
      **"Not asking any more"**.
- [ ] Each row now shows **who** it's from as the heading (e.g. "Php*finreliable Digite"), and underneath
      **what it covers** — "Money-out alerts · from YESBNK". No rupee amount, because a row now covers
      every amount.
- [ ] ⭐ Money **in** and money **out** from the same bank are silenced **separately**. Silencing your
      IDFC credits must NOT silence IDFC debits.
- [ ] ⭐ A silenced alert still lands in the **review list** — open it and confirm the transaction is
      there. Nothing is lost, you're just not interrupted.
- [ ] **Ask me again** on a silenced row → the next alert of that kind prompts you normally.

---

## v1.68.0 — two fixes to the new category screen

- [ ] ⭐ **Step back a cycle with the ‹ arrow.** The green panel must STOP saying "THIS CYCLE" and say
      **"EARLIER CYCLE"**. This was wrong in v1.67.0 — an old cycle announced itself as the current one.
- [ ] Step forward again to the current cycle → it says "THIS CYCLE" again.
- [ ] Pick **All time** or a multi-cycle range from the selector → it says **"SELECTED PERIOD"**,
      because those aren't a single cycle.
- [ ] ⭐ The comparison sentence updates for the older cycle too — and because that cycle has ENDED,
      it should say "less than" rather than "under … so far".

**Yearly now looks like Monthly** (you asked for consistency):

- [ ] Tap **Yearly**. Same green panel, same comparison box — not the old plain layout.
- [ ] The green panel still means what it did: **average per month** in that year, with the year's
      **total** on the line underneath.
- [ ] ⭐ The comparison now compares that year against **the previous year you have data for**,
      e.g. "About ₹2,000 more than your monthly average in 2025." Bars are labelled with the two years.
- [ ] ⭐ Both bars are **per month**, so a part-finished 2026 should NOT look tiny next to a full 2025.
      A line under the bars says so.
- [ ] Your **oldest** year has no earlier year to compare with → it says so plainly instead of
      showing a comparison against zero.
- [ ] The 3M/6M/All buttons do **not** appear in Yearly (they don't apply); the year chips do.
- [ ] ⭐ Numbers unchanged from v1.67.0 — this is a layout change, not a maths change.

---

## v1.67.0 — the category screen answers the question instead of posing it

Open any category from Analytics. **Monthly** view only — Yearly is deliberately unchanged.

- [ ] ⭐ **One big number now, not two.** The headline is **THIS CYCLE**, on a coloured panel. The
      6-month average is no longer a rival headline.
- [ ] ⭐ **A sentence tells you the answer** — e.g. "About ₹1,500 more than your usual month."
      Check it against the two bars underneath: this cycle vs usual.
- [ ] The words "SPENT IN THIS CYCLE ONLY" are gone.
- [ ] The **3M / 6M / All** buttons now sit *inside* the comparison box, with a line under them saying
      what "usual" is averaged over. Changing them must move only the **Usual** bar — never the big number.
- [ ] The cycle **‹ ›** stepper still only changes the big number and the list.
- [ ] ⭐ A **half-finished** cycle that is under the usual says "**so far**". If it drops the "so far"
      on a cycle that hasn't ended, tell me — that would be congratulating you too early.
- [ ] A cycle you've spent nothing in says "Nothing in this cycle yet." and draws an empty first bar.
- [ ] A brand-new category with no history says "Not enough history yet to say what a usual month
      looks like" rather than showing a comparison against zero.
- [ ] ⭐ **The odd-dated row now explains itself.** In your House Maintenance screenshot a **23 Jul**
      transaction sat under a "25 Jul – 24 Aug" heading. It should now carry a small
      **"billed this cycle"** tag. That is correct behaviour — Smart Cycle groups by the card's billing
      day — and the tag is there so it stops looking like a mistake.
- [ ] The tag appears **only** on rows genuinely outside the printed dates, and only in Smart Cycle.
- [ ] The list has a heading — "What's in this cycle" with an item count.
- [ ] **Yearly** view is unchanged: average per month, the year's total underneath, year chips.
- [ ] ⭐ The totals themselves have not moved. The big number must still equal the sum of the rows.

---

## v1.66.0 — you can undo an accidental "Ignore"

Find it at **Settings → Automatic Entries → Silenced alerts** (a new row, near the bottom, above
the two debug rows).

- [ ] The **Silenced alerts** row is there, and opens.
- [ ] With nothing silenced it says so plainly — no empty screen, no error.
- [ ] Tap **Ignore** on the same bank alert three times (same shop, same amount). It stops alerting.
- [ ] ⭐ That alert now appears under **"Not asking any more"**, showing the amount and the shop
      — e.g. "₹450.00 at Swiggy" — not a code or a jumble of symbols.
- [ ] It says how many times you ignored it and when you last did.
- [ ] After only **one or two** ignores it appears under **"Close to being silenced"** with a
      countdown ("1 more Ignore and Spends stops asking").
- [ ] ⭐ Tap **Ask me again** → the row disappears, and the *next* alert of that kind prompts
      you normally again.
- [ ] After un-silencing, the count starts from scratch — it takes three fresh ignores to silence
      it again, not one.
- [ ] **Ask me about everything again** asks for confirmation first, then clears the whole list.
- [ ] ⭐ Nothing on your timeline changes when you un-silence — no transaction is added,
      removed or altered.
- [ ] The **Open review list** link on that screen goes to the review queue.

---

## ⭐ Highest value right now

- [ ] ⭐⭐ **THE ONE THING — the SMS diagnostic** *(v1.63.0, new; this is the whole reason the release
      exists)*. Settings → Automatic Entries → Detect from SMS & notifications → **SMS debug**. **Leave
      Spends open on that screen**, send yourself any text from anywhere, and watch **"SMS delivered
      (this app run)"**. If it stays **0**, Android never handed the text to Spends and nothing inside
      the app is the cause. If it goes to **1**, the fault *is* inside the app and the rows under the
      verdict say which one. Send me that number and the verdict line, word for word. Full instructions
      in the v1.63.0 section below.
- [ ] **The summary card's "vs last cycle" on your REAL data** *(v1.61.0 fixed a bug that was already on
      your phone)*. Open Analytics, read page 1 of the insights carousel. Does the comparison against last
      cycle sound right? Until v1.61.0 it compared against a window that started days off your actual
      cycle, so a fixed-day charge like rent or an EMI could fall outside it and make the comparison badly
      wrong. Best checked in a month that follows a 28- or 31-day one.
- [ ] **The notification diagnostic** *(v1.57.0, and still the open question)*. Settings → Automatic
      Entries → Detect from SMS & notifications → **Notification debug**. Read the verdict line at the top
      and tell me what it says. This is the only thing standing between us and fixing Truecaller capture,
      and the diagnostic gets stripped out once we know the answer.
- [ ] **A real bank SMS still captures correctly.** Any release touching the parser is a money risk.
      Wait for (or find) a genuine bank alert and confirm the amount, the date and income-vs-expense are
      all right in the review queue.

---

## v1.65.0 — recurring notifications, the rule link, the category average, and the AI removal

**Recurring notification (needs a rule that fires — easiest: create a rule with a start date of today,
save, then let the 9 AM alarm run, or set the notify time a couple of minutes ahead in
Settings → Automatic Entries → Recurring.)**

- [ ] ⭐ The notification now shows **the name, the note and the amount** of what was added — not just
      "1 scheduled transaction was added".
- [ ] An income rule shows **+₹**; an expense rule shows **-₹**.
- [ ] A rule saved with a note but **no name** shows the note as the heading (not "Scheduled transaction").
- [ ] Tapping **Edit** opens **that exact transaction**, already filled in.
- [ ] Tapping **Dismiss** clears the notification — and the transaction is **still there** in the list.
      ⭐ If Dismiss deletes it, stop and tell me: that would be money disappearing.
- [ ] Two rules firing the same day give **two separate notifications**, each naming its own transaction.
- [ ] If you have been away from the app long enough for many occurrences to back-fill at once, you get
      **one roll-up** ("N scheduled transactions were added") rather than a wall of notifications.

**The rule behind a transaction**

- [ ] Open a transaction that a rule created → a band reads **"Added by a repeating rule"**. Tap it →
      the rule's editor opens, already loaded with that rule.
- [ ] The band does **not** appear on a transaction you typed yourself or one captured from an SMS.
- [ ] ⭐ Change the amount on the **transaction** and save → the **rule** is unchanged (check
      Settings → Automatic Entries → Recurring). The two are meant to be separate.
- [ ] Go back from the rule editor → you land on the transaction, and Back again leaves normally
      (it must not bounce you into the editor again).

**Category screen — average at the top, and the Yearly view**

- [ ] Open any category from Analytics. The **average is now the first thing** on the screen; the cycle
      total sits below it under **"SPENT IN THIS CYCLE ONLY"** with its own dates.
- [ ] It is now obvious that the list underneath is **one cycle**, not the 6 months the average covers.
- [ ] Tap **Yearly** → the 3M/6M/All buttons become **years**, and only years you actually have data for
      appear (newest first).
- [ ] ⭐ In Yearly, the big figure is the **average per month in that year** and the line under it is the
      **year's total**. Sanity-check one: total ÷ months should land near the average.
- [ ] ⭐ For the **current** year the average is divided by the months **so far**, not twelve — 2026
      should not look dramatically smaller than 2025 just because the year isn't over.
- [ ] In Yearly, the list below shows **the whole year**, and the cycle stepper is gone.
- [ ] Switch back to Monthly → everything behaves as it did before.

**The AI helper is gone**

- [ ] Settings → Automatic Entries: there is **no "AI helper" row** any more.
- [ ] Analytics: the insight card carousel is gone; the charts, donut and totals are unchanged.
- [ ] The review queue still works, and still auto-fills the category for merchants you have taught it.
      (The "Suggested:" / "Same as before" chip is gone — that was the AI.)
- [ ] Nothing anywhere in the app mentions AI, Groq or an API key.

**Truecaller / notification capture — read, don't fix**

- [ ] ⭐ Reproduce a Truecaller bank alert, then open Settings → Automatic Entries → Capture →
      Notification debug. If the verdict says **"Your phone is hiding these messages from Spends"**, that
      is the confirmed cause and there is nothing in the app to change.
- [ ] Try the suggested phone setting (**Settings → Notifications → Enhanced notifications → OFF**),
      reproduce the alert again, and tell me whether the text is still the placeholder. That answer
      decides whether this route can ever work on your phone.

---

## v1.64.0 — insight cards name what they're about

> **OBSOLETE — do not test.** Insight cards were removed in v1.65.0. Nothing here exists in the app any more.

- [ ] ⭐⭐ **Every card that's about a category now says which one.** Swipe the Analytics carousel. A card
      like "You had a ₹10,000 charge, which is 15.4 times the typical ₹650 charge" should now read more
      like "A single **Groceries** charge of ₹10,000 — about 15.4× your usual ₹650."
- [ ] ⭐ **The cards should stop contradicting each other.** Before, three cards could say ₹6,070, ₹2,017
      and ₹0 "so far this cycle". They were three *different categories* all along — now each says so.
- [ ] **"Top Categories" names the three.** It should list them, not just say "your top 3 categories".
      Check the three names against the donut right below it.
- [ ] Tap **refresh** (the ↻ on the Insights card) a couple of times. Wording may vary; the **category
      names and the numbers must not**.
- [ ] With the AI helper **off**, or with no internet, cards still appear with the plain built-in wording
      — which also names the category. Nothing should be blank.

**Note on cost:** this adds roughly 4% to each AI call, and one call covers the whole carousel. It stays
well inside Groq's free tier for normal use; only repeated refreshing would push it.

---

## v1.63.3 — the widget's balance

- [ ] ⭐⭐ **The widget balance now matches the app.** With carry-forward on, put the two side by side:
      the home-screen widget's Balance should equal the Balance shown at the top of the app for the same
      cycle. They disagreed before this release.
- [ ] The widget may need a moment or a tap to refresh after installing.
- [ ] ⭐ **Single Card view is deliberately different** — a single card's statement shows **no**
      carry-forward, on the widget and in the app alike, because a running whole-account balance means
      nothing over one card. Confirm it isn't quietly adding one.
- [ ] With carry-forward **off** in Settings → Money, the widget balance is plain income − expense, as
      before. Nothing should change for you if you don't use the feature.

---

## v1.63.2 — the capture fix

- [ ] ⭐⭐⭐ **A real bank SMS captures again.** This is the whole thing. Wait for (or trigger) a genuine
      bank alert with Spends **closed or in the background** — the normal way you'd use it. A "Review &
      Add" prompt should appear, or the entry should land in the **review queue**. Confirm the amount,
      the date, and income-vs-expense are all correct before saving.
- [ ] ⭐⭐ **SMS debug now opens without closing the app**, and after a text arrives shows
      **"SMS delivered (this app run)"** greater than 0 with the message listed below it.
- [ ] ⭐ **"Scan past SMS" finds your history.** Because the parser has been throwing since v1.58.0, this
      may now pull in a backlog of older bank texts it previously could not read. Check a handful of
      amounts before accepting them — this is the one step where a wrong figure could reach real money.
- [ ] The Automatic Entries screen shows **no** "Spends closed unexpectedly" notice any more (dismiss the
      old one if it is still there — it refers to the crash that is now fixed).
- [ ] ⚠️ **If capture still does not work**, that is genuinely useful: it means there is a *second*
      cause. Open SMS debug, leave the app open, text yourself, and send me the verdict line and the
      delivered count. The diagnostic finally works, so it can now answer that question properly.

---

## v1.63.1 — make the crash speak

- [ ] ⭐⭐ **Open SMS debug again.** Two outcomes, both useful:
      - **It opens** — carry straight on to the v1.63.0 test below. That is the real goal.
      - **It closes the app again** — reopen Spends, go to **Settings → Automatic Entries**, and the
        "Spends closed unexpectedly" notice will be at the top. Tap **Copy crash report** and paste it
        to me. That names the exact line that failed, which is what I need to fix it properly rather
        than guess.
- [ ] The crash notice is **safe to paste**: code names, your Android version and your phone model. No
      transactions, no amounts, no message text. Have a look before sending if you like.
- [ ] **Dismiss** removes the notice for good and it does not come back on its own.
- [ ] If Spends has *not* crashed, no notice appears at all — an empty Automatic Entries screen top is
      the correct result, not a missing feature.

---

## v1.63.0 — the SMS diagnostic, and two fixes that were quietly eating money

This release exists to answer **one question**: when a bank text arrives, does Android hand it to Spends
at all? Everything else below is secondary. The diagnostic screen is **temporary** and comes out once we
have the answer.

**The one test that matters.** Settings → Automatic Entries → Detect from SMS & notifications → **SMS debug**.

- [ ] ⭐⭐ **Leave Spends OPEN on the SMS debug screen.** Send yourself any text — from another phone, a
      friend, anything. It does **not** need to be a bank alert. Watch the counter **"SMS delivered
      (this app run)"**:
      - **0 → 1** means Android *is* delivering texts to Spends, so the fault is inside the app, and the
        rows under the verdict line say which part.
      - **Stays 0** means Android is *not* handing texts to Spends at all. Nothing in Spends is the cause
        — it is something on the phone (Truecaller holding the default-SMS role, battery optimisation,
        restricted background activity, or the permission being revoked without saying so).
      - Either way: **send me the number and the verdict line, word for word.**
- [ ] **Why "leave it open" matters:** the counter says *this app run*, and it resets whenever Android
      kills the app. If you send the text with Spends closed and open it afterwards, you will see 0 and
      it will mean nothing at all. Don't trust a 0 you didn't watch happen.
- [ ] Read the rows under the verdict and note any ⚠️: Receive SMS permission, Read SMS, Detect from bank
      SMS, Prompt can be shown, App start-up failures.
- [ ] The **"Messages kept"** list at the bottom — after your test text, an entry should appear naming the
      sender and the reason it was ignored (for a non-bank text, "The sender isn't a bank Spends knows"
      is the correct answer, not a failure).

**Two real fixes.** Both are permanent capture fixes, not diagnostics. Neither is likely to be *your*
cause, but both were silently losing money for someone.

- [ ] ⭐ **A parsed bank text is no longer thrown away when the pop-up can't be shown.** Before this, if
      the "Review & Add" prompt was blocked, the transaction simply vanished. It now goes to the **review
      queue** instead. Worth checking the queue for entries you never saw a pop-up for.
- [ ] ⭐ **The "Transaction detection" notification category being switched off on its own** is now
      detected and reported. Previously both live capture paths were blind to it and just did nothing.

**Honest scan messages.** "Scan past SMS" and "Scan for cards" used to print the same sentence no matter
what actually happened — that alone cost a full round of this investigation.

- [ ] Tap **Scan past SMS** and read the result. It should now tell the four cases apart: read nothing /
      read plenty but all already known / couldn't read / refused because demo mode is on.
- [ ] With **demo mode on**, both scans should refuse and *say* they refused, rather than reporting zero.

**Privacy — please actually check this one.** The screen has a copy-report button, and the report is
meant to be safe to send me.

- [ ] ⭐ Copy the report and **read it before sending it anywhere**. It should list sender **names** (that
      is deliberate — it's how a bank Spends doesn't recognise gets identified) but **no message text, no
      amounts, no OTP codes, no UPI IDs, no account or card numbers**. If you see any of those, stop and
      tell me instead of sending it.
- [ ] ⭐ **The NOTIFICATION debug report is a different thing, and it is not as clean.** That older
      screen's report lists conversation titles and sender names for *every* notification it saw,
      **including personal chats** — so a friend's name, or an unknown caller's raw phone number, can
      appear in it. That is pre-existing, not new this release, and the screen now says so. Read that one
      before pasting it anywhere. **The SMS debug report is the safe one to send me.**

---

## v1.62.0 — two judgement cards: commitments, and what you kept

> **OBSOLETE — do not test.** Insight cards were removed in v1.65.0. Nothing here exists in the app any more.

Needs the AI helper **on** (Settings → Automatic Entries → AI helper) with your Groq key. **Demo mode
shows both** — it plants recurring rules and ~14 months of income.

**The two new cards** — swipe the carousel on Analytics, now up to **7 pages**:

- [ ] ⭐ **Commitments** — "The monthly recurring payments already running come to ₹X — about Y% of the
      ₹Z that usually comes in each cycle." Check all three numbers against Settings → Recurring:
      **₹X** should be the total of your *monthly* expense rules that have already started (not yearly
      ones, not ones dated in the future), **₹Z** should look like a normal month's income for you, and
      **Y%** should be X ÷ Z.
- [ ] ⭐ **What you kept** — "You've kept ₹X — Y%, ahead of the Z% you'd usually have kept by this point."
      The ₹X is income minus spending **so far this cycle**, not a projection.
- [ ] The carousel still swipes cleanly and the dots match the number of pages.

**The one number most worth checking.** The commitments card's *"₹Z that usually comes in"* is the
**median of your finished cycles** — deliberately not this cycle's income, because early in a cycle
that would be a fraction of a month and the percentage would be nonsense.

- [ ] ⭐⭐ Open the commitments card on **day 2** of a cycle, then again on **day 25**. The sentence
      should read *identically*. If the percentage moves as the cycle progresses, that is a real bug.

**Cards that should stay QUIET.** Not appearing is the correct result:

- [ ] Fewer than **3 finished cycles** with income logged → neither card appears.
- [ ] A cycle with **no spending logged yet** → nothing appears at all, including commitments.
- [ ] **Smart Cycle** → no "what you kept" card. *(Commitments may still appear — that's correct, and
      was a bug fixed during review: it used to be silently killed for Smart Cycle users.)*
- [ ] Step **back** to a finished cycle → neither card appears.
- [ ] The first **7 days** of a cycle → no "what you kept" card. One rent charge would swing it wildly.
- [ ] If you **usually overspend** by this point in the cycle, the "what you kept" card says nothing
      rather than something bleak.

**Known limits — not bugs, already recorded in `docs/AI-INSIGHTS-PHASE-C.md`:**

- [ ] If you had a **big pay change**, the commitments card quotes your OLD usual income for the next
      3–4 cycles before catching up. Worth confirming it self-corrects rather than sticking.
- [ ] **Two** months with no income logged still lets the card fire (three silences it). If you skip
      logging salary, check the "usually comes in" figure still looks honest.

**Two cards were built and then dropped during review** — a "you could save ₹X" card and a weekend-habit
card. Neither should appear anywhere, and nothing should mention weekends or needs-versus-wants.

- [ ] No card suggests spending less, or proposes an action of any kind. *(The one exception, allowed
      deliberately: "worth a look in case one was billed twice" on the duplicate-charge card.)*

**The privacy text changed this release** — Settings → AI helper → the explainer:

- [ ] Read it. Does it describe what you'd expect the two new cards to send? It should mention your
      recurring-payment total, what a cycle *usually* brings in, and how many days into the cycle you are.

---

## v1.61.0 — insights that compare over time

> **OBSOLETE — do not test.** Insight cards were removed in v1.65.0. Nothing here exists in the app any more.

Needs the AI helper **on** (Settings → Automatic Entries → AI helper) with your Groq key. **Demo mode is
the fastest way to see these** — it has ~14 months of data planted to make each one appear.

**The four new cards** — swipe the carousel on Analytics, now up to 6 pages:

- [ ] **Pace** — "Day 12 of the cycle and ₹X spent. Your recent cycles were at ₹Y by this point."
- [ ] **Year-on-year** — "₹X so far this July, against ₹Y over the same stretch last July." *(needs 13+
      months of history, so realistically demo mode)*
- [ ] **Category trend** — "Dining ran about ₹X a cycle earlier, and about ₹Y a cycle lately."
- [ ] **Payday week** — "About X% of your spending lands in the seven days after payday, which is only
      Y% of the cycle."
- [ ] The carousel still swipes cleanly and the dots match the number of pages.

**Cards that should now stay QUIET.** A card *not* appearing is the correct result here:

- [ ] **Single Card** view → page 1 shows no "vs last cycle" comparison at all.
- [ ] **Smart Cycle** → no pace card and no year-on-year card. *(The trend and payday cards may still
      appear — that's correct.)*
- [ ] Step **back** to a finished cycle → no "day N of the cycle" card. Year-on-year may still show, but
      should not say "so far".
- [ ] Your **first ever** cycle, or a fresh install → page 1 does **not** claim your spending rose
      compared to last cycle. There was nothing to compare against.

**Sanity on the numbers** — the whole point of the design is that these are your real figures:

- [ ] ⭐ Any card claiming "X× your usual" — does the "usual" figure look like something you'd recognise?
- [ ] ⭐ A card about rent, EMI, insurance or school fees should **not** say you're wildly above normal
      just because the charge landed early in the cycle.

---

## v1.60.0 — the insights carousel (Phase A)

> **OBSOLETE — do not test.** The insights carousel were removed in v1.65.0. Nothing here exists in the app any more.

- [ ] Cards appear at all, with swipe dots, and the ✕ dismisses them.
- [ ] The refresh button re-generates them.
- [ ] "Unusual spending" / "quiet win" cards name a category you'd agree is unusual or quieter.
- [ ] "Charged twice?" — if it fires, check it really is two separate charges and not one split
      transaction.
- [ ] With the AI helper **off**, Analytics looks and behaves exactly as it did before.

---

## v1.59.0 — demo mode

- [ ] Settings → Data → turn demo mode **on**. The app restarts and shows invented data.
- [ ] ⭐ **Your real data is untouched** — turn it back off and confirm every transaction, category and
      balance is exactly as you left it.
- [ ] The home-screen **widget** shows a "DEMO MODE — sample data" header while it's on.
- [ ] While in demo mode, "Scan past SMS" and "Scan for cards" do **not** pull in your genuine bank
      alerts.
- [ ] The reset option restores the demo data to its starting state.

---

## v1.58.0 — merchant reading fix

- [ ] ⭐ A fuel or card SMS that ends with the usual "Not you? SMS BLOCK …" fraud line now records the
      **shop's name**, not the phone number from that line.
- [ ] Categories that were learned against a wrongly-read merchant may need re-teaching once — confirm a
      corrected merchant now categorises itself next time.
- [ ] Shops whose names start with "The" or "A" (The Body Shop, A One Sweets) are read correctly.

---

## v1.57.0 — notification capture reconnect

- [ ] After **updating** the app, notification capture still works without you toggling anything.
- [ ] After a **phone restart**, same.
- [ ] Settings → Automatic Entries → the "Open Android settings" link opens the notification-access page.
- [ ] ⭐ See the diagnostic item at the top of this file — that's the one I actually need.

---

## Conventions

- Every release adds a section here **before** the APK link is posted.
- Ticked sections older than two releases can be deleted; unticked ones stay, however old.
- ⭐ marks anything where being wrong means a wrong figure about real money.
- If a check needs a specific setup (demo mode, a particular cycle type), the item says so.
