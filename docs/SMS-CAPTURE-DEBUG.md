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

The last-capture timestamp is the load-bearing fact. `v1.57.0` was tagged at **19:08** that evening and
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

| # | Link | Where it stops | Outcome recorded |
|---|------|----------------|------------------|
| 0 | Android delivers `SMS_RECEIVED` | Force-stop, OEM background kill, revoked grant, app hibernation | **nothing at all — `totalReceived` stays 0** |
| 1 | Demo mode | `SmsReceiver:64` | `DEMO_MODE` |
| 2 | Readable message in the broadcast | `SmsReceiver:70` | `NO_MESSAGE_DATA` |
| 3 | Capture switch on | `SmsReceiver:99` | `CAPTURE_OFF` |
| 4 | Sender in `SenderAllowlist` | `SmsCaptureRepository.preview` → `SmsParser.parse` | `SENDER_NOT_RECOGNISED` |
| 5 | Parses as a money movement | same | `NOT_A_TRANSACTION` |
| 6 | Pattern not suppressed (#7) | `isPatternSuppressed` | `PATTERN_SUPPRESSED` (queued, not lost) |
| 7 | Not already held | `isKnownHash` | `ALREADY_KNOWN` |
| 8 | Wins the twin race | `guard.claimPrompt` | `TWIN_ALREADY_PROMPTED` |
| 9 | The phone will show the prompt | `CaptureNotifier.canPost` | `PROMPT_BLOCKED` (queued, not lost) |
| — | Prompt shown | — | `PROMPTED` |

**Link 0 is the one that matters.** `totalReceived` is incremented for every SMS the receiver is handed,
before any other check. If it stays at zero while texts are visibly arriving on the phone, Android is not
delivering to the app and nothing inside Spends can be the cause.

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
3. **Every stored body has every digit masked**, via `SmsParser.aiContextFor` — unconditionally.
4. **A sender that isn't an A2P header is masked**: no letters at all means a phone number; one
   containing `@` is an email-to-SMS address, a stronger identifier than the number.

`detail` is gated by rule 1 as well, and is what makes rule 3 free: the amount, kind and merchant Spends
actually parsed are reported there, already interpreted, so the raw digits were only ever contributing
the balance, the account tail and the reference number.

Consequently `buildReport` needs no redaction pass: what reaches it is already safe to paste. That is a
stronger guarantee than the notification screen's redact-on-the-way-out, which depends on getting an
outcome allow-list right forever.

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
- `app/src/test/.../SmsDebugLogTest.kt`, `app/src/test/.../ui/capture/SmsVerdictTest.kt`

**KEEP — these are permanent fixes, not diagnostics:**
- `CaptureNotifier.postCapturePrompt` returning `Boolean`, and `canPost` / `promptsCanBeSeen`
- the `queueForReview` fallback on **both** live paths
- `SmsCaptureRepository.scanMessage` and its use in `CaptureViewModel`
- `app/src/test/.../CapturePromptVisibilityTest.kt`, `app/src/test/.../ScanMessageTest.kt`
- `SenderAllowlist.lookup(sender)` being resolved in `SmsReceiver` — it is the privacy gate, but deleting
  it along with the log would also remove the only place the receiver names the institution

Removing the log without removing its callers will not compile, which is the intended safety net.
