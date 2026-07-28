# Live SMS capture — diagnosis log

Working doc for "bank SMS stopped being captured entirely" (opened 2026-07-28, against shipped
v1.62.0). Sibling of [`NOTIFICATION-CAPTURE-DEBUG.md`](NOTIFICATION-CAPTURE-DEBUG.md), which covers
the separate, older Truecaller-notification problem.

**These are two different bugs.** Notification capture has never produced a capture since it shipped
in v1.53.0. Live SMS capture worked for months and then stopped — and it is the more important of the
two, because it is the path that actually delivers the app's signature feature.

## What was established, and how

Owner evidence, gathered 2026-07-28:

| Fact | Value | Source |
|---|---|---|
| Build on the phone | v1.62.0 | Android app info |
| Demo mode | OFF (no strip) | the strip is permanent and non-dismissible while demo mode is on |
| SMS permission | Allowed | Android → Apps → Spends → Permissions |
| "Detect from bank SMS" switch | ON | in-app |
| Bank texts physically present | Yes | visible in Truecaller, the owner's default SMS app |
| Notification reader | bound, alive, seeing traffic | Notification debug: 3 notifications from `com.grofers.customerapp` |
| Notification prompt category | ON | Android → Apps → Spends → Notifications |
| Review queue | empty | in-app |
| **Last successful capture** | **~21:00, Sun 26 Jul 2026, via the "Review & Add" popup** | a transaction in the ledger the owner did not type |

The last-capture timestamp is the load-bearing fact. `v1.57.0` was tagged at **19:14** that evening and
`v1.58.0` at **21:19** — so capture was alive on v1.57.0 and died with v1.58.0 or later.

### Every code change in that window, and why none of them can be the cause

`git diff v1.57.0..v1.62.0 -- app/src/main` touches the live SMS path in exactly three places.

**v1.58.0 — `SmsParser` merchant extraction (`stripReportTrailer`, `looksLikeMerchant`).** The only
change in the release that touches the SMS path at all. Traced through all five routes by which a
merchant change could propagate:

1. *The TRANSACTION/IGNORED decision* — unaffected. `parse()` never requires a merchant; `extractMerchant`
   is called after `classify()` has already returned.
2. *Amount, kind, date, last4, ref* — unaffected. All read the untouched original `text`;
   `stripReportTrailer` is applied inside `extractMerchant` only.
3. *Confidence* — a null merchant costs 7 points. The floor for any TRANSACTION is
   `55 + 20 + 10 = 85`, against a `REVIEW_THRESHOLD` of 70 — and that constant gates nothing in the
   capture path anyway (only `ExpenseRepository.observeNeedsReview` uses its own copy).
4. *Dedupe hashes* — the value shifts (`key = last4 ?: merchant ?: ""`), but does not collapse: day and
   amount still separate distinct transactions. A shifted hash makes a message look **new**, not known.
5. *Prompt routing* — nothing downstream branches on the merchant.

**v1.59.0 — the demo-mode early return in `SmsReceiver`.** Ruled out by direct observation: demo mode
is off, and its banner is permanent and non-dismissible while it is on.

**v1.60.0 – v1.62.0 — AI insights.** No capture file touched.

**Conclusion: the cause is outside the app's code.** Something on the phone stopped feeding the
receiver, and the app had no way to say which part.

### The dead end that cost a round, and the fix it produced

"Scan past SMS" was used as the decisive test and returned *"Nothing new to review in that range"* for
both a one-month and a twelve-month range. That was read as "the inbox is unreadable" and sent the
diagnosis toward a ColorOS privacy-blanking theory. It was wrong.

`scanHistory` skips any parsed SMS matching a **manual** transaction on `day|amount|kind`. The owner had
been entering every transaction by hand since capture broke — so every bank text in twelve months
legitimately matched something they had typed, and the scan honestly had nothing to offer. **The owner's
own workaround had masked the only recovery path.**

The message was the same sentence for `scanned=0` and for `scanned=214, skipped=214` — opposite facts.
`ScanResult` computed both numbers and the UI threw them away. Fixed: `SmsCaptureRepository.scanMessage`
is now pure, unit-tested, and always reports the read count.

