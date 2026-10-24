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

API 26 is the first level with notification channels and adaptive icons and keeps java.time available without desugaring, while still covering almost all phones in use. Target 35 is the current Play requirement level supported by the chosen AGP. Android 12+ features (exact-alarm permission, dynamic colour) are handled with runtime checks.

#### Consequences

- Upgrading the debug APK in place requires the same debug signing key; see README.
- Behaviour below Android 12 is implemented but not a verification priority.

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

Travelling east or west shifts reminders to local wall-clock times; already recorded events keep their dates. This is the expected behaviour for "take at breakfast" style schedules, but it is not suitable for strict fixed-interval dosing across time zones; that limitation is documented in README.

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

## ADR-017 — Backup format and import behaviour

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

