#!/usr/bin/env bash
# Installs the debug APK, starts it, sends it to the background and back, and fails if the app crashed.
# The crash stack is printed so it can be read in the job log.
set -u
PKG=io.github.funnyhell.daymark.debug
ACTIVITY=io.github.funnyhell.daymark.MainActivity
adb shell settings put global hide_error_dialogs 1 || true
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am start -W -n "$PKG/$ACTIVITY"
sleep 15
adb shell input keyevent KEYCODE_HOME
sleep 8
adb shell am start -W -n "$PKG/$ACTIVITY"
sleep 10
adb shell input keyevent KEYCODE_BACK
sleep 5
adb logcat -d -v brief AndroidRuntime:E '*:S' > startup-crash.txt
echo "---- AndroidRuntime errors ----"
cat startup-crash.txt
echo "---- errors from the app process ----"
APP_PID=$(adb shell pidof "$PKG" | tr -d '\r')
if [ -n "$APP_PID" ]; then adb logcat -d -v brief --pid="$APP_PID" '*:E' | tail -n 80; fi
echo "---- app process ----"
adb shell pidof "$PKG" || echo "(not running)"
# Only a crash of this app counts; the emulator's own launcher or system apps sometimes crash on start too.
if grep -qE "Process: ${PKG}(,|\s|$)" startup-crash.txt; then
  echo "FAIL: the app crashed"
  exit 1
fi
echo "Startup check passed"
