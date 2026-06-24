# Build plan & status

Phases follow PRD §11 (ship in reviewable phases, pause after each). Each phase ends with a summary
and an explicit ask before any cloud build.

| # | Phase | Status |
|---|---|---|
| 1 | Scaffold + onboarding + CI; manual add (income+expense) + timeline + summary header + Trash | ✅ done (this build) |
| 2 | Analytics: My Cycle (composite) / Salary Cycle / Calendar Month / Single Card + charts + stats | ⬜ next |
| 3 | SMS capture: permissions + receiver + backfill + confidence-scored parser + confidence gate + heads-up → quick-add + payment-method discovery + ask-once classification + merchant learning | ⬜ |
| 4 | Notification capture: listener + allowlist + grant onboarding + cross-source dedupe | ⬜ |
| 5 | Splitting: equal/percentage/custom + largest-remainder + validation | ⬜ (math already in `domain/allocation`) |
| 6 | Recurring: rules CRUD + app-open catch-up + daily WorkManager job | ⬜ (WorkManager factory wired) |
| 7 | Backup: Drive sign-in/authorization + compressed snapshots + 60-day retention + restore | ⬜ |
| 8 | Import/Export: Monito / any-Excel / CSV import (category-preserving + confirm mapping) + CSV/XLSX export | ⬜ |
| 9 | Receipt split: ML Kit OCR + tier-1 keyword/learning (+ Nano / BYOK Gemini) + reconcile | ⬜ |
| 10 | Security & settings: app lock (BiometricPrompt) + hide-in-recents + daily reminder + carry-forward + default landing | ⬜ (settings partly done) |
| 11 | Polish & publishability: animations, accessibility, dark-mode, payment-method management, notification-only flavor, privacy policy | ⬜ |
| 12 | Hardening & QA: OEM background-kill mitigations + capture health-check + E2E + cloud device matrix + seeded UAT + Play pre-launch | ⬜ |

## Phase 1 — assumptions & decisions
- **Versions** pinned to the author's proven Pause toolchain + Room 2.6.1 / WorkManager 2.10.0,
  verified against current releases (see `docs/PLATFORM_NOTES.md`). `compileSdk`/`targetSdk` 35,
  `minSdk` 26 (so java.time needs no desugaring; adaptive icons everywhere).
- **Schema v1** holds Category / Expense / Allocation. PaymentMethod + capture/learning tables
  arrive via migrations in Phases 3–4 (Room schemas are exported for migration tests).
- **Period in Phase 1** = the salary cycle window (single anchor). The composite **My Cycle**
  (per-instrument union) lands in Phase 2 once cards/payment methods exist; `CycleUtils` already
  computes the per-anchor windows it will union.
- **Type-safe routes** use compile-time-checked string builders; full kotlinx-serialization routes
  land with the serialization dependency in the backup phase.
- **WorkManager** factory + `Configuration.Provider` are wired now (no workers yet) so Phase 6/7
  workers drop in cleanly; the default initializer is removed in the manifest.
- **No USE_FULL_SCREEN_INTENT** (Play-revoked for non-call/alarm apps); captures will use a
  high-importance heads-up notification — see `docs/PLATFORM_NOTES.md`.
- **Deferred to their phases:** charts (Vico 2.5.2 or custom Canvas), OCR (ML Kit 16.0.1),
  XLSX (dhatim fastexcel 0.20.2), Drive (Credential Manager + AuthorizationClient), biometrics.
