# Spends — Play Console "Data safety" answers

Fill this in Play Console → **Policy → App content → Data safety**. Below are the recommended answers
with the reasoning, so you can defend them if asked. Spends is local-first: nothing is sent to the
developer or any third party.

---

## Section 1 — Data collection and security

**Does your app collect or share any of the required user data types?**
→ **Recommended answer: No.**

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
  the merchant name as the bank wrote it, and the words of that one message with every run of digits
  replaced by "#". Under Play's definition this is **sharing with a third party**, so the "No" answer
  above is only defensible if you read it as "in the app's default configuration". **Recommendation: do
  NOT rely on that reading — declare it.** See the itemised table in Section 2.

> **Conservative alternative (only if you prefer maximum caution):** because the optional Drive backup
> does move *financial info* off the device (to the user's own Drive), you may instead declare
> **Financial info → "other financial info" as _collected_**, purpose **App functionality** (backup &
> restore), **not shared**, **optional**, encrypted in transit, deletable. Both positions are defensible;
> the "No" answer matches how comparable local-first apps with user-owned-cloud backup declare. Pick one
> and be consistent.

**Is all of the user data collected by your app encrypted in transit?**
→ **Yes.** (Both network paths — the optional Drive backup and the optional AI helper — use HTTPS.
On-device data is not in transit.)

**Do you provide a way for users to request that their data be deleted?**
→ **Yes.** Users delete entries in-app (Trash), clear app data / uninstall to wipe everything on-device,
and delete the Drive backup from within Spends or from Google Drive. (No server-side account exists.)

---

## Section 2 — If Google requires you to itemise (i.e. if you chose the conservative alternative)

| Data type | Collected | Shared | Purpose | Optional | Notes |
|---|---|---|---|---|---|
| Financial info → other financial info (your transactions) | Yes* | No | App functionality (backup & restore) | Yes | *Only via the user's own Google Drive backup, if enabled |
| SMS messages | Yes* | Yes* | App functionality (category suggestion) | Yes | *ONLY if the user enables the optional AI helper and supplies their own Groq key. A number-masked extract (all digit runs replaced by "#") plus the merchant string is sent to Groq. Off by default; nothing is sent otherwise. |
| Personal identifiers, contacts, location, etc. | No | No | — | — | Not accessed |

Because the AI helper shares message-derived content with Groq, **fill this table even if you otherwise
prefer the "No collection" answer** — the SMS row above is not optional once that feature ships.

---

## Key facts to keep consistent everywhere (listing, policy, this form)
- SMS is read **on-device**, is **optional**, and the app **works without it**. Message content leaves
  the device ONLY via the opt-in AI helper (off by default, user's own key, number-masked).
- No ads, no analytics. The ONLY third-party data sharing is the opt-in AI helper (Groq) — disclose it
  consistently in the listing, the policy and this form.
- Backup, if used, goes to the **user's own** Google Drive; the developer has no access.
- Data deletion is available (in-app Trash, clear data/uninstall, delete Drive backup).
- Encryption in transit: yes (Drive backup over HTTPS).
