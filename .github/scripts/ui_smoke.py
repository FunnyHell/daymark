#!/usr/bin/env python3
"""Emulator smoke test: launches the installed debug APK, walks through the main screens with adb input,
and saves screenshots plus a log to the output directory. Fails if the app process dies or a step's
expected text never appears."""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "io.github.funnyhell.daymark.debug"
ACTIVITY = "io.github.funnyhell.daymark.MainActivity"
OUT = sys.argv[1] if len(sys.argv) > 1 else "smoke"
os.makedirs(OUT, exist_ok=True)
log = open(os.path.join(OUT, "smoke-log.txt"), "w")


def say(msg):
    print(msg)
    log.write(msg + "\n")
    log.flush()


def adb(*args, check=True):
    r = subprocess.run(["adb", *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        say(f"adb {' '.join(args)} failed: {r.stderr.strip()}")
    return r.stdout


def dump():
    for _ in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
        xml = adb("shell", "cat", "/sdcard/ui.xml", check=False)
        if xml.strip().startswith("<?xml"):
            try:
                return ET.fromstring(xml[xml.index("<hierarchy"):])
            except (ET.ParseError, ValueError):
                pass
        time.sleep(1)
    return None


def find(text, exact=True, bottom=False):
    """Centre of the first matching node, or of the lowest one on screen when bottom=True (navigation bar)."""
    root = dump()
    if root is None:
        return None
    matches = []
    for node in root.iter("node"):
        t = node.get("text", "") or node.get("content-desc", "")
        if (t == text) if exact else (text.lower() in t.lower()):
            m = re.findall(r"\d+", node.get("bounds", ""))
            if len(m) == 4:
                x1, y1, x2, y2 = map(int, m)
                matches.append(((x1 + x2) // 2, (y1 + y2) // 2))
    if not matches:
        return None
    return max(matches, key=lambda p: p[1]) if bottom else matches[0]


def wait_for(text, timeout=15, exact=True, bottom=False):
    end = time.time() + timeout
    while time.time() < end:
        pos = find(text, exact, bottom)
        if pos:
            return pos
        time.sleep(1)
    return None


def tap(text, exact=True, bottom=False):
    pos = wait_for(text, exact=exact, bottom=bottom)
    if not pos:
        raise SystemExit(f"FAIL: '{text}' not found")
    adb("shell", "input", "tap", str(pos[0]), str(pos[1]))
    say(f"tapped '{text}' at {pos}")
    time.sleep(1.5)


def shot(name):
    path = os.path.join(OUT, f"{name}.png")
    with open(path, "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout)
    say(f"screenshot {path}")


def alive():
    pid = adb("shell", "pidof", PKG, check=False).strip()
    if not pid:
        say("FAIL: app process is not running")
        say(adb("logcat", "-d", "-t", "300", "AndroidRuntime:E", "*:S", check=False))
        raise SystemExit(1)
    return pid


def type_text(text):
    adb("shell", "input", "text", text.replace(" ", "%s"))
    time.sleep(1)


adb("shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS", check=False)
adb("logcat", "-c", check=False)
adb("shell", "am", "start", "-W", "-n", f"{PKG}/{ACTIVITY}")
time.sleep(4)
say(f"launched, pid {alive()}")
shot("01-onboarding")
tap("Get started")
shot("02-today-empty")

# Create a daily habit.
tap("New habit")
tap("Name", exact=False)
type_text("Morning stretch")
adb("shell", "input", "keyevent", "111")  # escape: hide keyboard
time.sleep(1)
shot("03-habit-editor")
tap("Save")
alive()

# Create a quantity habit.
tap("Habits", bottom=True)
tap("New habit")
tap("Name", exact=False)
type_text("Water")
adb("shell", "input", "keyevent", "111")
tap("Amount")
tap("Daily target", exact=False)
type_text("2000")
tap("Unit", exact=False)
type_text("ml")
adb("shell", "input", "keyevent", "111")
tap("Save")
alive()
shot("04-habits-list")

# Create a medication with the default 08:00 dose (taking a dose of today is allowed before its time).
tap("Medications", bottom=True)
tap("New medication")
tap("Medication name", exact=False)
type_text("Vitamin D")
adb("shell", "input", "keyevent", "111")
tap("Track stock")
tap("Current stock", exact=False)
type_text("30")
adb("shell", "input", "keyevent", "111")
shot("05-medication-editor")
tap("Save")
alive()
shot("06-medications-list")

tap("Today", bottom=True)
time.sleep(2)
shot("07-today-with-items")
if wait_for("Taken", timeout=5):
    tap("Taken")
    shot("08-today-after-taken")
if wait_for("Done", timeout=5):
    tap("Done")
    shot("09-today-after-done")
alive()

tap("History", bottom=True)
time.sleep(2)
shot("10-history-habits")
tap("Settings", bottom=True)
time.sleep(1)
shot("11-settings")
alive()

say("Smoke test passed")
crashes = adb("logcat", "-d", "AndroidRuntime:E", "*:S", check=False)
with open(os.path.join(OUT, "crash-log.txt"), "w") as f:
    f.write(crashes)
if "FATAL EXCEPTION" in crashes:
    say("FAIL: fatal exception in logcat")
    raise SystemExit(1)
