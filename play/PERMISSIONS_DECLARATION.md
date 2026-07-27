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

All parsing happens locally on the device. Message content is never sent to the developer and is never
used for advertising or marketing. The app has one optional feature, off by default, that can send a
limited extract off the device: an "AI helper" the user must switch on and supply their own third-party
API key for. It has two independently-switchable parts. **Category suggestions** send the merchant name,
whether it was money in or out, the words of that one message with every run of digits replaced by "#"
(removing amounts, balances, account and card numbers, numeric dates and phone numbers — but not a month
written in letters), the user's category names, and up to 100 of their saved merchant→category shortcuts
(merchant and category names only, no amounts or dates) so a merchant they have tagged before can be
recognised. This obtains a suggested spending category, which the user still confirms manually. **Spending insights** send no message content at all — only
figures about spending, income and the user's own recurring rules, mostly aggregates: per-category and income/expense totals for the cycle being viewed, and the spending totals for the one before it (never the previous cycle's income), what the
user typically spends in a category, for two of the cards a single charge's amount and category (plus, for
"charged twice?", how many identical charges there were), and for the
cards that compare over time the day reached in the cycle, the cycle's calendar month name, the same
stretch's total a year earlier, one category's per-cycle figures, and the share of spending falling in the
week after payday, the share of the cycle's days that week makes up, and how many earlier cycles it was
measured over. Two further cards send the total of the user's monthly recurring rules that have
already started and how many there are, measured against the median income of their COMPLETED cycles (never
this cycle's income); and how much of the cycle's income remains, as an amount and as a share, alongside
the share the user had usually kept by the same point. (The cycle's income TOTAL is listed earlier in this
paragraph; it is sent by the summary card, which has done so since v1.56.0, not by these two.) Every card describes
what the user's money did, and the model is explicitly instructed not to suggest what to do with it, tell
the user to spend less, or give financial advice, with no exceptions in the prompt. Spends' own wording on one card does say a repeated
charge is "worth a look in case one was billed twice" — a prompt to check one's own records, written by the
app rather than the model, and not advice about spending. No transaction dates and no transaction records are ever sent. With
the AI helper off — its default state — no message content and no spending figures leave the device. The SMS permission itself
is optional: the app is fully functional with manual entry, and prominently discloses why it needs SMS
before requesting it.
```

## Prominent in-app disclosure (already implemented — reviewers will see it)
On the SMS onboarding step, before the runtime permission prompt, Spends shows:
> "The moment a bank SMS arrives, Spends spots the transaction on your phone and notifies you to add it
> in one tap — that's why it asks for SMS and notification access. Your messages are read and parsed on
> your phone, and nothing is ever sent to us."

(Keep this quote in sync with `OnboardingScreen.kt` — it is the disclosure a Play reviewer will see. It
was reworded on 2026-07-26: the previous absolute "Nothing leaves your phone" stopped being true for
users who opt into the AI helper, which is covered separately in Settings → AI helper.)

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
