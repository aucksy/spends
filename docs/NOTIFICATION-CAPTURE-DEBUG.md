# Truecaller notification capture — diagnosis log

Working doc for the "Truecaller alerts never become Review & Add captures" bug (opened 2026-07-26,
against shipped v1.56.1 / notification capture shipped v1.53.0).

## The chain, link by link (as it exists in code today)

| # | Link | File | Verdict from code read |
|---|------|------|------------------------|
| 1 | Notification access granted → system binds listener | `AndroidManifest.xml:110-117` | Declaration is correct: `BIND_NOTIFICATION_LISTENER_SERVICE` + the `android.service.notification.NotificationListenerService` action. |
| 2 | Listener alive & rebound after an app update / OEM kill | `CaptureNotificationListenerService.onListenerDisconnected` | **GAP — see H1.** `requestRebind` is only reachable from a live process, and the only *proactive* rebind is in the Settings switch. |
| 3 | Package match | `NotificationCaptureApps.kt:18` = `com.truecaller` | Correct for the full Truecaller app. **Truecaller Lite is `com.truecaller.slim` and is NOT watched** — see H4. `<queries>` for both watched packages is declared (`AndroidManifest.xml:44-47`), so `isAppInstalled` is not a false negative. |
| 4 | Shape filters | `looksReadable()` | Skips own package, non-clearable, `FLAG_GROUP_SUMMARY`, `FLAG_ONGOING_EVENT`, `FLAG_FOREGROUND_SERVICE`. Nothing here should drop a normal message notification. |
| 5 | Text extraction | `extractCandidates()` + `NotificationCapture.candidates()` | MessagingStyle per-message (sender/text/timestamp) with `conversationTitle → title` sender fallback; else `bigText → text` with `title` as sender. **If MessagingStyle is present but no message resolves a sender, the bigText fallback is never tried** — see H3b. |
| 6 | Sender recognition | `SenderAllowlist.canonicalSenderFor()` | Header form first, then `byDisplayName` **exact match**, then up to 2 suffix strips from `{LIMITED, OFFICIAL, INDIA, CARDS, CARD, BANK, LTD}`. Anything else → `null` → candidate dropped **silently**. See H3a. |
| 7 | Age gate | `process()` | 72 h live / 7 d on the shade sweep, measured on the message timestamp. Fine for a fresh alert. |
| 8 | Repost guard | `RecentCaptureGuard.checkAndMark`, 7 d TTL | In-memory; keyed `msg\|pkg\|sender\|bodyHash\|len`. Resets on process death. Fine. |
| 9 | Parse | `SmsParser.parse()` via `handleNotificationAlert` | Untouched deterministic parser. Rejects OTP/promo/declined/limit/future-mandate. |
| 10 | Dedupe | `handleNotificationAlert` + `relaxedNoRefDuplicate` | **A ref-less capture is dropped if ANY same-day/same-amount/same-kind row exists in the ledger** (`ledgerHasDayAmountKind`). Known accepted residual from v1.53.0 — see H5. |
| 11 | Prompt / queue | `claimPrompt` → `CaptureNotifier`, else `queueForReview` | Blocked-notifications fallback queues silently. Fine. |

Every failure at links 5, 6, 9 and 10 is **silent** — no counter, no log, no UI. That is why this bug is
invisible from the outside and why an on-device diagnostic is warranted.

## Hypotheses, ranked

**H1 — the listener is not bound at all (top suspect, and a real hardening gap regardless).**
`NotificationListenerService.requestRebind(...)` is called from exactly two places, both in
`CaptureSection.kt` (the Settings switch, and the return-from-access-screen resume). Nothing asks for a
rebind at app launch. `onListenerDisconnected → requestRebind` only helps while the process is alive.
So after an APK update (the owner has installed v1.54.0 → v1.56.1 since capture shipped in v1.53.0) or
an OEM battery kill, the service can stay unbound indefinitely while the Settings toggle still reads ON
and Android's Notification-access screen still shows Spends as granted. Symptom: **nothing** is captured
from **any** watched app, with no error anywhere.
*Free test:* toggle Android's notification access for Spends OFF then ON, then reproduce an alert.
*Fix regardless:* call `requestRebind` on app launch (and after boot), idempotent and cheap.

