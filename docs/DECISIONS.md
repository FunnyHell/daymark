\# Daymark — Architecture Decision Log



This document records important technical decisions and their rationale.



Record decisions that affect architecture, persistence, scheduling, data integrity, compatibility, privacy, or future synchronization.



Do not record every minor implementation detail.



When a decision is superseded, preserve the old entry and add a new one explaining why.



\## Decision status



\- \*\*Accepted:\*\* Use this decision unless new evidence justifies reconsideration.

\- \*\*Proposed:\*\* Not yet approved.

\- \*\*Superseded:\*\* Replaced by a later decision.

\- \*\*Rejected:\*\* Considered and not selected.



\---



\## ADR-001 — Local-first application



\*\*Status:\*\* Accepted



\### Context



The core application should work without an account, a subscription, advertisements, or a mandatory cloud backend.



\### Decision



Persist core application data locally. Use Room for structured records and DataStore for appropriate lightweight preferences.



\### Consequences



\- Core functionality can work offline.

\- The user does not need to register.

\- Backups and export/import become important for data portability.

\- Synchronization between devices is a separate feature and must not be assumed to exist automatically.



\---



\## ADR-002 — Kotlin and Jetpack Compose



\*\*Status:\*\* Accepted



\### Context



The initial target is a modern Android application with a responsive UI and maintainable business logic.



\### Decision



Use Kotlin, Jetpack Compose, and Material 3 as the initial implementation baseline.



\### Consequences



\- UI is built with Compose.

\- UI state should be observable and lifecycle-aware.

\- Dependencies must remain compatible with the selected Android Gradle Plugin, Gradle wrapper, and JDK.

\- Specific library versions are selected after inspecting the actual build environment.



\---



\## ADR-003 — Separation of presentation, domain, and data



\*\*Status:\*\* Accepted



\### Context



Habit recurrence, medication intake, and inventory rules must be testable and reused by the main app, widgets, and notifications.



\### Decision



Separate presentation, domain logic, data persistence, and Android platform integrations.



Start with a single application module unless implementation evidence justifies a more complex module structure.



\### Consequences



\- Compose screens must not own critical business rules.

\- Notification and widget actions must use shared application logic.

\- Domain behavior should be testable without launching the full UI.

\- Avoid premature module splitting and unnecessary abstractions.



\---



\## ADR-004 — Preserve event history



\*\*Status:\*\* Accepted



\### Context



Users may edit schedules, pause habits, correct intake mistakes, or adjust inventory after an event has been recorded.



\### Decision



Historical events must not be silently deleted or rewritten merely because current configuration changes.



Use explicit correction, reversal, or adjustment records where appropriate.



\### Consequences



\- Historical data remains explainable.

\- Statistics may need to be recalculated from events and schedule history.

\- Database schema and event relationships require careful design.

\- Import and synchronization must preserve event identity and history.



\---



\## ADR-005 — Idempotent medication actions



\*\*Status:\*\* Accepted



\### Context



Android may deliver notification actions more than once. Users may tap repeatedly, and actions may be retried after process termination.



\### Decision



Medication intake processing must be idempotent. The same logical intake action must not be recorded twice or decrement stock twice.



Use stable occurrence/event identifiers, persisted validation, and database transactions where appropriate.



\### Consequences



\- Disabling a button is not sufficient protection.

\- Duplicate actions must be rejected or return a safe existing result.

\- Intake and inventory changes must be applied consistently.

\- Corrections must reconcile stock rather than blindly apply another adjustment.

\- These behaviors require automated tests.



\---



\## ADR-006 — Separate schedule definitions from historical events



\*\*Status:\*\* Accepted



\### Context



A user may change a habit schedule or medication reminder time after the schedule has already generated historical events.



\### Decision



Represent recurrence configuration and recorded events separately. Where necessary, use schedule versions or effective date ranges.



\### Consequences



\- Past events retain their original meaning.

\- Future occurrences can use updated schedules.

\- Recurrence calculations require explicit date and time semantics.

\- The implementation must define how schedule edits affect pending occurrences.



\---



\## ADR-007 — Use platform scheduling according to its guarantees



\*\*Status:\*\* Accepted



\### Context



Medication reminders can require user-visible timing, while maintenance work may be safely deferred.



\### Decision



Evaluate AlarmManager for reminders that require precise timing and WorkManager for deferrable background work.



Handle notification permission requirements, exact-alarm restrictions, reboot, and time changes explicitly.



\### Consequences



\- WorkManager must not be treated as a guaranteed exact-time scheduler.

\- Pending reminders may need to be reconstructed after reboot or schedule changes.

\- Device behavior must be verified on the primary Samsung phone.

\- The app must explain relevant restrictions rather than claiming guaranteed delivery in all circumstances.



\---



\## ADR-008 — Widgets and notifications share application rules



\*\*Status:\*\* Accepted



\### Context



The same medication or habit can be accessed through the main app, a notification, or a home-screen widget.



\### Decision



All interfaces must use shared business operations and validate actions against persisted state.



\### Consequences



\- UI state is not the authoritative data store.

\- Stale widget or notification actions must be handled safely.

\- Widget refresh and notification cancellation are platform integration concerns.

