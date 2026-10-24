\# Daymark — Architecture



\*\*Status:\*\* Initial architecture proposal  

\*\*Last updated:\*\* Initial project setup



\## 1. Goals



The architecture must support:

\- Reliable local persistence.

\- Testable habit and medication business logic.

\- Multiple interfaces to the same state: app UI, notifications, and widgets.

\- Safe medication intake and inventory updates.

\- Schedule changes without destroying history.

\- Future synchronization without rewriting core domain logic.

\- A possible separate Wear OS companion.



Prefer a small, understandable architecture over premature abstraction.



\## 2. Planned technology



\- Kotlin.

\- Jetpack Compose.

\- Material 3.

\- AndroidX lifecycle and navigation components as needed.

\- Room for structured persistent data.

\- Kotlin Coroutines and Flow.

\- WorkManager for deferrable background work.

\- AlarmManager for justified time-sensitive reminders.

\- DataStore for lightweight preferences.

\- Android widget APIs appropriate to the supported OS versions.

\- JUnit and suitable Android testing libraries.



Select dependency versions based on the actual project, compatible Gradle tooling, and Android SDK. Keep versions consistent and avoid unreviewed upgrades.



\## 3. Logical layers



\### Presentation



Responsibilities:

\- Compose screens and reusable UI components.

\- ViewModels and screen state.

\- User input validation suitable for the UI.

\- Navigation.

\- Display of loading, empty, success, and error states.



The presentation layer must not independently implement medication inventory rules or habit recurrence calculations.



\### Domain



Responsibilities:

\- Habit and medication business rules.

\- Schedule and occurrence calculations.

\- Habit completion and streak calculations.

\- Intake event transitions.

\- Inventory reconciliation rules.

\- Validation of domain operations.

\- Interfaces for repositories or other external capabilities where useful.



Keep this layer as independent of Android UI APIs as practical.



Use cases or domain services should be introduced when they clarify meaningful operations, not mechanically for every trivial function.



\### Data



Responsibilities:

\- Room entities and DAOs.

\- Repository implementations.

\- Database transactions.

\- Mapping between persisted and domain models.

\- Database migrations.

\- DataStore settings access.

\- Backup serialization and validation when implemented.



Room is the authoritative local store for persisted business records.



\### Platform integration



Responsibilities:

\- Notification creation and action handling.

\- Alarm scheduling and cancellation.

\- Boot and relevant time-change handling.

\- Widget refresh and interaction.

\- Android permissions.

\- WorkManager tasks.

\- Future Wear OS and synchronization adapters.



Platform callbacks should delegate meaningful work to shared application/domain logic.



\## 4. Suggested module strategy



Start with one Android application module unless the actual project or build requirements justify a different structure.



Organize packages by feature and responsibility, for example:



```text

app/

&#x20; src/main/java/<package>/

&#x20;   app/

&#x20;     navigation/

&#x20;     theme/

&#x20;   core/

&#x20;     database/

&#x20;     model/

&#x20;     time/

&#x20;     notifications/

&#x20;     widgets/

&#x20;   feature/

&#x20;     habits/

&#x20;       data/

&#x20;       domain/

&#x20;       ui/

&#x20;     medications/

&#x20;       data/

&#x20;       domain/

&#x20;       ui/

&#x20;     statistics/

&#x20;       domain/

&#x20;       ui/

&#x20;     settings/

&#x20;       ui/

```



This is an example, not a requirement to create empty packages or files. Create only the structure needed by implemented features.



Separate modules may be introduced later if there is a concrete benefit, especially for Wear OS. Do not split the project into many Gradle modules prematurely.



\## 5. Data model principles



Use stable identifiers for persisted entities and events.



Represent time deliberately:

\- Use `Instant` for absolute event timestamps where appropriate.

\- Use `LocalDate` for calendar dates where appropriate.

\- Use `LocalTime` for local clock-time schedule configuration where appropriate.

\- Use an explicit time-zone policy for recurrence calculations.



Do not store a daily local reminder only as a UTC timestamp and assume it will always occur at the intended local time.



Recurring schedules and generated occurrences must be distinguishable. The app needs enough information to explain which schedule generated an occurrence and to avoid rewriting the past when that schedule changes.



