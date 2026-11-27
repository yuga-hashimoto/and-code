# Play Alpine package inventory

This inventory lists packages in the bundled Alpine base and mandatory offline cache.
Versions, declared licenses and aports revisions come from Alpine package metadata.
The linked aports directories contain the matching APKBUILD and packaging files.
They refer to upstream source archives; this inventory alone is not a complete
corresponding-source bundle or a substitute for reviewing each license obligation.

Package archive hashes and signed repository index hashes are recorded in
runtime_tools/play-apk-cache.lock.json in the AndCode source repository.

## abseil-cpp
Packages: abseil-cpp-base 20250814.1-r0, abseil-cpp-city 20250814.1-r0, abseil-cpp-cord 20250814.1-r0, abseil-cpp-cord-internal 20250814.1-r0, abseil-cpp-cordz-functions 20250814.1-r0, abseil-cpp-cordz-handle 20250814.1-r0, abseil-cpp-cordz-info 20250814.1-r0, abseil-cpp-crc-cord-state 20250814.1-r0, abseil-cpp-crc-internal 20250814.1-r0, abseil-cpp-crc32c 20250814.1-r0, abseil-cpp-debugging-internal 20250814.1-r0, abseil-cpp-die-if-null 20250814.1-r0, abseil-cpp-examine-stack 20250814.1-r0, abseil-cpp-exponential-biased 20250814.1-r0, abseil-cpp-hash 20250814.1-r0, abseil-cpp-hashtablez-sampler 20250814.1-r0, abseil-cpp-int128 20250814.1-r0, abseil-cpp-kernel-timeout-internal 20250814.1-r0, abseil-cpp-leak-check 20250814.1-r0, abseil-cpp-log-globals 20250814.1-r0, abseil-cpp-log-initialize 20250814.1-r0, abseil-cpp-log-internal-check-op 20250814.1-r0, abseil-cpp-log-internal-conditions 20250814.1-r0, abseil-cpp-log-internal-format 20250814.1-r0, abseil-cpp-log-internal-globals 20250814.1-r0, abseil-cpp-log-internal-log-sink-set 20250814.1-r0, abseil-cpp-log-internal-message 20250814.1-r0, abseil-cpp-log-internal-nullguard 20250814.1-r0, abseil-cpp-log-internal-proto 20250814.1-r0, abseil-cpp-log-internal-structured-proto 20250814.1-r0, abseil-cpp-log-sink 20250814.1-r0, abseil-cpp-malloc-internal 20250814.1-r0, abseil-cpp-raw-hash-set 20250814.1-r0, abseil-cpp-raw-logging-internal 20250814.1-r0, abseil-cpp-spinlock-wait 20250814.1-r0, abseil-cpp-stacktrace 20250814.1-r0, abseil-cpp-status 20250814.1-r0, abseil-cpp-statusor 20250814.1-r0, abseil-cpp-str-format-internal 20250814.1-r0, abseil-cpp-strerror 20250814.1-r0, abseil-cpp-strings 20250814.1-r0, abseil-cpp-strings-internal 20250814.1-r0, abseil-cpp-symbolize 20250814.1-r0, abseil-cpp-synchronization 20250814.1-r0, abseil-cpp-throw-delegate 20250814.1-r0, abseil-cpp-time 20250814.1-r0, abseil-cpp-time-zone 20250814.1-r0, abseil-cpp-tracing-internal 20250814.1-r0
Architectures: arm64-v8a, x86_64
Declared licenses: Apache-2.0
Alpine build recipe: https://github.com/alpinelinux/aports/tree/68b166903c9439e617b4208635153c7a647dc9f0/main/abseil-cpp

## alpine-base
Packages: alpine-release 3.24.1-r0
Architectures: arm64-v8a, x86_64
Declared licenses: MIT
Alpine build recipe: https://github.com/alpinelinux/aports/tree/0934530484bbcde7498e2c694c710a49616a450e/main/alpine-base

