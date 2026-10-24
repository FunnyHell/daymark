# Daymark — Development Status

**Last updated:** 2026-10-24
**Current milestone:** M1 — Core local MVP (0.1.0 debug)
**Overall state:** Implemented; verified by automated tests and CI build; **not verified on a physical device**

## 1. Current milestone

### M1 — Core local MVP

Goal: an installable debug APK with persistent habits, medications, inventory, reminders, widgets, history/statistics and export/import.

Exit criteria and state:
- Debug APK builds in CI — **done** (see §4).
- Unit and database tests pass — **done** (84 tests).
- Instrumented tests and emulator smoke test — see §4 for the latest result.
- Physical Samsung Galaxy A52 verification — **not started** (requires the user's device).

## 2. Verified environment

- Repository: single module `:app`, Gradle wrapper 8.14.3, AGP 8.10.1, Kotlin 2.1.21.
- Cloud development sandbox: JDK 21 present, but no Android SDK and no access to `dl.google.com` / Google Maven, so Android builds cannot run there (Maven Central is reachable but rate-limited).
- Reference build environment: GitHub Actions `ubuntu-latest` (JDK 17 Temurin, preinstalled Android SDK platform 35, build-tools 35/36). See ADR-012.
- Emulator: GitHub Actions, API 34 `google_apis` x86_64 with KVM.
- Physical Samsung device tested: **No**.
- Wear OS module: not implemented. Synchronization: not implemented.

## 3. Feature status

| Area | Status | Notes |
|---|---|---|
| Project setup | Verified by automated tests | CI builds, tests, inspects APK |
| Local database | Verified by automated tests | Room v1, schema exported; reopen test (persistence across DB close) |
| Habit CRUD | Verified by automated tests | Create/edit/pause/resume/archive/delete in repository tests; UI flow in instrumented test |
| Habit recurrence | Verified by automated tests | Daily, weekdays, every N days, schedule versions |
| Weekly goals | Verified by automated tests | Week start setting, partial/paused weeks neutral |
| Abstinence habits | Verified by automated tests | Lapses, clean runs, corrections, pauses |
| Quantitative habits | Verified by automated tests | Sums, targets, invalid input rejected |
| Medication records | Verified by automated tests | |
| Medication schedules | Verified by automated tests | Multiple doses, weekday schedules, edit effective today/tomorrow |
| Intake event processing | Verified by automated tests | Duplicate and concurrent actions record once; stale/future rejected |
| Inventory tracking | Verified by automated tests | Ledger, packages, counts, insufficient stock, reversal once, low-stock crossing |
| Notifications | Implemented, not verified on device | Action broadcast idempotency covered by an instrumented test |
| Reboot/time changes | Implemented, not verified | Receivers rebuild the single alarm; planner catch-up unit-tested |
| Habit widget | Implemented, not verified | Glance; uses `completeIfIncomplete` (tested in repository tests) |
| Medication widget | Implemented, not verified | Glance; uses `recordDose` |
| Statistics and calendar | Verified by automated tests (rules) | UI heatmaps/charts not visually verified on device |
| Export/import | Verified by automated tests | Round trip, invalid files, rollback on failure |
| Accessibility/theme | Implemented, not verified | Light/dark/dynamic colour, content descriptions, text labels for states |
| Localization | Implemented | English (default) and Russian |
| Samsung phone verification | Not started | |
| Tablet adaptation | Not started | Later milestone |
| Wear OS companion | Not started | Future milestone |
| Device synchronization | Not started | Future milestone |

## 4. Build and test record

| Check | Result | Notes |
|---|---|---|
| Repository inspection | Done | Only docs existed before this milestone |
| `./gradlew testDebugUnitTest` (CI run 6, commit fe9118b) | **Passed: 84 tests, 0 failures** | HabitRules 22, MedicationRules 21, ReminderPlanner 6, Statistics 5, MedicationRepository 16, HabitRepository 10, BackupRepository 4 (Robolectric SDK 34 for DB tests) |
| `./gradlew assembleDebug` (CI run 6) | **Passed** | `app/build/outputs/apk/debug/app-debug.apk`, 14,479,867 bytes, SHA-256 `9502456d67b6955e004c5ce662bc36e9d0c614325b078d131d3f60b93270df64` |
| APK badging (aapt2) | Passed | package `io.github.funnyhell.daymark.debug`, versionCode 1, versionName `0.1.0-debug`, targetSdk 35, compileSdk 35, launchable `MainActivity` |
| Instrumented tests (emulator API 34) | See latest CI run | Run 6 could not boot the emulator (runner out of disk); fixed in commit 788bdc6 |
| Emulator UI smoke test | See latest CI run | `.github/scripts/ui_smoke.py`, screenshots on branch `ci-screens` |
| Physical-device smoke test | Not run | |

## 5. Known issues and blockers

- **No local Android build in the cloud sandbox.** Impact: builds only through CI. Evidence: `dl.google.com` and `maven.google.com` (redirects to `dl.google.com`) are denied by the sandbox proxy. Next action: allow these hosts in the environment, or keep using CI.
- **Not verified on a physical Samsung device**: notification delivery/actions, exact alarm timing, reboot behaviour, battery restrictions, widget interactions and refresh.
- Snooze length (30 min) and missed window (3 h) are fixed constants.
- Backups are not encrypted.

## 6. Recent completed work

- 2026-10-24: Implemented the full 0.1.0 MVP (domain rules, Room data layer, repositories, Compose UI, reminders, notifications, Glance widgets, statistics, export/import, EN/RU strings), CI pipeline with APK publishing. Decisions ADR-011 … ADR-022.

## 7. Next tasks

1. Install the APK on the Galaxy A52 and run the device checklist in README ("Next steps"): notification actions from the lock screen and shade, exact-alarm permission, reboot, widgets, battery settings.
2. Record device results here; fix issues found.
3. Add a migration test harness before the first schema change (schema v1 is exported).
4. Consider configurable snooze length and optional encrypted export.

## 8. Update rules

At the end of each meaningful task:
- Update the current milestone.
- Record actual changes.
- Record tests and build commands with results.
- Distinguish implementation from verification.
- Add known issues and blockers.
- Set the next concrete task.

Do not invent a percentage-complete estimate. Prefer feature-level status and objective exit criteria.
