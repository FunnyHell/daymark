# Modules

Daymark is built from modules: features the user can turn on or off in **Settings → Modules**. A module that is off disappears from Home, from the bottom bar and from reminders; its data stays. The Home screen is assembled from the sections of the enabled modules, and the user can hide and reorder them (**Edit Home**, the pencil on Home).

Current modules (`app/src/main/java/io/github/funnyhell/daymark/modules/`):

| Module | id | Default | What it adds |
|---|---|---|---|
| Habits | `habits` | on | Habits tab, habit screens, "Habits" section on Home |
| Medications | `medications` | on | Medications tab, medication screens, "Medications" section on Home |
| Interval reminders | `breaks` (id of its first version) | off | Intervals tab, edit screen, Home section, reminders on their own notification channel |

Habits and medications were the whole app before modules existed; their reminders, notifications, widgets, history, backup and sync are still built into the core and are only switched off when the module is off. **Interval reminders** (`modules/intervals/`) is the reference: a small module that uses every part of the contract and nothing else.

## The contract

`module/DaymarkModule.kt`. Everything except `id`, `title`, `description` and `icon` is optional.

| Member | Use |
|---|---|
| `id` | Stable id stored in settings. Never change it after a release. |
| `title`, `description`, `icon` | Shown in Settings → Modules. |
| `defaultEnabled` | On or off before the user chooses. New modules should usually start off. |
| `tab` | A screen that can be a bottom-bar tab (`ModuleTab`: route, label, optional floating button, content). The user picks the tabs (Settings → Bottom bar); screens not in the bar open from Home. |
| `homeSections` | Sections for Home (`HomeSection`). A section returns list items, not a whole screen (see below). |
| `settingsRoute` | A screen linked from Settings → Modules. |
| `screens(nav)` | Registers the module's screens in the navigation graph. Prefix routes with the module id. |
| `reminders` | `ModuleReminders`: `plan()` returns upcoming reminders, `show()` posts one when it is due. Give each reminder a `group`; after a long gap only the latest of each group is shown. Use a notification channel of the module's own, so the user can set its sound. |
| `onAppStart(context)` | Runs at process start for every module (create notification channels here). |
| `onDisabled(context)` | Runs when the user turns the module off (remove notifications). |

### Home sections

Home is one scrolling list. A section implements

```kotlin
@Composable
fun rememberItems(host: HomeHost): LazyListScope.() -> Unit
```

It collects its state in composition (usually from its own ViewModel) and returns the items to add, for example a header and a card. `host.day` is the day shown (Home can show earlier days to fill in marks; `host.isToday` tells which), `host.navigator` opens screens and shows messages. Section ids must be unique; use `"<module id>.<name>"`.

### Reminders

There is one alarm for the whole app (`platform/reminders/ReminderEngine`). It asks each enabled module for `plan(now, zone)` (about two days ahead), arms the alarm for the earliest reminder of all modules, and calls `show()` when one comes due. After a long gap only the latest due reminder of each module is shown. Call `context.container.reminders.reschedule()` whenever something changes the module's plan (settings, an action in a notification). Reminders use alarm-clock alarms, so they arrive with the screen off.

### Storage

- Settings and small state: a DataStore file of the module's own (`preferencesDataStore(name = "module_<id>")`), as `IntervalStore` does (a JSON list via kotlinx.serialization).
- Records the user cares about (anything that belongs in history, backups or sync): Room. That means a new entity and DAO in `data/db`, a migration with a test in `MigrationTest`, export/import in `data/backup` and merging in `data/sync/SyncMerger`. No module does this yet; the habit and medication tables show the patterns (stable ids, events that are corrected instead of deleted).

## Adding a module

1. Create `modules/<id>/` with an `object <Name>Module : DaymarkModule`.
2. Add it to `ModuleRegistry.all` (`module/ModuleRegistry.kt`). The order there is the order in the module list and the bottom bar.
3. Add its strings to `res/values/strings.xml` and `res/values-ru/strings.xml`.
4. Register broadcast receivers (notification actions) in `AndroidManifest.xml`, `android:exported="false"`.
5. Put the rules in plain Kotlin functions without Android types and unit-test them (see `BreakRules`, `IntervalCodec` and their tests).
6. Run `./gradlew testDebugUnitTest lintDebug assembleDebug`. CI also runs the emulator tests and screenshots (`.github/workflows/android.yml`).

`HomeLayoutTest.moduleAndSectionIdsAreUnique` fails if two modules use the same id, section id or tab route.

## What modules cannot do yet

- Add widgets: widgets are registered statically in the manifest; a module widget is added like the existing ones in `platform/widgets`.
- Add their own tab to History or their own part of backups and sync without touching the core (see Storage).
- Be separate Gradle modules. Everything is in the `app` module; the package boundaries (`module/`, `modules/<id>/`) are drawn so that a later split into Gradle modules mostly moves files.