**H2 — the alert also arrives as a real SMS, so the SMS path wins the twin race.**
By design: `claimPrompt` gives one real payment exactly one prompt, and the SMS receiver is faster. The
capture then shows as an SMS capture, not "DETECTED FROM NOTIFICATION". Not a bug — but it would look
like "Truecaller isn't detected". Only matters for alerts that also exist as SMS; the RCS-only case
(the whole point of watching Truecaller) is unaffected.

**H3 — the text is readable but the sender is rejected.**
- **H3a (likely):** `byDisplayName` is a fixed table matched *exactly* after at most two suffix strips.
  Real RBM/Truecaller agent names that would fail today include `HDFC Bank InstaAlerts`,
  `Axis Bank Alerts`, `SBI Card Services`, `ICICI Bank Ltd.` (trailing dot is stripped by the
  non-alphanumeric filter, so that one is fine) — anything with a *leading* or *middle* extra token, or
  a third trailing token, returns `null`. Also: if Truecaller titles the conversation with the raw
  **phone/shortcode number**, `headerOf` returns `null` for all-digit senders by design.
- **H3b (structural):** in `NotificationCapture.candidates`, a non-empty `messages` list short-circuits —
  if every message's sender fails to resolve, the function returns an empty list and **never falls back**
  to the `title` + `bigText` path, even when `title` would have resolved.

