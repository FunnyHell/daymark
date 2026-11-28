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
- Earlier days: on Today use the arrows next to the date (or tap the date) to open a previous day and mark its habits and doses; History → Medications shows a calendar of doses and a card per day where each dose can be marked, unmarked or skipped.
- Tap the circle on the left of a dose to mark it taken, tap again to unmark it (stock is restored). Skip / Snooze 30 min from the dose's menu (⋮) and the notification; Taken also from the notification and the widget (where it can be unmarked too); "Take all" for doses due at the same time; several doses (or habits) due at the same minute arrive as one notification with "Taken all" / "Skip all" / "Snooze all" ("Done all" for habits). Every action is validated and idempotent: repeated taps or duplicated notification actions record once.
- Missed doses are derived (nothing recorded within 3 hours), never invented; late doses can still be recorded.
- Pause/resume a schedule without false missed doses; corrections with exact inventory reconciliation.

**Inventory**
- Optional per medication, user-defined unit, initial stock, add packages (previous package sizes offered as chips; each tap adds that size, so two taps on 20 add 40), set a counted stock, low-stock threshold and notification, full change log.

**Habits with steps and notes**
- "Routine with steps" (each day: brush teeth ×2, shower…) and "Weekly list of steps" (cleaning: kitchen, bathroom, floors on any days). Tap a step to mark it; the routine is done when every step is. Partial progress counts as a share in the rings and statistics; streaks need the whole list.
- Notes: on any day of a habit (⋮ → Add note, or in the habit's calendar) and on a recorded dose.

**Home and modules**
- Home shows sections of the enabled modules; the pencil on Home hides and reorders them; the gear opens Settings. Settings → Bottom bar chooses up to four tabs after Home; screens not in the bar open from chips on Home. Settings → Modules turns features on or off; data of a module that is off is kept:
  - Habits, Medications;
  - Interval reminders: any number of reminders that repeat during chosen hours and days ("Stretch every hour"), with Done / Later, on a quieter notification type than medications;
  - Activity: steps and workouts from Samsung Health and other apps through Health Connect, with notes per workout; can mark a habit on workout days;
  - Outdoor quests: one public outdoor place a day (park, square, viewpoint, monument…) within your walking time, more on weekends; done when location shows you there ("I'm here") or marked by hand. Places come from OpenStreetMap; homes, shops, clinics and other private or indoor places are never offered.
- Notification types: medication reminders have their own chime and a longer vibration; each type's sound can be changed in Settings → Reminders → Notification types. Low stock warns after every dose while at or below the threshold.
- Contributing a module: see docs/MODULES.md; ideas in docs/IDEAS.md.

**Reminders and widgets**
- Reminders use alarm-clock alarms (delivered with the screen off; the alarm icon shows in the status bar while one is pending), with `USE_EXACT_ALARM` on Android 13+ and the "Alarms & reminders" permission on Android 12 (falls back to inexact). Settings → Reminders has a battery-restriction shortcut and a test reminder., rebuilt after reboot, app update, time and time-zone changes, plus a 6-hourly safety net. Reminders play the default notification sound and vibrate (change it in Settings → Reminders → Sound and vibration).
- An unmarked dose reminds again every 5 minutes (Settings → Reminders → Repeat until marked: off, 2–30 minutes) until it is taken, skipped or snoozed, for up to 3 hours.
- Lock-screen privacy: medication and habit names hidden on the lock screen by default.
- Seven home-screen widgets (Jetpack Glance), each with its own settings including accent color (system or fixed) and background opacity (long-press the widget → Settings on Android 12+):
  - **Habits** — today's habits with a compact tick or radio button that marks and unmarks;
  - **Medications** — today's doses grouped by time, tap to mark/unmark, "mark all" for a time group;
  - **Today** — one ring for today (one sector per habit, done/due count); resizable down to about one cell;
  - **Progress** — rings for today, this week and this month (average progress per habit, unfinished weekly goals included); a row, or today large with week and month stacked when the widget is narrow;
  - **Calendar** — the month with a ring around every day: one sector per habit due that day, filled by its progress (all habits or one);
  - **Streak** — one habit's current streak in large type, best streak and the last 7 days.
  - **Week** — every habit with its last 7 days as a grid (today on the right): a filled square is a done day, a cross is a slip, a dot is a day not done. Tap a day to mark it, tap again to remove the mark; today's overall progress is shown in the corner.

**History and statistics**
- Per-habit calendar heatmap with day details, current/best streaks, 30-day completion rate, weekly results, clean-streak history for abstinence habits.
- Overall activity heatmap, this/last week and month summaries, per-habit table, 14-day charts for amount habits.
- Medication adherence counts (taken/skipped/missed) for 7 and 30 days and a full event log.

**Sync between devices**
- Settings → Sync devices. First time: on one device tap Show code, on the other pick it under the nearby devices (or enter its IP:port) and type the code. After that the devices are paired: tap Sync, or leave "Sync automatically" on and they sync whenever Daymark is open on both. Both devices must be on the same Wi-Fi. Data is merged on both; settings stay per device.
- Merge rules: the later edit of a habit or medication wins, history from both devices is kept, deletions are applied on both, and a dose marked on both devices counts once (stock too).

**Data**
- Settings → Backups: automatic daily backups into a folder you choose (the last 7 are kept), back up now, restore from the list. Restoring or importing can merge with the phone's data or replace it.
- Export everything to a JSON file; import validates the whole file first and replaces data in one transaction only after confirmation.
- English and Russian UI.

## Install the debug APK on a phone (Samsung Galaxy A52)

The debug APK is built by GitHub Actions on every push and published to the branch **`ci-apk`** of this repository (file `app-debug.apk`, with `SHA256SUMS.txt`, `BADGING.txt` and `BUILD_INFO.txt`). It is also attached to each workflow run as the artifact `daymark-debug-apk`.

1. Download `app-debug.apk` (GitHub → branch `ci-apk` → `app-debug.apk` → Download raw file, or the workflow artifact).
2. Copy it to the phone (USB, Quick Share, cloud drive) or download it on the phone directly.
3. Open it with **My Files**. When asked, allow "Install unknown apps" for that app (Settings → Apps → Special access → Install unknown apps).
4. With USB debugging enabled you can instead run `adb install -r app-debug.apk`.

Package ID: `io.github.funnyhell.daymark.debug` · version `0.1.0-debug` · versionCode = CI run number · minSdk 26 (Android 8.0) · targetSdk 35.

**Updating:** every CI build is signed with one fixed key and numbered increasingly, so a newer APK installs over the old one and keeps the data. The key's certificate fingerprint is in `app/signing-cert.sha256`; CI checks the built APK against it and fails, without publishing, if the APK is signed with any other key (ADR-043). Builds up to CI run 55 were signed with a new random key each time and need one last reinstall (export a backup, uninstall, install, import).

The key is kept in the GitHub Actions cache (`daymark-debug-keystore-v1`), and `app/build.gradle.kts` signs with it when `DAYMARK_DEBUG_KEYSTORE` points to it. If the cache is ever lost, CI fails at "Check the APK is signed with the app's key" instead of publishing an APK that cannot update. To keep the key independent of the cache, a keystore can be stored as the repository secret `DEBUG_KEYSTORE_BASE64`:

```bash
keytool -genkeypair -keystore daymark-debug.keystore -storepass android -alias androiddebugkey -keypass android \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
base64 -w0 daymark-debug.keystore   # paste the output into Settings → Secrets and variables → Actions
keytool -list -v -keystore daymark-debug.keystore -storepass android | grep SHA256   # put this into app/signing-cert.sha256
```

Changing to a new key needs one reinstall. Keep the keystore file private and out of the repository.

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
- Widget buttons record only simple actions (habit done / +1 and unmark, a slip of a "quit" habit and its removal, dose taken and unmark, all doses of a time taken). A tap on a clean day of a "quit" habit records a slip without asking; tap again to remove it. Exact amounts, notes, skips and snoozes are recorded in the app or the notification.
- Backups are plain JSON (not encrypted). Import replaces all data; there is no merge.
- Sync is manual, needs both devices on the same Wi-Fi and is protected by a six-digit code (meant for a trusted home network). Concurrent edits of the same habit or medication keep the later one. No Wear OS app yet.

## Next steps: what to test on the Galaxy A52

1. Install, open, tap Get started and allow notifications, create one habit with a reminder a few minutes ahead and one medication with a dose a few minutes ahead and stock tracking on.
2. In Settings → Reminders, allow Battery → Unrestricted, then tap Test reminder and lock the phone: the reminder should sound about a minute later.
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
| `docs/DECISIONS.md` | Technical decisions (ADR-001 … ADR-035) |

## Data and privacy

All data is stored locally in a Room database on the device. Nothing is sent to any server. The only network use is device sync, which talks directly to your other device on the same Wi-Fi (this is why Android lists the network permission). Android cloud backup of app data is disabled because the data is sensitive; use Export to keep a copy. Exported files contain medication and routine history — store them privately.

## Product name

**Daymark** is a working name. Verify trademark, package-name and app-store availability before a public release.
