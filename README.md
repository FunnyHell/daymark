# Daymark

Daymark is a local-first Android application for habit tracking and medication management.

The goal is to combine flexible habit tracking, reliable medication reminders, intake history, inventory management, useful statistics, and interactive home-screen widgets in one application.

The app should remain useful without an account, a subscription, advertisements, or a mandatory cloud service.

> **Project status:** Early development. See [`docs/DEVELOPMENT_STATUS.md`](docs/DEVELOPMENT_STATUS.md) for verified progress.

## Product goals

- Track daily routines and flexible habits.
- Schedule medication reminders and record intake.
- Track medication stock and warn about low supplies.
- Use interactive notifications and home-screen widgets.
- Review streaks, calendars, history, and statistics.
- Pause and resume habits or medication schedules without destroying history.
- Keep personal data stored locally by default.
- Support data export and import.
- Leave room for future phone/tablet synchronization and Wear OS support.

## Target platform

Initial target:
- Android 12–14.
- Samsung Galaxy A52 with One UI 5–6 as the primary physical test device.
- Samsung Galaxy Tab S6 Lite as a potential tablet target.
- Samsung Galaxy Watch 6 Classic as a potential future Wear OS companion.

Other Android devices should be supported where practical.

The actual `minSdk`, `targetSdk`, and dependency versions must be documented once the project configuration is established. Compatibility and notification behavior must be verified rather than assumed.

## Technology stack

Planned baseline:

- Kotlin
- Jetpack Compose
- Material 3
- Room
- Kotlin Coroutines and Flow
- WorkManager
- AlarmManager when justified by scheduling requirements
- DataStore
- Android widget APIs
- Android Studio and Gradle

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for architectural constraints and [`docs/DECISIONS.md`](docs/DECISIONS.md) for accepted technical decisions.

## Documentation

| File | Purpose |
|---|---|
| `CLAUDE.md` | Instructions for Claude Code |
| `docs/SPEC.md` | Product requirements and acceptance criteria |
| `docs/ARCHITECTURE.md` | Application architecture |
| `docs/DEVELOPMENT_STATUS.md` | Current progress and verification state |
| `docs/DECISIONS.md` | Important technical decisions |
| `README.md` | Project overview and setup |

## Development prerequisites

Install and configure:

1. Android Studio with a compatible Android SDK.
2. A compatible JDK for the selected Gradle version.
3. Git.
4. An Android emulator or a physical Android device for testing.

Use the JDK version required by the project's Android Gradle Plugin and Gradle wrapper. Do not assume the latest JDK is automatically compatible.

For physical-device testing, enable Android developer options and USB debugging as appropriate.

## Build and test

Once the Android project and Gradle wrapper exist, run the following commands from the repository root.

On Windows:

```powershell
.\gradlew.bat projects
.\gradlew.bat assembleDebug
.\gradlew.bat test
```

On Linux or macOS:

```bash
./gradlew projects
./gradlew assembleDebug
./gradlew test
```

These are the intended baseline checks, not a claim that they currently pass. Some projects may require additional module-specific tasks, SDK components, device tests, or configuration.

For a particular module, use its actual Gradle task names. Record failed commands and environmental blockers in `docs/DEVELOPMENT_STATUS.md`.

A successful build does not prove that exact alarms, interactive notifications, widget refreshes, or Samsung background behavior work correctly on a physical device.

## Development workflow

1. Read `CLAUDE.md` and the relevant documentation.
2. Check the current milestone in `docs/DEVELOPMENT_STATUS.md`.
3. Implement one small, well-defined task.
4. Add or update tests.
5. Run relevant checks.
6. Review the changes.
7. Update the development status and technical decisions if needed.

The repository should remain the source of truth for the project's code and documentation.

## Data and privacy

The application is designed to be local-first. No account or cloud backend is required for core functionality.

Medication and habit data should not be sent to external services without explicit product approval and a clear user-facing explanation.

Exported backups may contain sensitive information and should be treated accordingly.

## Product name

**Daymark** is a working name only. Verify trademark, package-name, and app-store availability before public release.

## Roadmap

The planned order is:

1. Project foundation and build verification.
2. Local data model and persistence.
3. Habit creation and completion.
4. Medication schedules and intake history.
5. Reliable reminders and actionable notifications.
6. Inventory tracking.
7. Statistics and history.
8. Interactive widgets.
9. Export and import.
10. Physical-device testing and reliability fixes.
11. Tablet adaptation.
12. Optional synchronization and Wear OS companion.

The order may be refined as implementation reveals dependencies. The detailed requirements are in `docs/SPEC.md`.