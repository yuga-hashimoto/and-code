#!/bin/sh
# Prepare the runtime to build Android apps with Gradle (opt-in; costs ~1 GB of disk, plus the
# Gradle cache once a build runs).
#
# Usage:
#   android-build-setup --accept-licenses [--sdk-dir <dir>]
#
# What it does:
#   * seeds $ANDROID_HOME/licenses so the Android Gradle Plugin can install SDK packages itself
#     (this is accepting the Android SDK License: https://developer.android.com/studio/terms -
#     which is why it only runs with --accept-licenses);
#   * on aarch64, installs a statically linked aapt2 (Google ships no arm64 Linux build) and points
#     Gradle at it through android.aapt2FromMavenOverride in ~/.gradle/gradle.properties.
#
# JDK 17 is the one that runs Gradle here: openjdk21 does not start in this runtime, and the
# runtime already exports JAVA_HOME=/usr/lib/jvm/java-17-openjdk.
set -e

SDK_DIR="${ANDROID_HOME:-/root/android-sdk}"
ACCEPT=0
while [ "$#" -gt 0 ]; do
    case "$1" in
        --accept-licenses) ACCEPT=1 ;;
        --sdk-dir)
            [ -n "${2:-}" ] || { echo "usage: android-build-setup --accept-licenses [--sdk-dir <dir>]" >&2; exit 2; }
            SDK_DIR="$2"
            shift
            ;;
        *) echo "usage: android-build-setup --accept-licenses [--sdk-dir <dir>]" >&2; exit 2 ;;
    esac
    shift
done

if [ "$ACCEPT" -ne 1 ]; then
    echo "android-build-setup: this accepts the Android SDK License (https://developer.android.com/studio/terms)." >&2
    echo "Re-run with --accept-licenses to confirm." >&2
    exit 2
fi

# aapt2 build pinned by version and SHA-256, like the other downloads in the runtime manifest.
AAPT2_VERSION="v1.1.0"
AAPT2_URL="https://github.com/ReVanced/aapt2/releases/download/$AAPT2_VERSION/aapt2-arm64-v8a"
AAPT2_SHA256="7e5ae2e1f62fc24cab14072555ffd0a1a7e1ce27e82cc1008967b835b6d8df5b"

mkdir -p "$SDK_DIR/licenses"
printf '%s\n' \
    "8933bad161af4178b1185d1a37fbf41ea5269c55" \
    "d56f5187479451eabf01fb78af6dfcb131a6481e" \
    "24333f8a63b6825ea9c5514f83c2829b004d1fee" \
    "84831b9409646a918e30573bab4c9c91346d8abd" \
    > "$SDK_DIR/licenses/android-sdk-license"
echo "licenses: seeded $SDK_DIR/licenses/android-sdk-license"

case "$(uname -m)" in
    aarch64|arm64)
        AAPT2="$SDK_DIR/aapt2"
        if [ ! -x "$AAPT2" ] || ! echo "$AAPT2_SHA256  $AAPT2" | sha256sum -c - >/dev/null 2>&1; then
            TMP="$AAPT2.download"
            curl -fsSL -o "$TMP" "$AAPT2_URL"
            if ! echo "$AAPT2_SHA256  $TMP" | sha256sum -c - >/dev/null 2>&1; then
                rm -f "$TMP"
                echo "android-build-setup: aapt2 checksum mismatch; refusing to use the download." >&2
                exit 1
            fi
            chmod +x "$TMP"
            mv "$TMP" "$AAPT2"
        fi
        mkdir -p "$HOME/.gradle"
        PROPS="$HOME/.gradle/gradle.properties"
        touch "$PROPS"
        grep -v '^android.aapt2FromMavenOverride=' "$PROPS" > "$PROPS.new" || true
        echo "android.aapt2FromMavenOverride=$AAPT2" >> "$PROPS.new"
        mv "$PROPS.new" "$PROPS"
        echo "aapt2: installed $AAPT2 and set android.aapt2FromMavenOverride"
        ;;
    *)
        echo "aapt2: not needed on $(uname -m); Gradle downloads the stock build."
        ;;
esac

cat <<EOF

Done. In a project:
  export ANDROID_HOME=$SDK_DIR
  echo "sdk.dir=\$ANDROID_HOME" > local.properties
  ./gradlew :app:assembleDebug
Native (NDK) projects are not supported on arm64: Google ships no arm64 Linux NDK.
EOF
