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

