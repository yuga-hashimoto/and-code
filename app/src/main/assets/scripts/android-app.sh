#!/bin/sh
# Manage Android apps on the connected device via ADB (install, launch, stop, logs).
# Usage:
#   android-app install <apk_path>          Install or replace an APK
#   android-app launch <package>            Launch an app's launcher activity
#   android-app stop <package>              Force-stop an app
#   android-app clear <package>             Clear an app's data
#   android-app logcat <package> [lines]    Show recent logcat for a package (default 200)
#   android-app list                        List installed third-party packages
# Requires: adb connected (see ADB setup in Local runtime settings)
set -e
adb get-state >/dev/null 2>&1 || {
    echo "android: adb has no connected device. Enable wireless debugging and connect via AndCode -> Settings -> Local runtime -> ADB." >&2
    exit 1
}
CMD="${1:-}"
[ -n "$CMD" ] && shift
case "$CMD" in
    install)
        [ -n "${1:-}" ] || { echo "usage: android-app install <apk_path>" >&2; exit 2; }
        adb install -r "$1"
        ;;
    launch)
        [ -n "${1:-}" ] || { echo "usage: android-app launch <package>" >&2; exit 2; }
        adb shell monkey -p "$1" -c android.intent.category.LAUNCHER 1
        ;;
    stop)
        [ -n "${1:-}" ] || { echo "usage: android-app stop <package>" >&2; exit 2; }
        adb shell am force-stop "$1"
        ;;
    clear)
        [ -n "${1:-}" ] || { echo "usage: android-app clear <package>" >&2; exit 2; }
        adb shell pm clear "$1"
        ;;
    logcat)
        PKG="${1:-}"
        LINES="${2:-200}"
        [ -n "$PKG" ] || { echo "usage: android-app logcat <package> [lines]" >&2; exit 2; }
        PID="$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)"
        if [ -n "$PID" ]; then
            adb logcat -d -t "$LINES" --pid="$PID"
        else
            adb logcat -d -t "$LINES" | grep -F "$PKG" || true
        fi
        ;;
    list)
        adb shell pm list packages -3 | sed 's/^package://'
        ;;
    *)
        echo "usage: android-app {install|launch|stop|clear|logcat|list} ..." >&2
        exit 2
        ;;
esac
