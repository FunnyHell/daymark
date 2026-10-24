\# CLAUDE.md



\## 1. Project overview



Daymark is a local-first Android application for habit tracking and medication management.



The application should provide reliable scheduling, reminders, interaction history, statistics, inventory tracking, and home-screen widgets. It should be usable without an account, a subscription, advertising, or a mandatory cloud service.



The project may later include a companion application for Wear OS and direct synchronization between a phone, tablet, and watch.



\## 2. Source of truth



Before making changes, read the following files in this order:



1\. `CLAUDE.md` — development rules.

2\. `docs/SPEC.md` — functional and non-functional requirements.

3\. `docs/ARCHITECTURE.md` — approved architecture and technical constraints.

4\. `docs/DEVELOPMENT\_STATUS.md` — current milestone and verified project state.

5\. `docs/DECISIONS.md` — accepted technical decisions.



Use `README.md` for project setup and user-facing documentation.



If requirements conflict:

\- Explicit user instructions in the current task take priority.

\- `docs/SPEC.md` defines product requirements.

\- `docs/ARCHITECTURE.md` defines the accepted technical design.

\- `docs/DECISIONS.md` records decisions that clarify or constrain implementation.



If a conflict cannot be resolved confidently, explain it and ask before making a consequential change.



Do not silently remove, weaken, or reinterpret a requirement because it is inconvenient.



\## 3. Working principles



\- Inspect the existing repository before changing it.

\- Determine what is already implemented before creating new files or replacing code.

\- Prefer small, coherent, testable changes over large rewrites.

\- Work on one clearly defined milestone or task at a time.

\- Do not implement unrelated features unless explicitly requested.

\- Do not introduce unnecessary abstractions, dependencies, services, or frameworks.

\- Do not rewrite working code merely to match personal stylistic preferences.

\- Keep business logic independent of Android UI components wherever practical.

\- Prefer explicit, readable, maintainable code.

\- Do not leave placeholder implementations that appear functional but are not.

\- Do not claim that something works unless it has been verified.

\- Never fabricate test results, build results, device behavior, or completed features.

\- If a requirement cannot be completed in the current environment, explain the limitation and leave a clear next step.



\## 4. Before implementation



For every non-trivial task:



1\. Read the relevant requirements and current status.

2\. Inspect the relevant source code and existing tests.

3\. Identify affected modules, data models, migrations, UI, and background behavior.

4\. Identify failure cases and possible regressions.

5\. State a concise implementation plan when the task spans multiple files or subsystems.

6\. Implement the smallest complete solution.

7\. Run relevant tests and build checks.

8\. Review the resulting diff for accidental changes.

9\. Update documentation and development status.



For a small, isolated change, do not waste tokens producing an unnecessarily long plan.



\## 5. Technical baseline



The intended initial stack is:



\- Kotlin.

\- Jetpack Compose.

\- Material 3.

\- AndroidX and Android platform APIs where appropriate.

\- Room for persistent structured data.

\- Kotlin Coroutines and Flow for asynchronous work and reactive data.

\- WorkManager for deferrable background work.

\- AlarmManager only where exact or user-visible scheduling justifies it.

\- DataStore for lightweight application settings.

\- Android home-screen widgets using an appropriate supported implementation.

\- MVVM-style presentation with separate domain and data responsibilities.



Inspect the actual project and current Android tooling before selecting specific dependency versions or changing Gradle configuration.



Do not blindly upgrade dependencies or target SDK versions. Explain compatibility implications before making consequential changes.



The primary initial target is a Samsung Galaxy A52 running Android 12–14 / One UI 5–6. Other Android phones and tablets should be supported where practical.



\## 6. Data integrity and medication safety



Medication records, schedules, intake history, and inventory must be treated as sensitive user data.



\- Never silently delete historical intake events when editing or pausing a schedule.

\- Never silently rewrite past events to match a changed schedule.

\- Record corrections explicitly rather than concealing the original event.

\- Prevent duplicate processing of the same user action or notification action.

\- Use stable identifiers for schedules, medication records, and intake events.

\- Make medication intake and inventory updates transactional where applicable.

\- Ensure that retries do not decrement inventory twice.

\- Do not infer that a medication was taken merely because a reminder appeared.

\- Distinguish taken, skipped, snoozed, missed, and corrected states.

\- Do not present the app as a substitute for medical advice.

\- Do not recommend medication doses or make clinical decisions.



A reminder is not proof of intake. Only an explicit user action or another clearly defined, user-authorized workflow may record intake.



\## 7. Scheduling, notifications, and widgets



\- Treat notifications and widgets as interfaces to the same underlying application state.

\- Route their actions through shared domain logic instead of duplicating business rules.

\- Make notification actions idempotent.

\- Handle repeated taps, stale notifications, retries, process restarts, and outdated widget state.

\- Validate notification actions against the current record and schedule.

\- Do not assume that background execution or exact alarms are unrestricted on every Android device.

