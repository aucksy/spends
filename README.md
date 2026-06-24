# Spends

An offline-first, privacy-first **Android** expense tracker (Kotlin + Jetpack Compose, Material 3).
The signature feature (later phases) is near-frictionless expense capture from bank **SMS** and
bank/UPI **app notifications**. Money is stored as integer paise; everything stays on-device.

> Built in reviewable phases. **This is Phase 1** — the foundation.

## What's in Phase 1
- **Scaffold & theme:** Compose + Material 3, light/dark + Material You dynamic color (with a brand
  fallback), edge-to-edge, splash, type-safe-ish navigation.
- **Data model:** Room (categories, expenses, allocations) with integer-paise amounts, soft-delete,
  SQL aggregations, seeded prebuilt categories with **auto-assigned icons + distinct colors** (no
  pickers). `kind` (income / expense / transfer) drives all money math.
- **Onboarding shell:** start-fresh / import / restore choice (import & restore wired in later
  phases) + salary-day capture + the plain-language cycle explanation.
- **Transactions tab:** cycle-aware period (salary cycle) with a pinned, scrollable **summary
  header** (Expense / Income / Balance, + Carry Forward when enabled) with animated counters;
  day-grouped timeline with net subtotals; search; swipe-to-delete with undo.
- **Add / edit:** manual income & expense with category selection (and inline custom categories) +
  date picker.
- **Trash:** soft-delete recovery + restore + delete-forever; auto-purge on launch.
- **Settings:** theme, Material You toggle, salary day, default landing, carry-forward, Trash.
- **CI:** GitHub Actions — unit tests + lint on PRs, debug APK on `main`, signed release on tags.

Pure correctness-critical logic (money formatting/parsing, salary/card cycle windows, icon/color
assignment, largest-remainder split) is covered by JUnit tests under `app/src/test`.

## Build (cloud only)
This project builds in **GitHub Actions**, not locally.
- Push to `main` → **Android Debug APK** workflow → installable debug APK artifact.
- Push a tag `vX.Y.Z` → **Android Release** workflow → signed APK + AAB attached to a GitHub Release
  (falls back to a labelled debug APK if signing secrets are absent).

Signing secrets (repo → Settings → Secrets → Actions): `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`
(key alias must be `spends`). See `.github/workflows/android-release.yml`.

## Architecture
Feature-first, layered (`core/`, `data/`, `domain/`, `ui/`, `di/`), MVVM with Hilt + Room +
DataStore + Coroutines/Flow. See `docs/PHASE_PLAN.md` for the full roadmap and
`docs/PLATFORM_NOTES.md` for pinned versions and platform rules.

## Privacy
No account, no ads, no analytics, no telemetry on financial content. SMS/notification parsing
(later) happens entirely on-device. Never commit raw SMS exports, account numbers, or personal
Excel exports — see `.gitignore`.
