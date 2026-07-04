# Spends — Google Play submission runbook

Everything needed to take Spends from "signed AAB" to "live on Play." Items marked **[owner]** must be
done by you in the Play Console / Google Cloud Console (I can't do those). Items marked **[done]** are
already prepared in this repo.

The signed **AAB** is produced by CI on every `vX.Y.Z` tag and attached to the GitHub Release
(`Spends-vX.Y.Z.aab`). That's the file you upload — no local build needed.

---

## 0. Pre-flight
- **[owner] Confirm the package name is free.** Creating the app (step 1) with `com.spends.app` will fail
  if it's taken. If so, tell me — changing `applicationId` ripples into signing + the Drive OAuth client,
  so we'd handle it deliberately (e.g. `com.spendsapp.app`), keeping the Kotlin package `com.spends.app`.
- **[done] Target API level:** `targetSdk = 35` — meets Play's current requirement for new apps.
- **[owner] Developer account type:** if this is a **personal** account created after Nov 2023, Google
  requires a **closed test with ≥ 12 testers** (may be 20) opted in for **14 continuous days** before you
  can request production access. Plan for this (step 11) — it's the long pole.

## 1. Create the app  **[owner]**
Play Console → **Create app**. Name **Spends**, default language **English (India)** (or US), type **App**,
**Free**. Accept the declarations.

## 2. Main store listing  **[owner]** — copy is **[done]** in `play/listing/store-listing.md`
- App name, short description, full description → paste from that file.
- App icon (512), feature graphic (1024×500), phone screenshots → from `play/assets/` (Round 2) + your
  captures (see **Screenshots** below).
- Category **Finance**; contact email `simpleapps108@gmail.com`.

## 3. Privacy policy  **[owner]** — page is **[done]** in `docs/index.html`
- Enable GitHub Pages: repo **Settings → Pages → Source: Deploy from a branch → `main` / `/docs`**.
- Confirm it serves at **https://aucksy.github.io/spends/** (allow a minute after saving).
- Paste that URL into Play Console → **Policy → App content → Privacy policy**, and it's already in the
  store description.

## 4. Data safety  **[owner]** — answers are **[done]** in `play/DATA_SAFETY.md`
Follow the recommended answers (local-first → "No data collected/shared"; deletion available; encrypted
in transit). The conservative alternative is documented there if you want it.

## 5. Content rating  **[owner]**
Policy → App content → **Content ratings** → complete the IARC questionnaire. Spends is a finance/utility
app with no objectionable content → expect **Everyone / PEGI 3**. Answer "No" to violence, sexual content,
gambling (a budgeting tool is not gambling), etc.

## 6. Target audience & "Financial features"  **[owner]**
- Target audience: **adults (18+ or 13+)**; the app is **not** designed for or directed at children.
- Play may ask a **Financial features** declaration. Spends is a **personal budgeting / expense-tracking
  tool** — it does **not** offer loans, banking, payments, crypto, or investments. Select that none of the
  regulated financial-product categories apply.

## 7. Ads  **[owner]**
Policy → App content → **Ads** → **No, my app does not contain ads.**

## 8. App access  **[owner]**
Core capture needs SMS + a bank SMS, which a reviewer can't easily reproduce. In **App access**, either
mark all functionality available without special access **and** point the reviewer to the SMS demo video,
or provide the step list from `play/PERMISSIONS_DECLARATION.md`. State clearly: SMS is optional; manual
entry needs no login.

## 9. SMS Permissions Declaration  **[owner]** — content is **[done]** in `play/PERMISSIONS_DECLARATION.md`
Policy → App content → **Sensitive app permissions → SMS and Call Log**. Pick the financial-transaction
use case, paste the justification, and **upload the demo video** (record per that file's script). This is
the highest-risk gate — the store listing already foregrounds SMS to support approval.

## 10. Upload the AAB  **[owner]**
Test and release → pick a track (start with **Closed testing**) → **Create release** → upload
`Spends-vX.Y.Z.aab` from the GitHub Release. Let Play use **Play App Signing** (recommended). Add release
notes.

## 11. ⚠️ CRITICAL — keep Google Drive backup working in production  **[owner]**
Spends authorizes Drive with a **client-side token** (`AuthorizationClient`, `drive.file` scope — see
`data/backup/DriveAuthManager.kt`). That token is granted against an **Android OAuth client** identified by
**package name + app signing SHA-1** — there is **no web client id** involved. Play re-signs your app with
its **own** key, so the production SHA-1 differs from your upload/debug keystore. **Drive backup will fail
for Play-installed users unless an Android OAuth client with the Play signing SHA-1 exists.**
1. After the first upload, Play Console → **Test and release → Setup → App signing** → copy the
   **App signing key certificate SHA-1**.
2. In **Google Cloud Console → APIs & Services → Credentials** (use the project whose OAuth consent screen
   Spends already uses — expected to be the shared **`gmailapi-491903`**; confirm), create an **Android
   OAuth client** for package `com.spends.app` with that **Play signing SHA-1**. Add a *second* Android
   client with your **upload/debug** keystore SHA-1 so sideload + internal testing keep working.
3. On that project's **OAuth consent screen**, make sure the scope `.../auth/drive.file` is listed.
   `drive.file` is a **non-sensitive** scope, so **no Google verification / security assessment is
   required** — you can publish the consent screen to Production, or keep it in Testing with your testers'
   emails added.
4. Sanity-check: install the closed-test build from Play and run a backup end-to-end *before* production.

Get your **upload/debug** keystore SHA-1 with:
```
keytool -list -v -keystore <your-keystore> -alias spends
```
(look for `SHA1:`). The **Play signing** SHA-1 comes from the console in step 1.

## 12. Closed testing → production  **[owner]**
- Add ≥ 12 testers (an email list or a Google Group) to the **Closed testing** track; have them opt in via
  the test link and install.
- Keep the test running **14 continuous days**, then **Apply for production access** and submit the
  production release. First review can take a few days.

---

## Screenshots to capture (you, on-device — 2 to 8, portrait)
Real screenshots convert best and also help the reviewer understand SMS capture. Suggested set:
1. **Home / timeline** with a few transactions + the summary header.
2. **Add expense** with the calculator keypad open.
3. **SMS capture** — the heads-up notification, or the pre-filled sheet from an SMS.
4. **Smart Cycle** — the remaining-to-spend view.
5. **Banks & Cards**.
6. **Split across categories** (nice-to-have).
Capture at the phone's native resolution; upload PNGs. Tell me if you want them framed on device mockups.

## Quick status
- [done] Privacy policy page, store copy, data-safety spec, SMS declaration + demo script, this runbook.
- [Round 2] 512 icon + 1024×500 feature graphic.
- [owner] Everything marked **[owner]** above.
- [optional Round 3] CI auto-upload of the AAB to a Play track via a service account.