Prefer explicit state and event records over ambiguous Boolean combinations.



\## 6. Core data concepts



These are logical concepts, not a demand to implement all tables immediately.



\### Habit



Represents a user-defined activity and its configuration.



Potential fields:

\- ID.

\- Name and description.

\- Habit type.

\- Active/paused/archived state.

\- Start and optional end date.

\- Creation and modification timestamps.



\### HabitSchedule



Represents the recurrence configuration and its validity interval.



Potential fields:

\- ID.

\- Habit ID.

\- Recurrence configuration.

\- Effective-from date.

\- Optional effective-until date.

\- Relevant target values.



A schedule change should normally create or update a clearly bounded configuration rather than silently reinterpret old events.



\### HabitEvent



Represents a recorded action, such as completion, progress entry, lapse, or correction.



Potential fields:

\- ID.

\- Habit ID.

\- Relevant schedule or occurrence ID where applicable.

\- Event type.

\- Event timestamp.

\- Quantity and unit where applicable.

\- Correction or reversal reference where needed.



\### Medication



Represents a medication the user tracks.



Potential fields:

\- ID.

\- Display name.

\- Optional formulation/strength text.

\- Inventory tracking configuration.

\- Active/paused/archived state.

\- Creation and modification timestamps.



\### MedicationSchedule



Represents configured medication occurrences and their effective interval.



Potential fields:

\- ID.

\- Medication ID.

\- Recurrence configuration.

\- Dose description or user-configured quantity.

\- Effective-from date.

\- Optional effective-until date.



Do not assume the app can infer clinically correct dose information.



\### MedicationOccurrence



Represents a specific scheduled instance when the implementation needs a stable identity for reminder actions and history.



Potential fields:

\- ID.

\- Schedule ID.

\- Medication ID.

\- Intended local date/time or equivalent schedule context.

\- Resolved due timestamp and time zone as appropriate.

\- Current processing state or a link to the event that resolves it.



Choose whether occurrences are materialized in advance or generated as needed. Document the decision and ensure stale notifications can be rejected safely.



\### MedicationEvent



Represents a recorded intake, skip, correction, or another explicitly defined state transition.



Potential fields:

\- ID.

\- Medication ID.

\- Occurrence ID when applicable.

\- Event type.

\- Event timestamp.

\- Consumed quantity where applicable.

\- Idempotency key or stable action ID.

\- Correction/reversal reference where needed.



A reminder appearing is not a medication event indicating intake.



\### InventoryAdjustment



Represents a manual stock change, package addition, or reconciliation entry.



Potential fields:

\- ID.

\- Medication ID.

\- Quantity delta.

\- Unit.

\- Reason.

\- Timestamp.

\- Reference to the associated intake or correction when applicable.



Avoid maintaining multiple independent stock totals that can silently diverge.



\## 7. Medication event processing



Medication intake is a critical operation.



A successful intake action should:

1\. Identify the medication and scheduled occurrence.

2\. Validate that the occurrence is still eligible for the requested action.

3\. Detect whether the action has already been processed.

4\. Persist the event and associated inventory effect consistently.

5\. Update any derived state.

6\. Trigger UI/widget refresh and notification cleanup as appropriate.



Use a Room transaction when multiple database changes must succeed or fail together.



Idempotency must be based on stable event/occurrence identity and persisted state, not solely on disabling a button.



Repeated or delayed actions should return a safe, consistent result without double-consuming inventory.



Corrections to past events must reconcile the affected stock state. Do not implement a correction by blindly applying another stock decrement or increment.



\## 8. Scheduling and notification architecture



Use a single scheduling component responsible for:

\- Calculating the next relevant occurrences.

\- Scheduling and cancelling platform alarms.

\- Rebuilding pending reminders after reboot or relevant schedule changes.

\- Handling time-zone and wall-clock changes.

\- Respecting permissions and exact-alarm restrictions.

\- Removing or replacing stale reminders.



Keep platform scheduling separate from recurrence calculation.



The notification action handler must:

\- Validate the incoming action.

\- Use stable IDs to identify the occurrence.

\- Call shared domain/data operations.

