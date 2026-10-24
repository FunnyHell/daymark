# Daymark

Daymark is a local-first Android application for habit tracking and medication management.

It combines flexible habit tracking, medication reminders with actionable notifications, intake history, inventory tracking, statistics and home-screen widgets. It works without an account, a subscription, advertisements, or any network connection.

> **Project status:** 0.1.0 MVP, debug build. Automated tests pass in CI; behavior on a physical Samsung phone has **not** been verified yet. See [`docs/DEVELOPMENT_STATUS.md`](docs/DEVELOPMENT_STATUS.md).

Daymark records what you enter and reminds you at the times you choose. It does not give medical advice and is not a substitute for a doctor or pharmacist.

## Features (0.1.0)

**Habits**
- Types: daily check, several times a day, amount with a unit (e.g. 2000 ml), weekly goal (N times per week), abstinence with lapse records.
- Schedules: every day, selected weekdays, every N days. Optional daily reminder time.
- Create, edit, pause, resume, archive, restore, delete (with confirmation). In lists: swipe right = pause/resume, swipe left = archive, long press = select several (bulk pause, resume, archive, delete).
- On Today, tap the ring on the left to mark a habit done and tap again to unmark it (several-times-a-day habits: +1 per tap).
- Record completions, amounts, skips and lapses for today or for past days; correct any entry (corrections are kept in history).
- Schedule changes create a new schedule version from today; past days keep their original rules.

**Medications**
- Name, strength/form, notes, color; one or more dose times per day on chosen weekdays; a free-text dose description and a "uses from stock" amount per dose.
- Tap the circle on the left of a dose to mark it taken, tap again to unmark it (stock is restored). Skip / Snooze 30 min from the app and the notification; Taken also from the notification and the widget (where it can be unmarked too); "Take all" for doses due at the same time. Every action is validated and idempotent: repeated taps or duplicated notification actions record once.
- Missed doses are derived (nothing recorded within 3 hours), never invented; late doses can still be recorded.
- Pause/resume a schedule without false missed doses; corrections with exact inventory reconciliation.

**Inventory**
- Optional per medication, user-defined unit, initial stock, add packages (previous package sizes offered as one-tap choices), set a counted stock, low-stock threshold and notification, full change log.

**Reminders and widgets**
- Exact alarms (`USE_EXACT_ALARM` on Android 13+, the "Alarms & reminders" permission on Android 12; falls back to inexact), rebuilt after reboot, app update, time and time-zone changes, plus a 6-hourly safety net. Reminders play the default notification sound and vibrate (change it in Settings → Reminders → Sound and vibration).
- Lock-screen privacy: medication and habit names hidden on the lock screen by default.
- Five home-screen widgets (Jetpack Glance), each with its own settings including accent color (system or fixed) and background opacity (long-press the widget → Settings on Android 12+):
  - **Habits** — today's habits with a compact tick or radio button that marks and unmarks;
  - **Medications** — today's doses grouped by time, tap to mark/unmark, "mark all" for a time group;
  - **Progress** — rings for today (one sector per habit, done/due count), this week and this month (average progress per habit, unfinished weekly goals included); rings scale with the widget;
  - **Calendar** — the month with a ring around every day: one sector per habit due that day, filled by its progress (all habits or one);
  - **Streak** — one habit's current streak in large type, best streak and the last 7 days.

**History and statistics**
- Per-habit calendar heatmap with day details, current/best streaks, 30-day completion rate, weekly results, clean-streak history for abstinence habits.
- Overall activity heatmap, this/last week and month summaries, per-habit table, 14-day charts for amount habits.
- Medication adherence counts (taken/skipped/missed) for 7 and 30 days and a full event log.

**Data**
- Export everything to a JSON file; import validates the whole file first and replaces data in one transaction only after confirmation.
- English and Russian UI.

## Install the debug APK on a phone (Samsung Galaxy A52)

The debug APK is built by GitHub Actions on every push and published to the branch **`ci-apk`** of this repository (file `app-debug.apk`, with `SHA256SUMS.txt`, `BADGING.txt` and `BUILD_INFO.txt`). It is also attached to each workflow run as the artifact `daymark-debug-apk`.

1. Download `app-debug.apk` (GitHub → branch `ci-apk` → `app-debug.apk` → Download raw file, or the workflow artifact).
2. Copy it to the phone (USB, Quick Share, cloud drive) or download it on the phone directly.
3. Open it with **My Files**. When asked, allow "Install unknown apps" for that app (Settings → Apps → Special access → Install unknown apps).
4. With USB debugging enabled you can instead run `adb install -r app-debug.apk`.

