# Spends — Google Play store listing copy

Paste these verbatim into Play Console → **Grow → Store presence → Main store listing**.
SMS-based expense tracking is deliberately foregrounded (short description, and the FIRST
section of the full description) — this is required for the SMS Permissions Declaration to be approved.

---

## App name  (max 30 chars)
```
Spends: Offline Money Tracker
```
(29 chars.) Chosen by the owner on 31 Oct 2026. Replaces "Spends: SMS Expense Tracker", which read as if it
tracked SMS. SMS stays foregrounded where the declaration needs it: the short description opens with
it, and so does the first section of the full description.

## Short description  (max 80 chars)
```
Tracks spending from your bank SMS. You check each one. Private, works offline.
```
(79 chars.)

## Full description  (max 4000 chars)
```
Spends reads the payment SMS your bank already sends and turns it into an expense list. Your data stays on your phone.

▍ Expenses from your bank SMS
When a bank or card SMS arrives, Spends shows a notification with the amount and account already filled in. Tap "Review & Add", check it, and save. Spends never adds anything without you.
If your phone delays the SMS, Spends finds it the next time you open the app. "Scan past SMS" picks up older bank texts.
Some banks send alerts as app notifications instead of SMS. Spends can read those too, if you turn it on.
Spends reads these messages only on your phone. It never uploads them. SMS access is optional: you can add every entry by hand instead.

▍ Count by salary, not by calendar month
Smart Cycle counts your money from one salary day to the next. It shows how much of this salary is left, and can carry the leftover into the next cycle.

▍ Everything else you need
• Banks & Cards: check balances and card bill due dates in one place
• Split one payment across several categories
• Type amounts on a built-in calculator
• Two home-screen widgets: quick add, and a balance that stays hidden until you tap the eye
• Search, and a day-by-day list of every entry
• Long-press to delete or re-categorise many entries at once, with undo
• Repeating entries, with a reminder at 9 AM
• Export to Excel, or import from Excel or CSV
• Light theme, dark theme, or dark between times you choose

▍ Private by design
No account. No ads. No analytics.
Spends goes online for only two things, and both stay off until you turn them on:
• Backup to your own Google Drive. We never see it.
• Foreign-currency conversion with your own AI key (OpenAI, Groq or Gemini). It asks only the exchange rate, never your transactions. Converted amounts are marked as estimates.

Spends asks for SMS access only to find your bank transaction alerts on your phone. The app works fully without it.

Privacy policy: https://spends-privacy.aakashpahuja1990.workers.dev/
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
| **Privacy policy URL** | https://spends-privacy.aakashpahuja1990.workers.dev/ |
| **Default language** | English (India) — or English (US); the copy suits both |

## Graphics
- **App icon** — `play/assets/spends-icon-512.png` (512 × 512, ready to upload)
- **Feature graphic** — `play/assets/spends-feature-1024x500.png` (1024 × 500, ready to upload)
- **Phone screenshots** — 2–8, PNG/JPEG, min 320 px / max 3840 px on the long edge; **you capture these
  on-device** (see `play/PLAY_SUBMISSION_CHECKLIST.md` → Screenshots for exactly which)

## Notes
- Keep the SMS wording in the title/short description on every future update — the approved SMS
  Permissions Declaration depends on the listing continuing to foreground SMS as core functionality.
- ⚠️ **Do not re-add these claims.** They were in this file until 2026-08-03 and none of them is
  true of the app. Play treats a listing that overstates the product as a policy issue, and each one is
  trivially disprovable by a reviewer holding the phone:
  - *"swipe-to-delete with undo"* — there is **no swipe gesture anywhere** in the app; it was removed
    deliberately after too many accidental deletes. Deleting is tap-a-row, or long-press to multi-select.
  - *"Material You colours"* — Material You was removed in v0.12.0. The app always uses its own brand
    palette. (The old setting still exists only so pre-v0.12 backups still load; the theme ignores it.)
  - *"Auto-track" and "budgeting"* (removed 31 Oct 2026) — Spends never adds an expense on its own (you
    save each one), and it has no budget limits. Smart Cycle shows what is left of a salary; that is all.
  - *"add it in one tap"* — the notification's buttons are **Review & Add** and **Ignore**. "Review & Add"
    opens a pre-filled entry that you then Save. It is one tap to review, one to save — never a silent add.
    That is a selling point, not a shortfall: it is the whole "nothing is added without your confirmation"
    promise, and it is what the SMS demo video shows.
- Before each submission, re-verify every bullet against the running code, not against this file's history.
- Do **not** add "bank", a bank's name, or "UPI" logos to graphics in a way that implies an official
  partnership — Play rejects impersonation. "Reads your bank's SMS" as plain text is fine.
