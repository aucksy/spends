# SMS capture diagnostic — handoff state (2026-07-28)

The owner stopped the session: it burned hours and a weekly usage limit on **thirteen adversarial
review rounds** for a temporary diagnostic screen. That was the wrong call. The work is sound but it
should have shipped after round two or three and let the phone answer the rest.

**Nothing has been released. The owner's capture is still broken and still undiagnosed.**

## Where the investigation actually got to

Established from owner evidence, not assumption:

- Live SMS capture last worked **~21:00, Sun 26 Jul 2026**, via the "Review & Add" popup, on v1.57.0.
- It died with v1.58.0 or later. v1.62.0 is on the phone.
- Demo mode OFF, SMS permission Allowed, "Detect from bank SMS" ON, bank texts visibly present in
  Truecaller (the owner's default SMS app), notification reader alive, prompt category ON, review
  queue empty.
- **Every code change in that window was traced and eliminated on evidence.** Conclusion: the cause is
  outside the app — something on the phone stopped feeding the receiver — and the app had no way to
  say which part. That is what the new screen exists to answer.

Full reasoning: `docs/SMS-CAPTURE-DEBUG.md`.

## What is built and committed (HEAD = 95f1b5a + one uncommitted round)

1. **SMS debug screen** (Settings → Automatic Entries → Detect from SMS & notifications → SMS debug).
   A verdict line plus counters. The load-bearing number is **"SMS delivered (this app run)"** — if it
   stays 0 while texts arrive, Android is not handing them to the app and nothing in Spends is the cause.
2. **Two permanent capture fixes**, neither of which is the owner's cause but both of which silently ate
   money: a parsed bank SMS was discarded when the prompt could not be shown (now queued for review),
   and both live paths were blind to the "Transaction detection" notification category being off alone.
3. **Honest scan messages** — `scanMessage` / `cardScanMessage` now distinguish "read nothing",
   "read plenty, all already known", "couldn't read", and "refused in demo mode". The old single
   sentence for all of them cost a full round of this investigation.

Money safety unchanged throughout: `SmsParser` and its golden fixtures untouched, capture still
review-only, no DB/schema/migration/manifest/dependency change. Re-verified every round.

## Shipped as v1.63.0 (2026-07-29)

The loop was stopped and the work released. `c9c2a82` was pushed to `main` as-is, versionCode 68 → 69 /
versionName 1.62.0 → 1.63.0, and tagged `v1.63.0`.

**Round 14 — the one and only round of this session — returned COMPILE: CLEAN and LOGIC: CLEAN, zero
blockers on both agents.** Every finding was wording or test coverage, so by the stopping rule below,
none was fixed; they are recorded under "Known, not fixed" instead.

**The cloud answered what the reviewers could not.** Both agents correctly refused to claim the code
builds (no local Android toolchain). They did not need to: pushing `c9c2a82` to `main` triggered the
existing workflows, and at that exact SHA **`Android Debug APK` completed successfully** — which runs the
full Kotlin compile *and* KSP/Hilt annotation processing — and **`CI` (`testDebugUnitTest`) passed**,
executing the assertions whose runtime behaviour static review explicitly could not confirm (the regex
masking boundaries, the 2000-char scan bound, the `(?U)` Devanagari/Arabic-Indic cases, and the
`KFunction6` reference in `SmsVerdictTest`). Static review plus a real cloud build is the pairing that
should replace a fourteenth round of reading.

**Independently confirmed money-safety invariant**: `git diff --name-only 3c8b345..c9c2a82` touches no
parser, no golden fixture, no migration, no manifest and no dependency file.

## Known, not fixed — deliberately deferred

All are wording or test-coverage; none touches capture behaviour, money, or a privacy leak.

**From rounds 1–13:**

- `smsEmptyStateOf`'s `totalReceived > 0` boundary is unpinned (`> 1` passes the suite).
- Verdict branch order capture-switch ↔ early-fault is unpinned; both sentences are true, so it is
  priority-only.
- NUMERAL-before-link-rules ordering is not equivalent and is unpinned.
- `ADDRESS`'s letter-after-`@` requirement is unpinned.
- The ✅ / ⚠️ markers are unpinned on both screens, including a cross-string coupling where the verdict
  says "the ⚠️ rows below".
- `NotificationDebugScreen.verdictOf` and both `buildReport`s have thin or no coverage.
- `SmsReceiver`'s suspend calls inside the `goAsync` coroutine are not individually wrapped; an
  exception there escapes a bare `SupervisorJob` and crashes the process. **Pre-existing**, not
  introduced by this work, but worth its own round.

**Added by round 14 (the ship round). Recorded, not fixed:**

- `SmsDebugViewModel.kt:202` — **the closest call in the diff.** The app-fault verdict says "the ⚠️ rows
  below are ones it dropped", but `PROMPT_BLOCKED` also renders ⚠️ and those rows were *queued*, not
  dropped. Reachable only with an intermittent DB failure, the row's own text contradicts the verdict,
  and the error direction is over-alarming rather than falsely reassuring. Fix is one word: "the ⚠️
  *start-up* rows below". This is the same cross-string coupling already listed above.
- `CaptureViewModel.kt:129` — `runCatching{}.getOrNull()` collapses "threw" and "null cursor" into one
  message and swallows `CancellationException`. `CardsViewModel` got the honest treatment this release;
  its sibling did not. Fixing the class, not the instance — lesson 3, again.
- `SmsCaptureRepository.queueForReview` skips `relaxedNoRefDuplicate` for SMS rows (`sourceApp == null`),
  so an SMS-queued row can duplicate a ledger row committed from a ref-less notification twin.
  Confirm-time `twinAlreadyCommitted` catches it and deletes the row: cost is **one stale review row,
  never double money**. Traced explicitly, hence non-blocking.
- `SmsDebugViewModel`'s `buildReport` prints the raw enum (`PROMPTED`) and `YES`/`NO`, while the screen
  prints plain English and `Yes`/`No` — the pasted report and the screen use different vocabulary for
  the same row.
- `SmsDebugScreen.kt:193` claims the report "lists the sender name of each text"; for non-header-shaped
  senders it lists a marker instead. Safe-direction overstatement.
- `SmsDebugLog.ReceiverFailures` is a JVM-global with no reset — nothing increments it in tests today, so
  no pollution yet, but it is a live trap for the next test author.
- `maskContent` runs four regexes over up to 2000 chars while holding the `@Synchronized` monitor that
  `recordReceived()` needs on the main looper; and `SmsDebugViewModel.init` makes a binder call
  (`ensureChannel()`) on the main thread. Both bounded and both correct, but both are main-thread work.
- `CaptureNotifier.kt:86` catches only `SecurityException`; an `IllegalArgumentException` from `notify()`
  would escape. Reachable only if `getSystemService(NotificationManager)` returned null.
- Pre-existing, unchanged: `messages.joinToString { it.messageBody }` (`SmsReceiver.kt:99`) NPEs if the
  platform hands back an array containing null elements.

**One pre-existing privacy exposure, named here because it changes what is safe to paste.** It is *not*
introduced by this work and is *not* on the SMS debug screen — the SMS report was verified clean, with no
digit run of any script, no VPA/email shape, and no raw body surviving redaction. But
`NotificationDebugViewModel.buildReport` prints `title`, `detail` and `messageSenders` **unredacted for
every entry, including personal chats** — only the body goes through `bodyFor`. For a Google Messages
entry that is a contact name, or a raw phone number for an unknown sender. This release *adds* an
accurate disclosure on that screen rather than fixing the redaction. **The SMS debug report is safe to
send; the notification debug report should be read first.** Worth its own round.

## The five lessons worth keeping

1. **Almost every defect found in 13 rounds was the tool asserting something it could not know** — first
   a *cause*, later a *channel*. Never a crash, never wrong arithmetic. False confidence.
2. **Seven dead tests, one shape**: asserting what a change leaves BEHIND rather than what it REMOVES,
   or banning the removed words without pinning the replacement. Write both halves.
3. **Fixing the instance, not the class.** A TYPE closes the class; a rule about what to pass closes one
   instance. `kind` as a String, the closed OTP list on one screen, the twin wording on one screen.
4. **A negative check proves nothing until you have shown it CAN find something.** A byte-level search
   for a stray newline missed one because it searched for LF in a CRLF file. Every scanner since was
   validated against that known defect before its clean result was believed.
5. **Do not rewrite source containing escape sequences through a shell-quoted script.** Three artifacts
   from that, one a build-breaker that rendered as an ordinary line wrap in the diff.

## And the honest one

Thirteen rounds on a temporary diagnostic was disproportionate. The review process was working — it
caught an OTP leak, a UPI-ID leak and a build-breaking character literal — but the stopping rule was
wrong. "Clean on both agents" has no bound when each fix round can introduce a new wording defect.
A better rule: **ship when no finding touches behaviour, money, or a privacy leak.** That was true by
round 8.