\- Exact widget interactions depend on the selected Android API and supported versions.



\---



\## ADR-009 — Future synchronization must not dictate the initial release



\*\*Status:\*\* Accepted



\### Context



Phone/tablet synchronization and a Wear OS companion are desirable future features but are not prerequisites for a reliable local application.



\### Decision



Use stable IDs and explicit event semantics from the beginning, but postpone synchronization until core local behavior is stable.



Evaluate separate mechanisms for phone-to-tablet communication and phone-to-Wear OS communication.



\### Consequences



\- No mandatory cloud service is introduced.

\- The app must not pretend separate Room databases synchronize automatically.

\- Future synchronization must define retries, duplicate handling, conflicts, deletions, and offline changes.

\- Medication intake and inventory cannot be merged as arbitrary mutable counters.



\---



\## ADR-010 — Automated verification and device verification are distinct



\*\*Status:\*\* Accepted



\### Context



Unit tests can validate business rules, but Android scheduling, notification delivery, widgets, and manufacturer-specific behavior depend on the real platform.



\### Decision



Report automated test results and physical-device verification separately.



\### Consequences



\- A successful build does not prove notification reliability.

\- Emulator success does not prove Samsung-specific behavior.

\- Features remain marked unverified until the appropriate check has actually been performed.

\- Development status must distinguish implemented code from verified functionality.



\---



## ADR-011 — Application identity and SDK levels

**Status:** Accepted

#### Context

The spec leaves the package ID and SDK levels open. The primary device is a Samsung Galaxy A52 (Android 12–14).

#### Decision

- Product name: Daymark (working name).
- `applicationId` / namespace: `io.github.funnyhell.daymark`. Debug builds add the suffix `.debug`, so the test APK is `io.github.funnyhell.daymark.debug` and can later live next to a release build.
- `minSdk` 26 (Android 8.0), `targetSdk` 35, `compileSdk` 35.
- Version 0.1.0 (versionCode 1).

#### Rationale

API 26 is the first level with notification channels and adaptive icons and keeps java.time available without desugaring, while still covering almost all phones in use. Target 35 is the current Play requirement level supported by the chosen AGP. Android 12+ features (exact-alarm permission, dynamic color) are handled with runtime checks.

#### Consequences

- Upgrading the debug APK in place requires the same debug signing key; see README.
- Behavior below Android 12 is implemented but not a verification priority.

#### Date

2026-10-24

---

## ADR-012 — Build toolchain and CI-produced APK

**Status:** Accepted

#### Context

The cloud development sandbox used for the initial implementation cannot reach `dl.google.com` / Google Maven, so AGP, AndroidX and the Android SDK cannot be downloaded there.

#### Decision

- Gradle 8.14.3 wrapper, AGP 8.10.1, Kotlin 2.1.21 (Compose compiler plugin), KSP 2.1.21-2.0.1, JDK 17 bytecode. Compose BOM 2025.05.00, Room 2.7.1, WorkManager 2.10.1, Glance 1.1.1, DataStore 1.1.7, kotlinx-serialization 1.8.1.
- GitHub Actions (`.github/workflows/android.yml`) is the reference build: unit + Robolectric tests, `assembleDebug`, APK inspection with `aapt2`, and an API 34 emulator job with instrumented tests and an adb-driven smoke test.
- Each push publishes the debug APK to the orphan branch `ci-apk` and emulator screenshots to `ci-screens` (force-pushed, one commit each).

#### Consequences

- Local builds work normally in Android Studio or any machine with the Android SDK.
- Binary build outputs live only on the two CI branches, never on development branches.

#### Date

2026-10-24

---

## ADR-013 — Habit recurrence, day states, streaks and rates

**Status:** Accepted

#### Decision

- Supported recurrences for CHECK, MULTI and QUANTITY habits: every day; selected weekdays; every N days (2–365) counted from the schedule version's start. Anything else is not offered.
- WEEKLY_GOAL habits count completions per calendar week. The week start is a setting (Monday default; Sunday and Saturday offered).
- ABSTINENCE habits: every day after the start date is clean unless an active LAPSE event exists on that day.
- Schedules are versioned (`effectiveFrom` inclusive, `effectiveUntil` exclusive). Changing recurrence or target ends the current version before today and starts a new one today; if there is no history yet the version is replaced. The habit type cannot change after creation, and the start date is locked once any event exists.
- Pause and archive both open a pause interval starting today (inclusive); resume/unarchive closes it with end = today (exclusive). An interval that never covered a day is removed.
- Day states: FUTURE, BEFORE_START, PAUSED, NOT_SCHEDULED, DONE, PARTIAL, SKIPPED, PENDING (today, not done), MISSED, LAPSED.
- Streaks: DONE is a success; MISSED, PARTIAL and LAPSED are failures; everything else (paused, unscheduled, skipped, today not yet done) is neutral and neither extends nor breaks a streak. Weekly goals use weeks as units. A week is a success when the goal is met; a finished week that missed the goal is a failure unless it contained paused days or days before the start (then neutral); the current week is in progress.
- Completion rate = successes ÷ (successes + failures) over the period. Corrections void events (`voided_at`) instead of deleting them; voided events never count.

#### Date

2026-10-24

---

## ADR-014 — Time-zone semantics

