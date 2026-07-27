#!/bin/sh
# Capture a screenshot from the connected Android device via ADB.
# Usage: android-screenshot [output_path]
# Requires: adb connected (see ADB setup in Local OpenCode settings)
set -e
OUTPUT="${1:-/tmp/screenshot.png}"
adb shell screencap -p /sdcard/_oc_screenshot.png
adb pull /sdcard/_oc_screenshot.png "$OUTPUT" >/dev/null 2>&1
adb shell rm /sdcard/_oc_screenshot.png >/dev/null 2>&1
echo "$OUTPUT"
