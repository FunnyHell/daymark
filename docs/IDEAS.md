# Ideas for new modules

Ideas, not plans; nothing here is implemented unless docs/DEVELOPMENT_STATUS.md says so. Each fits the module contract in docs/MODULES.md and keeps the app local-first: no account, no server.

## Activity from Samsung Health (Health Connect)

Samsung Health can share its data with **Health Connect**, Android's on-device health store (part of the system on Android 14, an app on older versions; the user turns sharing on in Samsung Health's settings). Daymark can read it from Health Connect with the user's permission, without any cloud service:

- workouts (exercise sessions: type, start, end, often distance and calories), steps, sleep, heart rate, weight;
- a Home section "Activity today" (steps, last workout), a history of workouts, and automatic marks: a habit like "Gym 3× a week" is marked done on days with a strength workout.

What it needs: the `androidx.health.connect:connect-client` library, permission declarations and a screen explaining why the data is read (Health Connect requires one), reading data in the background only when the user allows it. Data is read, not written back. Samsung also has its own Health Data SDK; its conditions were not checked for this document.

## Notes on any mark

Habit marks and slips already have a `note` field in the database (only slips use it in the UI). A note could be attached to any mark, dose or workout: "bench 5×5 60 kg", "took it after lunch". Notes would show in History and be searchable. This is a small core change rather than a module.

## Journal

A short daily entry (mood 1–5, energy, a few words), a Home section for today's entry and a calendar of moods. Pairs well with habits: "days I slept 8 h vs. mood".

## Workouts log

Own records of workouts (exercises, sets, reps, weight, duration, notes), templates ("Leg day"), progress per exercise. Can be filled from Health Connect workouts and completed by hand.

## Water and simple counters

Tap +1 glass, a daily goal, a widget. Mostly covered by "amount" habits already; a module could add one-tap widgets and nicer charts.

## Focus / screen-free timer

A timer for focused work or "no phone" time that counts toward a habit when finished.

## Cycle, symptoms, measurements

Blood pressure, weight, symptoms with severity, linked to medications ("headache 2 h after the dose"). Sensitive health data: stays on the device, export like the rest.

## Wear OS

Mark doses and habits from the watch, see the next reminder; also a "breaks" vibration. Needs a separate Wear OS app (see CLAUDE.md).
