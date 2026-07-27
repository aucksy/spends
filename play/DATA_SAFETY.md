# Spends — Play Console "Data safety" answers

Fill this in Play Console → **Policy → App content → Data safety**. Below are the recommended answers
with the reasoning, so you can defend them if asked. Spends is local-first and sends **nothing to the
developer**, ever. It does send data to **one third party** — Groq — but only when the user switches on
the optional AI helper (off by default) and supplies their own API key. That exception is what the
answers below turn on, so read it before filling the form.

---

## Section 1 — Data collection and security

**Does your app collect or share any of the required user data types?**
→ **Answer: Yes** — because of the optional AI helper. ("No" would only be defensible read as "in the
app's default configuration", and Section 2 below explains why not to rely on that reading.)

Rationale (Play's definition of *collect* = transmitted off the device to you or a third party; *share*
= transferred to a third party):
- SMS content is parsed **on-device**. It is never transmitted with the app in its default state. See
  the AI-helper bullet below for the one opt-in exception.
- All transactions/financial data live in an on-device database.
- The **optional** Google Drive backup writes to the **user's own** Google account (a "Spends Backup"
  folder the app creates; `drive.file` scope — the app can only access files it created, never the rest of
  the user's Drive). Per Google's Data safety guidance, transferring data to a user-controlled cloud account
  that the developer cannot access is **not** developer "collection" or "sharing." The developer never
  receives this data.
- No analytics/ads/third-party SDKs collect anything.
- **The optional AI helper (off by default) DOES share with a third party when enabled.** The user must
  switch it on and paste their own Groq API key. It then sends, for a transaction the user is reviewing:
  the merchant name as the bank wrote it, the words of that one message with every run of digits
  replaced by "#", whether it was money in or out, the user's category names, and up to 100 of their
  saved merchant→category shortcuts (names only) — and, for the insight cards, aggregate spending figures: per-category and income/expense
  totals for the viewed cycle and the one before it, what the user typically spends in a category, one
  charge's amount and category for two of the cards, and for the over-time cards the day reached in the
  cycle, the cycle's calendar month name, the same stretch's total a year earlier, one category's per-cycle
  figures, and the share of spending falling in the week after payday. Never a transaction date, a merchant
  (for insights), a balance, an account/card number, or a transaction record.
  Under Play's definition this is **sharing with a third party**. That is why the answer above is Yes: a
  "No" would only hold read as "in the app's default configuration", and that reading is not worth relying
  on. See the itemised table in Section 2, which must be filled in.

> **Note on the optional Drive backup:** it also moves *financial info* off the device, but to the
> **user's own** Drive rather than to a third party — declare it as **Financial info → "other financial
> info" _collected_**, purpose **App functionality** (backup & restore), **not shared**, **optional**,
> encrypted in transit, deletable. This is a separate line from the AI helper, which IS third-party
> sharing. Before v1.56.0 a blanket "No" was defensible here; since the AI helper shipped it is not, so
> Section 2 below must be filled in.

**Is all of the user data collected by your app encrypted in transit?**
→ **Yes.** (Both network paths — the optional Drive backup and the optional AI helper — use HTTPS.
On-device data is not in transit.)

**Do you provide a way for users to request that their data be deleted?**
→ **Yes.** Users delete entries in-app (Trash), clear app data / uninstall to wipe everything on-device,
and delete the Drive backup from within Spends or from Google Drive. (No server-side account exists.)

---

## Section 2 — The itemised declaration (fill this in; the AI helper makes it mandatory)

| Data type | Collected | Shared | Purpose | Optional | Notes |
|---|---|---|---|---|---|
| Financial info → other financial info (your transactions) | Yes* | Yes** | App functionality (backup & restore; spending insights) | Yes | *Only via the user's own Google Drive backup, if enabled. **ONLY if the user enables the optional AI helper's insights and supplies their own Groq key: per-category and income/expense **totals** for the viewed cycle and the one before it, plus a comparison figure for what they typically spend in a category. Mostly aggregates; two insight cards ("a large charge", "charged twice?") additionally send **one charge's amount and its category**, and the cards that compare over time send the day reached in the cycle, the cycle's **calendar month name** (e.g. "July"), the same stretch's total a **year earlier**, one category's per-cycle figures across six cycles, and the **share of spending falling in the week after payday**. Never a merchant, a transaction date, a balance, an account/card number, or a transaction record. Off by default. |
| SMS messages | Yes* | Yes* | App functionality (category suggestion) | Yes | *ONLY if the user enables the optional AI helper and supplies their own Groq key. Sent to Groq: a number-masked extract of that one message (all digit runs replaced by "#" — which removes numeric dates but NOT a month written in letters), the merchant string as the bank wrote it, whether it was money in or out, the user's category names, and up to 100 of their saved merchant→category shortcuts (merchant and category names only, no amounts or dates) so a merchant they have tagged before can be recognised. Off by default; nothing is sent otherwise. |
| Personal identifiers, contacts, location, etc. | No | No | — | — | Not accessed |

Because the AI helper shares message-derived content with Groq, **this table is mandatory** — the SMS row
above is not optional now that the feature has shipped.

> **Corrected 2026-07-27.** The financial-info row previously said sharing happened *only* via the user's own
> Drive backup. That has been inaccurate since v1.56.0: the AI helper's insights card already sent spending
> **aggregates** to Groq. The row now covers both paths. Re-check this table whenever the insights payload
> changes — it is the declaration a Play reviewer holds the app to.

---

## Key facts to keep consistent everywhere (listing, policy, this form)
- SMS is read **on-device**, is **optional**, and the app **works without it**. Message content leaves
  the device ONLY via the opt-in AI helper (off by default, user's own key, number-masked).
- No ads, no analytics. The ONLY third-party data sharing is the opt-in AI helper (Groq) — disclose it
  consistently in the listing, the policy and this form.
- Backup, if used, goes to the **user's own** Google Drive; the developer has no access.
- Data deletion is available (in-app Trash, clear data/uninstall, delete Drive backup).
- Encryption in transit: yes — both network paths (Drive backup and the optional AI helper) use HTTPS.
