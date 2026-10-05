#!/usr/bin/env bash
#
# Builds the Android runtime's native payload (proot, libtalloc, libandroid-shmem) from
# source, using the pinned Termux package recipes and the Android NDK, and drops the
# resulting .deb files into one directory per Termux architecture.
#
# The F-Droid build and the GitHub release build both run this so the fdroid APK does not
# ship prebuilt binaries downloaded from a package mirror, and stays reproducible. Local
# developer builds and the CI test job keep using the pinned-mirror path (see
# scripts/prepare_android_runtime_assets.py) and do not need an NDK.
#
# Adapted from gwitko/Conduit's tools/build-local-shell-binaries.sh (same Termux +
# NDK approach); the recipe patches are the ones that environment is known to need.
#
# Inputs (environment):
#   TERMUX_PACKAGES_DIR      An existing termux-packages checkout (an F-Droid srclib).
#                            Cloned at TERMUX_PACKAGES_COMMIT when unset.
#   TERMUX_PACKAGES_COMMIT   Pinned termux-packages commit. Defaults to a commit whose
#                            toolchain still needs NDK r29, matching the recipe.
#   ANDROID_HOME             Android SDK directory (used by the Termux toolchain setup).
#   NDK                      Android NDK r29 directory.
#   TERMUX_TOPDIR            Termux package build root. Defaults to /tmp/andcode-termux-build
#                            for reproducible paths.
#
# Usage: build_android_runtime_native_debs.sh [--output-dir DIR] [--setup] [--no-android-setup]
set -euo pipefail

TERMUX_PACKAGES_COMMIT="${TERMUX_PACKAGES_COMMIT:-2a342d4bd78454dd20760e1fded33939f1712b2d}"
TERMUX_PACKAGES_REPO="${TERMUX_PACKAGES_REPO:-https://github.com/termux/termux-packages.git}"
TERMUX_PACKAGES_DIR="${TERMUX_PACKAGES_DIR:-}"
SETUP_UBUNTU=0
SETUP_ANDROID=0
NO_ANDROID_SETUP=0

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_DIR="$REPO_ROOT/build/termux-debs"

PACKAGES=(libandroid-shmem libtalloc proot)
TERMUX_ARCHES=(aarch64 x86_64)

usage() {
  cat <<'USAGE'
Usage: scripts/build_android_runtime_native_debs.sh [options]

Options:
  --output-dir DIR      Where to write one .deb set per Termux arch (default: build/termux-debs).
  --setup               Run the Termux host (ubuntu) and Android SDK/NDK setup first.
  --no-android-setup    Fail instead of downloading the Android SDK/NDK when NDK is missing.
  -h, --help            Show this help.
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --output-dir) OUTPUT_DIR="$2"; shift 2 ;;
    --setup) SETUP_UBUNTU=1; SETUP_ANDROID=1; shift ;;
    --no-android-setup) NO_ANDROID_SETUP=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

export NDK="${NDK:-$REPO_ROOT/build/android-ndk-r29}"
export ANDROID_HOME="${ANDROID_HOME:-$REPO_ROOT/build/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export TERMUX_TOPDIR="${TERMUX_TOPDIR:-/tmp/andcode-termux-build}"