\- Handle Android notification permission requirements.

\- Account for reboot, time-zone changes, daylight-saving transitions, and edits to schedules.

\- Use the least intrusive background mechanism that meets the requirement.

\- Clearly document behavior that depends on Android or manufacturer restrictions.

\- Never claim Samsung-specific behavior has been verified without testing on an actual Samsung device.



For widgets, use the simplest supported implementation that satisfies the required interactivity and device compatibility. Do not assume that every widget technology supports identical interactions on every OS version.



\## 8. Architecture and synchronization



The initial application must work offline and without an account.



\- Room is the authoritative local store for persisted application data.

\- UI state must not become the only copy of important data.

\- Keep business rules independent of a specific synchronization provider.

\- Use stable IDs, timestamps, and explicit event semantics for records that may eventually synchronize.

\- Design for idempotent processing and conflict detection.

\- Do not implement phone-to-tablet or phone-to-watch synchronization until the core local application is stable, unless explicitly requested.

\- Do not introduce a cloud backend or account system without approval.

\- Do not assume a phone and tablet share a database automatically.

\- Treat Wear OS as a separate application/module with its own lifecycle and offline behavior if watch support is implemented.



\## 9. Testing requirements



Add tests with the implementation, not as an afterthought.



Prioritize tests for:



\- Habit recurrence and completion rules.

\- Daily, weekly, custom-interval, and quantitative habits.

\- Abstinence habits and their event history.

\- Medication schedules and multiple daily doses.

\- Schedule changes, pauses, and resumes.

\- Duplicate notification actions.

\- Intake event corrections.

\- Inventory changes, package additions, and insufficient stock.

\- Date boundaries, time zones, and daylight-saving transitions.

\- Database migrations and data persistence.

\- Widget and notification actions where automated testing is feasible.



Use unit tests for domain logic and database tests for persistence behavior. Use UI or instrumentation tests where appropriate.



After changes, run the narrowest relevant tests first, followed by broader checks when practical.



At minimum, attempt the available project build and relevant test tasks. Report exactly what was run and whether it passed.



If a build cannot run because the environment lacks the Android SDK, emulator, network access, or another prerequisite, state that explicitly. Do not substitute an assumed success.



\## 10. Documentation and status tracking



At the start of a task, read `docs/DEVELOPMENT\_STATUS.md`.



At the end of every meaningful implementation task, update it with:



\- Current milestone.

\- Features implemented.

\- Features verified by tests or builds.

\- Features implemented but not yet verified on a device.

\- Known issues and blockers.

\- The next concrete task.



Keep statuses precise. Use terms such as:

\- Not started

\- In progress

\- Implemented, not verified

\- Verified by automated tests

\- Verified on physical device

\- Blocked



Do not mark a feature complete merely because code was written.



Update `docs/ARCHITECTURE.md` when the architecture changes. Record consequential decisions in `docs/DECISIONS.md`.



Update `README.md` when setup, supported platforms, build instructions, or user-facing functionality changes.



\## 11. Dependencies, security, and repository hygiene



\- Prefer established AndroidX and Kotlin ecosystem libraries.

\- Avoid adding a dependency for functionality that can be implemented safely with existing tools.

\- Explain why a new dependency is needed.

\- Never commit passwords, API keys, signing keys, private credentials, or personal medication data.

\- Do not disable tests or lint checks merely to make a build pass.

\- Do not suppress warnings without understanding their cause.

\- Keep generated files, local configuration, build output, and IDE-specific personal state out of version control as appropriate.

\- Preserve user changes already present in the repository.

\- Do not run destructive Git operations or overwrite unrelated files without explicit authorization.



\## 12. Scope control



The product vision includes:

\- Habit tracking.

\- Medication scheduling and intake history.

\- Medication inventory tracking.

\- Notifications with actionable controls.

\- Interactive home-screen widgets.

\- Statistics and history.

\- Data export and import.

\- Possible future phone/tablet synchronization.

\- Possible future Wear OS support.



Implement the core Android application first. Do not attempt to deliver every future feature in a single task.



Do not add accounts, advertisements, subscriptions, analytics tracking, a mandatory network connection, or a backend unless the user explicitly changes the product requirements.



\## 13. Completion report



At the end of a task, provide a concise report:



1\. What changed.

2\. Which important files changed.

3\. Which tests and build commands were run, with actual results.

4\. What remains unverified.

5\. Any known issues or decisions requiring user input.

6\. The next recommended task.



Never claim that an APK was installed, a widget works on a device, or a notification survives process termination unless that behavior was actually tested.



\## 14. Initial task



When starting in a new or empty repository:



1\. Inspect the repository and available development tools.

2\. Read all project documents.

3\. Check whether the Android project already exists.

4\. Identify missing prerequisites and build-environment limitations.

5\. Propose a short, ordered implementation plan.

6\. Begin with the smallest useful foundation only when authorized by the task.



Do not generate the entire application in one unreviewed code dump.

