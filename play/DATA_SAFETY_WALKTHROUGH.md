# Data safety form — click-by-click guide

Companion to `DATA_SAFETY.md` (which holds the answers **and the reasoning**). This file is the
running order: what to click, what to pick, and what to watch out for.

**This is a first submission — nothing has been filed with Play yet, so there is nothing to correct.**
Work straight down this page. The one thing to be careful about is that the form's defaults and its
wording both nudge you toward over-declaring; the notes below say where.

*(An earlier draft of this file was written as a "re-submission" guide, because at the time the app had
an optional AI helper that shared data with a third party. That helper was removed in v1.65.0, before
any Play submission. Every "shared" answer is **No**, and SMS is not collected at all.)*

**The app does contact an AI provider — and every "shared" answer is still No.** The optional AI
currency-conversion feature (off by default, needs the user's own API key) transmits one generic question
— *"How many INR is 1 MYR right now?"* — and no user data whatsoever: no amount, merchant, account number,
message text or identifier. Play defines collection and sharing in terms of **user data** leaving the
device, and an exchange-rate question is identical for every user on earth. `DATA_SAFETY.md` §1a is the
full argument; read it before answering, and quote it if a reviewer asks.

Play Console wording shifts occasionally. If a label differs slightly, match on meaning — the answers
below are what matters.

---

## Where to go

**Play Console → your app → Policy and programmes → App content → Data safety → Manage** (or *Start*).

There are four steps: **Overview → Data types → Data usage and handling → Preview**.

---

## Step 1 — Overview / "Data collection and security"

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** |
| Is all of the user data collected by your app encrypted in transit? | **Yes** |
| Do you provide a way for users to request that their data be deleted? | **Yes** |

**"Yes" to the first one surprises people** — Spends sends nothing to the developer. But Play defines
*collect* as "leaves the device", and the optional Google Drive backup does leave the device (to the
user's own account). So: **collected yes, shared no**. Answering No here and then declaring the backup
would contradict itself.

If it asks for a **data deletion URL** or about **account deletion**: Spends has no accounts and no
server. Deletion happens in-app (Trash), by clearing app data or uninstalling, and by deleting the
backup from inside Spends or from Google Drive. If a URL is mandatory, point it at the privacy policy
page, which describes exactly that.

---

## Step 2 — Data types

A long checklist grouped by category. **Tick exactly one box in the whole list:**

- **Financial info → Other financial info** ✅

**Leave everything else unticked.** In particular:

- **Messages → SMS or MMS** — ❌ **leave it unticked.** This is the one people get wrong, because the app
  so obviously *reads* SMS. But Play's question is whether data **leaves the device**, and texts are read
  and parsed on the phone and never transmitted anywhere, in any configuration. Under Play's definition
  that is not collection. Ticking it would declare a data flow that does not exist.
- **Financial info → User payment info / Purchase history / Credit score** — not touched.
- Location, Personal info, Contacts, Photos, Calendar, Web browsing, Device or other IDs — none.
- **App activity / App info and performance** — nothing is collected; there are no analytics or crash
  reporting SDKs. (Spends keeps a crash note **on the phone**, which the user copies out manually. It
  is never transmitted, so it is not collected.)

---

## Step 3 — Data usage and handling

Only one entry to fill in, for **Other financial info**:

| Question | Answer |
|---|---|
| Is this data collected, shared, or both? | **Collected** ✅ · **Shared** ❌ (leave unticked) |
| Is this data processed ephemerally? | **No** (a backup is stored, not transient) |
| Is this data required, or can users choose whether it's collected? | **Users can choose** (backup is opt-in) |
| Why is this user data collected? | **App functionality** only |

**Do not tick** Analytics, Advertising or marketing, Personalisation, Developer communications,
Fraud prevention, or Account management. None of them apply.

⚠️ **The single most important box on the whole form is "Shared".** It must be **unticked**. Spends
sends nothing to anyone but the user's own Drive, and "Shared" is the box a reviewer reads first.

---

## Step 4 — Preview and submit

The preview is what users see on the store listing. Read it back and check it says, in substance:

- **No data shared with third parties**
- **Data is encrypted in transit**
- **You can request that data be deleted**
- Collects: financial info (optional)

If the preview still mentions **SMS**, or says data **is shared**, go back — a box is still ticked.

Then **Save**, and **submit for review** if prompted. Changes go live with the next app update or
after review, depending on what Play asks for.

---

## Keep these consistent

Any future feature that makes a network call means redoing this form **and** the six-file disclosure
sweep listed in `README.md`. The declaration is what a Play reviewer holds the app to.

Today Spends makes exactly **two** network calls, both optional and both user-enabled: the user's own
Google Drive backup, and the AI exchange-rate question (`DATA_SAFETY.md` §1a) — which carries no user data,
and which the user can avoid entirely by pinning their own rate in Settings.