**H4 — wrong Truecaller build.** Truecaller Lite ships as `com.truecaller.slim`
([Play listing](https://play.google.com/store/apps/details?id=com.truecaller.slim)) and is not in
`CANDIDATES`. If the phone has Lite, capture can never fire.

**H5 — parsed and recognised, but silently deduped.** `relaxedNoRefDuplicate`: RCS/business-chat alerts
often carry no reference number, and any same-day + same-amount + same-kind ledger row then swallows the
capture. Conservative by design (never double-count), but it does make genuine alerts disappear.

**H6 — genuinely unreadable (the known RCS caveat).** A true RBM rich card can be rendered with custom
`RemoteViews` and no `EXTRA_TEXT` / `EXTRA_BIG_TEXT` and no MessagingStyle. No third-party app can read
that. If the diagnostic shows empty title *and* empty text for a Truecaller bank alert, this is the
answer and it is not fixable from our side.

## What can't be settled from code

H1, H3a, H3b, H5 and H6 all produce the identical outward symptom (nothing appears). Distinguishing them
needs to know what the listener actually *received*. Next step is an owner-readable in-app diagnostic
list recording, per notification from a watched app: package · title · text/bigText · MessagingStyle
sender · whether a sender resolved · whether it parsed · and the reason it was dropped. To be removed or
hidden before the real fix ships.

## What was built (2026-07-26)

Owner confirmed: **notification capture has never once produced a capture**, from either watched app.
That kills the "Truecaller-specific payload" theories as the *sole* cause and promotes H1. Owner chose
"diagnostic + reconnect fix in one build".

**1. The reconnect fix (real behaviour change).** New `service/NotificationListenerControl.kt`:
`hasAccess` / `requestRebind` / `ensureBound(context, alreadyConnected)`. Called from
`SpendsApp.onCreate` (gated on `notificationCaptureEnabled`, after a 5 s grace so a healthy install
never pays for a pointless unbind/rebind cycle) and from `BootReceiver` (unconditional `requestRebind`
there — nothing is bound that early, so there is no connected flag to consult). This closes the gap
where the grant and the toggle both read "on" while the service is not bound to anything.
Caveat to keep honest: `requestRebind` is documented for the post-`requestUnbind` case, so it is a
*likely* remedy for a lost binding, not a guaranteed one — which is exactly why the diagnostic ships
alongside it rather than after it.

**2. The diagnostic (temporary).**
- `data/capture/NotificationDebugLog.kt` — `@Singleton`, **in memory only** (never persisted, never in
  the backup snapshot, cleared on process restart). Ring buffer of 60 entries + a per-package counter.
  Notification *content* is kept only for watched apps; every other app contributes a package name and
  a count, which is what proves whether the reader is alive at all.
- `NotificationCapture.diagnose()` — pure mirror of `candidates()` that names WHY nothing survived
  (`NO_READABLE_TEXT` vs `SENDER_NOT_RECOGNISED`) and lists the sender strings that were tried.
- `CaptureNotificationListenerService` — records at every drop point (`SKIPPED_SHAPE`, `TOO_OLD`,
  `ALREADY_SEEN`, plus the repository's `NOT_A_TRANSACTION` / `DUPLICATE` / `QUEUED` / `PROMPTED`) and
  sets connected state in `onListenerConnected` / `onListenerDisconnected`. **Capture behaviour is
  unchanged** — the payload is now read once into a `Payload` and shared between `candidatesOf()` and
  the debug entry, and every existing guard/dedupe/review-only rule is byte-for-byte the same.
- `ui/capture/NotificationDebug{Screen,ViewModel}.kt` + `Routes.NOTIFICATION_DEBUG`, reached from
  Settings → Automatic Entries → Detect from SMS & notifications → **Notification debug**. Deliberately
  outside the `notificationEnabled` block so it is reachable when capture looks on but is doing nothing.
  Leads with a one-line verdict, then access/connected/seen counters, a **Reconnect** button, the list of
  every package that posted a notification, and per-alert detail. **Copy report** puts the whole thing on
  the clipboard for pasting back into chat.
- Tests: `NotificationDebugLogTest` (ring cap, newest-first, counters, clear-does-not-lie-about-connection,
  and the four `diagnose()` verdicts).

## How to read the result

| What the screen says | What it means | Next move |
|---|---|---|
| Access granted: **No** | Never granted, or revoked. | Grant it; nothing else matters until then. |
| Connected: **No** | H1 confirmed — the binding is the bug. | Tap Reconnect; if that fixes it, the launch-time rebind is the permanent fix. |
| Connected yes, seen **0** | Bound but handed nothing — very unusual. | Trigger any notification at all. |
| Seen > 0, watched-app events **0** | Reader works; Truecaller alerts aren't reaching us. | Read the package list — the bank alert is coming from some other package (or Truecaller Lite, H4). |
| Events present, `SENDER_NOT_RECOGNISED` | H3a — add the shown name to `SenderAllowlist.byDisplayName`. | Paste the `detail` line back; it names exactly what to map. |
| Events present, `MESSAGES_SHADOWED_BIG_TEXT` | **H3b — our bug, not the RCS limit.** Textless MessagingStyle messages made `candidates()` commit to the messages branch and skip a perfectly readable `bigText`. | Fix `candidates()` to fall through to the plain branch when no message has text. |
| Events present, `NO_READABLE_TEXT` | H6 — the RCS caveat, and only now that H3b is ruled out separately. | Not fixable from our side; report it plainly. |
| Events present, `DUPLICATE` | H5 — the relaxed no-ref net swallowed it. | Revisit `relaxedNoRefDuplicate` for notification-only alerts. |

## Removal checklist (once the root cause is fixed)

Delete: `NotificationDebugLog.kt` · `NotificationCapture.diagnose` + `Rejection`/`Diagnosis` ·
`NotificationDebug{Screen,ViewModel}.kt` · `NotificationDebugLogTest.kt` · `Routes.NOTIFICATION_DEBUG`
+ its `composable` · the `onOpenNotificationDebug` params on `CaptureSettingsScreen`/`CaptureSection`
+ the row · the `debugLog` injection, the `Payload`-based debug entry builders, and every `debugLog.*`
call in the listener.

**Keep — these are the permanent fix, not diagnostics:** `NotificationListenerControl` (including
`connected`/`setConnected`, `openAccessSettings`, and the `Boolean` return on `requestRebind`), the
`NotificationListenerControl.setConnected(...)` calls in `onListenerConnected`/`onListenerDisconnected`,
the launch-time `ensureBound` in `SpendsApp`, the boot-time `requestRebind` in `BootReceiver`, and
`CaptureSection`'s delegation to `NotificationListenerControl`.

The `connected` flag deliberately lives on `NotificationListenerControl`, **not** on
`NotificationDebugLog` — an earlier draft had `SpendsApp` reading it off the debug log, which would
have made this checklist break the build. `NotificationDebugLog` keeps its own display copy, fed by the
same callbacks; that copy goes away with the rest of the diagnostic.

## Status

- 2026-07-26 — code trace complete; reconnect fix + diagnostic written. Not yet tagged (owner says
  when to ship). Release ritual still owed at tag time: 2 adversarial review agents, then bump.