## alpine-baselayout
Packages: alpine-baselayout 3.7.2-r1, alpine-baselayout-data 3.7.2-r1
Architectures: arm64-v8a, x86_64
Declared licenses: GPL-2.0-only
Alpine build recipe: https://github.com/alpinelinux/aports/tree/60a7585bbab2fa0f762504eb617dbca90216e31f/main/alpine-baselayout

## alpine-keys
Packages: alpine-keys 2.6-r0
Architectures: arm64-v8a, x86_64
Declared licenses: MIT
Alpine build recipe: https://github.com/alpinelinux/aports/tree/b9f23becced4d7b3ccc0fa0f28530243ccd314a0/main/alpine-keys

## android-tools
Packages: android-tools 35.0.2-r21, android-tools-adb 35.0.2-r21, android-tools-append2simg 35.0.2-r21, android-tools-avbtool 35.0.2-r21, android-tools-e2fsdroid 35.0.2-r21, android-tools-ext2simg 35.0.2-r21, android-tools-fastboot 35.0.2-r21, android-tools-img2simg 35.0.2-r21, android-tools-lpadd 35.0.2-r21, android-tools-lpdump 35.0.2-r21, android-tools-lpflash 35.0.2-r21, android-tools-lpmake 35.0.2-r21, android-tools-lpunpack 35.0.2-r21, android-tools-make_f2fs 35.0.2-r21, android-tools-mkbootimg 35.0.2-r21, android-tools-mkdtboimg 35.0.2-r21, android-tools-mke2fs.android 35.0.2-r21, android-tools-repack_bootimg 35.0.2-r21, android-tools-simg2img 35.0.2-r21, android-tools-sload_f2fs 35.0.2-r21, android-tools-unpack_bootimg 35.0.2-r21
Architectures: arm64-v8a, x86_64
Declared licenses: Apache-2.0 MIT
Alpine build recipe: https://github.com/alpinelinux/aports/tree/89b983710cf59f6352bca8d55f536bebb2256e4a/community/android-tools

## apk-tools
Packages: apk-tools 3.0.6-r0, libapk 3.0.6-r0
Architectures: arm64-v8a, x86_64
Declared licenses: GPL-2.0-only
Alpine build recipe: https://github.com/alpinelinux/aports/tree/1403ea7a8ae3fb8e5a11207ddb1ff536eb8de912/main/apk-tools

## bash
Packages: bash 5.3.9-r1
Architectures: arm64-v8a, x86_64
Declared licenses: GPL-3.0-or-later
Alpine build recipe: https://github.com/alpinelinux/aports/tree/1522c3193610902d8493f9790a2755c11f21f26d/main/bash

## brotli
Packages: brotli-libs 1.2.1-r0
Architectures: arm64-v8a, x86_64
Declared licenses: MIT
Alpine build recipe: https://github.com/alpinelinux/aports/tree/8313352127992a9d82e2c2024494f6c38a4dffb4/main/brotli

## busybox
Packages: busybox 1.37.0-r31, busybox-binsh 1.37.0-r31, ssl_client 1.37.0-r31
Architectures: arm64-v8a, x86_64
Declared licenses: GPL-2.0-only
Alpine build recipe: https://github.com/alpinelinux/aports/tree/c3ef5d10e6ef6528852c51f0564963e2f8c1be19/main/busybox

## bzip2
Packages: libbz2 1.0.8-r6
Architectures: arm64-v8a, x86_64
Declared licenses: bzip2-1.0.6
Alpine build recipe: https://github.com/alpinelinux/aports/tree/33283848034c9885d984c8e8697c645c57324938/main/bzip2

## c-ares
Packages: c-ares 1.34.9-r0
Architectures: arm64-v8a, x86_64
Declared licenses: MIT
Alpine build recipe: https://github.com/alpinelinux/aports/tree/50f178d7a4c30ea44b54b7d4731ab58558c38b14/main/c-ares

## ca-certificates
Packages: ca-certificates-bundle 20260611-r0
Architectures: arm64-v8a, x86_64
Declared licenses: MPL-2.0 AND MIT
Alpine build recipe: https://github.com/alpinelinux/aports/tree/e41cbd2ae991adfe8df298ba8e8e777e90bd0e03/main/ca-certificates

