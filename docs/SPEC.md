\# Daymark — Product Specification



\*\*Document status:\*\* Initial specification  

\*\*Product name:\*\* Daymark (working title)  

\*\*Primary platform:\*\* Android  

\*\*Primary language for initial implementation:\*\* Kotlin  

\*\*Primary UI language:\*\* To be decided; keep user-facing strings externalized so localization can be added without rewriting screens.



\## 1. Product vision



Daymark is a local-first application that combines habit tracking and medication management.



The app should make it easy to:

\- Build and maintain routines.

\- Track habits with different schedules and measurement types.

\- Schedule medication reminders.

\- Record medication intake accurately.

\- Track remaining medication stock.

\- Review history, streaks, and statistics.

\- Complete common actions from notifications and home-screen widgets.



The application must prioritize reliability, clarity, privacy, and low-friction everyday use over feature quantity.



\## 2. Product principles



1\. \*\*Local-first:\*\* Core functionality must work without an account or internet connection.

2\. \*\*No artificial paywalls:\*\* Habit and medication tracking, widgets, statistics, reminders, and export/import must not be locked behind a subscription.

3\. \*\*No advertisements:\*\* Do not introduce advertising SDKs or ad placements.

4\. \*\*History preservation:\*\* Editing or pausing a schedule must not silently erase or rewrite past events.

5\. \*\*Explicit actions:\*\* A reminder is not evidence that an action occurred.

6\. \*\*Consistent state:\*\* The main app, notifications, and widgets must reflect the same persisted data.

7\. \*\*Recoverability:\*\* Users should be able to correct mistakes and export their data.

8\. \*\*Incremental delivery:\*\* Implement and verify the app in small milestones.



\## 3. Target devices and compatibility



\### Initial target



\- Android 12–14.

\- Samsung Galaxy A52 as the primary phone test device.

\- One UI 5–6 as a target manufacturer environment.

\- Samsung Galaxy Tab S6 Lite as a potential tablet target.

\- Samsung Galaxy Watch 6 Classic as a possible future Wear OS companion.



The application should use standard Android APIs where practical and avoid unnecessary Samsung-only dependencies.



\### Compatibility requirements



\- Respect runtime notification permissions where applicable.

\- Handle system restrictions on background execution and exact alarms.

\- Handle application process termination and device reboot.

\- Handle changes in time zone and daylight-saving time.

\- Support screen resizing and different display densities.

\- Preserve user data across ordinary app updates.

\- Use database migrations for schema changes.



The actual minimum and target SDK levels must be selected and documented after checking current platform requirements and dependencies.



\## 4. Scope and priorities



\### Core release



\- Local data persistence.

\- Habit creation, scheduling, completion, and history.

\- Medication records, schedules, intake events, and history.

\- Actionable reminders.

\- Medication inventory tracking.

\- Habit and medication widgets.

\- Calendar and basic statistics.

\- Pause/resume behavior.

\- Data export and import.

\- Automated tests and physical-device verification.



\### Later enhancements



\- Advanced analytics.

\- Tablet-specific layout refinements.

\- Direct synchronization between phone and tablet.

\- A separate Wear OS companion application.

\- Phone-to-watch synchronization.

\- Optional additional habit templates and customization.



Do not implement future enhancements before the core data model and workflows are stable unless explicitly requested.



\## 5. Habit management



\### 5.1 Habit properties



A habit should support, where applicable:



\- Stable unique ID.

\- Name.

\- Optional description.

\- Optional icon and color.

\- Habit type.

\- Schedule configuration.

\- Creation timestamp.

\- Start date.

\- Optional end date.

\- Active, paused, or archived state.

\- Optional target value and unit.

\- Optional reminder configuration.

\- Creation and modification timestamps.



Use a model that can accommodate new habit types without storing all behavior in a single unstructured text field.



\### 5.2 Supported habit types



\#### A. Daily habit



A habit intended to be completed once per scheduled day.



Examples: reading, stretching, studying.



