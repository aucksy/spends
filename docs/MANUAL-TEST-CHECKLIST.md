# Manual test checklist — things only a phone can confirm

A **running** list. Every release appends a section; nothing is deleted until it has actually been
checked on a device. Cloud CI proves the code compiles and the maths is right; it cannot see a screen,
a notification, or a real bank SMS arriving.

**How to use it:** tick as you go, in any order. If something is wrong, note what you saw next to the
box — "said 3× but it was more like 1.5×" is enough to act on. Anything left unticked is simply still
unknown, which is fine; it just isn't confirmed.

**Priority when time is short:** do the ⭐ items first. They are the ones where being wrong means a
wrong number about real money, rather than something looking untidy.

**Currently untested: v1.57.0 → v1.63.2 — nine releases.**

> **The capture bug is found and fixed in v1.63.2.** A Java-only regex flag made the SMS parser throw on
> any real Android phone the first time it was touched, and the error was being swallowed — so bank texts
> silently stopped becoming transactions from **v1.58.0** onward, which matches exactly when capture
> stopped working. The same copied line is what crashed the v1.63.0 debug screen. **The single most
> valuable thing to check now is whether a real bank text captures again.**

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
- If a check needs a specific setup (demo mode, AI helper on, a particular cycle type), the item says so.