## ca-certificates
Packages: ca-certificates 20261020-r0, ca-certificates-bundle 20261020-r0
Architectures: arm64-v8a, x86_64
Declared licenses: MPL-2.0 AND MIT
Alpine build recipe: https://github.com/alpinelinux/aports/tree/e78f5987166c6ffbe23f6472e2e8f040298fe2da/main/ca-certificates

## curl
Packages: curl 8.23.0-r0, libcurl 8.23.0-r0
Architectures: arm64-v8a, x86_64
Declared licenses: curl
Alpine build recipe: https://github.com/alpinelinux/aports/tree/c65a7b3f6d6bc4b3c0a8a6fff759d8fd05a167ce/main/curl

## expat
Packages: libexpat 2.11.0-r0
Architectures: arm64-v8a, x86_64
Declared licenses: MIT
Alpine build recipe: https://github.com/alpinelinux/aports/tree/e3335f476c69ffe92817447e265ce2f18323e4ae/main/expat

## fmt
Packages: fmt 11.2.0-r1
Architectures: arm64-v8a, x86_64
Declared licenses: MIT
Alpine build recipe: https://github.com/alpinelinux/aports/tree/b01c471c954a054e703baee3fa242df2cea570c1/main/fmt

## freetype
Packages: freetype 2.14.3-r0
Architectures: arm64-v8a, x86_64
Declared licenses: FTL OR GPL-2.0-or-later
Alpine build recipe: https://github.com/alpinelinux/aports/tree/ad16b0afc1a315c4095a75ff72ebbe3cfa29b671/main/freetype

## gcc
Packages: libgcc 15.2.0-r5, libstdc++ 15.2.0-r5
Architectures: arm64-v8a, x86_64
Declared licenses: GPL-2.0-or-later AND LGPL-2.1-or-later
Alpine build recipe: https://github.com/alpinelinux/aports/tree/423a8ad043d07f2c7546c8ec3e2b0384cda360ae/main/gcc

## gdbm
Packages: gdbm 1.26-r0
Architectures: arm64-v8a, x86_64
Declared licenses: GPL-3.0-or-later
Alpine build recipe: https://github.com/alpinelinux/aports/tree/1ea03990396db618edfcc8a9a0bc6b72662bac8f/main/gdbm

## git
Packages: git 2.54.1-r0, git-init-template 2.54.1-r0
Architectures: arm64-v8a, x86_64
Declared licenses: GPL-2.0-only
Alpine build recipe: https://github.com/alpinelinux/aports/tree/e1bb2d7fd69b2bbed321077da3085b8195abef69/main/git

## jq
Packages: jq 1.8.2-r0
Architectures: arm64-v8a, x86_64
Declared licenses: MIT
Alpine build recipe: https://github.com/alpinelinux/aports/tree/2437351bfa2aecb328f0b859f4a4987476ae31c9/main/jq

## lcms2
Packages: lcms2 2.19-r0
Architectures: arm64-v8a, x86_64
Declared licenses: MIT
Alpine build recipe: https://github.com/alpinelinux/aports/tree/b103184b887bd33a5672484a96704363084b2901/main/lcms2

## libbsd
Packages: libbsd 0.12.2-r0
Architectures: arm64-v8a, x86_64
Declared licenses: BSD-3-Clause
Alpine build recipe: https://github.com/alpinelinux/aports/tree/02365e78d654a7beb23fd35d7d69f4a649009ede/main/libbsd

## libedit
Packages: libedit 20260508.3.1-r1
Architectures: arm64-v8a, x86_64
Declared licenses: BSD-3-Clause
Alpine build recipe: https://github.com/alpinelinux/aports/tree/7c805161e588c028cdee3f19f500849fb2c75d42/main/libedit

## libffi
Packages: libffi 3.8.1-r0
Architectures: arm64-v8a, x86_64
Declared licenses: MIT
Alpine build recipe: https://github.com/alpinelinux/aports/tree/4dd2037b983ed2fd1ae54bd491b165ae0b2f300a/main/libffi