## The chain, link by link

Every one of these is silent from outside the app, and the phone shows the same thing for all of them:
nothing happens. That is why the diagnostic exists.

Line numbers are deliberately omitted — an earlier revision of this table cited five and every one had
gone stale within a single commit.

| # | Link | Gate | Outcome recorded |
|---|------|------|------------------|
| 0 | Android delivers `SMS_RECEIVED` | force-stop, OEM background kill, revoked grant, hibernation | **nothing at all — `totalReceived` stays 0** |
| 0b | The log itself is obtainable | `EntryPointAccessors` / `smsDebugLog()` throws | **`graphFailures` only — nothing can be recorded** |
| 1 | Demo mode off | `DemoMode.isEnabled` | `DEMO_MODE` |
| 2 | Broadcast carries a message | `getMessagesFromIntent` | `NO_MESSAGE_DATA` |
| 3 | The message has text | `body.isBlank()` | `BLANK_BODY` |
| 3b | The rest of the graph builds | `captureRepository()` etc. throws — a corrupt DB opens here | `APP_NOT_READY` **+ `graphFailures`** |
| 4 | Capture switch on | `smsCaptureEnabled` | `CAPTURE_OFF` |
| 5 | Sender in `SenderAllowlist` | `SenderAllowlist.lookup`, in the receiver | `SENDER_NOT_RECOGNISED` |
| 6 | Parses as a money movement | same | `NOT_A_TRANSACTION` |
| 7 | Pattern not suppressed (#7) | `isPatternSuppressed` | `PATTERN_SUPPRESSED` (queued, not lost) |
| 8 | Not already held | `isKnownHash` | `ALREADY_KNOWN` |
| 9 | Wins the twin race | `guard.claimPrompt` | `TWIN_ALREADY_PROMPTED` |
| 10 | The phone will show the prompt | `CaptureNotifier.canPost` | `PROMPT_BLOCKED` (queued, not lost) |
| — | Prompt shown | — | `PROMPTED` |

**Link 0 is the one that matters.** `totalReceived` is incremented for every SMS the receiver is handed,
before any other check. If it stays at zero while texts are visibly arriving on the phone, Android is not
delivering to the app and nothing inside Spends can be the cause.

**Links 0b and 3b exist so that claim stays true**, and the split between them matters. At 0b nothing
can be recorded, so only the counter moves and the verdict keys on `totalReceived == 0`. At 3b the log
IS in hand, so the message is recorded as `APP_NOT_READY` — because `recordReceived()` has already run
by then, and keying the verdict on a zero count would have missed the likelier failure entirely. That
gap shipped for one round: a database that would not open produced "its sender name has changed, that's
a one-line fix", printed above an empty list. The sender advice is now withheld whenever `APP_NOT_READY`
rows are VISIBLE — evidence the reader can see, not a counter. Three rounds were spent moving a
`graphFailures > 0` test up and down the branch list: too high it latched (the counter never decays, so
one cold-start hiccup suppressed every actionable diagnosis for the rest of the run); too low, the
blocked-prompt branch claimed "nothing is lost" while a corrupt database dropped every message. Keying
on the entries settles both, and decays with "Clear what's recorded" and the 60-entry ring exactly as
the reader's own evidence does.

The counter is a plain object — the injected log is the thing that failed — and is never reset,
including by "Clear what's recorded": a confirmed fault outranks tidiness, and the failing broadcasts
cannot be replayed.

## Two real defects found while building the diagnostic

**1. The SMS path discarded a parsed transaction when the prompt could not be shown.**
`CaptureNotifier.postCapturePrompt` returned early on `!areNotificationsEnabled()`; `SmsReceiver` ignored
that and moved on. The transaction was not shown, not queued, and not recorded anywhere. The identical
bug was found on the notification path during the Phase 4 review and fixed there — and never back-ported.
`postCapturePrompt` now returns whether the prompt was actually handed to Android, and both paths queue
when it was not.

**2. Both paths only ever checked the app's MASTER notification toggle.**
A prompt hidden by the "Transaction detection" **category** alone was accepted by `notify()`, silently
binned by the system, and lost. One long-press on a prompt and "turn these off" — or an OEM notification
manager — kills SMS capture permanently and invisibly, while every screen in the app and in Android keeps
reading ON. `CaptureNotifier.canPost(notificationsEnabled, channelImportance)` is now the single pure rule
that both live paths and the debug screen branch on, so the screen cannot report a verdict the capture
path disagrees with.

Neither is the cause of this outage — the owner confirmed both switches are on — but both eat real money
in silence, so both are fixed.

## Privacy stance (stricter than the notification log, deliberately)

Every SMS on the phone flows through `SmsReceiver`, so most of what passes through this log is personal
mail. Four rules are enforced **inside `SmsDebugLog.record`**, not at the call site, so no future caller
can leak by forgetting. `record` takes **no `institution` parameter** — it resolves the sender itself, so
a caller cannot assert "this came from a bank" and be believed:

1. **A message body is stored only when the sender resolves to a tracked bank**, resolved here via
   `SenderAllowlist`. Everything else keeps `body = null` regardless of what was passed in.
2. **…and only for an outcome reached with the capture switch ON** (`BODY_BEARING`). An owner who never
   enabled capture never has a bank alert transcribed.
3. **Every stored body has every number masked, every link PATH removed and every address removed** —
   unconditionally — and the ADDRESS rule is applied to `detail` too (not the numeral rule: the parsed
   amount is precisely what makes masking the body free). Links matter because Indian bank alerts
   carry per-customer short paths that identify the recipient; the HOST is kept, because it is the
   merchant and never the identifier (`AMAZON.IN/(link)`, `HDFCBK.IO/(link)`). Addresses matter because
   statement and UPI alerts quote the registered email or the payee's VPA — and because `detail` carries
   the PARSED merchant, which is a verbatim substring of the bank's text, so for
   `INR 250 spent at coffeeday@ybl` the body rule stripped the VPA and `detail` reprinted it one line
   above until round 6. The honest claim is "every number", not "every secret": a code written in
   letters, or a personal name, survives. No Indian bank uses a lettered passcode.
4. **Only header-shaped senders are kept.** This is an ALLOW-LIST, not an address detector, and that
   distinction is the whole lesson: `contains('@')` masked genuine GSM 03.38 padding (`AD-HDFCBK@`,
   where septet 0x00 *is* `@`), hiding the string the verdict asks the owner to report; the anchored
   email pattern that replaced it was then strictly weaker than what it replaced, letting
   `john.smith@gmail.com@` through verbatim. A shape the allow-list doesn't recognise defaults to
   withheld, so a new address form cannot leak by not having been thought of.

The masking is written **in `SmsDebugLog`**, not borrowed from `SmsParser.aiContextFor` as one revision
did. That function builds AI context and strips the "not you? / SMS BLOCK…" trailer first, so a body
opening with such a phrase was deleted entirely and stored as null — indistinguishable on screen from
"the privacy gate withheld it". Its 300-char cap also silently took ownership of this class's own bound,
leaving the local cap unreachable and untestable. **A privacy control must not be a borrowed function
whose purpose, and therefore whose future edits, belong to something else.**

`detail` is gated by rules 1 and 2 as well. For a parsed outcome it carries the amount, kind and merchant
Spends actually read, which is what makes rule 3 cost nothing. For `NOT_A_TRANSACTION` the receiver adds the
parser's own verdict (`read as ignored` / `read as statement`) — and *only* that. An earlier version
also reported "amount found / no amount matched" and the direction; both were dead code (reaching that
branch means a null amount and kind), and "no amount matched" was usually false, because the OTP, promo,
declined and mandate rejects fire before the amount regex ever runs. Masking does cost real diagnostic
power here, and this narrows the loss rather than papering over it with a claim.

Consequently `buildReport` needs no redaction pass: what reaches it is already safe to paste. That is a
stronger guarantee than the notification screen's redact-on-the-way-out, which depends on getting an
outcome allow-list right forever — **but it is only as good as the rules being applied to every stored
field.** `detail` was gated but not masked for two rounds, which made the same sentence false while it
was written in this file. If a field is added to `Entry`, it needs a rule here or it needs to not exist.

### The rule-3 near-miss, kept as a warning

Rule 3 originally masked **only** `NOT_A_TRANSACTION`, on the reasoning that a non-transaction from a
bank is overwhelmingly an OTP. The gate producing that outcome is `SmsParser.isOtp`, which excludes any
text containing `spent` or `debited` — so these all parsed as genuine transactions, reached `PROMPTED`,
and had the passcode stored and exported verbatim while the screen promised the opposite:

```
Rs.5000.00 has been debited from your a/c XX1234. OTP 481920 to confirm.
Rs.500 spent on card XX1234 at SHOP. OTP 481920 to authorise.
Your OTP for the transaction of Rs 2,000 debited from A/c XX1234 is 481920.
```

Three lessons, all of which cost a review round:

- **A parser heuristic is not a privacy control.** `isOtp` exists to decide what to log as money. It was
  never designed to decide what is safe to put on a clipboard, and it fails that job on the commonest
  formats.
- **The masking was inverted with respect to diagnostic value.** The one outcome it covered
  (`NOT_A_TRANSACTION`) is where digits genuinely help — a parse failure is usually an amount format the
  regex missed. The outcomes it skipped are the ones where `detail` already carries the parsed figures.
- **A test can pin a defect in place.** `SmsDebugLogTest` asserted that a real transaction "keeps its
  numbers — they are the diagnosis". That test went red under the correct fix, so anyone attempting the
  obvious repair would have been told the suite forbade it. It is now inverted, and named for the
  regression instead.

Nothing is written to disk, nothing enters the backup snapshot, and everything is dropped when the
process restarts.

## Removal checklist

The diagnostic is temporary. The **fixes are not** — keep them.

**DELETE when the root cause is known:**
- `data/capture/SmsDebugLog.kt`
- `ui/capture/SmsDebugScreen.kt`, `ui/capture/SmsDebugViewModel.kt` (including the top-level
  `smsVerdictOf`)
- `Routes.SMS_DEBUG` + its `composable(...)` in `SpendsNavHost`
- the `onOpenSmsDebug` parameter threaded through `CaptureSettingsScreen` → `CaptureSection`, and the
  "SMS debug" row
- the `smsDebugLog()` accessor on `SmsReceiver.SmsCaptureEntryPoint`, and every `debug.record(...)` /
  `debug.recordReceived()` call plus the local `note(...)` helper in `SmsReceiver`
- `SmsDebugLog.ReceiverFailures` and the `recordGraphFailure()` calls — **but NOT the `runCatching`
  guards around them**, which prevent a crash on the main looper and are a permanent fix (see KEEP)
- the `graphFailures` parameter threaded through `smsVerdictOf` and `SmsDebugUiState`, and the
  `APP_NOT_READY` outcome
- `app/src/test/.../SmsDebugLogTest.kt`, `app/src/test/.../ui/capture/SmsVerdictTest.kt`

**KEEP — these are permanent fixes, not diagnostics:**
- `CaptureNotifier.postCapturePrompt` returning `Boolean`, and `canPost` / `promptsCanBeSeen`
- the `queueForReview` fallback on **both** live paths, and both paths reporting its returned id
- `SmsCaptureRepository.scanMessage` / `cardScanMessage`, `ScanResult.refusedDemoMode`, and the nullable
  returns of `scanHistory` / `scanInboxForCards`
- the `runCatching` around the entry-point lookup **and** around the four provision calls in
  `SmsReceiver` — the graph is built inside the provision methods, so that is where a corrupt database
  or a failed migration actually throws, on the main looper inside a system broadcast
- `app/src/test/.../CapturePromptVisibilityTest.kt`, `app/src/test/.../ScanMessageTest.kt`
- `SenderAllowlist.lookup(sender)` being resolved in `SmsReceiver` — it only separates
  `SENDER_NOT_RECOGNISED` from `NOT_A_TRANSACTION` and is safe to delete with the log, but note it is
  **not** the privacy gate: `SmsDebugLog` resolves the sender itself and ignores what callers claim

Removing the log without removing its callers will not compile, which is the intended safety net.