The user can mark it completed or explicitly skip it if the interface supports skipping.



\#### B. Multiple-times-per-day habit



A habit with a configurable number of completions or scheduled occurrences per day.



Examples: stretching three times a day or taking walking breaks.



The app must distinguish:

\- The habit itself.

\- Its daily target.

\- Individual scheduled occurrences, if configured.

\- Recorded completion events.



The UI should show progress toward the daily target.



\#### C. Weekly goal



A habit measured against a target number of completions per week.



Example: exercise four times per week.



The user should be able to see progress toward the weekly goal without being forced to choose fixed days.



The definition of a week must be consistent and respect the configured calendar conventions.



\#### D. Custom schedule or interval



The user can define supported recurrence patterns, such as:

\- Selected days of the week.

\- Every N days.

\- A configurable interval or recurrence pattern supported by the implementation.



The UI must explain the selected schedule clearly.



Do not silently interpret an unsupported recurrence pattern. If a pattern is not supported, explain the limitation.



\#### E. Abstinence habit



A habit tracking avoidance of an unwanted behavior.



Examples: avoiding a selected behavior or maintaining a period without it.



The user should be able to:

\- Start tracking.

\- Record a lapse or reset event.

\- Review the history of lapses.

\- See the current streak and previous streaks.



A lapse must not erase historical progress. Store events so that streaks can be recalculated.



\#### F. Quantitative habit



A habit with a numeric target and unit.



Examples: drink a target amount of water, walk a distance, or study for a number of minutes.



The user should be able to:

\- Set a target.

\- Choose or enter a unit.

\- Record progress.

\- Correct a mistaken entry.

\- See progress against the target.



The app must validate numeric input and avoid silently accepting invalid or nonsensical values.



\### 5.3 Habit completion and history



\- Completion actions must be persisted.

\- The user should be able to inspect historical completion records.

\- Where supported, the user can undo or correct an accidental action.

\- Corrections must not silently rewrite unrelated historical records.

\- Streaks must be derived from the schedule and persisted events rather than maintained as an unchecked counter.

\- Missing an occurrence must not be treated as completion.

\- The UI must distinguish a scheduled occurrence from a completion event.

\- Changes to a future schedule must not automatically rewrite past records.



\### 5.4 Pausing, resuming, and archiving



The user can pause a habit without deleting it.



When paused:

\- No new scheduled reminders should be generated for the paused period.

\- Existing history must remain accessible.

\- The pause interval must be represented explicitly or otherwise recoverable.

\- Resuming must not create a false streak or retroactively mark the paused period complete.



Archiving should remove a habit from the active workflow without destroying its history.



The exact behavior when editing a schedule, changing a start date, or resuming after a long pause must be consistent and documented.



\## 6. Medication management



\### 6.1 Medication record



A medication record should support:



\- Stable unique ID.

\- Display name.

\- Optional description.

\- Optional strength or formulation text.

\- Optional user-defined notes.

\- Optional inventory configuration.

\- Active, paused, or archived state.

\- Creation and modification timestamps.



The application is a tracking and reminder tool. It must not prescribe medication, infer an appropriate dose, or replace professional medical advice.



\### 6.2 Medication schedule



The user should be able to configure:

\- One or more scheduled times per day.

\- A start date.

\- An optional end date.

\- A dose description or quantity for each occurrence.

\- A schedule that can be paused and resumed.

\- Reminder behavior for each scheduled occurrence.



The model should allow schedule changes over time without overwriting historical intake events.



If the schedule changes, past records must remain associated with the schedule and occurrence that existed when they were created.



\### 6.3 Intake event states



The system must distinguish at least:



\- \*\*Taken:\*\* The user explicitly records that the dose was taken.

\- \*\*Skipped:\*\* The user explicitly records that the dose was skipped.

\- \*\*Snoozed:\*\* The reminder is deferred; this does not record intake.

\- \*\*Missed:\*\* An occurrence is considered missed according to a documented rule.

\- \*\*Corrected:\*\* A previously recorded event has been corrected or reversed.