## libidn2
Packages: libidn2 2.3.8-r0
Architectures: arm64-v8a, x86_64
Declared licenses: GPL-2.0-or-later OR LGPL-3.0-or-later
Alpine build recipe: https://github.com/alpinelinux/aports/tree/8240e09aa2fdd8f9979b2016be082273965aff0c/main/libidn2

## libimagequant
Packages: libimagequant 4.2.2-r0
Architectures: arm64-v8a, x86_64
Declared licenses: GPL-3.0-or-later
Alpine build recipe: https://github.com/alpinelinux/aports/tree/4ecf0c93ff3dfb56a98f9c9f501a5923c96544db/community/libimagequant

## libjpeg-turbo
Packages: libjpeg-turbo 3.1.3-r0
Architectures: arm64-v8a, x86_64
Declared licenses: BSD-3-Clause AND IJG AND Zlib
Alpine build recipe: https://github.com/alpinelinux/aports/tree/5fe6bebf850fc964a8525c81548397c39cb6f7cd/main/libjpeg-turbo

## libmd
Packages: libmd 1.2.0-r0
Architectures: arm64-v8a, x86_64
Declared licenses: BSD-3-Clause AND BSD-2-Clause AND ISC AND Beerware AND Public Domain
Alpine build recipe: https://github.com/alpinelinux/aports/tree/87b78bc6890645c317d3167bdbb0187502d0cda9/main/libmd

## libpng
Packages: libpng 1.6.59-r0
Architectures: arm64-v8a, x86_64
Declared licenses: Libpng
Alpine build recipe: https://github.com/alpinelinux/aports/tree/8f222b50cd6e31c83f7dd69ab7c00e7471e11d4b/main/libpng

## libpsl
Packages: libpsl 0.21.5-r3
Architectures: arm64-v8a, x86_64
Declared licenses: MIT
Alpine build recipe: https://github.com/alpinelinux/aports/tree/6bd45b53fb316dc01bda59f5feaf1e127eb64f22/main/libpsl

## libunistring
Packages: libunistring 1.4.2-r0
Architectures: arm64-v8a, x86_64
Declared licenses: GPL-2.0-or-later OR LGPL-3.0-or-later
Alpine build recipe: https://github.com/alpinelinux/aports/tree/30d9f19340385b2cd16eaf1cc6a9a8b30c4afe1c/main/libunistring

## libwebp
Packages: libsharpyuv 1.6.0-r0, libwebp 1.6.0-r0, libwebpdemux 1.6.0-r0, libwebpmux 1.6.0-r0
Architectures: arm64-v8a, x86_64
Declared licenses: BSD-3-Clause
Alpine build recipe: https://github.com/alpinelinux/aports/tree/af8b48fabd19b69b00f5efe192f461896a38cf74/main/libwebp

## libxau
Packages: libxau 1.0.12-r0
Architectures: arm64-v8a, x86_64
Declared licenses: MIT
Alpine build recipe: https://github.com/alpinelinux/aports/tree/6bd45b53fb316dc01bda59f5feaf1e127eb64f22/main/libxau

## libxcb
Packages: libxcb 1.17.0-r2
Architectures: arm64-v8a, x86_64
Declared licenses: MIT
Alpine build recipe: https://github.com/alpinelinux/aports/tree/5e1b822309fb17cc2ebd46d6fbacb1763acbcdd1/main/libxcb

## libxdmcp
Packages: libxdmcp 1.1.5-r1
Architectures: arm64-v8a, x86_64
Declared licenses: MIT
Alpine build recipe: https://github.com/alpinelinux/aports/tree/614e9166706cec427fc3d907b8d981390a42e088/main/libxdmcp

## lz4
Packages: lz4-libs 1.10.0-r1
Architectures: arm64-v8a, x86_64
Declared licenses: BSD-2-Clause AND GPL-2.0-or-later
Alpine build recipe: https://github.com/alpinelinux/aports/tree/1f16962f34234a77fab0f4651459c4381b4a0cd6/main/lz4

