# Daymark — Development Status

**Last updated:** 2026-10-24
**Current milestone:** M1 — Core local MVP (0.1.0 debug)
**Overall state:** Implemented (feedback round 2 applied); verified by automated tests, CI build and an emulator smoke test; **not verified on a physical device**

## 1. Current milestone

### M1 — Core local MVP

Goal: an installable debug APK with persistent habits, medications, inventory, reminders, widgets, history/statistics and export/import.

Exit criteria and state:
- Debug APK builds in CI — **done** (see §4).
- Unit and database tests pass — **done** (see §4).
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
| Habit widget | Implemented, not verified | Glance; mark/unmark via `setDone` (tested in repository tests) |
| Medication widget | Implemented, not verified | Glance; `recordDose` / `undoTaken` (tested in repository tests) |
| Progress / Calendar / Streak widgets | Implemented, not verified | Period progress and per-day fractions unit-tested; colors, opacity and sizing not verified on a device |
| Multi-select in lists | Implemented, not verified | Bulk pause/resume/archive/delete; no automated UI test |
| Statistics and calendar | Verified by automated tests (rules) | UI heatmaps/charts not visually verified on device |
| Export/import | Verified by automated tests | Round trip, invalid files, rollback on failure |
| Accessibility/theme | Implemented, not verified | Light/dark/dynamic color, content descriptions, text labels for states |
| Localization | Implemented | English (default) and Russian |
| Samsung phone verification | Not started | |
| Tablet adaptation | Not started | Later milestone |
| Wear OS companion | Not started | Future milestone |
| Device synchronization | Implemented, not verified on devices | Local Wi-Fi; pairing once, then Sync or automatic when open on both; merge/protocol/two-database tests pass |

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
- **Not verified on a physical Samsung device**: notification delivery/actions, exact alarm timing, reboot behavior, battery restrictions, widget interactions and refresh.
- Snooze length (30 min) and missed window (3 h) are fixed constants.
- A medication created during the day shows its earlier doses of that day (e.g. 08:00 when created at 15:00) as "Missed" until recorded; this follows the documented rule but may surprise users.
- In the emulator, `uiautomator dump` did not list the Extended FAB ("New habit") on the Habits screen although it is drawn and works (Compose tests can click it). TalkBack reachability of the FAB must be checked on the device; the same action is also available from the empty state and the Today screen.
- Backups are not encrypted.
- CI signs with a persistent key (Actions cache, or the `DEBUG_KEYSTORE_BASE64` secret if set); builds before run 36 used throwaway keys and need one reinstall. The cached key expires after 7 days without builds (see README, "Updating").

## 6. Recent completed work

- 2026-11-09 (round 5): snoozed reminders come back when the snooze ends (bug: planner used the original dose time); same-minute reminders grouped into one notification with Taken all / Snooze all (Done all for habits); widgets redrawn in parallel before reminders are rescheduled; Settings split into a short main screen plus Reminders and Backups; Today/History decluttered; automatic daily backups into a chosen folder (last 7 kept), restore/import with merge or replace (ADR-034); paired sync without a code and automatic sync when Daymark is open on both devices (protocol v2, ADR-033). A crash found on the emulator (NSD callback delivered to a shut-down executor when the app went to the background) was fixed before delivery. CI run 42656161332 (commit 110d656): unit/DB tests passed (new: snooze-end planner test, protocol v2 pairing/key/unknown-device tests), debug APK built (SHA-256 `6c50eda76d9851b9840dfba54104b22d5a49c4503a27c7d8680e831ee4d8c816`), emulator: instrumented tests 4/4 (new: group notification shrinks and disappears), smoke test passed (settings sub-screens, test reminder with the screen off, sync code). **Not verified on devices**: snooze and grouped notifications on the Samsung phone, widget speed, automatic backups into a real folder, paired/automatic sync between two devices.

