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
Spends parses it ON THE DEVICE to extract the amount and account and notifies the user to save the
expense in one tap.

READ_SMS is required to read existing bank transaction messages (e.g. to record spends made before
install, on user request), and RECEIVE_SMS is required to detect a new transaction the moment its SMS
arrives. There is no alternative API that can read a bank's transaction SMS: the SMS Retriever / User
Consent APIs only work for one-time-password messages the app itself triggers, which does not apply to
arbitrary bank alerts.

All parsing happens locally on the device. Message content is never transmitted off the device, never
sent to the developer, and is never used for advertising or marketing. The permission is optional: the
app is fully functional with manual entry, and prominently discloses why it needs SMS before requesting
it.
```

## Prominent in-app disclosure (already implemented — reviewers will see it)
On the SMS onboarding step, before the runtime permission prompt, Spends shows:
> "The moment a bank SMS arrives, Spends spots the transaction on your phone and notifies you to add it
> in one tap — that's why it asks for SMS and notification access. Nothing leaves your phone."

The step is skippable, and the app works fully without the grant.

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