Do not infer that a medication was taken merely because the user dismissed a notification.



A snooze must not decrement inventory or mark the dose taken.



The exact rules for when an occurrence becomes missed must be explicit and testable. Do not mark an occurrence missed simply because a notification was delivered.



\### 6.4 Corrections and history



The user must be able to inspect past medication events.



Where practical, the user should be able to correct an accidental intake or skip action.



Corrections must:

\- Preserve a traceable event history.

\- Update derived state and inventory consistently.

\- Avoid double-counting or double-decrementing stock.

\- Be idempotent when the same action is retried.

\- Avoid rewriting unrelated events.



A correction to a past event should recalculate any affected inventory totals rather than blindly applying another decrement.



\### 6.5 Pausing medication schedules



Pausing a medication schedule must:

\- Stop future reminders for the paused period.

\- Preserve medication information and historical events.

\- Avoid deleting inventory.

\- Avoid creating false missed-dose records for the pause interval.

\- Allow the user to resume later.



Pausing a schedule is not the same as deleting the medication record.



The app must not suggest whether a medication should be stopped or restarted. The user is responsible for configuring their tracking schedule.



\## 7. Medication inventory



Inventory tracking is optional and should be configurable per medication.



\### 7.1 Inventory setup



Support:

\- Manual entry of the current stock quantity.

\- Addition of a package with a user-defined quantity.

\- A configurable low-stock threshold.

\- Display of estimated remaining stock.

\- A history of inventory adjustments.



For example, a user may record that a package contains a certain number of tablets and add that quantity to the current stock.



Do not assume all medications are counted in tablets. Support a user-defined inventory unit where practical.



\### 7.2 Inventory updates



When an intake event is recorded as taken:

\- Apply the configured inventory consumption rule.

\- Persist the intake and inventory update consistently.

\- Ensure repeated notification actions do not consume stock twice.



When an event is skipped or snoozed:

\- Do not consume inventory.



When a past event is corrected:

\- Reconcile inventory against the corrected event history.

\- Do not apply duplicate stock changes.



When inventory is manually adjusted:

\- Record the adjustment and its timestamp.

\- Preserve enough information to explain why the current quantity changed.



\### 7.3 Low-stock warnings



The user can configure a threshold.



When remaining stock reaches or falls below that threshold, the app should indicate low stock in the relevant UI and, if configured, issue a reminder.



The app must not claim to know the actual physical stock if the user has not kept the inventory up to date.



\## 8. Notifications and reminders



\### 8.1 Reminder requirements



Notifications should be available for:

\- Habit reminders.

\- Medication occurrences.

\- Low-stock alerts, if enabled.



Users must be able to configure or disable applicable reminders.



Notification text should be concise and should avoid unnecessarily exposing sensitive details on the lock screen. Where appropriate, provide a privacy-conscious display option.



\### 8.2 Medication notification actions



A medication reminder should support the following actions where Android permits:



\- Mark as taken.

\- Skip.

\- Snooze for 30 minutes.



These actions must call the same domain-level logic used by the main application.



Snoozing must reschedule or defer the reminder without marking the dose taken or consuming stock.



The app must handle:

\- Repeated taps.

\- Stale notifications.

\- Duplicate broadcast or intent delivery.

\- Process death.

\- Application restarts.

\- Schedule edits while a notification is visible.

\- Device reboot.

\- Permission denial.

\- Exact-alarm restrictions.

\- Changes in local time and time zone.



A notification action must validate that the targeted occurrence is still valid and has not already been processed.



\### 8.3 Scheduling reliability



Use AlarmManager only where precise user-visible timing justifies it. Use WorkManager for deferrable work.



Do not assume WorkManager can guarantee an exact clock-time reminder.



Explain permission and battery-optimization limitations to the user where necessary.



The application must maintain enough persisted schedule data to reconstruct pending reminders after process death or reboot.



Notification delivery and action reliability must be verified on the primary physical Samsung device. An emulator-only test is insufficient to claim Samsung-specific reliability.