## mpdecimal
Packages: mpdecimal 4.0.1-r0
Architectures: arm64-v8a, x86_64
Declared licenses: BSD-2-Clause
Alpine build recipe: https://github.com/alpinelinux/aports/tree/c0519b85456e9b838e3142e1d233aab0b433b476/main/mpdecimal

## musl
Packages: musl 1.2.6-r3
Architectures: arm64-v8a, x86_64
Declared licenses: MIT
Alpine build recipe: https://github.com/alpinelinux/aports/tree/8f79d39391b46d6dbf1800fb68b8212a21d5ee2e/main/musl

## musl
Packages: musl 1.2.6-r2, musl-utils 1.2.6-r2
Architectures: arm64-v8a, x86_64
Declared licenses: MIT; MIT AND BSD-2-Clause AND GPL-2.0-or-later
Alpine build recipe: https://github.com/alpinelinux/aports/tree/f5640d3a10f664c9119720c60515265d3d6f6d01/main/musl

## ncurses
Packages: libncursesw 6.6_p20260516-r0, libpanelw 6.6_p20260516-r0, ncurses-terminfo-base 6.6_p20260516-r0
Architectures: arm64-v8a, x86_64
Declared licenses: X11
Alpine build recipe: https://github.com/alpinelinux/aports/tree/2cee8a7328d061418336ad327b512d96bcd7bc5e/main/ncurses

## nghttp2
Packages: nghttp2-libs 1.70.0-r0
Architectures: arm64-v8a, x86_64
Declared licenses: MIT
Alpine build recipe: https://github.com/alpinelinux/aports/tree/d5a34efc88bbe8d18737fda28125071c4aef8517/main/nghttp2

## oniguruma
Packages: oniguruma 6.9.10-r0
Architectures: arm64-v8a, x86_64
Declared licenses: BSD-2-Clause
Alpine build recipe: https://github.com/alpinelinux/aports/tree/31082e89063dfb6b02af0b38c46af803a118137a/main/oniguruma

## openjpeg
Packages: openjpeg 2.5.4-r2
Architectures: arm64-v8a, x86_64
Declared licenses: BSD-2-Clause
Alpine build recipe: https://github.com/alpinelinux/aports/tree/b6d19fe5cb62ff83b899e224cd2408868fc21e5e/main/openjpeg

## openssh
Packages: openssh-client-common 10.3_p1-r2, openssh-client-default 10.3_p1-r2, openssh-keygen 10.3_p1-r2
Architectures: arm64-v8a, x86_64
Declared licenses: SSH-OpenSSH
Alpine build recipe: https://github.com/alpinelinux/aports/tree/384e25e6bd1f2e7b30095900e48b570d46953510/main/openssh

## openssl
Packages: libcrypto3 3.5.9-r1, libssl3 3.5.9-r1
Architectures: arm64-v8a, x86_64
Declared licenses: Apache-2.0
Alpine build recipe: https://github.com/alpinelinux/aports/tree/0f4c7e8d0f5f7cebbdc2156eca4391080b732bf2/main/openssl

## openssl
Packages: libcrypto3 3.5.7-r0, libssl3 3.5.7-r0
Architectures: arm64-v8a, x86_64
Declared licenses: Apache-2.0
Alpine build recipe: https://github.com/alpinelinux/aports/tree/4a4352d83ab03720965454a8c7b30b1b6a996c84/main/openssl

## pax-utils
Packages: scanelf 1.3.9-r1
Architectures: arm64-v8a, x86_64
Declared licenses: GPL-2.0-only
Alpine build recipe: https://github.com/alpinelinux/aports/tree/c61801eeacb3ffcd9c2025b09e402153bb93fb39/main/pax-utils

## pcre2
Packages: pcre2 10.49-r0
Architectures: arm64-v8a, x86_64
Declared licenses: BSD-3-Clause
Alpine build recipe: https://github.com/alpinelinux/aports/tree/3d497cbb3217749c4fb85f90f3b6f7908b0c1f95/main/pcre2

## protobuf
Packages: libprotobuf 31.1-r1, libprotobuf-lite 31.1-r1, libprotoc 31.1-r1, protobuf 31.1-r1, protoc 31.1-r1
Architectures: arm64-v8a, x86_64
Declared licenses: BSD-3-Clause
Alpine build recipe: https://github.com/alpinelinux/aports/tree/376203ab3c643835e4a1e5e0de31076e8a429b37/main/protobuf

