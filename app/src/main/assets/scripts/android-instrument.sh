#!/bin/sh
# Run an Android instrumentation test package on the connected device via ADB.
# Usage: android-instrument <test_package>/<runner_class> [extra am instrument args...]
# Example: android-instrument com.example.app.test/androidx.test.runner.AndroidJUnitRunner
# The test APK must already be installed (build it externally, then `android-app install`).
# Requires: adb connected (see ADB setup in Local runtime settings)
set -e
adb get-state >/dev/null 2>&1 || {
    echo "android: adb has no connected device. Enable wireless debugging and connect via AndCode -> Settings -> Local runtime -> ADB." >&2
    exit 1
}
COMPONENT="${1:-}"
if [ -z "$COMPONENT" ]; then
    echo "usage: android-instrument <test_package>/<runner_class> [extra am instrument args...]" >&2
    exit 2
fi
shift
exec adb shell am instrument -w -r "$COMPONENT" "$@"