**Status:** Accepted

#### Decision

- Medication dose times and habit reminder times are floating local times: 08:00 means 08:00 in whatever zone the device is in on that date. A time that does not exist because of a DST gap is moved forward by java.time (02:30 → 03:30).
- Habit events store the local calendar date on which the user recorded them plus an absolute timestamp.
- Medication events store the occurrence's scheduled local date/time plus an absolute timestamp.
- Time-zone and clock changes rebuild the reminder alarm (`TIMEZONE_CHANGED`, `TIME_SET`).

#### Consequences

Travelling east or west shifts reminders to local wall-clock times; already recorded events keep their dates. This is the expected behavior for "take at breakfast" style schedules, but it is not suitable for strict fixed-interval dosing across time zones; that limitation is documented in README.

#### Date

2026-10-24

---

## ADR-015 — Medication occurrences, missed rule and action idempotency

**Status:** Accepted

#### Decision

- Occurrences are generated on demand from schedule versions, never stored. The stable occurrence key is `<doseId>@<ISO date>`; notifications, widgets and events refer to it.
- States: UPCOMING, DUE, SNOOZED, TAKEN, SKIPPED, MISSED. TAKEN/SKIPPED come only from explicit user actions. An unresolved occurrence becomes MISSED 3 hours after its due time, or 3 hours after the end of an active snooze if later. MISSED is derived, never stored, and a missed dose can still be recorded later.
- Every action (`recordDose`, `snooze`) runs validation and writes inside one Room transaction: an occurrence with an active TAKEN/SKIPPED returns `AlreadyResolved` and writes nothing; an occurrence that is not in the current schedule (edited, paused, archived, unknown) or lies in the future is rejected. A second snooze for the same occurrence within 60 s is treated as a duplicate delivery.
- Schedule edits take effect today, unless a dose of today has already been recorded under the current version; then they take effect tomorrow.
- Pausing creates no occurrences for paused days, so no false missed doses.
- Corrections void the event; a voided TAKEN reverses its inventory effect exactly once.

#### Date

2026-10-24

---

## ADR-016 — Inventory ledger

**Status:** Accepted

#### Decision

- Inventory is optional per medication, with a user-defined unit and a per-dose "uses from stock" quantity (fractions allowed).
- Stock is never stored as a total; it is the sum of `inventory_adjustments` rows: INITIAL, PACKAGE_ADDED, MANUAL_CORRECTION (counted stock − computed stock), INTAKE, INTAKE_REVERSAL.
- A unique index on (`event_id`, `type`) guarantees at most one INTAKE and one INTAKE_REVERSAL per taken event.
- An intake is always recorded; if stock is insufficient the INTAKE adjustment is clamped to the available amount and the UI asks the user to recount. Its reversal restores exactly what was consumed.
- Low stock: stock ≤ threshold. A low-stock notification is sent only when an action makes the stock cross the threshold.

#### Date

2026-10-24

---

## ADR-017 — Backup format and import behavior

**Status:** Accepted

#### Decision

- Export: a single UTF-8 JSON file (`format: "daymark-backup"`, `formatVersion: 1`) with all tables and the relevant settings, written through the Storage Access Framework. Files are not encrypted; the UI says so.
- Import: the file is parsed and fully validated first (format/version, unique IDs, references, enums, dates, numbers, at most one active resolution per occurrence, no duplicate intake adjustments). Only a valid file can be applied. Applying it replaces all current data in one transaction after explicit confirmation; the dialog offers "Export first". There is no merge.
- Android auto-backup and device-transfer are disabled for app data (`allowBackup=false`, data extraction rules), because the data is sensitive and the explicit backup is the supported path.

#### Date

2026-10-24

---

## ADR-018 — Reminder scheduling

**Status:** Accepted

#### Decision

- One component (`ReminderEngine`) recomputes reminders from the database and keeps a single AlarmManager alarm for the earliest of: the next reminder, or just after the next local midnight (widget day rollover).
- `setExactAndAllowWhileIdle` when exact alarms are allowed, otherwise `setAndAllowWhileIdle` (may be late). The app requests `SCHEDULE_EXACT_ALARM` through the system settings screen and explains the effect; it does not use `USE_EXACT_ALARM`.
- When the alarm fires, reminders that came due since the last check are posted (at most 2 hours back, to avoid a flood after the phone was off). Snoozed doses are reminded at the end of the snooze.
- The alarm is rebuilt on app start, after every data change, on boot, app update, time and time-zone changes, exact-alarm permission changes, and every 6 hours by a WorkManager job (deferrable safety net, not used for timing).
- Notifications are only posted when POST_NOTIFICATIONS is granted; the permission is requested when the user turns on a reminder or adds a dose, and from banners/settings.

#### Date

2026-10-24

---

## ADR-019 — Widgets with Jetpack Glance

**Status:** Accepted

#### Decision

- Two Glance (1.1.1) widgets: habits due today (with a Done/+1 button) and today's doses (with a Taken button). Rows open the app.
- Buttons call `ActionCallback`s that use the same repository methods as notifications: `HabitRepository.completeIfIncomplete` (records only while below target, one completion per day for weekly goals) and `MedicationRepository.recordDose`. Quantity and abstinence habits show progress only; amounts and lapses are recorded in the app.
- Widgets observe Room flows while their session is active and are refreshed after every data change, by the midnight alarm, the 6-hour maintenance job and the system's 30-minute update period.