Package ID: `io.github.funnyhell.daymark.debug` · version `0.1.0-debug` (versionCode 1) · minSdk 26 · targetSdk 35.

Debug builds are signed with the CI runner's temporary debug key, so a later CI build usually cannot be installed over an earlier one ("App not installed" / signature conflict). Export a backup, uninstall, install the new APK, then import the backup.

### Recommended phone settings for reliable reminders

- Allow notifications when Daymark asks (or Settings → Notifications in the app).
- Allow **Alarms & reminders** (exact alarms) from Daymark's settings; without it Android may delay reminders.
- Samsung: Settings → Apps → Daymark → Battery → **Unrestricted**, and make sure Daymark is not in "Sleeping apps" / "Deep sleeping apps".

## Build from source

Prerequisites: JDK 17 or newer, Android SDK with platform 35 (Android Studio Ladybug or newer, or command-line tools), network access to Google Maven and Maven Central.

```bash
./gradlew testDebugUnitTest      # JVM unit tests + Robolectric database tests
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew connectedDebugAndroidTest   # instrumented tests, needs a device or emulator
```

On Windows use `gradlew.bat`. Versions: Gradle 8.14.3, AGP 8.10.1, Kotlin 2.1.21, Compose BOM 2025.05.00, Room 2.7.1 (schema exported to `app/schemas`).

CI (`.github/workflows/android.yml`) runs the unit tests, builds and inspects the APK, then boots an API 34 emulator to run the instrumented tests and an adb-driven UI smoke test (`.github/scripts/ui_smoke.py`), publishing screenshots to the `ci-screens` branch.

## Known limitations

- Not yet verified on a physical Samsung device: notification delivery and actions, exact alarm timing, behavior after reboot or with battery optimisation, widgets.
- Reminder times are local wall-clock times; after travelling to another time zone a 08:00 dose is reminded at 08:00 local time.
- Snooze is fixed at 30 minutes; the missed window is fixed at 3 hours.
- Widget buttons record only simple actions (habit done / +1 and unmark, dose taken and unmark, all doses of a time taken). Amounts, slips, skips and snoozes are recorded in the app or the notification.
- Backups are plain JSON (not encrypted). Import replaces all data; there is no merge.
- No synchronization between devices and no Wear OS app yet.

## Next steps: what to test on the Galaxy A52

1. Install, open, finish onboarding, create one habit with a reminder a few minutes ahead and one medication with a dose a few minutes ahead and stock tracking on.
2. Allow notifications; in Settings → Daymark → Reminders, open "Alarms & reminders" and allow it.
3. Lock the phone and wait: does the medication reminder arrive on time, with names hidden on the lock screen?
4. Press **Taken** in the notification; then open the app: one "Taken" record, stock reduced by exactly one. Repeat with **Skip** and **Snooze 30 min** on other doses (snooze must bring the reminder back ~30 min later).
5. Add the widgets to the home screen; tap a tick on the widget, then tap it again to unmark; check the app shows the same state and the widget updates after changes made in the app. Change a widget's color and opacity in its settings.
6. Reboot the phone with a dose scheduled after the reboot; check the reminder still arrives.
7. Set Daymark's battery usage to "Unrestricted" if reminders are late; note whether it was needed.
8. Export a backup, delete something, import the backup, confirm everything is back.

Please report the results (or anything odd) so they can be recorded in `docs/DEVELOPMENT_STATUS.md`.

## Documentation

| File | Purpose |
|---|---|
| `CLAUDE.md` | Development rules |
| `docs/SPEC.md` | Product requirements and acceptance criteria |
| `docs/ARCHITECTURE.md` | Architecture, including the as-built structure |
| `docs/DEVELOPMENT_STATUS.md` | Current progress and verification state |
| `docs/DECISIONS.md` | Technical decisions (ADR-001 … ADR-027) |

## Data and privacy

All data is stored locally in a Room database on the device. Nothing is sent anywhere; the app requests no internet permission. Android cloud backup of app data is disabled because the data is sensitive; use Export to keep a copy. Exported files contain medication and routine history — store them privately.

## Product name

**Daymark** is a working name. Verify trademark, package-name and app-store availability before a public release.
