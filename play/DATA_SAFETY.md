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
- SMS content is parsed **on-device** and never transmitted. Only the expense you confirm is saved,
  locally.
- All transactions/financial data live in an on-device database.
- The **optional** Google Drive backup writes to the **user's own** Google account (a "Spends Backup"
  folder the app creates; `drive.file` scope — the app can only access files it created, never the rest of
  the user's Drive). Per Google's Data safety guidance, transferring data to a user-controlled cloud account
  that the developer cannot access is **not** developer "collection" or "sharing." The developer never
  receives this data.
- No analytics/ads/third-party SDKs collect anything.

> **Conservative alternative (only if you prefer maximum caution):** because the optional Drive backup
> does move *financial info* off the device (to the user's own Drive), you may instead declare
> **Financial info → "other financial info" as _collected_**, purpose **App functionality** (backup &
> restore), **not shared**, **optional**, encrypted in transit, deletable. Both positions are defensible;
> the "No" answer matches how comparable local-first apps with user-owned-cloud backup declare. Pick one
> and be consistent.

**Is all of the user data collected by your app encrypted in transit?**
→ **Yes.** (The only network path — the optional Drive backup — uses HTTPS. On-device data is not in transit.)

**Do you provide a way for users to request that their data be deleted?**
→ **Yes.** Users delete entries in-app (Trash), clear app data / uninstall to wipe everything on-device,
and delete the Drive backup from within Spends or from Google Drive. (No server-side account exists.)

---

## Section 2 — If Google requires you to itemise (i.e. if you chose the conservative alternative)

| Data type | Collected | Shared | Purpose | Optional | Notes |
|---|---|---|---|---|---|
| Financial info → other financial info (your transactions) | Yes* | No | App functionality (backup & restore) | Yes | *Only via the user's own Google Drive backup, if enabled |
| SMS messages | No | No | — | — | Read & parsed on-device only; content never leaves the phone |
| Personal identifiers, contacts, location, etc. | No | No | — | — | Not accessed |

If you chose the recommended "No collection" answer, you do **not** fill this table.

---

## Key facts to keep consistent everywhere (listing, policy, this form)
- SMS is read **on-device only**, is **optional**, and the app **works without it**.
- No ads, no analytics, no third-party data sharing.
- Backup, if used, goes to the **user's own** Google Drive; the developer has no access.
- Data deletion is available (in-app Trash, clear data/uninstall, delete Drive backup).
- Encryption in transit: yes (Drive backup over HTTPS).
