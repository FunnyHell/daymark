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


LAST_DUMP = {"raw": ""}


def dump():
    for _ in range(5):
        LAST_DUMP["raw"] = adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
        xml = adb("shell", "cat", "/sdcard/ui.xml", check=False)
        LAST_DUMP["raw"] += "\n" + xml
        if xml.strip().startswith("<?xml"):
            if "isn't responding" in xml and 'text="Wait"' in xml:
                # Emulator system ANR dialog, unrelated to the app: dismiss it and dump again.
                m = re.search(r'text="Wait"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
                if m:
                    x1, y1, x2, y2 = map(int, m.groups())
                    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                    say("dismissed system ANR dialog")
                    time.sleep(2)
                    continue
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


def swipe_up():
    adb("shell", "input", "swipe", "500", "1500", "500", "700", "300")
    time.sleep(1)


def tap(text, exact=True, bottom=False):
    pos = wait_for(text, exact=exact, bottom=bottom)
    scrolls = 0
    while not pos and scrolls < 4:
        swipe_up()
        scrolls += 1
        pos = wait_for(text, timeout=3, exact=exact, bottom=bottom)
    if not pos:
        shot(f"fail-{re.sub(r'[^A-Za-z0-9]+', '-', text)}")
        with open(os.path.join(OUT, "fail-dump.xml"), "w") as f:
            f.write(LAST_DUMP["raw"])
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


def tap_fab(label):
    """Extended FAB above the navigation bar; falls back to its on-screen position if the dump omits it."""
    pos = wait_for(label, timeout=5)
    if pos:
        adb("shell", "input", "tap", str(pos[0]), str(pos[1]))
        say(f"tapped FAB '{label}' at {pos}")
    else:
        size = re.findall(r"(\d+)x(\d+)", adb("shell", "wm", "size"))[-1]
        w, h = int(size[0]), int(size[1])
        x, y = int(w * 0.8), int(h * 0.84)
        adb("shell", "input", "tap", str(x), str(y))
        say(f"FAB '{label}' not in UI dump; tapped its position ({x}, {y})")
    time.sleep(1.5)


def fill(label, value):
    """Focus a text field by its label (tapping twice, so focus really moves) and type a value."""
    tap(label, exact=False)
    pos = find(label, exact=False)
    if pos:
        adb("shell", "input", "tap", str(pos[0]), str(pos[1]))
        time.sleep(1)
    type_text(value)
    adb("shell", "input", "keyevent", "111")
    time.sleep(1)


def type_text(text):
    adb("shell", "input", "text", text.replace(" ", "%s"))
    time.sleep(1)


adb("logcat", "-c", check=False)
adb("shell", "am", "start", "-W", "-n", f"{PKG}/{ACTIVITY}")
time.sleep(4)
say(f"launched, pid {alive()}")
shot("01-onboarding")
tap("Get started")
# First launch asks for notification permission (Android 13+).
if wait_for("Allow", timeout=8):
    shot("01b-notification-permission")
    tap("Allow")
shot("02-today-empty")

# Create a daily habit.
tap("New habit")
fill("Name", "Morning stretch")
time.sleep(1)
shot("03-habit-editor")
tap("Save")
alive()

# Create a several-times-a-day habit (default target 3).
tap("Habits", bottom=True)
tap_fab("New habit")
fill("Name", "Water breaks")
tap("Several times a day")
tap("Save")
alive()
shot("04-habits-list")

# Create a medication with the default 08:00 dose (taking a dose of today is allowed before its time).
tap("Medications", bottom=True)
tap_fab("New medication")
fill("Name", "Vitamin D")
tap("Track stock")
shot("05-medication-editor")
tap("Save")
alive()
shot("06-medications-list")

tap("Today", bottom=True)
time.sleep(2)
shot("07-today-with-items")
# The circle / ring on the left of a row marks it; tapping again removes the mark.
tap("Mark Vitamin D taken")
shot("08-today-after-taken")
tap("Mark Morning stretch done")
shot("09-today-after-done")
tap("Unmark Morning stretch")
shot("09b-today-after-unmark")
alive()

tap("History", bottom=True)
time.sleep(2)
shot("10-history-habits")
tap("Settings", bottom=True)
time.sleep(1)
shot("11-settings")
alive()

# Test reminder through the real alarm path with the screen off: it must be posted about a minute later.
tap("Test reminder")
adb("shell", "input", "keyevent", "KEYCODE_SLEEP")
say("screen off, waiting for the test reminder")
posted = False
for _ in range(30):
    time.sleep(5)
    if "Test reminder" in adb("shell", "dumpsys", "notification", "--noredact", check=False):
        posted = True
        break
adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
time.sleep(2)
adb("shell", "wm", "dismiss-keyguard", check=False)
time.sleep(1)
shot("12-after-test-reminder")
if not posted:
    say("FAIL: test reminder was not posted within 150 s with the screen off")
    raise SystemExit(1)
say("test reminder posted with the screen off")

# Device sync screen: showing a code starts waiting for another device; cancel stops it.
tap("Sync devices")
time.sleep(2)
shot("13-sync")
tap("Show code")
time.sleep(2)
shot("14-sync-code")
tap("Cancel")
alive()

say("Smoke test passed")
crashes = adb("logcat", "-d", "AndroidRuntime:E", "*:S", check=False)
with open(os.path.join(OUT, "crash-log.txt"), "w") as f:
    f.write(crashes)
if "FATAL EXCEPTION" in crashes:
    say("FAIL: fatal exception in logcat")
    raise SystemExit(1)