- 2026-10-25 (round 4): widgets are drawn directly with `GlanceAppWidget.compose` instead of waiting for a Glance session (ADR-031; fixes widgets stuck on "Loading" on the test phone — the cause is inferred, not reproduced); live preview in widget settings; device sync over the local Wi-Fi (ADR-030): schema v2 with `deleted_records` and `MIGRATION_1_2`, order-independent merge, AES-GCM exchange keyed by a six-digit code, DNS-SD discovery, Settings → Sync devices. CI run 40180766669 (commit 264325d): **106 unit/DB tests passed** (new: SyncMergerTest 7, SyncProtocolTest 3 over loopback sockets, SyncRepositoryTest 3 with two databases, MigrationTest 1 — its v1 SQL matches Room's generated statements), debug APK built (SHA-256 `f1145a51f61b2064cf6629b4afb07d7c396c2c5e7e99151a9728a6b63ed2ffa8`), emulator: instrumented tests 3/3 (including all six widgets composed and inflated at two sizes), smoke test passed including opening the sync screen and showing a code. **Not verified**: sync between two real devices (discovery, Wi-Fi, firewall/AP isolation), widgets on the Samsung launcher.

- 2026-10-25 (feedback round 3): onboarding reduced to one screen and asks for notification permission; reminders scheduled with `setAlarmClock` (ADR-028), battery-restriction row and banner, test reminder in Settings; Today ring split into its own widget and the progress widget switches between a row and a stacked layout (ADR-029); explanatory notes removed, strings shortened, US spelling. CI run for commit 8697b34: 92 unit/DB tests passed, debug APK built (SHA-256 `db6bd38b056e9e015f3a601ff075062727e49a64d9970ce2f2d604fe3d9b8abe`), emulator instrumented tests 2/2 passed, smoke test passed including the notification-permission dialog and a test reminder posted about a minute later **with the emulator screen off**. Screen-off sound on the Samsung device and the widget layouts are **not verified on a device**. (The first run of this round, commit 41a7fcc, failed the smoke test only because the script still looked for the old "Medication name" label.)

- 2026-10-24 (feedback round 2): `USE_EXACT_ALARM` so reminders are not deferred with the screen off (ADR-025); multi-select with bulk actions in habit and medication lists; press ripple clipped to the rounded card; tap-to-toggle marks on Today (habit ring, dose circle) and in widgets with an explicit target state (ADR-026); widget settings observed (Streak widget no longer stuck on "choose a habit"); new week/month progress rule, rings sized to the widget, calendar with a sector ring per day, per-widget accent color and background opacity (ADR-027). New tests: `setDone`/`undoCompletion`, `undoTaken`, `periodProgress` (the reported 2-habit case → 50%), `dayFractions` for past days. CI run 40118132792 (commit a585df1) failed to compile (when-branch parse error); fixed in b083453. CI run 40120588398 (commit b083453): 92 unit/DB tests passed, debug APK built (SHA-256 `aa1a2bedac45e64bd2bf0e5e54853b340193b2b95a648a503fefa7c2cd07b660`, 14,645,178 bytes), emulator instrumented tests 2/2 passed, smoke test passed including marking a dose and a habit by tapping the circle/ring and unmarking the habit. Widgets, colors/opacity, multi-select and screen-off reminders are **not verified on a device**.

- 2026-10-24 (feedback round 1): fixed blank screen after deleting (double back navigation), permission prompt that opened system settings when permission was already granted, silent notifications (new v2 channels with sound/vibration), long-lived snackbars (short, closable, swipeable). Added package-size presets, clearer habit types (radio list with examples; "Quit a habit" with "Slip"), swipe/long-press list actions, "Take all" for doses at the same time, compact widget check controls, medications widget grouped by time with "mark all", new Progress / Calendar / Streak widgets, per-widget settings and picker previews. CI run 40113713487 (commit ddfa7e7): unit tests passed, debug APK built (SHA-256 `501fa40e828077226bbd7ded26980f20771abc456337f8e9bb950de8837b6ccf`), emulator instrumented tests 2/2 and smoke test passed. Widgets, sound and swipe gestures are **not verified on a device**; the emulator smoke test does not place widgets.

- 2026-10-24: Implemented the full 0.1.0 MVP (domain rules, Room data layer, repositories, Compose UI, reminders, notifications, Glance widgets, statistics, export/import, EN/RU strings), CI pipeline with APK publishing. Decisions ADR-011 … ADR-022.

## 7. Next tasks

1. Install the APK on the Galaxy A52 and run the device checklist in README ("Next steps"): notification actions from the lock screen and shade, exact-alarm permission, reboot, widgets, battery settings.
2. Record device results here; fix issues found.
3. Verify sync between the phone and a tablet on the same Wi-Fi (discovery, wrong code, a dose taken on both).
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
