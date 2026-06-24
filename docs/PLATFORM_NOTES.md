# Platform & version notes (verified 2026-06-24)

Findings from a live verification pass over the v2 build prompt's assumptions. These pin the build
toolchain and capture platform rules that later phases depend on. **The repo builds only in CI**
(the author's machine cannot run local Gradle/emulators), so versions are pinned to the most
conservative known-green set rather than the newest.

## Build toolchain (pinned in `gradle/libs.versions.toml`)
| Lib | Pin | Why |
|---|---|---|
| AGP | 8.7.3 | proven on the author's shipped app (Pause) |
| Kotlin / KSP | 2.0.21 / 2.0.21-1.0.28 | same |
| Compose BOM | 2024.10.01 | same |
| Hilt (Dagger) | 2.52 | same; KSP-processed |
| androidx.hilt (nav + work + compiler) | 1.2.0 | KSP since 1.1.0; compatible with Hilt 2.52 |
| **Room** | **2.6.1** | conservative; predates KSP2 transition risk. Do NOT chase 2.7/2.8. |
| **WorkManager** | **2.10.0** | contemporaneous with BOM 2024.10.01 / compileSdk 35. Avoid 2.11.x (minSdk 23, much newer). |
| DataStore | 1.1.1 | proven |
| JDK / Gradle | 17 / 8.10.2 | proven |

**KSP processor gotcha:** run BOTH `ksp(com.google.dagger:hilt-android-compiler:2.52)` AND
`ksp(androidx.hilt:hilt-compiler:1.2.0)` or `@HiltWorker` codegen is silently skipped. Room compiler
is also KSP. (All three are wired in `app/build.gradle.kts`.)

**Deferred deps (add in their phase, versions confirmed compatible with Kotlin 2.0.21):**
- kotlinx-serialization: plugin `org.jetbrains.kotlin.plugin.serialization:2.0.21` (MUST equal Kotlin version) + runtime `org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3` — for the Drive JSON backup (Phase 7).
- Charts (Phase 2): Vico `com.patrykandpatrick.vico:compose-m3:2.5.2` (2.x line matches BOM 2024.10.01; do NOT use 3.x with this BOM, and avoid the legacy `com.patrykandpatryk` spelling). A custom Canvas donut is fine for the signature donut.
- OCR (Phase 9): `com.google.mlkit:text-recognition:16.0.1` — prefer the **unbundled** (Play services) variant (~260 KB vs ~4 MB bundled).
- XLSX (Phase 8): `org.dhatim:fastexcel:0.20.2` + `org.dhatim:fastexcel-reader:0.20.2` (keep writer+reader same version; off-main-thread; .xlsx only).

## Capture rules (Phases 3–4)
- **SMS:** request only `READ_SMS` + `RECEIVE_SMS` (+ `RECEIVE_WAP_PUSH` only if parsing WAP). Play "SMS-based money management" use case is still accepted; the **store listing must foreground SMS-based expense tracking as core functionality** or the Permissions Declaration is rejected. New tightening effective **Oct 28 2026** (answer/16558241): no ad/marketing use of SMS data. App must still work (manual entry) if denied.
- **NotificationListenerService:** manifest `android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"` + intent-filter for `android.service.notification.NotificationListenerService`. Grant via `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`. Self-heal a dead listener with `requestRebind(componentName)` from `onListenerDisconnected` and/or `PackageManager.setComponentEnabledSetting(DISABLED→ENABLED, DONT_KILL_APP)`. Persist each SBN immediately in `onNotificationPosted`.
- **DO NOT use `USE_FULL_SCREEN_INTENT`** — Play auto-revoked it for non-call/alarm apps (live since Jan 22 2025). Drop the permission entirely. Instead post a high-importance heads-up notification (IMPORTANCE_HIGH channel) with a `contentIntent` PendingIntent to the capture activity; the user taps to open the half-sheet.
- **Background activity start:** cannot silently launch the capture Activity from a background receiver/service. Use the notification-tap PendingIntent path. On target API 35 (compileSdk 35) the PendingIntent **creator** must opt in via `ActivityOptions.setPendingIntentCreatorBackgroundActivityStartMode(MODE_BACKGROUND_ACTIVITY_START_ALLOWED)` passed into `PendingIntent.getActivity(...)`; use `FLAG_IMMUTABLE`.

## Drive backup (Phase 7)
- **Auth ≠ Authorization.** Authenticate with **Credential Manager** (`androidx.credentials:credentials:1.6.0` + `:credentials-play-services-auth:1.6.0` + `com.google.android.libraries.identity.googleid:googleid:1.0.0`); `GetGoogleIdOption(serverClientId = WEB client id)` → `GoogleIdTokenCredential`. Then, only when the user first backs up, **authorize** the Drive scope with `AuthorizationClient` (`com.google.android.gms:play-services-auth:21.6.0`) requesting **only** `Scopes.DRIVE_APPDATA`.
- **Drive REST v3 appDataFolder:** create with `parents:["appDataFolder"]`; list with `spaces=appDataFolder` (omitting this = you see nothing); download `files.get?alt=media`; update existing by fileId. **Delete is permanent** (appdata can't be trashed). It **counts against the user's Drive quota** and the user can delete it → treat backup as best-effort. Prefer a single overwritten file over accumulation; for 60-day retention keep small rolling snapshots and prune.
- Legacy `GoogleSignIn` is deprecated (not yet removed) — do not build on it. The `play-services-auth` **artifact** stays (it hosts `AuthorizationClient`). Android 16 broke old Credential Manager versions → stay on credentials 1.5.0+.

## UI (Phases 1–2, 15)
- Material You: `dynamicLightColorScheme(context)` / `dynamicDarkColorScheme(context)` gated on `Build.VERSION.SDK_INT >= S` (API 31), with a hand-authored seed palette fallback. Don't cache the scheme across config changes. Gate behind a user setting if brand consistency matters. Pair with `enableEdgeToEdge()`.
