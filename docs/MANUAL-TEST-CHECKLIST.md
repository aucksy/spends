# Manual test checklist — things only a phone can confirm

A **running** list. Every release appends a section; nothing is deleted until it has actually been
checked on a device. Cloud CI proves the code compiles and the maths is right; it cannot see a screen,
a notification, or a real bank SMS arriving.

**How to use it:** tick as you go, in any order. If something is wrong, note what you saw next to the
box — "said 3× but it was more like 1.5×" is enough to act on. Anything left unticked is simply still
unknown, which is fine; it just isn't confirmed.

**Priority when time is short:** do the ⭐ items first. They are the ones where being wrong means a
wrong number about real money, rather than something looking untidy.

**Currently untested: v1.57.0 → v1.61.0 — five releases.**

---

## ⭐ Highest value right now

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