\## 9. Home-screen widgets



Widgets are a core feature, not a premium feature.



\### 9.1 Habit widget



The habit widget should provide a useful glanceable view of selected habits and their current progress.



Where supported by the selected widget implementation, the user should be able to mark a habit complete or record progress directly from the widget.



The widget should:

\- Reflect persisted state.

\- Refresh after relevant actions.

\- Avoid showing stale completion state for long periods.

\- Open the relevant screen when more detail is needed.



\### 9.2 Medication widget



The medication widget should show relevant upcoming medication occurrences and their current states.



Where supported, it should allow the user to record an intake action without opening the full application.



All widget actions must use the same validation and idempotent processing rules as notification actions.



\### 9.3 Widget constraints



The exact widget technology and supported interaction model must be chosen based on Android version and device compatibility.



Do not promise an interaction that the chosen API cannot support.



Test widget behavior on the primary Samsung device, including updates after changes in the main application and after process recreation.



\## 10. Statistics and history



\### 10.1 Calendar view



Provide a calendar-based history of habit activity.



A heatmap inspired by GitHub contribution graphs is desirable where it fits the selected design.



The calendar should communicate activity clearly and provide a way to inspect individual dates.



Do not encode meaning only through color; use accessible labels or other cues.



\### 10.2 Statistics



At minimum, provide useful statistics for applicable habit types:

\- Completion counts.

\- Completion rate against scheduled occurrences.

\- Current streak.

\- Best streak.

\- Weekly and monthly summaries.

\- Quantitative progress over time.

\- History of abstinence and lapse events.



Medication statistics should focus on recorded events and schedule adherence, without implying medical efficacy.



\### 10.3 Data correctness



Statistics must be derived from persisted records and clearly defined rules.



\- Paused periods must not be silently treated as missed activity.

\- Unscheduled days must not count as missed occurrences.

\- Schedule changes must not corrupt previous statistics.

\- Corrected events must be reflected consistently.

\- Historical summaries must remain stable unless the underlying records or explicitly applicable calculation rules change.



\## 11. UI and interaction design



Use Jetpack Compose and Material 3 as the initial UI baseline.



The visual direction should be inspired by modern One UI and Material 3:

\- Clear hierarchy.

\- Comfortable touch targets.

\- Simple navigation.

\- Consistent spacing.

\- Readable typography.

\- Useful light and dark themes.

\- Minimal friction for frequent actions.

\- Responsive layouts for phone and tablet screens.



Do not copy proprietary Samsung interface assets.



The app should not overwhelm the user with dashboards or unnecessary configuration on first launch.



Provide sensible defaults where possible while allowing advanced settings to be changed.



Keep user-facing strings separate from business logic so localization can be added later.



\## 12. Data storage and privacy



Core features must work offline.



Use local persistent storage for habits, schedules, intake events, inventory, settings, and history.



Requirements:

\- Persist important state across process death and device restart.

\- Use database migrations for schema changes.

\- Avoid storing derived state as the only authoritative copy.

\- Do not send personal data to analytics or remote services by default.

\- Do not introduce mandatory registration.

\- Do not introduce an external backend without explicit approval.

\- Treat medication information and exported backups as sensitive.

\- Avoid exposing unnecessary medication details in notifications.



The exact backup, encryption, and export formats must be documented before implementation.



\## 13. Export and import



The user should be able to export their application data to a user-accessible file and import a compatible backup.



The format should be versioned so it can evolve.



Export/import must account for:

\- Habit definitions.

\- Schedule configurations.

\- Historical events.

\- Medication records.

\- Inventory adjustments.

\- Relevant settings.



Import must validate the file before modifying existing data.



Do not silently overwrite current data or merge records unpredictably. Define and communicate the import behavior before implementing it.



A failed import must not leave the database partially corrupted.



\## 14. Future synchronization



Synchronization is not required for the first core release.



Future goals may include:

\- Direct phone-to-tablet synchronization without a mandatory cloud backend.

