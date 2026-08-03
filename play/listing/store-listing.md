# Spends — Google Play store listing copy

Paste these verbatim into Play Console → **Grow → Store presence → Main store listing**.
SMS-based expense tracking is deliberately foregrounded (title, short description, and the FIRST
section of the full description) — this is required for the SMS Permissions Declaration to be approved.

---

## App name  (max 30 chars)
```
Spends: SMS Expense Tracker
```
(27 chars.)

## Short description  (max 80 chars)
```
Auto-track expenses from bank SMS. Private, offline, salary-cycle budgeting.
```
(75 chars.)

## Full description  (max 4000 chars)
```
Spends turns the transaction SMS your bank already sends into an effortless expense tracker — and your data stays on your phone.

▍ Automatic expense capture from SMS
The moment your bank or credit-card SMS arrives, Spends detects the transaction and notifies you. Tap "Review & Add", check the entry it has already filled in, and save — no typing the amount, no switching apps. Nothing is ever added without your confirmation. Spends reads these messages on your device to find the amount and account; nothing is ever uploaded to us, and nothing is sent to anyone else. Prefer not to grant SMS access? Spends works fully with quick manual entry too — SMS is optional, and the app is completely usable without it.

▍ Budget by YOUR cycle, not the calendar
Smart Cycle tracks your money by your real salary and card-billing cycles, so you always know what's actually left to spend before your next paycheck — not just a generic calendar month.

▍ Everything you need to stay on top of spending
• Banks & Cards — track balances and see what's due when each statement generates
• Split a single payment across multiple categories
• A fast built-in calculator keypad for amounts
• Home-screen widgets — one-tap quick-add and a private balance summary you can hide
• Search, a clean day-by-day timeline, and long-press to bulk delete or re-categorise, with undo
• Recurring entries with a daily reminder
• Some banks now send alerts as chat messages instead of SMS — Spends can optionally read those too
• Optional backup to YOUR OWN Google Drive — we never see it
• Light, dark, or automatic dark on a schedule you set

▍ Private by design
No account. No ads. No analytics or tracking on your financial data. Your SMS and transactions are processed and stored on your device. The only network Spends uses is your own Google Drive backup — and only if you turn it on.

Spends requests SMS access solely to auto-detect your bank transaction alerts on your device, and it always works without it.

Privacy policy: https://aucksy.github.io/spends/
Questions or feedback: simpleapps108@gmail.com
```

---

## Listing metadata
| Field | Value |
|---|---|
| **App category** | Finance |
| **Tags** | expense tracker, budget planner, money manager (pick from Play's tag list) |
| **Email** | simpleapps108@gmail.com |
| **Website** (optional) | https://github.com/aucksy/spends |
| **Privacy policy URL** | https://aucksy.github.io/spends/ |
| **Default language** | English (India) — or English (US); the copy suits both |

## Graphics
- **App icon** — `play/assets/spends-icon-512.png` (512 × 512, ready to upload)
- **Feature graphic** — `play/assets/spends-feature-1024x500.png` (1024 × 500, ready to upload)
- **Phone screenshots** — 2–8, PNG/JPEG, min 320 px / max 3840 px on the long edge; **you capture these
  on-device** (see `play/PLAY_SUBMISSION_CHECKLIST.md` → Screenshots for exactly which)

## Notes
- Keep the SMS wording in the title/short description on every future update — the approved SMS
  Permissions Declaration depends on the listing continuing to foreground SMS as core functionality.
- ⚠️ **Do not re-add these three claims.** They were in this file until 2026-08-03 and none of them is
  true of the app. Play treats a listing that overstates the product as a policy issue, and each one is
  trivially disprovable by a reviewer holding the phone:
  - *"swipe-to-delete with undo"* — there is **no swipe gesture anywhere** in the app; it was removed
    deliberately after too many accidental deletes. Deleting is tap-a-row, or long-press to multi-select.
  - *"Material You colours"* — Material You was removed in v0.12.0. The app always uses its own brand
    palette. (The old setting still exists only so pre-v0.12 backups still load; the theme ignores it.)
  - *"add it in one tap"* — the notification's buttons are **Review & Add** and **Ignore**. "Review & Add"
    opens a pre-filled entry that you then Save. It is one tap to review, one to save — never a silent add.
    That is a selling point, not a shortfall: it is the whole "nothing is added without your confirmation"
    promise, and it is what the SMS demo video shows.
- Before each submission, re-verify every bullet against the running code, not against this file's history.
- Do **not** add "bank", a bank's name, or "UPI" logos to graphics in a way that implies an official
  partnership — Play rejects impersonation. "Reads your bank's SMS" as plain text is fine.
