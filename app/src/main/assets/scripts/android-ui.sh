#!/bin/sh
# Interact with the Android device via the OpenCode Accessibility Service.
# This provides ADB-free UI automation through a local HTTP server.
#
# Usage:
#   android-ui screen           # Get the current view hierarchy as JSON
#   android-ui tap <x> <y>      # Tap at coordinates
#   android-ui swipe <x1> <y1> <x2> <y2> [duration_ms]
#   android-ui text "hello"     # Type text into the focused field
#   android-ui key back|home|recents
#   android-ui health           # Check if the service is connected
#
# Prerequisites: Enable the OpenCode Accessibility Service in
# Settings > Accessibility.

set -e
HOST="http://127.0.0.1:4098"
CMD="${1:-health}"
shift 2>/dev/null || true

case "$CMD" in
    screen)
        exec curl -s "$HOST/screen"
        ;;
    tap)
        exec curl -s -X POST "$HOST/tap" -H 'Content-Type: application/json' -d "{\"x\":$1,\"y\":$2}"
        ;;
    swipe)
        DUR="${5:-300}"
        exec curl -s -X POST "$HOST/swipe" -H 'Content-Type: application/json' -d "{\"x1\":$1,\"y1\":$2,\"x2\":$3,\"y2\":$4,\"duration\":$DUR}"
        ;;
    text)
        TEXT=$(printf '%s' "$1" | python3 -c 'import sys,json; print(json.dumps(sys.stdin.read()))')
        exec curl -s -X POST "$HOST/text" -H 'Content-Type: application/json' -d "{\"text\":$TEXT}"
        ;;
    key)
        exec curl -s -X POST "$HOST/key" -H 'Content-Type: application/json' -d "{\"key\":\"$1\"}"
        ;;
    health)
        exec curl -s "$HOST/health"
        ;;
    *)
        echo "Usage: android-ui [screen|tap|swipe|text|key|health] [args...]"
        exit 1
        ;;
esac
