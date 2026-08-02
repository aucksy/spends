# Known issue — a genuine second payment can be dropped by the twin guard

**Status: deliberately NOT fixed.** Owner's decision, 2026-08-02: rare on his setup, and he will
report it if money actually goes missing. This file exists so the next session does not have to
re-derive any of it.

**Severity if it fires: MONEY.** The second payment is not queued, not logged, not recoverable.
The only trace is one in-memory diagnostic line that dies with the process.

---

## What the owner would see

Two payments, same shop, same amount, same day, minutes apart. Spends prompts for the first and
**silently bins the second** — no notification, no review row, nothing on the timeline. The worst
case is a retried payment that actually went through twice: a genuine double charge, hidden.

## Why

`RecentCaptureGuard.claimPrompt` collapses the SMS and notification copies of ONE bank alert into
one prompt, keyed on the RELAXED hash (day | amount | kind | last4-or-merchant, ref excluded).
Two payments only escape the collapse when **both** carry a reference number **and** the two refs
differ. If either side is ref-less the guard calls it a twin and the caller drops it.

Both live paths then simply record a debug outcome and return:

- `SmsReceiver` — the `else` branch, `SmsDebugLog.Outcome.TWIN_ALREADY_PROMPTED`.
- `CaptureNotificationListenerService.process` — the `else` branch, `Outcome.DUPLICATE`.

Neither calls `queueForReview`. Compare the ignore-suppression path a few lines above in
`SmsReceiver`, which **does** queue — that asymmetry is the whole defect.

## How likely, on this owner's phone

Low, and that is the basis of the decision to defer:

- Notification capture is **off** by default, so in practice there is only ONE channel (raw SMS).
  Android delivers `SMS_RECEIVED` to Spends directly regardless of which app is the default SMS
  app, so Truecaller being default is irrelevant — the twin case needs the listener switched on.
- With only SMS in play, both texts come straight from the bank and Indian bank alerts almost
  always carry a reference number, so the refs differ and both prompt correctly.
- It therefore needs a bank that omits the reference number, plus two same-amount same-shop
  payments inside 15 minutes.

## Why it is not a one-line fix

Queueing the dropped capture is **not sufficient**, and shipping only that would be worse than
today: the row would appear in review and then vanish when the owner tapped Add.

`confirmPending` / `confirmPendingEdited` / `confirmAllPending` all re-run `twinAlreadyCommitted`,
which deletes the row when:

1. `p.dedupeHash` is already in the ledger — hits when both texts are ref-less, because two
   identical ref-less payments produce the **same** hash. The dedupe input format is FROZEN, so
   there is no field left to tell them apart.
2. `!refless && relaxed in ledgerHashes` — hits when the FIRST text was ref-less and the second
   carried a ref.
3. `refless && sourceApp != null && ledgerHasDayAmountKind(...)` — hits any notification-sourced
   ref-less row once any same-day/amount/kind row exists.

Only the case "first text HAS a ref, second is ref-less, arriving over SMS" survives all three.

## The fix, if it is ever wanted

1. `claimPrompt` returns three states instead of a Boolean: claimed / provably-identical twin
   (refs both present and equal — keep dropping silently) / **uncertain** (either side ref-less).
2. On *uncertain*, both live paths call `queueForReview` instead of returning.
3. Add a column to `pending_captures` marking "queued because it collided with something already
   prompted — a human must decide". DB v16 → v17, one additive `ALTER TABLE ... DEFAULT 0`.
   `pending_captures` is **not** part of the Drive backup snapshot, so backup/restore is unaffected.
4. `twinAlreadyCommitted` and the `confirmAllPending` loop return early (false) for flagged rows —
   the automatic guard must not overrule a decision the owner is explicitly making.
5. Store flagged rows under a disambiguated dedupe hash so case (1) above can insert and commit at
   all. Re-scans still skip the original hash, so no double-queue.
6. Review card shows the reason in plain words, e.g. "Second ₹450 at Swiggy within minutes — real
   payment, or the same one twice?" A flagged row without that sentence is unanswerable.

Estimated risk of introducing new problems: **4/10**. The migration is additive and the flag is
inert for every row that exists today, so there is no regression path for current behaviour. The
one real trade-off: a true twin now leaves a visible review row to dismiss, and "Confirm all"
would add it. That is deliberate — a visible duplicate is correctable, a silent loss is not.

**Irreversibility to flag to the owner before shipping it:** once DB v17 is installed, going back
to any v1.66.0-or-earlier build needs app data cleared and a backup restore.
