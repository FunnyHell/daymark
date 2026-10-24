# Daymark — Development Status

**Last updated:** 2026-10-24
**Current milestone:** M1 — Core local MVP (0.1.0 debug)
**Overall state:** Implemented; verified by automated tests, CI build and an emulator smoke test; **not verified on a physical device**

## 1. Current milestone

### M1 — Core local MVP

Goal: an installable debug APK with persistent habits, medications, inventory, reminders, widgets, history/statistics and export/import.

Exit criteria and state:
- Debug APK builds in CI — **done** (see §4).
- Unit and database tests pass — **done** (84 tests).
- Instrumented tests and emulator smoke test — **done** on an API 34 emulator (see §4).
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
| `./gradlew assembleDebug` (final, CI run 40108071847, commit bdfc06d) | **Passed** | `app/build/outputs/apk/debug/app-debug.apk`, 14,479,867 bytes, SHA-256 `4527589861e8c755574f06f94c592589a1a11bb15f6f0bd757039c2e4a5fb74c`; published on branch `ci-apk` |
| APK badging (aapt2) | Passed | package `io.github.funnyhell.daymark.debug`, versionCode 1, versionName `0.1.0-debug`, minSdk 26, targetSdk 35; permissions POST_NOTIFICATIONS, SCHEDULE_EXACT_ALARM, RECEIVE_BOOT_COMPLETED, plus WAKE_LOCK / ACCESS_NETWORK_STATE / FOREGROUND_SERVICE added by WorkManager; no INTERNET |
| Instrumented tests (emulator API 34, Pixel 6 profile) | **Passed: 2/2** | `NotificationActionInstrumentedTest` (duplicate TAKEN broadcast to the real receiver → one event, stock 10→9; late snooze ignored) and `HabitFlowInstrumentedTest` (onboarding → create habit → Done on Today → completion persisted) |
| Emulator UI smoke test | **Passed** | `.github/scripts/ui_smoke.py`: onboarding, two habits, one medication, Taken (with Undo snackbar), Done, History, Settings; no crash in logcat. Screenshots on branch `ci-screens` |
| Physical-device smoke test | Not run | |

## 5. Known issues and blockers

- **No local Android build in the cloud sandbox.** Impact: builds only through CI. Evidence: `dl.google.com` and `maven.google.com` (redirects to `dl.google.com`) are denied by the sandbox proxy. Next action: allow these hosts in the environment, or keep using CI.
- **Not verified on a physical Samsung device**: notification delivery/actions, exact alarm timing, reboot behaviour, battery restrictions, widget interactions and refresh.
- Snooze length (30 min) and missed window (3 h) are fixed constants.
- A medication created during the day shows its earlier doses of that day (e.g. 08:00 when created at 15:00) as "Missed" until recorded; this follows the documented rule but may surprise users.
- In the emulator, `uiautomator dump` did not list the Extended FAB ("New habit") on the Habits screen although it is drawn and works (Compose tests can click it). TalkBack reachability of the FAB must be checked on the device; the same action is also available from the empty state and the Today screen.
- Backups are not encrypted.
- Each CI run signs with that runner's own debug key, so installing a newer CI build over an older one may fail with a signature conflict; export a backup, uninstall, reinstall, import.

## 6. Recent completed work

- 2026-10-24 (feedback round 1): fixed blank screen after deleting (double back navigation), permission prompt that opened system settings when permission was already granted, silent notifications (new v2 channels with sound/vibration), long-lived snackbars (short, closable, swipeable). Added package-size presets, clearer habit types (radio list with examples; "Quit a habit" with "Slip"), swipe/long-press list actions, "Take all" for doses at the same time, compact widget check controls, medications widget grouped by time with "mark all", new Progress / Calendar / Streak widgets, per-widget settings and picker previews. CI run 40113713487 (commit ddfa7e7): unit tests passed, debug APK built (SHA-256 `501fa40e828077226bbd7ded26980f20771abc456337f8e9bb950de8837b6ccf`), emulator instrumented tests 2/2 and smoke test passed. Widgets, sound and swipe gestures are **not verified on a device**; the emulator smoke test does not place widgets.

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