#### Date

2026-10-24

---

## ADR-020 — Notification privacy

**Status:** Accepted

#### Decision

"Hide names on the lock screen" is on by default: notifications are `VISIBILITY_PRIVATE` with a public version that says only "Medication reminder" / "Habit reminder". Channels are also created with private lock-screen visibility.

#### Date

2026-10-24

---

## ADR-021 — Deleting records

**Status:** Accepted

#### Decision

Archive is the normal way to remove an item from daily use and keeps all history. A separate "Delete permanently" action exists for user-initiated removal of mistaken or unwanted items; it requires an explicit confirmation that explains data loss and suggests archiving instead.

#### Date

2026-10-24

---

## ADR-022 — Localization, synchronization and Wear OS

**Status:** Accepted

#### Decision

- All user-facing text is in Android string resources. The first release ships English (default) and Russian.
- Phone/tablet synchronization and the Wear OS companion are not implemented in 0.1.0. Stable string IDs, explicit events and timestamps are kept so they can be added later.

#### Date

2026-10-24

---

## ADR-023 — Widget set and per-widget settings (supersedes parts of ADR-019)

**Status:** Accepted

#### Context

Feedback from first use: text buttons in widgets were too large, doses due at the same time needed a "mark all" action, and more visual widgets were wanted (streaks, calendar, completion rings), with previews in the widget picker and per-widget settings.

#### Decision

- Five Glance widgets: Habits, Medications, Progress (rings), Calendar (month of dots), Streak (one habit).
- List widgets use a compact check icon (round tick or radio button, chosen per widget). Medications are grouped by time; a group with several open doses has a "mark all" control (`TakeAllDosesAction`), which records each dose separately through `recordDose`.
- Progress rings are drawn as bitmaps: today's ring has one sector per due habit (filled by its progress); week and month rings use the same completion-rate rules as History.
- Per-widget settings (check style, hide completed, ring range, habit for calendar/streak) are stored in a separate DataStore keyed by app widget id and edited in `WidgetConfigActivity`. On Android 12+ configuration is optional at placement and available later via the widget's long-press menu ("reconfigurable"); on older Android the settings screen opens when the widget is added. Settings are deleted with the widget.
- Static vector previews (`previewImage`) are shown in the picker.

#### Date

2026-10-24

---

## ADR-024 — Notification channels v2

**Status:** Accepted

#### Context

On the test phone reminders arrived without sound. Version 1 channels relied on implicit defaults and the medication notification used `setOnlyAlertOnce`, which also silences a reminder re-posted after a snooze.

#### Decision

Channels are recreated with new ids (`*_v2`) that set the default notification sound, vibration pattern and lights explicitly; habit reminders use high importance as well. The v1 channels are deleted. `setOnlyAlertOnce` is no longer used. Settings → Reminders links to the channel's system settings to change the sound.

#### Consequences

Any per-channel customisation the user made to v1 channels is lost once; Android does not allow changing a channel's sound programmatically after creation.

#### Date

2026-10-24

---

## ADR-025 — Exact reminders without a user-granted permission

**Status:** Accepted

#### Context

On the test phone reminders sounded only when the screen was on. Since Android 14, `SCHEDULE_EXACT_ALARM` is no longer pre-granted to new installs, so the app fell back to inexact alarms, which Doze defers until the device wakes.

#### Decision

Declare `USE_EXACT_ALARM` (granted at install on Android 13+ to apps whose core function is reminders; medication reminders qualify) and keep `SCHEDULE_EXACT_ALARM` for Android 12 only (`maxSdkVersion="32"`). The alarm stays `setExactAndAllowWhileIdle`; the inexact fallback remains for the case where neither permission is available.

#### Consequences

A store release must justify `USE_EXACT_ALARM` under Google Play's policy (alarm/reminder apps). Delivery on a Samsung device in deep sleep is not yet verified.

#### Date

2026-10-24

---

## ADR-026 — Tap-to-toggle marks with an explicit target state

**Status:** Accepted

#### Context

Marks could be added from widgets but not removed, and the app used separate buttons. The user asked for one control that marks and unmarks.

#### Decision

- The ring of a habit and the circle of a dose (Today screen) and the check icon in widgets toggle.
- Every toggle request carries the state the user wants (`done = true/false`), not "flip": `HabitRepository.setDone` marks with `completeIfIncomplete` or unmarks with `undoCompletion` (voids the latest completion of that day); `TakeDoseAction` records TAKEN or calls `MedicationRepository.undoTaken` (voids the TAKEN event, reverses its stock use once). Marking a done item or unmarking an open one changes nothing, so a stale widget or a duplicated tap is harmless.
- Unmarking never deletes: the event stays in history as corrected (ADR on corrections).
- For a several-times-a-day habit a tap adds one until the target is reached; a tap on a complete day removes one. Amount habits keep their "+" dialog; abstinence habits keep "Slip". Skipped doses are corrected in the app, not from the widget.

#### Date

2026-10-24

---