## py3-pillow
Packages: py3-pillow 12.2.0-r0, py3-pillow-pyc 12.2.0-r0
Architectures: arm64-v8a, x86_64
Declared licenses: custom:PIL
Alpine build recipe: https://github.com/alpinelinux/aports/tree/7bfed94effe81d30e64917fec1785667b7d360c6/community/py3-pillow

## python3
Packages: pyc 3.14.10-r2, python3 3.14.10-r2, python3-pyc 3.14.10-r2, python3-pycache-pyc0 3.14.10-r2
Architectures: arm64-v8a, x86_64
Declared licenses: PSF-2.0
Alpine build recipe: https://github.com/alpinelinux/aports/tree/179ee5469b64879728d220ccf91e5cc618fb4bd4/main/python3

## python3-extra
Packages: python3-ndbm 3.14.10-r3
Architectures: arm64-v8a, x86_64
Declared licenses: PSF-2.0
Alpine build recipe: https://github.com/alpinelinux/aports/tree/dc9b0d47c468fee1b0a5af6952afc71525fef748/main/python3-extra

## readline
Packages: readline 8.3.3-r1
Architectures: arm64-v8a, x86_64
Declared licenses: GPL-3.0-or-later
Alpine build recipe: https://github.com/alpinelinux/aports/tree/a854c03acdac188901fb012f7acbee70a36e8041/main/readline

## ripgrep
Packages: ripgrep 15.1.0-r0
Architectures: arm64-v8a, x86_64
Declared licenses: MIT OR Unlicense
Alpine build recipe: https://github.com/alpinelinux/aports/tree/8c9a0c8397086710fcb193a9af64fc0458a1b54b/community/ripgrep

## sqlite
Packages: sqlite-libs 3.53.4-r0
Architectures: arm64-v8a, x86_64
Declared licenses: blessing
Alpine build recipe: https://github.com/alpinelinux/aports/tree/a9c3ba8b9129ec0f294d0a9bf538c2f1632bb016/main/sqlite

## tiff
Packages: tiff 4.7.2-r0
Architectures: arm64-v8a, x86_64
Declared licenses: libtiff
Alpine build recipe: https://github.com/alpinelinux/aports/tree/bbcfbafeadf5dac4744d065ac69338c1b58fc0d1/main/tiff

## wget
Packages: wget 1.25.0-r3
Architectures: arm64-v8a, x86_64
Declared licenses: GPL-3.0-or-later WITH OpenSSL-Exception
Alpine build recipe: https://github.com/alpinelinux/aports/tree/7a02ccbc115c031eddb5836df5ed9bf41fb3bd8a/main/wget

## xz
Packages: xz-libs 5.8.4-r0
Architectures: arm64-v8a, x86_64
Declared licenses: GPL-2.0-or-later AND 0BSD AND Public-Domain AND LGPL-2.1-or-later
Alpine build recipe: https://github.com/alpinelinux/aports/tree/0c088d609f9fe69eedd6b4c7f4b99cdad847c20a/main/xz

## zlib
Packages: zlib 1.4-r0
Architectures: arm64-v8a, x86_64
Declared licenses: Zlib
Alpine build recipe: https://github.com/alpinelinux/aports/tree/bdf501c870a92a05c097857401f69c48c4810384/main/zlib

## zlib
Packages: zlib 1.3.2-r0
Architectures: arm64-v8a, x86_64
Declared licenses: Zlib
Alpine build recipe: https://github.com/alpinelinux/aports/tree/f248b33b5943c7dc69bf691031d7612ab2e8ed93/main/zlib

## zstd
Packages: zstd-libs 1.6.0-r1
Architectures: arm64-v8a, x86_64
Declared licenses: BSD-3-Clause OR GPL-2.0-or-later
Alpine build recipe: https://github.com/alpinelinux/aports/tree/8875de0237da8c118a74218f07ab60adf722e419/main/zstd