# build-package.sh changes into the termux-packages checkout before resolving -o, so the
# output directory must be absolute or the .deb files land inside that checkout instead.
case "$OUTPUT_DIR" in
  /*) : ;;
  *) OUTPUT_DIR="$REPO_ROOT/$OUTPUT_DIR" ;;
esac
OUTPUT_DIR="$(realpath -m "$OUTPUT_DIR")"

if [ -z "$TERMUX_PACKAGES_DIR" ]; then
  TERMUX_PACKAGES_DIR="$REPO_ROOT/build/termux-packages"
fi

if [ ! -d "$TERMUX_PACKAGES_DIR/.git" ]; then
  mkdir -p "$(dirname "$TERMUX_PACKAGES_DIR")"
  git clone "$TERMUX_PACKAGES_REPO" "$TERMUX_PACKAGES_DIR"
fi

git -C "$TERMUX_PACKAGES_DIR" fetch --tags --force origin "$TERMUX_PACKAGES_COMMIT" || true
git -C "$TERMUX_PACKAGES_DIR" checkout -f "$TERMUX_PACKAGES_COMMIT"
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-$(git -C "$TERMUX_PACKAGES_DIR" show -s --format=%ct "$TERMUX_PACKAGES_COMMIT")}"
export TZ=UTC
export LC_ALL=C
export LANG=C
export KCONFIG_NOTIMESTAMP=1
export TERMUX_PKG_MAKE_PROCESSES="${TERMUX_PKG_MAKE_PROCESSES:-1}"

termux_env() {
  TERMUX_PKGS__BUILD__REPO_ROOT_DIR="$TERMUX_PACKAGES_DIR" "$@"
}

# The Termux setup scripts and build helpers assume a full interactive Ubuntu; the
# following edits are what make them run unattended here. Each is guarded so a commit
# that no longer contains the pattern is a no-op rather than a failure.
patch_setup_ubuntu() {
  local script="$TERMUX_PACKAGES_DIR/scripts/setup-ubuntu.sh"
  [ -f "$script" ] || return 0
  if command -v apt-cache >/dev/null 2>&1 && ! apt-cache show python3.14-venv >/dev/null 2>&1; then
    sed -i 's/python3\.14-venv/python3-venv/g' "$script"
  fi
  sed -i -E \
    '/LLVM_PACKAGES\+=" (llvm|clang|lld)-\$\{TERMUX_HOST_LLVM_MAJOR_VERSION\}/s/^/# /' \
    "$script" || true
  sed -i -E \
    's/(^|[[:space:]])coreutils-from-uutils([[:space:]]|$)/ /g' \
    "$script" || true
}

patch_buildarch() {
  local script="$TERMUX_PACKAGES_DIR/scripts/build/termux_step_handle_buildarch.sh"
  [ -f "$script" ] || return 0
  perl -0pi -e 's#local TERMUX_ARCH_FILE=/data/TERMUX_ARCH#local TERMUX_ARCH_FILE="\$\{TERMUX_TOPDIR\}/TERMUX_ARCH"\n\tmkdir -p "\$\{TERMUX_TOPDIR\}"#' \
    "$script" || true
}

patch_toolchain() {
  local script="$TERMUX_PACKAGES_DIR/scripts/build/toolchain/termux_setup_toolchain_29.sh"
  [ -f "$script" ] || return 0
  # fuse-overlayfs is not available in every build container; copying the NDK toolchain
  # instead of overlaying it produces the same tree.
  perl -0pi -e 's#if ! mountpoint -q "\$\{TERMUX_STANDALONE_TOOLCHAIN\}"; then\n\t\tfuse-overlayfs \\\n\t\t\t"\$\{TERMUX_STANDALONE_TOOLCHAIN\}" \\\n\t\t\t-o lowerdir="\$\{NDK\}/toolchains/llvm/prebuilt/linux-x86_64" \\\n\t\t\t-o upperdir="\$\{TERMUX_STANDALONE_TOOLCHAIN\}-upper" \\\n\t\t\t-o workdir="\$\{TERMUX_STANDALONE_TOOLCHAIN\}-work"\n\tfi#if [ ! -e "\${TERMUX_STANDALONE_TOOLCHAIN}/bin/clang" ]; then\n\t\tcp -a "\${NDK}/toolchains/llvm/prebuilt/linux-x86_64/." "\${TERMUX_STANDALONE_TOOLCHAIN}/"\n\tfi#' \
    "$script" || true
}

patch_setup_ubuntu
patch_buildarch
patch_toolchain

if [ "$SETUP_UBUNTU" -eq 1 ]; then
  termux_env bash "$TERMUX_PACKAGES_DIR/scripts/setup-ubuntu.sh"
fi

if [ "$SETUP_ANDROID" -eq 1 ]; then
  termux_env bash "$TERMUX_PACKAGES_DIR/scripts/setup-android-sdk.sh"
fi

if [ ! -d "$NDK" ] && [ "$NO_ANDROID_SETUP" -eq 0 ]; then
  echo "Android NDK not found at $NDK; installing the Termux Android SDK/NDK."
  termux_env bash "$TERMUX_PACKAGES_DIR/scripts/setup-android-sdk.sh"
fi

if [ ! -d "$NDK" ]; then
  echo "Android NDK not found at $NDK." >&2
  echo "Provide NDK or omit --no-android-setup." >&2
  exit 1
fi

echo "TERMUX_PACKAGES_DIR=$TERMUX_PACKAGES_DIR"
echo "NDK=$NDK"
echo "ANDROID_HOME=$ANDROID_HOME"

for arch in "${TERMUX_ARCHES[@]}"; do
  arch_output="$OUTPUT_DIR/$arch"
  rm -rf "$arch_output"
  mkdir -p "$arch_output"
  echo "Building ${PACKAGES[*]} for $arch"
  termux_env "$TERMUX_PACKAGES_DIR/build-package.sh" \
    -a "$arch" \
    -F \
    -o "$arch_output" \
    "${PACKAGES[@]}"
  shopt -s nullglob
  debs=("$arch_output"/*.deb)
  if [ "${#debs[@]}" -eq 0 ]; then
    echo "No .deb outputs for $arch in $arch_output" >&2
    exit 1
  fi
  echo "Built for $arch: ${debs[*]##*/}"
done

echo "Native runtime .deb sets written under $OUTPUT_DIR"
