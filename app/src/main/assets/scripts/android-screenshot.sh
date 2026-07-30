#!/bin/sh
# Capture a screenshot from the connected Android device via ADB.
# Usage: android-screenshot [output_path]
# Requires: adb connected (see ADB setup in Local runtime settings)
set -e
adb get-state >/dev/null 2>&1 || {
    echo "android: adb has no connected device. Enable wireless debugging and connect via AndCode -> Settings -> Local runtime -> ADB." >&2
    exit 1
}
OUTPUT="${1:-/tmp/screenshot.png}"
adb shell screencap -p /sdcard/_oc_screenshot.png
adb pull /sdcard/_oc_screenshot.png "$OUTPUT" >/dev/null 2>&1
adb shell rm /sdcard/_oc_screenshot.png >/dev/null 2>&1
echo "$OUTPUT"
