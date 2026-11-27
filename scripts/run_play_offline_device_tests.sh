#!/usr/bin/env bash
# Run only on a disposable CI emulator. Installs test APKs and changes network settings.
set -euo pipefail
serial="$(adb get-serialno)"
case "$serial" in emulator-*) ;; *) echo 'A disposable Android emulator is required' >&2; exit 1 ;; esac
result_dir="${RUNNER_TEMP:-build}"
mkdir -p "$result_dir"
collect_logcat() { adb logcat -d > "$result_dir/play-offline-logcat.txt" 2>&1 || true; }
trap collect_logcat EXIT
adb shell getconf PAGE_SIZE | tr -d '\r' | grep -Fx 16384
adb install -r app/build/outputs/apk/play/debug/app-play-debug.apk
adb install -r app/build/outputs/apk/androidTest/play/debug/app-play-debug-androidTest.apk
adb logcat -c
adb shell cmd connectivity airplane-mode enable
adb shell svc wifi disable
adb shell svc data disable
stable=0
for attempt in $(seq 1 60); do
  if adb shell dumpsys connectivity | grep -q 'Active default network: none'; then
    stable=$((stable + 1))
  else
    stable=0
    adb shell svc wifi disable
    adb shell svc data disable
  fi
  if [ "$stable" -ge 10 ]; then break; fi
  sleep 1
done
if [ "$stable" -lt 10 ]; then echo 'No stable offline state' >&2; exit 1; fi
adb shell am instrument -w -e class com.yugahashimoto.andcode.runtime.local.PlayOfflineInstallInstrumentedTest,com.yugahashimoto.andcode.runtime.local.PlayBundledAssetsInstrumentedTest com.yugahashimoto.andcode.play.test/androidx.test.runner.AndroidJUnitRunner | tee "$result_dir/play-offline-instrumentation.txt"
grep -Eq '^OK \(2 tests\)' "$result_dir/play-offline-instrumentation.txt"