\- Handle duplicate delivery.

\- Refresh or cancel the affected notification as appropriate.



Do not treat notification delivery as proof that a reminder was seen.



Use WorkManager for deferrable reconciliation or maintenance work. Do not rely on it for exact-clock-time delivery.



Document any platform limitations and verify actual behavior on a physical Samsung device.



\## 9. Habit recurrence and statistics



Keep recurrence and statistics calculations in testable code independent of Compose screens.



Define explicitly:

\- What constitutes a scheduled occurrence.

\- How weekly goals are evaluated.

\- How paused intervals are excluded.

\- How streaks behave after missed occurrences.

\- How schedule changes affect historical calculations.

\- How abstinence lapses affect streaks.

\- How quantitative targets are aggregated.



Do not store a streak as the only authoritative representation of history. Prefer deriving it from schedule definitions and persisted events, or maintaining a cache that can be rebuilt.



\## 10. UI state and persistence



Compose UI should observe state through ViewModels and repositories.



\- Use Flow for observable persistent data where appropriate.

\- Keep transient UI state separate from persistent domain state.

\- Avoid duplicate business logic in widgets, notification handlers, and screens.

\- Treat the database as authoritative after an action completes.

\- Provide clear feedback for failed persistence operations.

\- Avoid blocking the main thread.



Do not create a global mutable singleton as the main data store.



\## 11. Database migrations



\- Use explicit Room migrations for released schema changes.

\- Do not use destructive migrations for user data unless the user has explicitly approved data loss in a disposable development database.

\- Test important migrations.

\- Keep sample/test data separate from real user data.

\- Validate that database changes preserve historical events and stable IDs.



If a development-only reset is needed, make it explicit and never confuse it with production migration behavior.



\## 12. Export and import architecture



Define a versioned export format.



The import pipeline should:

1\. Read the selected file.

2\. Validate format version and required fields.

3\. Validate identifiers, values, and relationships.

4\. Determine how duplicates and existing data will be handled.

5\. Apply changes transactionally where possible.

6\. Report success or failure clearly.



Never partially import data silently.



Treat exported files as sensitive because they may contain medication and personal routine history.



\## 13. Future synchronization



Synchronization is a future capability, not a core-release prerequisite.



Keep the following principles:

\- Stable IDs across devices.

\- Explicit timestamps and event types.

\- Idempotent event processing.

\- A defined deletion strategy.

\- Conflict detection for concurrent edits.

\- Offline queues and retry behavior.

\- Versioned data contracts.



Do not assume last-write-wins is safe for every entity. In particular, intake events and inventory adjustments should not be merged as arbitrary mutable counters.



For phone-to-watch communication, evaluate the Wear OS Data Layer API when a Wear OS companion is implemented. It is intended for supported paired-device communication; it does not provide a general shared database for every Android tablet.



For phone-to-tablet synchronization, evaluate direct local-network or nearby-device communication separately. Do not introduce a cloud backend without explicit approval.



\## 14. Testing architecture



Use:

\- Unit tests for domain rules and recurrence.

\- Repository/database tests for persistence and transactions.

\- Migration tests for schema evolution.

\- ViewModel tests where they cover meaningful state transitions.

\- Instrumentation/UI tests for critical Android integrations where practical.

\- Manual physical-device tests for notification timing, widgets, permissions, reboot, and manufacturer-specific restrictions.



Critical test cases include:

\- Duplicate intake action.

\- Duplicate notification delivery.

\- Intake correction and inventory reconciliation.

\- Schedule edit with existing history.

\- Pause/resume across date boundaries.

\- Time-zone changes.

\- App process termination.

\- Device reboot.

\- Low-stock threshold behavior.

\- Failed import.

\- Widget action against stale state.



\## 15. Architecture change policy



If a different architecture or library would materially improve the implementation:



1\. Describe the current constraint.

2\. Explain the alternative and its trade-offs.

3\. Identify migration and compatibility implications.

4\. Update `docs/DECISIONS.md`.

5\. Update this file when the accepted architecture changes.



Do not rewrite the architecture based on speculation. Prefer evidence from implementation, tests, platform documentation, and actual device behavior.

