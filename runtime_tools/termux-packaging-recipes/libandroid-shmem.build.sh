# Mirrored verbatim from https://github.com/termux/termux-packages/blob/master/packages/libandroid-shmem/build.sh
# Retrieved: 2026-08-02. Mirrored here (rather than only linked) because the upstream
# 'master' branch is mutable and would otherwise be an unstable reference for the exact
# packaging recipe used to build the binaries pinned by SHA-256 in
# runtime_tools/termux_assets.lock.json.
#
TERMUX_PKG_HOMEPAGE=https://github.com/termux/libandroid-shmem
TERMUX_PKG_DESCRIPTION="System V shared memory emulation on Android using ashmem"
TERMUX_PKG_LICENSE="BSD 3-Clause"
TERMUX_PKG_MAINTAINER="@termux"
TERMUX_PKG_VERSION=0.7
TERMUX_PKG_SRCURL=https://github.com/termux/libandroid-shmem/archive/refs/tags/v${TERMUX_PKG_VERSION}.tar.gz
TERMUX_PKG_SHA256=1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867
TERMUX_PKG_BREAKS="libandroid-shmem-dev"
TERMUX_PKG_REPLACES="libandroid-shmem-dev"
TERMUX_PKG_BUILD_IN_SRC=true
