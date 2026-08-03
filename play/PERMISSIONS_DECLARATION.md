# Spends — SMS Permissions Declaration (Play Console)

Spends requests `READ_SMS` + `RECEIVE_SMS`, which are **restricted permissions**. You must complete the
**Sensitive app permissions → SMS and Call Log permissions** declaration in Play Console
(**Policy → App content → Sensitive app permissions**, and again in the release flow if prompted).

Approval hinges on two things, both already true for Spends:
1. The **core purpose** genuinely needs SMS, and
2. The **store listing foregrounds** SMS-based expense tracking (it does — see `play/listing/`).

---

## Declared permissions
- `android.permission.READ_SMS`
- `android.permission.RECEIVE_SMS`

## Core use case to select
Choose the financial / transaction SMS use case — in the form this is the option for an app whose core
feature is detecting the user's own **financial transactions from SMS** (money-management / expense
tracking). Do **not** claim default-SMS-handler, backup, or OTP — none apply.

## Justification text (paste into the declaration's description box)
```
Spends is a personal expense tracker whose core feature is automatically capturing the user's own bank
and credit-card transactions from the SMS alerts their bank sends. When a transaction SMS arrives,
Spends parses it ON THE DEVICE to extract the amount and account, then posts a notification offering
"Review & Add" or "Ignore". "Review & Add" opens the expense pre-filled so the user checks it and saves
it. Nothing is ever recorded without the user's explicit confirmation.

READ_SMS is required to read existing bank transaction messages (e.g. to record spends made before
install, on user request), and RECEIVE_SMS is required to detect a new transaction the moment its SMS
arrives. There is no alternative API that can read a bank's transaction SMS: the SMS Retriever / User
Consent APIs only work for one-time-password messages the app itself triggers, which does not apply to
arbitrary bank alerts.

All parsing happens locally on the device. Message content is never sent to the developer, is never sent to
any third party, and is never used for advertising or marketing. Nothing derived from a message leaves the
phone at all. The SMS permission itself
is optional: the app is fully functional with manual entry, and prominently discloses why it needs SMS
before requesting it.
```

## Prominent in-app disclosure (already implemented — reviewers will see it)
On the SMS onboarding step, before the runtime permission prompt, Spends shows:
> "The moment a bank SMS arrives, Spends spots the transaction on your phone and notifies you to add it
> in one tap — that's why it asks for SMS and notification access. Your messages are read and parsed on
> your phone, and nothing is ever sent to us."

(Keep this quote in sync with `OnboardingScreen.kt` — it is the disclosure a Play reviewer will see. It
was reworded on 2026-07-26 to accommodate the optional AI helper; that helper was removed in v1.65.0, so
the app once again sends nothing derived from a message anywhere.)

⚠️ **Known wording drift, worth fixing in the app before you submit.** That on-screen line says "add it in
one tap", but the notification's actual buttons are **Review & Add** and **Ignore**, and "Review & Add"
opens a pre-filled entry the user then saves. The justification text above and the demo-video script below
both describe the real two-step flow. Reword the app's onboarding line to match (then update this quote),
so the reviewer never sees the app promise something the video doesn't show.

The step is skippable, and the app works fully without the grant.

---

## Not part of this form, but expect questions: notification access
Spends also ships an optional **NotificationListenerService**, because some Indian banks now send alerts
as RCS chat or Truecaller "Business Chat" instead of SMS, which no app can read as SMS. It is **off by
default**, reads only the messaging apps the user explicitly ticks (Google Messages, Truecaller), parses
on-device, and — like SMS — adds nothing without confirmation.

This is **not** covered by the SMS/Call Log declaration, so there is no form to fill. But notification
access draws reviewer attention on its own, so keep the in-app explanation (shown before the user is sent
to Android's notification-access screen) intact, and be ready to point to it in **App access** if asked.

---

## Demo video (required by the declaration)
Record a short (30–60 s) screen recording, upload it (unlisted YouTube is fine), and paste the link in
the form. Script:
1. Launch Spends → onboarding **"Detect spends from SMS"** step — show the disclosure text, tap Continue,
   grant the SMS permission on the system dialog.
2. Trigger/receive a bank-style transaction SMS (e.g. send yourself: *"Rs 450 debited from A/c XX1234 at
   CAFE COFFEE on 04-Jul-26"*).
3. Show the heads-up notification Spends posts; tap it.
4. Show the expense sheet pre-filled with the amount; tap **Save**.
5. Show the saved transaction in the timeline.
6. (Optional) Open the app and add an expense **manually** to show SMS is optional.

Keep a copy of the video link here once recorded: `______________________`

## If the declaration is rejected
Fallback (agreed): ship an SMS-free Play build (remove `READ_SMS`/`RECEIVE_SMS` from the manifest;
manual entry remains), publish, and pursue notification-based capture separately. The full SMS build
stays available on GitHub Releases for sideloading.