## ADR-027 — Widget progress, calendar rings and colors (refines ADR-023)

**Status:** Accepted

#### Context

The week and month rings used the History completion rate, which leaves out a weekly goal that is still in progress. With one daily habit done and a 3×/week habit not started, the widget showed 50% today but 100% for the week and month. The month-of-dots calendar did not show how many habits were done. Widgets used one fixed color scheme.

#### Decision

- `HabitStatistics.periodProgress`: each habit contributes one value (mean day progress over due days including today; for weekly goals the mean of `min(1, completions / goal)` over the weeks touching the period, the current week included; share of clean days for abstinence), and the result is the mean over habits. The History statistics keep their completion-rate rules.
- The calendar widget draws a sector ring around every day number (one sector per due habit, filled by its progress) using `dayFractions` evaluated for that day; the header shows today's done/due count.
- Ring and calendar bitmaps are sized from the widget's actual size (`SizeMode.Exact`).
- Per-widget accent (system wallpaper colors or a fixed palette) and background opacity (0–100%) are stored with the widget settings; settings are observed, so changes apply without re-adding the widget.

#### Date

2026-10-24

---

## ADR-028 — Alarm-clock alarms for reminders (refines ADR-025)

**Status:** Accepted

#### Context

With `USE_EXACT_ALARM` in place, reminders on the test Samsung phone still sounded only when the screen was on. Exact alarms set with `setExactAndAllowWhileIdle` can still be deferred by Samsung's battery management.

#### Decision

- When the next alarm is a reminder, it is set with `AlarmManager.setAlarmClock`, which Android and Samsung deliver on time in Doze; the status bar shows the alarm icon while one is set. The midnight widget refresh keeps an ordinary exact alarm.
- Settings shows the battery-optimization state and offers the system dialog to exempt Daymark (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), and the Today banner asks for it when reminders exist. A "Test reminder" posts a notification one minute later through the same alarm path.
- Onboarding asks for notification permission on its single button (Android 13+); the app continues whatever the answer.

#### Consequences

The alarm icon is visible whenever a reminder is pending. A store release must justify the battery-optimization request. Screen-off delivery on the Samsung device still needs confirmation by the user.

#### Date

2026-10-24

---

## ADR-029 — Separate Today widget; shorter, US-English text

**Status:** Accepted

#### Decision

- The "today only" option of the progress widget became its own widget (Daymark today), resizable down to about one cell. The progress widget keeps three rings and chooses between a row and "today large, week and month stacked" by whichever keeps the smallest ring larger; its minimum size is raised.
- English strings use US spelling. Explanatory notes under statistics, lists and editors were removed and the remaining text shortened; the medical disclaimer stays in onboarding and Settings → About.

#### Date

2026-10-24

---

## ADR-030 — Device sync over the local network (supersedes the sync part of ADR-022)

**Status:** Accepted

#### Context

The user asked for synchronization between devices (phone and tablet). SPEC §14 and ARCHITECTURE §13 require: no mandatory cloud backend, stable IDs, idempotent processing, handling of duplicates, conflicts and deletions, and no last-write-wins for intake events or inventory.

#### Decision

- Manual sync between two devices on the same Wi-Fi: one shows a six-digit code, the other connects (found via DNS-SD, or by typing IP:port) and enters it. Each side sends its full data set once; both merge with the same deterministic, order-independent function, so they end up identical.
- Merge rules: a deleted habit/medication stays deleted (tombstones in a new `deleted_records` table, schema v2); a habit/medication aggregate (record, schedule versions, doses, pauses) comes from the device where it was changed last; events and stock adjustments are history and are unioned by id; a void on either device applies to both; duplicate "taken/skipped" records for one dose keep the earliest and a voided "taken" returns its stock exactly once.
- Transport: TCP, payload = backup JSON (gzip) encrypted with AES-GCM, key from the code via PBKDF2-HMAC-SHA256 (100 000 iterations). The host stops after five wrong codes. Settings are not synced.
- The app now declares `INTERNET` (needed for any socket, including on the local network) and `ACCESS_NETWORK_STATE`; it still contacts no server.

#### Consequences

- Concurrent edits of the same habit or medication on two devices keep only the later one (history is never lost).
- A six-digit code protects against casual access on the same network, not against an attacker who records the traffic and brute-forces the code offline; sync is meant for a trusted home network.
- A stock count ("set stock") is stored as a correction relative to the counting device's history; after merging with doses recorded elsewhere the total can differ from the counted number until the next count.
- Two habit completions recorded offline on two devices for a several-times-a-day habit both count.
- No automatic or background sync; Wear OS is still not implemented.

#### Date

2026-10-25

---

## ADR-031 — Draw widgets directly

**Status:** Accepted

#### Context

On the test Samsung phone new widgets stayed on "Loading". Glance renders in a session started through WorkManager, and that work can be deferred; when a configuration activity is declared the launcher also does not always send the first update.

#### Decision

Widgets are composed with `GlanceAppWidget.compose` and pushed with `AppWidgetManager.updateAppWidget` on update, resize, settings save and every data change; Glance sessions still run as before. The widget settings screen uses the same composition as a live preview at the widget's real size; changes are saved as they are made and Cancel restores the previous settings.

#### Date

2026-10-25

