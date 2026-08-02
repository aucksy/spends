# Spends — Play Console "Data safety" answers

Fill this in Play Console → **Policy → App content → Data safety**. Below are the recommended answers
with the reasoning, so you can defend them if asked. Spends is local-first and sends **nothing to the
developer**, ever, and **nothing to any third party**. The only place data ever leaves the phone is the
user's **own** Google Drive, if they switch backup on.

> **Changed in v1.65.0.** The optional AI helper — the one feature that shared anything with a third
> party (Groq) — has been **removed from the app**. Sections 1 and 2 below have been rewritten to match.
> If you have already submitted the older declaration, **this form must be re-submitted**: it currently
> tells Play the app shares financial data and SMS content with a third party, and that is no longer true.

---

## Section 1 — Data collection and security

**Does your app collect or share any of the required user data types?**
→ **Answer: Yes**, for the **optional Google Drive backup** only — and **"shared": No**.

Rationale (Play's definition of *collect* = transmitted off the device to you or a third party; *share*
= transferred to a third party):
- SMS content is parsed **on-device** and is **never transmitted anywhere**, in any configuration.
- All transactions/financial data live in an on-device database.
- The **optional** Google Drive backup writes to the **user's own** Google account (a "Spends Backup"
  folder the app creates; `drive.file` scope — the app can only access files it created, never the rest of
  the user's Drive). Per Google's Data safety guidance, transferring data to a user-controlled cloud account
  that the developer cannot access is **not** developer "collection" or "sharing." The developer never
  receives this data. It is still declared, as **collected / not shared / optional**, because the data does
  leave the device.
- No analytics/ads/third-party SDKs collect anything.
- **There is no third-party sharing of any kind.** Between v1.56.0 and v1.64.0 an opt-in AI helper sent
  masked message extracts and spending aggregates to Groq; that feature no longer exists in the app, and
  any API key the user had saved is erased the next time they open Spends.

**Is all of the user data collected by your app encrypted in transit?**
→ **Yes.** (The one network path — the optional Drive backup — uses HTTPS. On-device data is not in transit.)

**Do you provide a way for users to request that their data be deleted?**
→ **Yes.** Users delete entries in-app (Trash), clear app data / uninstall to wipe everything on-device,
and delete the Drive backup from within Spends or from Google Drive. (No server-side account exists.)

---

## Section 2 — The itemised declaration

| Data type | Collected | Shared | Purpose | Optional | Notes |
|---|---|---|---|---|---|
| Financial info → other financial info (your transactions) | Yes* | **No** | App functionality (backup & restore) | Yes | *Only via the user's **own** Google Drive backup, if the user enables it. The developer cannot access it. Nothing is sent to any third party. |
| SMS messages | **No** | **No** | — | — | Read and parsed **on-device only**; never transmitted. |
| Personal identifiers, contacts, location, etc. | No | No | — | — | Not accessed |

> **Corrected 2026-08-02 (v1.65.0).** The two rows above previously declared third-party *sharing* of both
> financial info and SMS content, because of the AI helper. The helper has been removed, so both "Shared"
> answers are now No and the SMS row collects nothing at all. Re-check this table whenever a feature is added
> that makes a network call — it is the declaration a Play reviewer holds the app to.

---

## Key facts to keep consistent everywhere (listing, policy, this form)
- SMS is read **on-device**, is **optional**, and the app **works without it**. Message content **never**
  leaves the device.
- No ads, no analytics, **no third-party data sharing at all**.
- Backup, if used, goes to the **user's own** Google Drive; the developer has no access.
- Data deletion is available (in-app Trash, clear data/uninstall, delete Drive backup).
- Encryption in transit: yes — the one network path (Drive backup) uses HTTPS.