\- Phone-to-Wear OS companion synchronization.

\- Offline changes queued until a connection becomes available.



The design should use stable IDs and explicit events so synchronization can be added without replacing all business logic.



Future synchronization must address duplicate delivery, conflict handling, retries, deleted records, and stale clients.



Do not implement synchronization prematurely or assume that Bluetooth or a shared Google account automatically synchronizes the application's Room database.



\## 15. Future Wear OS companion



Wear OS support is a later milestone.



Potential capabilities:

\- View upcoming medication occurrences.

\- Confirm or skip a dose.

\- View selected habit progress.

\- Record supported habit actions.

\- Queue actions while offline and synchronize later.



The watch application must not assume the phone is always reachable.



Use stable event identifiers and idempotent processing to avoid duplicate intake or inventory updates.



The watch UI should be designed for the small screen and should not simply reuse phone layouts.



\## 16. Accessibility and usability



\- Support scalable text where practical.

\- Use appropriate content descriptions for interactive icons.

\- Maintain sufficient contrast.

\- Do not rely only on color to distinguish completion states.

\- Provide clear feedback after user actions.

\- Make undo or correction possible for common accidental actions where safe.

\- Keep important actions reachable with reasonable tap counts.



\## 17. Non-functional requirements



\### Reliability

\- No silent loss of persisted user records.

\- No duplicate intake processing from repeated actions.

\- No double inventory decrement for the same intake event.

\- No silent deletion of history during schedule edits.

\- Predictable behavior after process death and reboot.



\### Maintainability

\- Separate presentation, domain logic, and persistence.

\- Test critical business rules independently of the UI.

\- Keep migrations and scheduling logic explicit.

\- Avoid unnecessary architectural complexity.



\### Performance

\- Keep common screens responsive.

\- Avoid blocking the main thread with database or scheduling work.

\- Use asynchronous operations where appropriate.

\- Avoid unnecessary background polling.



\### Privacy

\- Local-first behavior.

\- No mandatory account.

\- No ads.

\- No undisclosed transmission of personal data.



\## 18. Acceptance criteria for the core release



The core release is not complete until all of the following are addressed and their verification state is documented.



1\. A user can create, edit, pause, resume, and archive a habit.

2\. Each supported habit type follows its documented recurrence and completion rules.

3\. Habit history survives process death and application restart.

4\. A user can create a medication record and configure multiple daily occurrences.

5\. A user can record taken, skipped, and snoozed states distinctly.

6\. Repeated actions cannot process the same intake event twice.

7\. Inventory changes are consistent with intake history and corrections.

8\. Schedule edits do not silently rewrite past events.

9\. Reminders can be reconstructed after a reboot or application restart where the platform permits.

10\. Notification permission denial and relevant alarm restrictions are handled clearly.

11\. Habit and medication widgets display persisted state and support the interactions promised by their implementation.

12\. Calendar and statistics use documented, testable calculation rules.

13\. Export and import validate data and do not silently destroy existing records.

14\. Core functionality works without an internet connection or account.

15\. Automated tests cover critical recurrence, intake, inventory, and idempotency rules.

16\. Physical-device verification results are recorded separately from automated test results.



Do not label the release complete while critical medication, inventory, or history-integrity requirements remain unverified.



\## 19. Open decisions



The following decisions must be resolved during planning or implementation and recorded in `docs/DECISIONS.md`:



\- Final application name and package ID.

\- Minimum and target Android SDK.

\- Exact supported recurrence rules for custom habits.

\- Week-start convention and handling of travel across time zones.

\- The precise rule for when an occurrence becomes missed.

\- Whether inventory is tracked in units, doses, or a user-configured quantity.

\- Export format and import/merge behavior.

\- Exact widget technology and its interaction limits.

\- Privacy options for lock-screen notifications.

\- Whether and when to implement synchronization.

\- Whether to create a separate Wear OS module.

\- Localization and first-release language.



Do not block unrelated development on decisions that can safely remain open, but do not silently invent consequential product behavior.