---

## ADR-032 — Snooze end and grouped reminders

**Status:** Accepted

#### Context

"Snooze 30 min" never brought the reminder back: when the snooze ended the dose was "due" again and the planner used its original time, which lay before the last check, so nothing was posted. Several reminders at the same minute arrived as separate notifications.

#### Decision

- The reminder time of an open dose is the end of its latest snooze if that is later than the dose time (`OccurrenceRules.reminderAt`), also after the snooze has ended. Unit test reproduces the bug (snooze 08:05→08:35, alarm at 08:35) and a snooze that runs past the missed window.
- Reminders due in the same minute are posted as one notification per kind. Doses: "Taken all" and "Snooze all", each dose still recorded separately through `recordDose`/`snooze`. Habits: "Done all" for types that can be completed from a notification. When a dose of a group is recorded elsewhere it is removed from the group notification (rebuilt from the notification's own extras, without ringing again); an empty group disappears.

#### Date

2026-11-09

---

## ADR-033 — Paired devices and automatic sync (extends ADR-030)

**Status:** Accepted

#### Context

Entering a code for every sync is tedious; the user asked for sync settings between devices over the local network without cloud storage.

#### Decision

- Sync protocol version 2: the first exchange uses the six-digit code as before and the host hands out a random 256-bit key inside that encrypted exchange; both devices store it with the other's random device id (`sync_peers` DataStore). Later exchanges use the key directly; an unknown device or a wrong key is refused.
- While Daymark is in the foreground it listens on a TCP port and announces itself with DNS-SD (TXT attribute `id`). It also discovers other devices; with "Sync automatically" on (default), it syncs with each paired device it finds, at most every 2 minutes. When both devices are open, only the one with the smaller id starts the exchange.
- Nothing listens while the app is closed (no background service), so automatic sync happens when Daymark is open on both devices; "Sync" on a paired device does it on demand.
- Version 1 devices cannot sync with version 2 ("Update Daymark on both devices").

#### Consequences

Anyone with the pairing key (stored on both devices) can sync; "Forget" removes it on this device only. The threat model of ADR-030 (trusted home network) still applies to the pairing step.

#### Date

2026-11-09

---

## ADR-034 — Automatic backups and merging imports

**Status:** Accepted

#### Decision

- Backups screen: the user picks a folder through the system folder picker (persisted permission). A daily WorkManager job (battery not low) writes `daymark-backup-YYYY-MM-DD.json` there, replacing today's file if it exists, and deletes all but the newest 7. "Back up now" does the same immediately. Backups in the folder are listed for restoring.
- Restoring or importing a file offers **Merge** (the device-sync merge of ADR-030: nothing on the phone is lost) or **Replace** (the previous "replace all").
- No cloud component: the folder is whatever the user chooses; if another app syncs that folder, that is the user's choice.

#### Date

2026-11-09

---

## ADR-035 — Stable signing for updates; API-level checks

**Status:** Accepted

#### Context

Every CI build was signed with that runner's throwaway debug key and had versionCode 1, so each new APK had to replace the old install (uninstall, losing data unless exported). The app also crashed at start on Android 13 and older: `LocalDate.ofInstant` / `LocalTime.ofInstant` exist on Android only from API 34, and the only emulator tests ran on API 34.

#### Decision

- CI signs the debug APK with one persistent key: the repository secret `DEBUG_KEYSTORE_BASE64` if present, otherwise a key kept in the Actions cache (`daymark-debug-keystore-v1`). The key fingerprint is recorded in `BUILD_INFO.txt` on the `ci-apk` branch. The key is never committed.
- `versionCode` is the CI run number (1 for local builds), so newer builds install as updates.
- The calls were replaced with `Instant.atZone(zone).toLocalDate()/toLocalTime()`. Android Lint runs in CI with `NewApi` breaking the build. A startup job installs and starts the app on API 26, 28, 30, 31, 33 and 34 emulators (tablet profile), backgrounds and resumes it, and fails on any crash.

#### Consequences

The cached key expires after 7 days without builds; then a new key is created and one reinstall is needed. A repository secret removes that limitation (README, "Updating").

#### Date

2026-11-09

---

\## Future decisions



Add an entry when a consequential choice is made about:

\- Application package name and final product name.

\- Minimum and target Android SDK.

\- Recurrence and time-zone semantics.

\- Missed-dose calculation rules.

\- Inventory units and reconciliation.

\- Widget implementation.

\- Export/import format.

\- Data encryption and backup behavior.

\- Phone/tablet synchronization.

\- Wear OS module structure.

\- Localization.



Use the following template:



\### ADR-XXX — Decision title



\*\*Status:\*\* Proposed / Accepted / Superseded / Rejected



\#### Context



What problem or constraint requires a decision?



\#### Options considered



What reasonable alternatives exist?



\#### Decision



What was selected?



\#### Rationale



Why is this option preferred?



\#### Consequences



What trade-offs, limitations, tests, or follow-up tasks result?



\#### Date



YYYY-MM-DD

---

## ADR-036 — Reminders before widgets; grouped actions as one change; week widget

**Status:** Accepted

#### Context

Snoozed reminders still did not always come back. A notification action runs in a broadcast receiver with about ten seconds; after each recorded dose the app first redrew every widget and only then cancelled the notification and set the next alarm. "Snooze all" repeated that for every dose of the group. In a freshly started process with several widgets this could run out of time, and the alarm for the snooze end was never set. Two more gaps were found while reading the code: a snoozed dose of an earlier day (snoozed past midnight, or from a notification left overnight) was not planned at all, because only today and tomorrow were considered; and the doses of a group were snoozed a moment apart, so they would have come back as separate notifications. The cause on the user's phone was not reproduced; these are the defects found.

#### Decision

- After a data change the reminder work (cancel notifications, set the alarm) runs at once and widgets are redrawn in parallel; nothing in the reminder path waits for a widget.
- `MedicationRepository.recordDoses` / `snoozeAll` handle a list of doses, each validated as before, and report one change. All doses of one `snoozeAll` get the same end time. The group notification actions and the widget's "mark all" use them.
- The planner also plans occurrences of earlier days that have an active snooze ending within the catch-up window.
- A failure while showing reminders no longer prevents the next alarm from being set.
- `HabitRepository.setDone` covers every habit type: for an abstinence habit "not done" records a slip and "done" voids the day's slips; for an amount "done" adds the remainder to the target and "not done" voids the latest entry. Voided entries stay in the history.
- New Week widget: the last seven days of each habit (today last) as a grid of tappable days, calling `setDone(habit, wanted state, date)`. Days before the start, paused days and days a habit is not scheduled cannot be tapped. The list and streak widgets can now toggle abstinence habits the same way.
- Package size chips add their size to the amount in the field instead of replacing it.

#### Consequences

A tap on a clean abstinence day in a widget records a slip without a confirmation; a second tap removes it. Tests: planner (snooze past midnight), repository (grouped actions report once and share the snooze end; slip and amount toggles), week grid rules, and on the emulator the real "Snooze all" broadcast followed by a check of the system's next alarm, and a short snooze that brings the notification back.

#### Date

2026-11-13

---

## ADR-037 — Widget failures are visible; past days are marked from Today and History

**Status:** Accepted

#### Context

The Week and Calendar widgets stayed on "Loading" on the user's device. A widget that cannot be drawn left no trace: the direct render swallowed the error and the launcher kept the initial layout. The settings preview of the list widgets showed no rows, because a scrolling list has no content outside a launcher. Marks of earlier days could only be made from a habit's or medication's own screen, and the medication history was a raw event log.

#### Decision

- When the direct render of a widget fails, the widget shows a short error text (exception, message, source line) instead of the loading layout. Bitmaps drawn for widgets are limited by the screen size, because launchers refuse a widget whose bitmaps exceed about one and a half screens over all its sizes.
- CI binds every widget to an `AppWidgetHost` on API 26–34 emulators, with sample data, at several sizes, and draws it through `WidgetRenderer.render`; all widgets are also composed in Russian.
- Widgets take a `preview` flag for the settings screen: lists are laid out directly, without click actions, and show sample rows when there is nothing real to show.
- The Today screen can show an earlier day (previous/next, date picker, "Back to today"); the same rows and repository calls are used with that date. Snooze and low-stock notes are only offered for today.
- History → Medications shows adherence tiles, a 12-week dose calendar and one card per day with a line per dose, which can be marked taken, unmarked or skipped; the event log is folded away.

#### Consequences

The cause of the stuck widgets on the device is not known at the time of this decision; the error text is what will show it if it happens again.

#### Date

2026-11-15

---

## ADR-038 — Repeated medication reminders; Skip all

**Status:** Accepted

#### Context

The user asked for a dose reminder to ring again every few minutes until the dose is marked, with the interval configurable or off, and noticed that a group notification had no way to skip.

#### Decision

- Setting "Repeat until marked" (Settings → Reminders): off, 2, 5, 10, 15 or 30 minutes; default 5. The planner adds repeated reminders of an open dose at that interval after its reminder time (the dose time, or the end of the latest snooze), until the dose is taken, skipped or snoozed, or its 3-hour missed window ends. A snooze therefore restarts the series from the snooze end.
- Before a dose is shown again, its earlier notification is removed (alone or from a group), so a dose is in one notification at a time. After a long gap only the latest reminder of each dose is shown. A group notification rings again when reposted; a group shrunk because a dose was recorded elsewhere stays silent.
- Group notifications offer Taken all, Skip all and Snooze all (Android shows at most three actions, so a per-dose skip in a group is done in the app).

#### Consequences

With the default, an unmarked dose rings up to 36 times. Repeats end with the missed window instead of continuing indefinitely.

#### Date

2026-11-18

---

## ADR-039 — Feature modules and a configurable Home

**Status:** Accepted

#### Context

A second developer wants to add features (first a reminder to move during the day), and the user wants a Home screen like Samsung Health's, where sections are added and removed, and features that are not needed can be turned off.

#### Decision

- A feature is a `DaymarkModule` (`module/DaymarkModule.kt`) listed in `ModuleRegistry`. It can provide a bottom-bar tab, Home sections, screens, a settings screen and reminders. The user turns modules on or off in Settings → Modules; the choice is stored as two sets (`modulesOn`, `modulesOff`) so that a module's default applies until the user chooses.
- Home is the list of sections of the enabled modules. The user hides and reorders them (Edit Home); new sections from a module turned on later are added at the end (`HomeLayout.resolve`). The date navigation (earlier days) stays on Home and is passed to the sections.
- Habits and Medications became the first two modules. Their reminders, notifications, widgets, history, backup and sync remain in the core and are switched off with the module where they are user-visible (tab, Home, reminders); widgets keep working.
- Module reminders go through the single reminder alarm: `ReminderEngine` asks each enabled module for its upcoming reminders and calls it back when one is due. A failing module is logged and skipped.
- The first new module, Movement breaks (off by default), keeps its settings in its own DataStore file and is not part of backups or sync.
- One Gradle module for now; packages (`module/`, `modules/<id>/`) are laid out for a later split. The guide for contributors is docs/MODULES.md; ideas are in docs/IDEAS.md.

#### Consequences

Turning off Medications or Habits stops their reminders but leaves their widgets and History tab. Data of a module that is off is still exported and synced.

#### Date

2026-11-22

---

## ADR-040 — Bottom bar chosen by the user, interval reminders, notification types, repeated low-stock warnings

**Status:** Accepted

#### Decision

- The bottom bar is Home plus up to four screens the user picks and orders (Settings → Bottom bar): tabs of enabled modules and History. Until the user arranges it, the available screens are shown in default order. Screens not in the bar are opened from chips on Home. Settings moved from the bar to a gear in Home's top bar.
- Movement breaks became Interval reminders: any number of reminders with a title, an interval (15–180 min), active hours and days; a tab with the list, an editor, a Home section and Done / Later. The module id stays `breaks` and its data file stays `module_breaks`; the single break of the first version is carried over as one reminder. Each reminder is its own reminder group (`ModuleReminder.group`), so after a long gap only the latest of each is shown.
- Notification types: medication reminders use a new channel with a bundled chime (`res/raw/medication_chime.wav`, generated for the app) and a longer vibration; interval reminders have their own quieter channel; Settings → Reminders lists the types and opens each one's system settings for sound and vibration. Android does not allow an app to change a channel's sound after creation, so the medication channel was replaced (the user's earlier channel settings for it are not carried over).
- Low stock: after every dose that lowers the stock while it is at or below the threshold, the low-stock notification is shown again with the new count (`InventoryRules.warnAfterIntake`).

#### Date

2026-11-28

---

## ADR-041 — Checklist habits and notes (schema 3)

**Status:** Accepted

#### Context

Routines consist of steps ("brush teeth ×2, shower"), and some lists are weekly ("cleaning: kitchen, bathroom, floors" on any days). Progress and streaks must account for partly done lists. Users want to write what exactly was done.

#### Decision

- Two habit types: CHECKLIST (steps each scheduled day) and WEEKLY_CHECKLIST (steps over the week). Steps are rows of `habit_steps` (title, times per day/week, order, added on, removed on). A step completion is a COMPLETION event with `step_id`.
- Progress of a day (CHECKLIST) is the sum over its steps of min(done, target) out of the sum of targets; the day is done only when every step is. A weekly checklist counts the same over the week up to the evaluated day; streaks and rates use weeks, like weekly goals. Statistics therefore count a half-done routine as half.
- Editing steps never rewrites history: a removed step with marks ends today (its earlier days keep it), a new step counts from today when there is history.
- Notes: a NOTE habit event per day (replacing a note voids the previous one); a nullable `note` on medication events, edited in place because it describes a record and does not change it. Workout notes live in the Activity module (ADR-042).
- Schema 3 (`MIGRATION_2_3`): table `habit_steps`, columns `habit_events.step_id` and `medication_events.note`. Backups and sync carry steps, step ids and notes; files from older versions still import. Older app versions cannot read new backups or sync with this version (unknown fields are rejected), so all devices should be updated together.

#### Date

2026-11-28

---

## ADR-042 — Activity module (Health Connect)

**Status:** Accepted

#### Decision

- New module Activity (off by default) reads workouts (exercise sessions) and steps from Health Connect with `androidx.health.connect:connect-client` 1.1.0. Samsung Health shares these with Health Connect when the user turns that on in Samsung Health. Read only, only while the app is open; nothing is written back or sent anywhere.
- Permissions: READ_EXERCISE and READ_STEPS, requested from the module screen; a rationale screen (`HealthRationaleActivity`, plus the Android 14 `VIEW_PERMISSION_USAGE` alias) explains the use, as Health Connect requires.
- The module shows today's steps and the last 14 days of workouts, lets the user write a note per workout (stored in the module's own DataStore by Health Connect record id), and can mark a chosen habit done on days with a workout. Each workout marks once (`ActivityRules.daysToMark`), so a mark the user removes stays removed. Marking runs when the app comes to the foreground (new optional `DaymarkModule.onForeground`).
- Workout notes are not part of backups or sync yet.
- connect-client 1.1.0 requires compileSdk 36, so the app is compiled against Android 16 (API 36). targetSdk stays 35 and minSdk 26: compiling against a newer SDK only makes newer APIs visible to the compiler and does not change behavior on devices; Lint (NewApi) still rejects calls missing on older versions.

#### Date

2026-11-28
