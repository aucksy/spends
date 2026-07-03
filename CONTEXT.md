# Spends — Context Entrypoint

Stable orientation doc for any new chat/session. Read this first, then `PROGRESS.md`
for live state. This file changes rarely; `PROGRESS.md` changes every release.

## What Spends is
Offline-first native **Android expense tracker**. Kotlin + Jetpack Compose + Material 3
+ Hilt + Room + DataStore + WorkManager. Money is **integer paise (`Long`)** everywhere;
`kind` (income / expense / transfer) drives all math. Signature feature = near-frictionless
capture from **bank SMS + bank/UPI notifications** (review-only — captures land in a
`pending_captures` queue and NEVER touch balance/analytics until the user confirms).

- **Codebase root:** `D:\Apps\Spends\Spends CodeBase`
- **Repo:** github.com/aucksy/spends (public, lowercase)
- **applicationId:** `com.spends.app` · **namespace:** `com.spends.app`
- **Source of truth:** `D:\Apps\Spends\PRD\` (build prompt + parser fixtures) and the
  design export in `D:\Apps\Spends\Design System\project\*.dc.html`.

## How we build & ship (non-negotiable)
- **Cloud-only builds.** The local Android toolchain (SDK/JDK/Gradle) is DELETED — the
  ~5.9 GB-RAM machine crashes on builds. Never build locally, never re-download the
  toolchain without explicit approval.
- **Tag-driven CI.** Push to `main`, then push a `v*` tag → GitHub Actions runs unit
  tests, builds the **signed APK + AAB**, and cuts a GitHub Release. Debug APK builds on
  push to main; full CI on PRs.
- **No `gh` CLI** on this machine. Poll CI with `curl` against the GitHub REST API
  (`https://api.github.com/repos/aucksy/spends/actions/runs?per_page=6`), filter
  `name == "Android Release"` + the tag branch.
- **Release signing:** keystore + secrets already configured in the repo (key alias
  `spends`). Unset secrets fall back to an unsigned/debug build.
- **Version:** `app/build.gradle.kts` — `versionCode` (integer, THE install-ordering
  field, always bump) + `versionName` (cosmetic string). Currently on a `0.x` line;
  a `1.0.0` stamp is a pending product decision, not a technical gate.
- After every release, **paste the direct APK URL** in chat:
  `https://github.com/aucksy/spends/releases/download/<tag>/Spends-<tag>.apk`

## Release ritual (every single tag)
1. Build the change; keep UI look/feel, symmetry, alignment intact.
2. Run **2 parallel adversarial review agents** before tagging — one
   compile/Hilt/Room/migration, one logic/regression/data-safety. **Explicitly tell them
   to scan `app/src/test`** (a stale test assert broke a prior tag once).
3. Fix all real findings. Only then bump versionCode+versionName, commit, tag, push.
4. Poll CI green, post the direct APK link, then give me a **manual-test checklist** of
   things you couldn't verify yourself (money + DB correctness first).

## Working agreement (standing user preferences)
- **Pause and ask between rounds.** Don't chain multiple feature rounds without a check-in.
- **NEVER auto-build.** Only tag a release when I explicitly say to ship.
- **Test like an expert:** edge cases, negative cases, regression. This is real money in a
  Room DB — database mistakes are unacceptable.
- **Ask when genuinely in doubt** (don't emit vague formulaic answers). Always use the
  **interactive clickable question UI** (AskUserQuestion), never numbered questions in text.
- **Design fidelity:** build UI from the `.dc.html` design export, don't improvise; audit
  against `docs/DESIGN_FIDELITY.md` before shipping.
- **Git identity:** commit as `simpleapps108@gmail.com` (the dev account), NOT the harness
  login. Node/npm aren't on the default PATH (prepend `%LOCALAPPDATA%\nodejs` if needed).

## Room / data-safety rules (learned the hard way)
- Migration DDL must EXACTLY match Room's generated schema. Nullable `Long?`/`Int?` →
  `INTEGER` (no NOT NULL, no default). Defaulted Int → `@ColumnInfo(defaultValue="0")`.
  Room validates columns by NAME, not order.
- Autoincrement PKs need `@PrimaryKey(autoGenerate = true)` — the no-autoGenerate form
  silently fails all inserts after the first (this bit us once on `pending_captures`).
- Adding a defaulted field to a backup Snapshot DTO is backward-compatible; ids are
  preserved on restore so cross-entity references stay valid.
- WorkManager: use `CANCEL_AND_REENQUEUE` for explicit time changes (`UPDATE` keeps a
  running periodic job on its OLD anchor — a time change silently never takes effect);
  `KEEP` at launch. Periodic work is Doze-approximate.

## Key architecture landmarks
- **Periods:** `core/period/` — `PeriodResolver`, `SmartCycleDetector`,
  `PeriodSelection`(+`describe()`), shared `PeriodSelectionStore` (@Singleton flow synced
  across Transactions + Analytics). Smart Cycle is a **composite** of per-instrument
  windows (each card by its billingDay, Bank/UPI by salaryDay) via `CompositeCycleResolver`.
- **Capture:** `data/capture/` — `SmsParser` (pure, rules-based; 30 golden fixtures are
  unit tests and a hard release gate), `SenderAllowlist`, `SmsCaptureRepository`
  (dedupe by hash + Mutex-serialized), `pending_captures` queue, review screen in `ui/review/`.
- **Backup:** encrypted `.spsenc` (AES-256-GCM; DEK wrapped by both device Keystore key
  AND a PBKDF2 recovery-password KEK → same-device + new-phone restore). Drive (`drive.file`
  scope, visible "Spends Backup" folder) + local SAF file + daily auto-backup worker.
- **Money math:** integer paise; carry-forward = opening + balanceBefore(periodStart) −
  balanceBefore(anchor), null when the period predates the anchor.

## The full detailed history lives in Claude's project memory
`spends-app.md` (in the memory dir) holds the complete per-version changelog and every
gotcha. This CONTEXT.md is the short, stable orientation; `PROGRESS.md` is the live pointer.
