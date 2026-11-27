#!/usr/bin/env bash
# CI diagnostic only: shell-UID QEMU is not an app implementation or a passing offline test.
set -euo pipefail
case "$(adb get-serialno)" in emulator-*) ;; *) echo 'A disposable Android emulator is required' >&2; exit 1 ;; esac
payload="${PLAY_QEMU_EXPERIMENT_PAYLOAD:-build/play-qemu-experiment-x86}"
result_dir="${RUNNER_TEMP:-build}"
base="$(adb shell mktemp -d /data/local/tmp/andcode-qemu-ci.XXXXXX | tr -d '\r')"
case "$base" in /data/local/tmp/andcode-qemu-ci.*) ;; *) echo 'Unable to create experiment directory' >&2; exit 1 ;; esac
port=41997
mkdir -p "$result_dir"
server_pid=''
cleanup() {
  if [ -n "$server_pid" ]; then kill "$server_pid" 2>/dev/null || true; wait "$server_pid" 2>/dev/null || true; fi
  remote_pid="$(adb shell cat "$base/server.pid" 2>/dev/null | tr -d '\r' || true)"
  case "$remote_pid" in ''|*[!0-9]*) ;; *) adb shell kill "$remote_pid" 2>/dev/null || true ;; esac
  adb shell rm -rf "$base" 2>/dev/null || true
}
trap cleanup EXIT
adb shell mkdir -p "$base/rootfs" "$base/home"
adb push "$payload/." "$base/" > /dev/null
adb push build/generated/play-runtime-assets/play-runtime/x86_64/alpine.tgz "$base/alpine.tgz" > /dev/null
adb push build/generated/play-runtime-assets/play-runtime/x86_64/opencode.tgz "$base/opencode.tgz" > /dev/null
adb push build/generated/play-apk-cache-assets/play-runtime/x86_64/apk-cache.tgz "$base/apk-cache.tgz" > /dev/null
adb shell "chmod 755 '$base/qemu-x86_64'; tar -xzf '$base/alpine.tgz' -C '$base/rootfs'; tar -xzf '$base/opencode.tgz' -C '$base/rootfs'; chmod 755 '$base/rootfs/opencode'; mkdir -p '$base/rootfs/var/cache/apk' '$base/tmp'; tar -xzf '$base/apk-cache.tgz' -C '$base/rootfs/var/cache/apk'"
cat > "$result_dir/qemu-experiment-install.sh" <<'DEVICE_SCRIPT'
#!/system/bin/sh
set -eu
base=$1
apk_path=$(pm path com.yugahashimoto.andcode.play)
apk_path=${apk_path#package:}
native_dir=${apk_path%/base.apk}/lib/$(getprop ro.product.cpu.abi | sed 's/arm64-v8a/arm64/')
export PROOT_FORCE_FOREIGN_BINARY=1 LD_LIBRARY_PATH="$native_dir:$base/lib" PROOT_LOADER="$native_dir/libopencode_android_proot_loader.so" PROOT_LOADER_32="$native_dir/libopencode_android_proot_loader32.so" PROOT_TMP_DIR="$base/tmp"
"$native_dir/libopencode_android_proot.so" --kill-on-exit --link2symlink -0 -q "$base/qemu-x86_64" -r "$base/rootfs" -b /dev -b /proc -b /sys -w /root /bin/sh -lc '/sbin/apk --no-network --cache-dir /var/cache/apk add bash git curl wget jq openssh-client ripgrep ca-certificates libstdc++ android-tools python3 py3-pillow'
DEVICE_SCRIPT
cat > "$result_dir/qemu-experiment-native-probe.sh" <<'DEVICE_SCRIPT'
#!/system/bin/sh
set -eu
base=$1
apk_path=$(pm path com.yugahashimoto.andcode.play)
apk_path=${apk_path#package:}
native_dir=${apk_path%/base.apk}/lib/$(getprop ro.product.cpu.abi | sed 's/arm64-v8a/arm64/')
export LD_LIBRARY_PATH="$native_dir" PROOT_LOADER="$native_dir/libopencode_android_proot_loader.so" PROOT_LOADER_32="$native_dir/libopencode_android_proot_loader32.so" PROOT_TMP_DIR="$base/tmp"
"$native_dir/libopencode_android_proot.so" -v 9 --kill-on-exit --link2symlink -0 -r "$base/rootfs" -b /dev -b /proc -b /sys -w /root /bin/busybox echo native-proot-ok
DEVICE_SCRIPT
adb push "$result_dir/qemu-experiment-native-probe.sh" "$base/native-probe.sh" > /dev/null
adb shell sh "$base/native-probe.sh" "$base" > "$result_dir/qemu-experiment-native-probe.txt" 2>&1 || true
adb shell "export LD_LIBRARY_PATH='$base/lib'; '$base/qemu-x86_64' -U LD_LIBRARY_PATH -E HOME='$base/home' -E BUN_JSC_useJIT=false -L '$base/rootfs' '$base/rootfs/opencode' --version" > "$result_dir/qemu-experiment-version.txt" 2>&1 || true
adb push "$result_dir/qemu-experiment-install.sh" "$base/install.sh" > /dev/null
adb shell sh "$base/install.sh" "$base" > "$result_dir/qemu-experiment-install.txt" 2>&1
grep -q '^OK:' "$result_dir/qemu-experiment-install.txt"
adb shell "export LD_LIBRARY_PATH='$base/lib'; '$base/qemu-x86_64' -U LD_LIBRARY_PATH -E HOME='$base/home' -E BUN_JSC_useJIT=false -L '$base/rootfs' '$base/rootfs/opencode' --version" > "$result_dir/qemu-experiment-version.txt" 2>&1
grep -Fx '1.19.6' "$result_dir/qemu-experiment-version.txt"
adb shell "echo \$\$ > '$base/server.pid'; export LD_LIBRARY_PATH='$base/lib'; exec '$base/qemu-x86_64' -U LD_LIBRARY_PATH -E HOME='$base/home' -E BUN_JSC_useJIT=false -E OPENCODE_SERVER_PASSWORD=synthetic-test-only -L '$base/rootfs' '$base/rootfs/opencode' serve --hostname 127.0.0.1 --port '$port'" > "$result_dir/qemu-experiment-server.txt" 2>&1 &
server_pid=$!
response=''
for attempt in $(seq 1 60); do
  response="$(adb shell "printf 'GET /global/health HTTP/1.0\\r\\nHost: 127.0.0.1\\r\\nAuthorization: Basic b3BlbmNvZGU6c3ludGhldGljLXRlc3Qtb25seQ==\\r\\n\\r\\n' | timeout 2 nc 127.0.0.1 '$port'" 2>/dev/null || true)"
  if printf '%s' "$response" | grep -q '200 OK'; then break; fi
  if ! kill -0 "$server_pid" 2>/dev/null; then break; fi
  sleep 1
done
printf '%s\n' "$response" > "$result_dir/qemu-experiment-health.txt"
if ! printf '%s' "$response" | grep -q '200 OK'; then echo 'QEMU server health probe failed' >&2; exit 1; fi
sleep 10
if ! kill -0 "$server_pid" 2>/dev/null; then echo 'QEMU server exited after health probe' >&2; exit 1; fi
printf '%s\n' 'QEMU research server answered a health probe and remained running for 10 seconds.'
