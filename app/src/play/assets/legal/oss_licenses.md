# Third-Party Notices

AndCode includes, depends on, or downloads and runs the third-party software listed below. **This
license inventory covers those third-party components only — it is separate from, and not covered
by, AndCode's own [MIT License](LICENSE), which applies solely to this repository's Kotlin/Android
source code.** See [TRADEMARKS.md](TRADEMARKS.md) for trademark notices.

## Bundled native runtime components (PRoot / Termux-derived)

These binaries reach the APK one of two ways. The **github** flavor downloads them from the official
Termux package mirror at build time (pinned by package version and SHA-256 hash of the compiled
`.deb` in [`runtime_tools/termux_assets.lock.json`](runtime_tools/termux_assets.lock.json)). The
**fdroid** flavor compiles the same three Termux packages from source with the Android NDK, from the
pinned `termux-packages` commit declared by the fdroiddata recipe's `srclibs:` (`TermuxPackages`).
Either way they are packaged into the APK so the on-device Linux runtime can start, and AndCode does
not patch or modify these packages beyond what Termux's own packaging performs. **Full license text
for each of GPL-2.0, GPL-3.0 (which LGPL-3.0 incorporates by reference), LGPL-3.0, and the
BSD-3-Clause text used by `libandroid-shmem` is bundled verbatim, unmodified, in
[`THIRD_PARTY_LICENSES/`](THIRD_PARTY_LICENSES/) in this repository and at
`assets/legal/licenses/*.txt` inside the shipped APK** — not just linked.

| Package | Version | License (verified against upstream source, not guessed) | Copyright | Corresponding source (content-addressed) |
|---|---|---|---|---|
| `proot` | 5.1.107.102 | [GPL-2.0](THIRD_PARTY_LICENSES/GPL-2.0.txt), per [`TERMUX_PKG_LICENSE`](runtime_tools/termux-packaging-recipes/proot.build.sh) | The PRoot contributors | Upstream source archive `v5.1.107.102.zip`, SHA-256 `30d12a6e2cc4147123756ff58edea262d3083bbc238c12d0ab9b39e3d1c40852` (from [github.com/termux/proot](https://github.com/termux/proot/archive/v5.1.107.102.zip), tagged release, not `master`) |
| `libandroid-shmem` | 0.7 | [BSD-3-Clause](THIRD_PARTY_LICENSES/BSD-3-Clause-libandroid-shmem.txt), per [`TERMUX_PKG_LICENSE`](runtime_tools/termux-packaging-recipes/libandroid-shmem.build.sh) and the project's own `LICENSE` file | Copyright (c) 2013 Sergii Pylypenko; Copyright (c) 2017 Fredrik Fornwall | Upstream source archive `v0.7.tar.gz`, SHA-256 `1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867` (from [github.com/termux/libandroid-shmem](https://github.com/termux/libandroid-shmem/archive/refs/tags/v0.7.tar.gz), tagged release, not `master`) |
| `libtalloc` | 2.5.0 | [LGPL-3.0-or-later](THIRD_PARTY_LICENSES/LGPL-3.0.txt) for the actual runtime library. Termux's own packaging metadata tags the *package* `GPL-3.0` (a coarser, package-level tag), but the shared library source itself (`talloc.c`/`talloc.h`, the only files that become `libtalloc.a`/`libtalloc.so`) carries its own header: *"the following LGPL license applies to the talloc library. This does NOT imply that all of Samba is released under the LGPL"* — version 3 or later. Resolved; no longer `REQUIRES_LICENSE_REVIEW`. | Copyright (C) Andrew Tridgell 2004; Copyright (C) Stefan Metzmacher 2006 | Upstream source archive `talloc-2.5.0.tar.gz`, SHA-256 `912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007` (from [samba.org/ftp/talloc](https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz), versioned release path, not a mutable branch) |

The fdroid flavor compiles all three from the pinned `TermuxPackages` commit, which can lag the
mirror by a few versions (currently `proot` 5.1.107.94, `libtalloc` 2.4.3, `libandroid-shmem` 0.7).

**Packaging recipes, mirrored (not just linked):** the exact `TERMUX_PKG_*` build recipe used for
each package above — the thing that actually produces the `.deb` pinned by hash in
`termux_assets.lock.json` — is copied verbatim into
[`runtime_tools/termux-packaging-recipes/`](runtime_tools/termux-packaging-recipes/) in this
repository, as retrieved from `termux/termux-packages` (the retrieval date, and for the recipes
refreshed here the exact commit, are recorded in each file's header). This exists specifically so
the corresponding-source reference does not depend on the upstream `termux-packages` repository's
mutable `master` branch continuing to show the same content in the future; the recipe as it existed
at the time these exact binaries were built is preserved here.

**Source availability — what is provided today, and what is `REQUIRES_LICENSE_REVIEW`:**

- *Provided:* the exact, versioned, content-addressed upstream source archive for each package
  (verifiable by the SHA-256 shown, independent of any branch or tag being later force-moved), and
  the exact `TERMUX_PKG_*` recipe file for each package, mirrored as retrieved from
  `termux/termux-packages` (see each file's header).
- *Not yet provided, and not claimed to be complete:* the recipe files above are not self-contained.
  They call into the broader `termux-packages` **build framework** — `termux_step_*` helper
  functions, environment set up by the framework's own scripts, the `termux-chroot` template file
  `proot`'s recipe references, and the cross-compilation toolchain — none of which is vendored into
  this repository. Nor has this project independently confirmed that the `termux-packages` `master`
  commit these recipes were retrieved from is the *exact* commit that produced the
  specific `.deb` binaries pinned by SHA-256 in `termux_assets.lock.json` (Termux does not publish a
  per-package-build commit pin in the `.deb` itself, and this project has not yet cross-referenced
  `termux-packages`' commit history against the pinned hashes to establish that link).

**Because of the gaps above, this project does not assert that the corresponding-source obligation
for `proot`, `libandroid-shmem`, or `libtalloc` under GPLv2 §3(a)/(b) or LGPLv3 is fully satisfied
yet — this is flagged `REQUIRES_LICENSE_REVIEW`, not resolved.** Until it is fully resolved by either
(a) identifying and pinning the exact `termux-packages` commit and vendoring the complete build
framework/toolchain instructions needed to reproduce the pinned binaries, or (b) rebuilding these
three packages from source under this project's own pinned, fully self-contained build process, the
following stands as a GPLv2 §3(b)-style written offer for these three packages specifically: **on
request (open an issue at [yuga-hashimoto/and-code](https://github.com/yuga-hashimoto/and-code/issues)),
for at least three years from the release you obtained, this project will provide, at no more than
the cost of physically performing the distribution, a complete machine-readable copy of the
corresponding source it is able to identify or reconstruct for the pinned `proot`, `libandroid-shmem`,
and `libtalloc` binaries in that release.**

## Coding agent binaries downloaded and run on-device

Not bundled in the APK — downloaded from the official distribution channel at first setup/update
and verified by checksum before use. See
[docs/AUTHENTICATION_AND_DATA_FLOW.md](docs/AUTHENTICATION_AND_DATA_FLOW.md) for how each is
installed and run.

| Component | Source | License | Notes |
|---|---|---|---|
| OpenCode | `github.com/anomalyco/opencode` releases (musl Linux binary; URL/version pinned in [`app/src/main/assets/local-runtime-manifest.json`](app/src/main/assets/local-runtime-manifest.json)) | [MIT](THIRD_PARTY_LICENSES/MIT-OpenCode.txt), copyright (c) 2025 opencode | The experimental Play build includes the pinned unmodified release archive; AndCode is independent of the OpenCode project. Audit embedded dependency notices before distribution |
| Claude Code | `downloads.claude.ai/claude-code/apk/latest` (Anthropic's official Alpine package repository, signature-verified) | Proprietary; governed by Anthropic's own Claude Code terms | Official CLI, unmodified; AndCode does not fork or re-host it |
| Google Antigravity CLI | `github.com/google-antigravity/antigravity-cli` releases (newest release resolved at install time; offline fallback pinned in `AntigravityManifest.kt`, currently 1.5.13) | Proprietary; governed by Google Antigravity's own terms | Official CLI, unmodified; AndCode does not fork or re-host it |

## Base Linux root filesystems

| Component | Source | License | Notes |
|---|---|---|---|
| Alpine Linux minirootfs | `dl-cdn.alpinelinux.org` (version pinned in [`local-runtime-manifest.json`](app/src/main/assets/local-runtime-manifest.json), currently 3.24.1) | Each Alpine package keeps its own upstream license (mix of MIT, BSD, GPL, and others); see [alpinelinux.org](https://alpinelinux.org/) and each package's `APKINDEX`/`.PKGINFO` metadata | Used as the rootfs for OpenCode and Claude Code; the experimental Play build includes the unmodified archives as app assets |
| Alpine mandatory runtime packages | Signed Alpine repository indexes and package archives pinned in [`runtime_tools/play-apk-cache.lock.json`](runtime_tools/play-apk-cache.lock.json) | Per-package license metadata and exact Alpine build recipes are listed in the [Play Alpine inventory](docs/play-store/ALPINE_PACKAGE_INVENTORY.md) | The experimental Play build includes these packages for offline setup. The inventory is not a complete corresponding-source distribution; source and notice obligations must be completed before publishing |
| Debian Bookworm rootfs (bootstrap + packages) | Official Debian mirrors (`deb.debian.org`, `security.debian.org`), fetched via `apt` at setup time — see `DebianRootfsInstaller.kt` | Each Debian package keeps its own upstream license (mix of GPL, LGPL, MIT, BSD, and others); see [debian.org/legal](https://www.debian.org/legal/) | Used only for the Antigravity CLI, which requires glibc |

## Gradle / Android dependencies

The `releaseRuntimeClasspath` Gradle configuration — everything actually resolved into a release
build, direct **and** transitive — has ~190 distinct artifacts as of this writing. Rather than a
hand-maintained table (which drifted from reality before: it previously listed Gson as a direct
dependency, which it isn't, and omitted `org.tukaani:xz`, which is actually resolved), the authoritative, generated list is committed at
[`THIRD_PARTY_LICENSES/release-dependencies-releaseRuntimeClasspath.txt`](THIRD_PARTY_LICENSES/release-dependencies-releaseRuntimeClasspath.txt).
Regenerate it with:

```bash
./scripts/generate_dependency_report.sh
```

and diff the result before releasing if `app/build.gradle.kts` changed. The table below is a
**curated summary of the most relevant/highest-profile entries** from that generated list, not a
claim that it is exhaustive — consult the generated file for the complete set.

| Dependency | Version | License |
|---|---|---|
| AndroidX Core, Lifecycle, Activity Compose, Navigation Compose, Compose BOM, Security Crypto, DocumentFile, Room, Startup, Profileinstaller, DataStore | `core-ktx:1.15.0`, `lifecycle:2.8.7`, `activity-compose:1.9.3`, `navigation-compose:2.8.5`, `compose-bom:2024.12.01`, `security-crypto:1.1.0-alpha06`, `documentfile:1.0.1`, `room:2.6.1`, `datastore:1.1.7` | Apache License 2.0 — [developer.android.com/jetpack](https://developer.android.com/jetpack) |
| OkHttp / OkHttp-SSE / Okio | `okhttp:4.12.0`, `okio:3.4.0`/`3.6.0` | Apache License 2.0 — [square.github.io/okhttp](https://square.github.io/okhttp/) |
| kotlinx.serialization (JSON) | `1.7.3` | Apache License 2.0 — [github.com/Kotlin/kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) |
| ZXing (`com.journeyapps:zxing-android-embedded`, `com.google.zxing:core`) | `zxing-android-embedded:4.3.0`, `core:3.4.1` | Apache License 2.0 — [github.com/journeyapps/zxing-android-embedded](https://github.com/journeyapps/zxing-android-embedded), [github.com/zxing/zxing](https://github.com/zxing/zxing) |
| Apache Commons Compress, Commons Codec, Commons IO, Commons Lang3 | `commons-compress:1.27.1`, `commons-codec:1.17.1`, `commons-io:2.16.1`, `commons-lang3:3.16.0` | Apache License 2.0 — [commons.apache.org](https://commons.apache.org/) |
| **`org.tukaani:xz`** (used by `commons-compress` for `.xz` archive support) | `1.9` | Public-domain-style permissive license ("Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby granted"), per the project's own `COPYING` file — [github.com/tukaani-project/xz-java](https://github.com/tukaani-project/xz-java) |
| Kotlin stdlib / Kotlin Gradle plugins | `2.0.21` | Apache License 2.0 — [kotlinlang.org](https://kotlinlang.org/) |
| kotlinx.coroutines (Android, core, Play Services interop) | `1.9.0` | Apache License 2.0 — [github.com/Kotlin/kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| Koin (`koin-android`, `koin-androidx-compose`, `koin-core`) | `4.0.1` | Apache License 2.0 — [insert-koin.io](https://insert-koin.io/) |
| **`com.alphacephei:vosk-android`** (wake-word speech recognition; bundles Kaldi) | `0.3.75` | Apache License 2.0 — [github.com/alphacep/vosk-api](https://github.com/alphacep/vosk-api) |
| Google Play Services (`play-services-basement`, `play-services-tasks`), Firebase Android SDK (BOM + Crashlytics/Installations/DataTransport client libraries), Google Tink, `com.google.android.odml:image`, Guava `listenablefuture`, Gson (transitive via Play Services) | `firebase-bom:34.17.0` and related, `tink-android:1.8.0`, `gson:2.8.9` | Apache License 2.0 for these client SDKs; the **Firebase Crashlytics service** they talk to is a proprietary Google service governed by the [Firebase Terms of Service](https://firebase.google.com/terms) (see [THIRD_PARTY_SERVICES.md](THIRD_PARTY_SERVICES.md)) |
| AndroidX Test / Espresso, JUnit 4, MockWebServer, `kotlinx-coroutines-test` (test only, not shipped in a release APK) | various | Apache License 2.0 (AndroidX Test/Espresso, MockWebServer, coroutines-test) / Eclipse Public License 1.0 (JUnit 4 — [junit.org/junit4](https://junit.org/junit4/)) |

Apache License 2.0's full text is bundled at
[`THIRD_PARTY_LICENSES/Apache-2.0.txt`](THIRD_PARTY_LICENSES/Apache-2.0.txt) and
`assets/legal/licenses/Apache-2.0.txt` inside the APK, covering the dependencies above.

**NOTICE files are not preserved in the built APK and must be aggregated separately.** An earlier
version of this document claimed that Gradle keeps each dependency's `META-INF/NOTICE` file intact
in the final package; that was checked against the actual `and-code-debug` CI artifact and found to
be false. AGP's resource merging deduplicates files that collide under `META-INF/NOTICE*` across
dependencies by keeping a single, arbitrarily-chosen copy (a "pick first" rule, not per-artifact
preservation) — inspecting the built APK showed only `okhttp3/internal/publicsuffix/NOTICE`
survived; the Apache Commons artifacts' own `NOTICE` files did not make it into the APK at all.

The actual NOTICE text for every `releaseRuntimeClasspath` artifact that ships one is aggregated,
straight out of the dependency archives (not the built APK), at
[`THIRD_PARTY_LICENSES/NOTICE-aggregate.txt`](THIRD_PARTY_LICENSES/NOTICE-aggregate.txt) and
`assets/legal/notice_aggregate.md` inside the APK, reachable from the in-app Legal screen. As of
this writing that is `commons-codec`, `commons-io`, `commons-compress`, and `commons-lang3` — all
four are the standard "Copyright The Apache Software Foundation" boilerplate NOTICE, not a notice
of any modification. Regenerate it after any dependency change with:

```bash
./scripts/generate_notice_aggregate.sh
```

and re-copy the result into `app/src/main/assets/legal/notice_aggregate.md` before releasing (a
test enforces the two stay byte-for-byte identical). An artifact absent from the aggregate is not
a claim that it ships no NOTICE file — only that none of the standard `NOTICE`/`NOTICE.txt` entry
names were found at the top level of its jar/aar or its nested `classes.jar`.

## Bundled fonts

| Font | Version | License |
|---|---|---|
| JetBrains Mono (NL, Regular and Bold), used for code, diffs and the terminal | `2.304` | SIL Open Font License 1.1 — [github.com/JetBrains/JetBrainsMono](https://github.com/JetBrains/JetBrainsMono) |

The font files ship unmodified at `res/font/jetbrains_mono_*.ttf`. The OFL text is bundled at
[`THIRD_PARTY_LICENSES/OFL-1.1-JetBrainsMono.txt`](THIRD_PARTY_LICENSES/OFL-1.1-JetBrainsMono.txt)
and `assets/legal/licenses/OFL-1.1-JetBrainsMono.txt` inside the APK.

## Wake-word speech model (downloaded at runtime, not bundled)

The on-device wake word is recognised with [Vosk](https://github.com/alphacep/vosk-api)
(`com.alphacephei:vosk-android`), which is **Apache License 2.0** — full text bundled at
[`THIRD_PARTY_LICENSES/Apache-2.0.txt`](THIRD_PARTY_LICENSES/Apache-2.0.txt). Vosk in turn builds on
[Kaldi](https://github.com/kaldi-asr/kaldi), also Apache-2.0.

The speech model Vosk loads is **not part of the APK**. It is downloaded from
[alphacephei.com/vosk/models](https://alphacephei.com/vosk/models) the first time the wake word is
switched on, into the app's private storage, and can be removed again from voice settings. The
models offered are:

| Model | Download | License |
|---|---|---|
| `vosk-model-small-en-us-0.15` (English, ~40 MB) | [alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip](https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip) | Apache License 2.0, per the model list published by the Vosk project |
| `vosk-model-small-ja-0.22` (Japanese, ~48 MB) | [alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip](https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip) | Apache License 2.0, per the model list published by the Vosk project |

No hash is pinned for these files because they are fetched from the vendor at runtime rather than
redistributed by this project; the archive is validated structurally (it must unpack to the
expected model directory) before it is used, and is discarded if it does not.

Because nothing is redistributed and both the library and the models are Apache-2.0, the wake-word
feature carries no `REQUIRES_LICENSE_REVIEW` flag. Downloading is subject to whatever terms the
Vosk project applies to its own hosting.

**This replaces the previous openWakeWord implementation**, whose three bundled `.tflite` model
files were licensed CC BY-NC-SA 4.0 (Attribution-**NonCommercial**-ShareAlike) and were flagged
`REQUIRES_LICENSE_REVIEW` here, because this project could not determine which downstream uses
would qualify as NonCommercial. Those files and the `org.tensorflow:tensorflow-lite` dependency
that ran them have been removed, so that question no longer arises.

## Generating a full SBOM

For a machine-readable inventory of every resolved Gradle dependency (including transitive ones not
itemized above), see [`scripts/generate_dependency_report.sh`](scripts/generate_dependency_report.sh)
and its committed output at
[`THIRD_PARTY_LICENSES/release-dependencies-releaseRuntimeClasspath.txt`](THIRD_PARTY_LICENSES/release-dependencies-releaseRuntimeClasspath.txt).

Optional: use [Syft](https://github.com/anchore/syft) or the Gradle CycloneDX plugin in CI for
SPDX/CycloneDX output.

## In-app access

This file, along with [PRIVACY.md](PRIVACY.md), [TERMS.md](TERMS.md),
[THIRD_PARTY_SERVICES.md](THIRD_PARTY_SERVICES.md), [TRADEMARKS.md](TRADEMARKS.md), and
[docs/AUTHENTICATION_AND_DATA_FLOW.md](docs/AUTHENTICATION_AND_DATA_FLOW.md), is bundled into the
APK as an asset and reachable offline from **Settings → Legal & Privacy → Open-source Licenses**.
The full GPL-2.0, GPL-3.0, LGPL-3.0, BSD-3-Clause, and OFL-1.1 license texts referenced above are bundled at
`assets/legal/licenses/*.txt` inside the same APK (extractable with e.g. `unzip -p app.apk
assets/legal/licenses/GPL-2.0.txt`), and at [`THIRD_PARTY_LICENSES/`](THIRD_PARTY_LICENSES/) in this
source repository.

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

# Bun 1.4.4 and JavaScriptCore

The Play-bundled OpenCode binary identifies Bun 1.4.4. Bun documents a statically linked
JavaScriptCore component; the notices below reproduce its upstream license information.
Full corresponding-source and relinking obligations remain under review.

## Bun upstream license information

Bun itself is MIT-licensed.

## JavaScriptCore

Bun statically links JavaScriptCore (and WebKit) which is LGPL-2 licensed. WebCore files from WebKit are also licensed under LGPL2. Per LGPL2:

> (1) If you statically link against an LGPL’d library, you must also provide your application in an object (not necessarily source) format, so that a user has the opportunity to modify the library and relink the application.

You can find the patched version of WebKit used by Bun here: <https://github.com/oven-sh/webkit>. If you would like to relink Bun with changes:

- `git clone https://github.com/oven-sh/WebKit vendor/WebKit`
- `bun sync-webkit-source` (checks out the version pinned in `WEBKIT_VERSION` in `scripts/build/deps/webkit.ts`)
- `bun run build:local`

This compiles JavaScriptCore, compiles Bun’s `.cpp` bindings for JavaScriptCore (which are the object files using JavaScriptCore) and outputs a new `bun` binary with your changes.

## Linked libraries

Bun statically links these libraries:

| Library | License |
|---------|---------|
| [`boringssl`](https://boringssl.googlesource.com/boringssl/) | [several licenses](https://boringssl.googlesource.com/boringssl/+/refs/heads/master/LICENSE) |
| [`brotli`](https://github.com/google/brotli) | MIT |
| [`libarchive`](https://github.com/libarchive/libarchive) | [several licenses](https://github.com/libarchive/libarchive/blob/master/COPYING) |
| [`lol-html`](https://github.com/cloudflare/lol-html/tree/master/c-api) | BSD 3-Clause |
| [`ls-hpack`](https://github.com/litespeedtech/ls-hpack) | MIT |
| [`ls-qpack`](https://github.com/litespeedtech/ls-qpack) | MIT |
| [`lsquic`](https://github.com/litespeedtech/lsquic) | MIT (portions derived from [Chromium proto-quic](https://github.com/litespeedtech/lsquic/blob/master/LICENSE.chrome), BSD 3-Clause) |
| [`mimalloc`](https://github.com/microsoft/mimalloc) | MIT |
| [`picohttp`](https://github.com/h2o/picohttpparser) | dual-licensed under the Perl License or the MIT License |
| [`zstd`](https://github.com/facebook/zstd) | dual-licensed under the BSD License or GPLv2 license |
| [`simdutf`](https://github.com/simdutf/simdutf) | Apache 2.0 |
| [`tinycc`](https://github.com/tinycc/tinycc) | LGPL v2.1 |
| [`uSockets`](https://github.com/uNetworking/uSockets) | Apache 2.0 |
| [`zlib-ng`](https://github.com/zlib-ng/zlib-ng) | zlib |
| [`c-ares`](https://github.com/c-ares/c-ares) | MIT licensed |
| [`libicu`](https://github.com/unicode-org/icu) 78 | [license here](https://github.com/unicode-org/icu/blob/main/icu4c/LICENSE) |
| [`libbase64`](https://github.com/aklomp/base64/blob/master/LICENSE) | BSD 2-Clause |
| [`libuv`](https://github.com/libuv/libuv) (on Windows) | MIT |
| [`libdeflate`](https://github.com/ebiggers/libdeflate) | MIT |
| [`libjpeg-turbo`](https://github.com/libjpeg-turbo/libjpeg-turbo) | [BSD 3-Clause / IJG / zlib](https://github.com/libjpeg-turbo/libjpeg-turbo/blob/main/LICENSE.md) |
| [`libspng`](https://github.com/randy408/libspng) | BSD 2-Clause |
| [`libwebp`](https://github.com/webmproject/libwebp) | BSD 3-Clause |
| [`highway`](https://github.com/google/highway) | Apache 2.0 |
| [`uucode`](https://github.com/jacobsandlund/uucode) | MIT |
| A fork of [`uWebsockets`](https://github.com/jarred-sumner/uwebsockets) | Apache 2.0 licensed |
| Parts of [Tigerbeetle's IO code](https://github.com/tigerbeetle/tigerbeetle/blob/532c8b70b9142c17e07737ab6d3da68d7500cbca/src/io/windows.zig#L1) | Apache 2.0 licensed |
| `__cxa_thread_atexit` fallback from [LLVM libc++abi](https://github.com/llvm/llvm-project/blob/llvmorg-19.1.0/libcxxabi/src/cxa_thread_atexit.cpp) | Apache 2.0 with LLVM exception |
| The type checker behind `bun check` and the parser it shares with `bun lint` and `bun fmt` are ports of [`typescript-go`](https://github.com/microsoft/typescript-go). They use TypeScript's diagnostic messages and `lib.*.d.ts` files | Apache 2.0 |
| `bun lint` ports large parts of [ESLint](https://github.com/eslint/eslint): code path analysis, rule helpers such as `ast-utils.js`, config and comment handling, and many rules. The ESLint rules in `bun lint` use ESLint's rule names, options, option schemas, messages and descriptions. The `eslint:recommended` and `eslint:all` presets are ESLint's. Parts of `main.js` in `src/lint/js_plugin/worker` are copied from ESLint, and parts of `LintReport.cpp`, `LintSourceCode.cpp` and `LintTokens.cpp` in `src/jsc/bindings` are ported from it | MIT |
| `bun lint` does the same with the rules of [`typescript-eslint`](https://github.com/typescript-eslint/typescript-eslint). It also ports `type-utils` and the plugin's `util` helpers, and uses its presets, the error messages of `typescript-estree` and the `lib` globals of `scope-manager` | MIT |
| Selectors in `bun lint` are a port of [`esquery`](https://github.com/estools/esquery) | BSD 3-Clause |
| `bun lint` has ports of [`@eslint-community/regexpp`](https://github.com/eslint-community/regexpp), [`@eslint-community/eslint-utils`](https://github.com/eslint-community/eslint-utils), [`levn`](https://github.com/gkz/levn) and [`natural-compare`](https://github.com/litejs/natural-compare-lite), and of parts of [`ts-api-utils`](https://github.com/JoshuaKGoldberg/ts-api-utils) and [`@eslint/eslintrc`](https://github.com/eslint/eslintrc) | MIT |
| `bun lint` uses the error messages of [`ajv`](https://github.com/ajv-validator/ajv), [`acorn`](https://github.com/acornjs/acorn) and [`optionator`](https://github.com/gkz/optionator), and the lists in [`globals`](https://github.com/sindresorhus/globals) and [`is-core-module`](https://github.com/inspect-js/is-core-module) | MIT |
| `bun lint` uses the error messages of [`espree`](https://github.com/eslint/js/tree/main/packages/espree) | BSD 2-Clause |
| `bun lint` uses the `JSON.parse` error messages of [V8](https://github.com/v8/v8) | BSD 3-Clause |
| `bun lint` has ports of parts of [`@eslint/plugin-kit`, `@eslint/config-array`, `@eslint/config-helpers` and `@eslint/compat`](https://github.com/eslint/rewrite) | Apache 2.0 |
| `bun lint` and `bun fmt` have a port of the pattern-to-regular-expression table of [`ignore`](https://github.com/kaelzhang/node-ignore) | MIT |
| `bun lint` and `bun fmt` have ports of [`is-glob`](https://github.com/micromatch/is-glob) and [`is-extglob`](https://github.com/jonschlinkert/is-extglob) | MIT |
| `bun lint` and `bun fmt` have a port of [`glob-parent`](https://github.com/gulpjs/glob-parent) | ISC |
| The `react-hooks`, `import`, `n`, `react` and `prettier` rules in `bun lint` are ports of the rules in [`eslint-plugin-react-hooks`](https://github.com/facebook/react/tree/main/packages/eslint-plugin-react-hooks), [`eslint-plugin-import`](https://github.com/import-js/eslint-plugin-import), [`eslint-plugin-n`](https://github.com/eslint-community/eslint-plugin-n), [`eslint-plugin-es-x`](https://github.com/eslint-community/eslint-plugin-es-x), [`eslint-plugin-react`](https://github.com/jsx-eslint/eslint-plugin-react) and [`eslint-plugin-prettier`](https://github.com/prettier/eslint-plugin-prettier), with the same rule names, options and messages. They include the Node.js version tables of `eslint-plugin-n` and a few functions from [`jsx-ast-utils`](https://github.com/jsx-eslint/jsx-ast-utils) and [`prettier-linter-helpers`](https://github.com/prettier/prettier-linter-helpers) | MIT |
| The text diff used by `bun test` and the `prettier/prettier` rule has parts of [`diff-match-patch`](https://github.com/google/diff-match-patch) and of [`fast-diff`](https://github.com/jhchen/fast-diff), which is derived from it | Apache 2.0 |
| The `unicorn`, `oxc`, `react-perf`, `jsx-a11y`, `nextjs`, `promise`, `node`, `jest` and `vitest` rules in `bun lint` are ports of the rules in [`oxlint`](https://github.com/oxc-project/oxc), with its messages and help text. With an oxlint config, the other rules use the messages of oxlint and [`tsgolint`](https://github.com/oxc-project/tsgolint) too. `bun lint` also ports parts of `oxc_jsdoc` and `oxc_react_compiler` | MIT |
| oxlint ported those rules from [`eslint-plugin-unicorn`](https://github.com/sindresorhus/eslint-plugin-unicorn), [`eslint-plugin-react-perf`](https://github.com/cvazac/eslint-plugin-react-perf), [`eslint-plugin-jsx-a11y`](https://github.com/jsx-eslint/eslint-plugin-jsx-a11y), [`@next/eslint-plugin-next`](https://github.com/vercel/next.js/tree/canary/packages/eslint-plugin-next), [`eslint-plugin-n`](https://github.com/eslint-community/eslint-plugin-n), [`eslint-plugin-jest`](https://github.com/jest-community/eslint-plugin-jest) and [`@vitest/eslint-plugin`](https://github.com/vitest-dev/eslint-plugin-vitest). Their rule names, options and some of their messages are in `bun lint` | MIT |
| oxlint ported its `promise` rules from [`eslint-plugin-promise`](https://github.com/eslint-community/eslint-plugin-promise) | ISC |
| The ARIA tables of the `jsx-a11y` rules come from [`aria-query`](https://github.com/A11yance/aria-query) by way of oxlint | Apache 2.0 |
| `bun fmt` is a port of [Prettier](https://github.com/prettier/prettier): its printers for CSS, SCSS, Less, HTML, Vue, Angular, Markdown, YAML, GraphQL and Handlebars, its document printer, its handling of options, pragmas, ranges, cursor offsets and embedded code, and the HTML tables it is built with | MIT |
| The JavaScript and TypeScript printer of `bun fmt`, its JSDoc formatting and its oxfmt-style import sorting are a port of [`oxc_formatter`](https://github.com/oxc-project/oxc/tree/main/crates/oxc_formatter), which is a port of the [Biome](https://github.com/biomejs/biome) formatter. The intermediate representation and its printer come from Biome by way of `oxc_formatter_core` | MIT |
| `bun fmt` has ports of the parsers in [`postcss`](https://github.com/postcss/postcss), [`postcss-scss`](https://npmjs.com/package/postcss-scss), [`postcss-less`](https://npmjs.com/package/postcss-less), [`postcss-selector-parser`](https://github.com/postcss/postcss-selector-parser), [`postcss-media-query-parser`](https://github.com/dryoma/postcss-media-query-parser), [`postcss-values-parser`](https://npmjs.com/package/postcss-values-parser), [`angular-html-parser`](https://npmjs.com/package/angular-html-parser), [`graphql-js`](https://github.com/graphql/graphql-js), [`@glimmer/syntax`](https://github.com/glimmerjs/glimmer-vm), [`simple-html-tokenizer`](https://github.com/tildeio/simple-html-tokenizer) and [`@angular/compiler`](https://github.com/angular/angular), and of parts of [`yaml-unist-parser`](https://npmjs.com/package/yaml-unist-parser) and [`angular-estree-parser`](https://npmjs.com/package/angular-estree-parser) | MIT |
| `bun fmt` also has a port of [`@handlebars/parser`](https://github.com/handlebars-lang/handlebars-parser) and of one file of [`yaml`](https://npmjs.com/package/yaml) | ISC |
| Import sorting in `bun fmt` has ports of [`@trivago/prettier-plugin-sort-imports`](https://github.com/trivago/prettier-plugin-sort-imports) and [`@ianvs/prettier-plugin-sort-imports`](https://github.com/ianvs/prettier-plugin-sort-imports), and of `organizeImports.ts` in [TypeScript](https://github.com/microsoft/TypeScript) | Apache 2.0 |
| `bun fmt` has ports of [`jest-docblock`](https://github.com/jestjs/jest/tree/main/packages/jest-docblock) and the crates [`sort-package-json`](https://crates.io/crates/sort-package-json) and [`oxc-toml`](https://crates.io/crates/oxc-toml) (a fork of taplo), and of parts of [`@babel/generator`](https://github.com/babel/babel), [`micromark`](https://github.com/micromark/micromark) and [`editorconfig-without-wasm`](https://github.com/fisker/editorconfig-core-js-without-wasm). Its character width tables are generated from [`emoji-regex`](https://github.com/mathiasbynens/emoji-regex), [`get-east-asian-width`](https://github.com/sindresorhus/get-east-asian-width) and [`narrow-emojis`](https://npmjs.com/package/narrow-emojis) | MIT |
| Tailwind class sorting in `bun fmt` is a port of parts of [`prettier-plugin-tailwindcss`](https://github.com/tailwindlabs/prettier-plugin-tailwindcss) | MIT |
| Svelte formatting in `bun fmt` is a port of [`prettier-plugin-svelte`](https://github.com/sveltejs/prettier-plugin-svelte) and of the template parser of [Svelte](https://github.com/sveltejs/svelte) | MIT |
| With `prettier-plugin-packagejson` in a Prettier config, `bun fmt` sorts `package.json` with a port of the npm package [`sort-package-json`](https://github.com/keithamus/sort-package-json) and the list in [`git-hooks-list`](https://github.com/fisker/git-hooks-list) | MIT |

The tests in `test/cli/lint/conformance` and `test/cli/format` include test cases from ESLint, typescript-eslint, the ESLint plugins above, oxc, tsgolint, Prettier and the two import sorting plugins. They are not part of the `bun` binary. Their licenses are next to them.

## Polyfills

For compatibility reasons, the following packages are embedded into Bun's binary and injected if imported.

| Package | License |
|---------|---------|
| [`acorn`](https://github.com/acornjs/acorn) | MIT |
| [`acorn-walk`](https://github.com/acornjs/acorn) | MIT |
| [`assert`](https://npmjs.com/package/assert) | MIT |
| [`browserify-zlib`](https://npmjs.com/package/browserify-zlib) | MIT |
| [`buffer`](https://npmjs.com/package/buffer) | MIT |
| [`constants-browserify`](https://npmjs.com/package/constants-browserify) | MIT |
| [`crypto-browserify`](https://npmjs.com/package/crypto-browserify) | MIT |
| [`domain-browser`](https://npmjs.com/package/domain-browser) | MIT |
| [`events`](https://npmjs.com/package/events) | MIT |
| [`https-browserify`](https://npmjs.com/package/https-browserify) | MIT |
| [`os-browserify`](https://npmjs.com/package/os-browserify) | MIT |
| [`path-browserify`](https://npmjs.com/package/path-browserify) | MIT |
| [`process`](https://npmjs.com/package/process) | MIT |
| [`punycode`](https://npmjs.com/package/punycode) | MIT |
| [`querystring-es3`](https://npmjs.com/package/querystring-es3) | MIT |
| [`stream-browserify`](https://npmjs.com/package/stream-browserify) | MIT |
| [`stream-http`](https://npmjs.com/package/stream-http) | MIT |
| [`string_decoder`](https://npmjs.com/package/string_decoder) | MIT |
| [`timers-browserify`](https://npmjs.com/package/timers-browserify) | MIT |
| [`tty-browserify`](https://npmjs.com/package/tty-browserify) | MIT |
| [`url`](https://npmjs.com/package/url) | MIT |
| [`util`](https://npmjs.com/package/util) | MIT |
| [`vm-browserify`](https://npmjs.com/package/vm-browserify) | MIT |

## Additional credits

- Bun's JS transpiler, CSS lexer, and Node.js module resolver source code is a port of [@evanw](https://github.com/evanw)’s [esbuild](https://github.com/evanw/esbuild) project.
- Credit to [@kipply](https://github.com/kipply) for the name "Bun"!

## JavaScriptCore upstream LGPL text

NOTE! The LGPL below is copyrighted by the Free Software Foundation, but
the instance of code that it refers to (the kde libraries) are copyrighted
by the authors who actually wrote it.

---------------------------------------------------------------------------
		  GNU LIBRARY GENERAL PUBLIC LICENSE
		       Version 2, June 1991

 Copyright (C) 1991 Free Software Foundation, Inc.
                    51 Franklin Street, Fifth Floor
                    Boston, MA 02110-1301, USA.
 Everyone is permitted to copy and distribute verbatim copies
 of this license document, but changing it is not allowed.

[This is the first released version of the library GPL.  It is
 numbered 2 because it goes with version 2 of the ordinary GPL.]

			    Preamble

  The licenses for most software are designed to take away your
freedom to share and change it.  By contrast, the GNU General Public
Licenses are intended to guarantee your freedom to share and change
free software--to make sure the software is free for all its users.

  This license, the Library General Public License, applies to some
specially designated Free Software Foundation software, and to any
other libraries whose authors decide to use it.  You can use it for
your libraries, too.

  When we speak of free software, we are referring to freedom, not
price.  Our General Public Licenses are designed to make sure that you
have the freedom to distribute copies of free software (and charge for
this service if you wish), that you receive source code or can get it
if you want it, that you can change the software or use pieces of it
in new free programs; and that you know you can do these things.

  To protect your rights, we need to make restrictions that forbid
anyone to deny you these rights or to ask you to surrender the rights.
These restrictions translate to certain responsibilities for you if
you distribute copies of the library, or if you modify it.

  For example, if you distribute copies of the library, whether gratis
or for a fee, you must give the recipients all the rights that we gave
you.  You must make sure that they, too, receive or can get the source
code.  If you link a program with the library, you must provide
complete object files to the recipients so that they can relink them
with the library, after making changes to the library and recompiling
it.  And you must show them these terms so they know their rights.

  Our method of protecting your rights has two steps: (1) copyright
the library, and (2) offer you this license which gives you legal
permission to copy, distribute and/or modify the library.

  Also, for each distributor's protection, we want to make certain
that everyone understands that there is no warranty for this free
library.  If the library is modified by someone else and passed on, we
want its recipients to know that what they have is not the original
version, so that any problems introduced by others will not reflect on
the original authors' reputations.

  Finally, any free program is threatened constantly by software
patents.  We wish to avoid the danger that companies distributing free
software will individually obtain patent licenses, thus in effect
transforming the program into proprietary software.  To prevent this,
we have made it clear that any patent must be licensed for everyone's
free use or not licensed at all.

  Most GNU software, including some libraries, is covered by the ordinary
GNU General Public License, which was designed for utility programs.  This
license, the GNU Library General Public License, applies to certain
designated libraries.  This license is quite different from the ordinary
one; be sure to read it in full, and don't assume that anything in it is
the same as in the ordinary license.

  The reason we have a separate public license for some libraries is that
they blur the distinction we usually make between modifying or adding to a
program and simply using it.  Linking a program with a library, without
changing the library, is in some sense simply using the library, and is
analogous to running a utility program or application program.  However, in
a textual and legal sense, the linked executable is a combined work, a
derivative of the original library, and the ordinary General Public License
treats it as such.

  Because of this blurred distinction, using the ordinary General
Public License for libraries did not effectively promote software
sharing, because most developers did not use the libraries.  We
concluded that weaker conditions might promote sharing better.

  However, unrestricted linking of non-free programs would deprive the
users of those programs of all benefit from the free status of the
libraries themselves.  This Library General Public License is intended to
permit developers of non-free programs to use free libraries, while
preserving your freedom as a user of such programs to change the free
libraries that are incorporated in them.  (We have not seen how to achieve
this as regards changes in header files, but we have achieved it as regards
changes in the actual functions of the Library.)  The hope is that this
will lead to faster development of free libraries.

  The precise terms and conditions for copying, distribution and
modification follow.  Pay close attention to the difference between a
"work based on the library" and a "work that uses the library".  The
former contains code derived from the library, while the latter only
works together with the library.

  Note that it is possible for a library to be covered by the ordinary
General Public License rather than by this special one.

		  GNU LIBRARY GENERAL PUBLIC LICENSE
   TERMS AND CONDITIONS FOR COPYING, DISTRIBUTION AND MODIFICATION

  0. This License Agreement applies to any software library which
contains a notice placed by the copyright holder or other authorized
party saying it may be distributed under the terms of this Library
General Public License (also called "this License").  Each licensee is
addressed as "you".

  A "library" means a collection of software functions and/or data
prepared so as to be conveniently linked with application programs
(which use some of those functions and data) to form executables.

  The "Library", below, refers to any such software library or work
which has been distributed under these terms.  A "work based on the
Library" means either the Library or any derivative work under
copyright law: that is to say, a work containing the Library or a
portion of it, either verbatim or with modifications and/or translated
straightforwardly into another language.  (Hereinafter, translation is
included without limitation in the term "modification".)

  "Source code" for a work means the preferred form of the work for
making modifications to it.  For a library, complete source code means
all the source code for all modules it contains, plus any associated
interface definition files, plus the scripts used to control compilation
and installation of the library.

  Activities other than copying, distribution and modification are not
covered by this License; they are outside its scope.  The act of
running a program using the Library is not restricted, and output from
such a program is covered only if its contents constitute a work based
on the Library (independent of the use of the Library in a tool for
writing it).  Whether that is true depends on what the Library does
and what the program that uses the Library does.

  1. You may copy and distribute verbatim copies of the Library's
complete source code as you receive it, in any medium, provided that
you conspicuously and appropriately publish on each copy an
appropriate copyright notice and disclaimer of warranty; keep intact
all the notices that refer to this License and to the absence of any
warranty; and distribute a copy of this License along with the
Library.

  You may charge a fee for the physical act of transferring a copy,
and you may at your option offer warranty protection in exchange for a
fee.

  2. You may modify your copy or copies of the Library or any portion
of it, thus forming a work based on the Library, and copy and
distribute such modifications or work under the terms of Section 1
above, provided that you also meet all of these conditions:

    a) The modified work must itself be a software library.

    b) You must cause the files modified to carry prominent notices
    stating that you changed the files and the date of any change.

    c) You must cause the whole of the work to be licensed at no
    charge to all third parties under the terms of this License.

    d) If a facility in the modified Library refers to a function or a
    table of data to be supplied by an application program that uses
    the facility, other than as an argument passed when the facility
    is invoked, then you must make a good faith effort to ensure that,
    in the event an application does not supply such function or
    table, the facility still operates, and performs whatever part of
    its purpose remains meaningful.

    (For example, a function in a library to compute square roots has
    a purpose that is entirely well-defined independent of the
    application.  Therefore, Subsection 2d requires that any
    application-supplied function or table used by this function must
    be optional: if the application does not supply it, the square
    root function must still compute square roots.)

These requirements apply to the modified work as a whole.  If
identifiable sections of that work are not derived from the Library,
and can be reasonably considered independent and separate works in
themselves, then this License, and its terms, do not apply to those
sections when you distribute them as separate works.  But when you
distribute the same sections as part of a whole which is a work based
on the Library, the distribution of the whole must be on the terms of
this License, whose permissions for other licensees extend to the
entire whole, and thus to each and every part regardless of who wrote
it.

Thus, it is not the intent of this section to claim rights or contest
your rights to work written entirely by you; rather, the intent is to
exercise the right to control the distribution of derivative or
collective works based on the Library.

In addition, mere aggregation of another work not based on the Library
with the Library (or with a work based on the Library) on a volume of
a storage or distribution medium does not bring the other work under
the scope of this License.

  3. You may opt to apply the terms of the ordinary GNU General Public
License instead of this License to a given copy of the Library.  To do
this, you must alter all the notices that refer to this License, so
that they refer to the ordinary GNU General Public License, version 2,
instead of to this License.  (If a newer version than version 2 of the
ordinary GNU General Public License has appeared, then you can specify
that version instead if you wish.)  Do not make any other change in
these notices.

  Once this change is made in a given copy, it is irreversible for
that copy, so the ordinary GNU General Public License applies to all
subsequent copies and derivative works made from that copy.

  This option is useful when you wish to copy part of the code of
the Library into a program that is not a library.

  4. You may copy and distribute the Library (or a portion or
derivative of it, under Section 2) in object code or executable form
under the terms of Sections 1 and 2 above provided that you accompany
it with the complete corresponding machine-readable source code, which
must be distributed under the terms of Sections 1 and 2 above on a
medium customarily used for software interchange.

  If distribution of object code is made by offering access to copy
from a designated place, then offering equivalent access to copy the
source code from the same place satisfies the requirement to
distribute the source code, even though third parties are not
compelled to copy the source along with the object code.

  5. A program that contains no derivative of any portion of the
Library, but is designed to work with the Library by being compiled or
linked with it, is called a "work that uses the Library".  Such a
work, in isolation, is not a derivative work of the Library, and
therefore falls outside the scope of this License.

  However, linking a "work that uses the Library" with the Library
creates an executable that is a derivative of the Library (because it
contains portions of the Library), rather than a "work that uses the
library".  The executable is therefore covered by this License.
Section 6 states terms for distribution of such executables.

  When a "work that uses the Library" uses material from a header file
that is part of the Library, the object code for the work may be a
derivative work of the Library even though the source code is not.
Whether this is true is especially significant if the work can be
linked without the Library, or if the work is itself a library.  The
threshold for this to be true is not precisely defined by law.

  If such an object file uses only numerical parameters, data
structure layouts and accessors, and small macros and small inline
functions (ten lines or less in length), then the use of the object
file is unrestricted, regardless of whether it is legally a derivative
work.  (Executables containing this object code plus portions of the
Library will still fall under Section 6.)

  Otherwise, if the work is a derivative of the Library, you may
distribute the object code for the work under the terms of Section 6.
Any executables containing that work also fall under Section 6,
whether or not they are linked directly with the Library itself.

  6. As an exception to the Sections above, you may also compile or
link a "work that uses the Library" with the Library to produce a
work containing portions of the Library, and distribute that work
under terms of your choice, provided that the terms permit
modification of the work for the customer's own use and reverse
engineering for debugging such modifications.

  You must give prominent notice with each copy of the work that the
Library is used in it and that the Library and its use are covered by
this License.  You must supply a copy of this License.  If the work
during execution displays copyright notices, you must include the
copyright notice for the Library among them, as well as a reference
directing the user to the copy of this License.  Also, you must do one
of these things:

    a) Accompany the work with the complete corresponding
    machine-readable source code for the Library including whatever
    changes were used in the work (which must be distributed under
    Sections 1 and 2 above); and, if the work is an executable linked
    with the Library, with the complete machine-readable "work that
    uses the Library", as object code and/or source code, so that the
    user can modify the Library and then relink to produce a modified
    executable containing the modified Library.  (It is understood
    that the user who changes the contents of definitions files in the
    Library will not necessarily be able to recompile the application
    to use the modified definitions.)

    b) Accompany the work with a written offer, valid for at
    least three years, to give the same user the materials
    specified in Subsection 6a, above, for a charge no more
    than the cost of performing this distribution.

    c) If distribution of the work is made by offering access to copy
    from a designated place, offer equivalent access to copy the above
    specified materials from the same place.

    d) Verify that the user has already received a copy of these
    materials or that you have already sent this user a copy.

  For an executable, the required form of the "work that uses the
Library" must include any data and utility programs needed for
reproducing the executable from it.  However, as a special exception,
the source code distributed need not include anything that is normally
distributed (in either source or binary form) with the major
components (compiler, kernel, and so on) of the operating system on
which the executable runs, unless that component itself accompanies
the executable.

  It may happen that this requirement contradicts the license
restrictions of other proprietary libraries that do not normally
accompany the operating system.  Such a contradiction means you cannot
use both them and the Library together in an executable that you
distribute.

  7. You may place library facilities that are a work based on the
Library side-by-side in a single library together with other library
facilities not covered by this License, and distribute such a combined
library, provided that the separate distribution of the work based on
the Library and of the other library facilities is otherwise
permitted, and provided that you do these two things:

    a) Accompany the combined library with a copy of the same work
    based on the Library, uncombined with any other library
    facilities.  This must be distributed under the terms of the
    Sections above.

    b) Give prominent notice with the combined library of the fact
    that part of it is a work based on the Library, and explaining
    where to find the accompanying uncombined form of the same work.

  8. You may not copy, modify, sublicense, link with, or distribute
the Library except as expressly provided under this License.  Any
attempt otherwise to copy, modify, sublicense, link with, or
distribute the Library is void, and will automatically terminate your
rights under this License.  However, parties who have received copies,
or rights, from you under this License will not have their licenses
terminated so long as such parties remain in full compliance.

  9. You are not required to accept this License, since you have not
signed it.  However, nothing else grants you permission to modify or
distribute the Library or its derivative works.  These actions are
prohibited by law if you do not accept this License.  Therefore, by
modifying or distributing the Library (or any work based on the
Library), you indicate your acceptance of this License to do so, and
all its terms and conditions for copying, distributing or modifying
the Library or works based on it.

  10. Each time you redistribute the Library (or any work based on the
Library), the recipient automatically receives a license from the
original licensor to copy, distribute, link with or modify the Library
subject to these terms and conditions.  You may not impose any further
restrictions on the recipients' exercise of the rights granted herein.
You are not responsible for enforcing compliance by third parties to
this License.

  11. If, as a consequence of a court judgment or allegation of patent
infringement or for any other reason (not limited to patent issues),
conditions are imposed on you (whether by court order, agreement or
otherwise) that contradict the conditions of this License, they do not
excuse you from the conditions of this License.  If you cannot
distribute so as to satisfy simultaneously your obligations under this
License and any other pertinent obligations, then as a consequence you
may not distribute the Library at all.  For example, if a patent
license would not permit royalty-free redistribution of the Library by
all those who receive copies directly or indirectly through you, then
the only way you could satisfy both it and this License would be to
refrain entirely from distribution of the Library.

If any portion of this section is held invalid or unenforceable under any
particular circumstance, the balance of the section is intended to apply,
and the section as a whole is intended to apply in other circumstances.

It is not the purpose of this section to induce you to infringe any
patents or other property right claims or to contest validity of any
such claims; this section has the sole purpose of protecting the
integrity of the free software distribution system which is
implemented by public license practices.  Many people have made
generous contributions to the wide range of software distributed
through that system in reliance on consistent application of that
system; it is up to the author/donor to decide if he or she is willing
to distribute software through any other system and a licensee cannot
impose that choice.

This section is intended to make thoroughly clear what is believed to
be a consequence of the rest of this License.

  12. If the distribution and/or use of the Library is restricted in
certain countries either by patents or by copyrighted interfaces, the
original copyright holder who places the Library under this License may add
an explicit geographical distribution limitation excluding those countries,
so that distribution is permitted only in or among countries not thus
excluded.  In such case, this License incorporates the limitation as if
written in the body of this License.

  13. The Free Software Foundation may publish revised and/or new
versions of the Library General Public License from time to time.
Such new versions will be similar in spirit to the present version,
but may differ in detail to address new problems or concerns.

Each version is given a distinguishing version number.  If the Library
specifies a version number of this License which applies to it and
"any later version", you have the option of following the terms and
conditions either of that version or of any later version published by
the Free Software Foundation.  If the Library does not specify a
license version number, you may choose any version ever published by
the Free Software Foundation.

  14. If you wish to incorporate parts of the Library into other free
programs whose distribution conditions are incompatible with these,
write to the author to ask for permission.  For software which is
copyrighted by the Free Software Foundation, write to the Free
Software Foundation; we sometimes make exceptions for this.  Our
decision will be guided by the two goals of preserving the free status
of all derivatives of our free software and of promoting the sharing
and reuse of software generally.

			    NO WARRANTY

  15. BECAUSE THE LIBRARY IS LICENSED FREE OF CHARGE, THERE IS NO
WARRANTY FOR THE LIBRARY, TO THE EXTENT PERMITTED BY APPLICABLE LAW.
EXCEPT WHEN OTHERWISE STATED IN WRITING THE COPYRIGHT HOLDERS AND/OR
OTHER PARTIES PROVIDE THE LIBRARY "AS IS" WITHOUT WARRANTY OF ANY
KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
PURPOSE.  THE ENTIRE RISK AS TO THE QUALITY AND PERFORMANCE OF THE
LIBRARY IS WITH YOU.  SHOULD THE LIBRARY PROVE DEFECTIVE, YOU ASSUME
THE COST OF ALL NECESSARY SERVICING, REPAIR OR CORRECTION.

  16. IN NO EVENT UNLESS REQUIRED BY APPLICABLE LAW OR AGREED TO IN
WRITING WILL ANY COPYRIGHT HOLDER, OR ANY OTHER PARTY WHO MAY MODIFY
AND/OR REDISTRIBUTE THE LIBRARY AS PERMITTED ABOVE, BE LIABLE TO YOU
FOR DAMAGES, INCLUDING ANY GENERAL, SPECIAL, INCIDENTAL OR
CONSEQUENTIAL DAMAGES ARISING OUT OF THE USE OR INABILITY TO USE THE
LIBRARY (INCLUDING BUT NOT LIMITED TO LOSS OF DATA OR DATA BEING
RENDERED INACCURATE OR LOSSES SUSTAINED BY YOU OR THIRD PARTIES OR A
FAILURE OF THE LIBRARY TO OPERATE WITH ANY OTHER SOFTWARE), EVEN IF
SUCH HOLDER OR OTHER PARTY HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH
DAMAGES.

		     END OF TERMS AND CONDITIONS
           How to Apply These Terms to Your New Libraries

  If you develop a new library, and you want it to be of the greatest
possible use to the public, we recommend making it free software that
everyone can redistribute and change.  You can do so by permitting
redistribution under these terms (or, alternatively, under the terms of the
ordinary General Public License).

  To apply these terms, attach the following notices to the library.  It is
safest to attach them to the start of each source file to most effectively
convey the exclusion of warranty; and each file should have at least the
"copyright" line and a pointer to where the full notice is found.

    <one line to give the library's name and a brief idea of what it does.>
    Copyright (C) <year>  <name of author>

    This library is free software; you can redistribute it and/or
    modify it under the terms of the GNU Lesser General Public
    License as published by the Free Software Foundation; either
    version 2 of the License, or (at your option) any later version.

    This library is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
    Lesser General Public License for more details.

    You should have received a copy of the GNU Lesser General Public
    License along with this library; if not, write to the Free Software
    Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA

Also add information on how to contact you by electronic and paper mail.

You should also get your employer (if you work as a programmer) or your
school, if any, to sign a "copyright disclaimer" for the library, if
necessary.  Here is a sample; alter the names:

  Yoyodyne, Inc., hereby disclaims all copyright interest in the
  library `Frob' (a library for tweaking knobs) written by James Random Hacker.

  <signature of Ty Coon>, 1 April 1990
  Ty Coon, President of Vice

That's all there is to it!

## LGPL 2.1 (Bun linked libraries)

GNU LESSER GENERAL PUBLIC LICENSE
                       Version 2.1, February 1999

 Copyright (C) 1991, 1999 Free Software Foundation, Inc.
 <https://fsf.org/>
 Everyone is permitted to copy and distribute verbatim copies
 of this license document, but changing it is not allowed.

[This is the first released version of the Lesser GPL.  It also counts
 as the successor of the GNU Library Public License, version 2, hence
 the version number 2.1.]

                            Preamble

  The licenses for most software are designed to take away your
freedom to share and change it.  By contrast, the GNU General Public
Licenses are intended to guarantee your freedom to share and change
free software--to make sure the software is free for all its users.

  This license, the Lesser General Public License, applies to some
specially designated software packages--typically libraries--of the
Free Software Foundation and other authors who decide to use it.  You
can use it too, but we suggest you first think carefully about whether
this license or the ordinary General Public License is the better
strategy to use in any particular case, based on the explanations below.

  When we speak of free software, we are referring to freedom of use,
not price.  Our General Public Licenses are designed to make sure that
you have the freedom to distribute copies of free software (and charge
for this service if you wish); that you receive source code or can get
it if you want it; that you can change the software and use pieces of
it in new free programs; and that you are informed that you can do
these things.

  To protect your rights, we need to make restrictions that forbid
distributors to deny you these rights or to ask you to surrender these
rights.  These restrictions translate to certain responsibilities for
you if you distribute copies of the library or if you modify it.

  For example, if you distribute copies of the library, whether gratis
or for a fee, you must give the recipients all the rights that we gave
you.  You must make sure that they, too, receive or can get the source
code.  If you link other code with the library, you must provide
complete object files to the recipients, so that they can relink them
with the library after making changes to the library and recompiling
it.  And you must show them these terms so they know their rights.

  We protect your rights with a two-step method: (1) we copyright the
library, and (2) we offer you this license, which gives you legal
permission to copy, distribute and/or modify the library.

  To protect each distributor, we want to make it very clear that
there is no warranty for the free library.  Also, if the library is
modified by someone else and passed on, the recipients should know
that what they have is not the original version, so that the original
author's reputation will not be affected by problems that might be
introduced by others.


  Finally, software patents pose a constant threat to the existence of
any free program.  We wish to make sure that a company cannot
effectively restrict the users of a free program by obtaining a
restrictive license from a patent holder.  Therefore, we insist that
any patent license obtained for a version of the library must be
consistent with the full freedom of use specified in this license.

  Most GNU software, including some libraries, is covered by the
ordinary GNU General Public License.  This license, the GNU Lesser
General Public License, applies to certain designated libraries, and
is quite different from the ordinary General Public License.  We use
this license for certain libraries in order to permit linking those
libraries into non-free programs.

  When a program is linked with a library, whether statically or using
a shared library, the combination of the two is legally speaking a
combined work, a derivative of the original library.  The ordinary
General Public License therefore permits such linking only if the
entire combination fits its criteria of freedom.  The Lesser General
Public License permits more lax criteria for linking other code with
the library.

  We call this license the "Lesser" General Public License because it
does Less to protect the user's freedom than the ordinary General
Public License.  It also provides other free software developers Less
of an advantage over competing non-free programs.  These disadvantages
are the reason we use the ordinary General Public License for many
libraries.  However, the Lesser license provides advantages in certain
special circumstances.

  For example, on rare occasions, there may be a special need to
encourage the widest possible use of a certain library, so that it becomes
a de-facto standard.  To achieve this, non-free programs must be
allowed to use the library.  A more frequent case is that a free
library does the same job as widely used non-free libraries.  In this
case, there is little to gain by limiting the free library to free
software only, so we use the Lesser General Public License.

  In other cases, permission to use a particular library in non-free
programs enables a greater number of people to use a large body of
free software.  For example, permission to use the GNU C Library in
non-free programs enables many more people to use the whole GNU
operating system, as well as its variant, the GNU/Linux operating
system.

  Although the Lesser General Public License is Less protective of the
users' freedom, it does ensure that the user of a program that is
linked with the Library has the freedom and the wherewithal to run
that program using a modified version of the Library.

  The precise terms and conditions for copying, distribution and
modification follow.  Pay close attention to the difference between a
"work based on the library" and a "work that uses the library".  The
former contains code derived from the library, whereas the latter must
be combined with the library in order to run.


                  GNU LESSER GENERAL PUBLIC LICENSE
   TERMS AND CONDITIONS FOR COPYING, DISTRIBUTION AND MODIFICATION

  0. This License Agreement applies to any software library or other
program which contains a notice placed by the copyright holder or
other authorized party saying it may be distributed under the terms of
this Lesser General Public License (also called "this License").
Each licensee is addressed as "you".

  A "library" means a collection of software functions and/or data
prepared so as to be conveniently linked with application programs
(which use some of those functions and data) to form executables.

  The "Library", below, refers to any such software library or work
which has been distributed under these terms.  A "work based on the
Library" means either the Library or any derivative work under
copyright law: that is to say, a work containing the Library or a
portion of it, either verbatim or with modifications and/or translated
straightforwardly into another language.  (Hereinafter, translation is
included without limitation in the term "modification".)

  "Source code" for a work means the preferred form of the work for
making modifications to it.  For a library, complete source code means
all the source code for all modules it contains, plus any associated
interface definition files, plus the scripts used to control compilation
and installation of the library.

  Activities other than copying, distribution and modification are not
covered by this License; they are outside its scope.  The act of
running a program using the Library is not restricted, and output from
such a program is covered only if its contents constitute a work based
on the Library (independent of the use of the Library in a tool for
writing it).  Whether that is true depends on what the Library does
and what the program that uses the Library does.

  1. You may copy and distribute verbatim copies of the Library's
complete source code as you receive it, in any medium, provided that
you conspicuously and appropriately publish on each copy an
appropriate copyright notice and disclaimer of warranty; keep intact
all the notices that refer to this License and to the absence of any
warranty; and distribute a copy of this License along with the
Library.

  You may charge a fee for the physical act of transferring a copy,
and you may at your option offer warranty protection in exchange for a
fee.


  2. You may modify your copy or copies of the Library or any portion
of it, thus forming a work based on the Library, and copy and
distribute such modifications or work under the terms of Section 1
above, provided that you also meet all of these conditions:

    a) The modified work must itself be a software library.

    b) You must cause the files modified to carry prominent notices
    stating that you changed the files and the date of any change.

    c) You must cause the whole of the work to be licensed at no
    charge to all third parties under the terms of this License.

    d) If a facility in the modified Library refers to a function or a
    table of data to be supplied by an application program that uses
    the facility, other than as an argument passed when the facility
    is invoked, then you must make a good faith effort to ensure that,
    in the event an application does not supply such function or
    table, the facility still operates, and performs whatever part of
    its purpose remains meaningful.

    (For example, a function in a library to compute square roots has
    a purpose that is entirely well-defined independent of the
    application.  Therefore, Subsection 2d requires that any
    application-supplied function or table used by this function must
    be optional: if the application does not supply it, the square
    root function must still compute square roots.)

These requirements apply to the modified work as a whole.  If
identifiable sections of that work are not derived from the Library,
and can be reasonably considered independent and separate works in
themselves, then this License, and its terms, do not apply to those
sections when you distribute them as separate works.  But when you
distribute the same sections as part of a whole which is a work based
on the Library, the distribution of the whole must be on the terms of
this License, whose permissions for other licensees extend to the
entire whole, and thus to each and every part regardless of who wrote
it.

Thus, it is not the intent of this section to claim rights or contest
your rights to work written entirely by you; rather, the intent is to
exercise the right to control the distribution of derivative or
collective works based on the Library.

In addition, mere aggregation of another work not based on the Library
with the Library (or with a work based on the Library) on a volume of
a storage or distribution medium does not bring the other work under
the scope of this License.

  3. You may opt to apply the terms of the ordinary GNU General Public
License instead of this License to a given copy of the Library.  To do
this, you must alter all the notices that refer to this License, so
that they refer to the ordinary GNU General Public License, version 2,
instead of to this License.  (If a newer version than version 2 of the
ordinary GNU General Public License has appeared, then you can specify
that version instead if you wish.)  Do not make any other change in
these notices.


  Once this change is made in a given copy, it is irreversible for
that copy, so the ordinary GNU General Public License applies to all
subsequent copies and derivative works made from that copy.

  This option is useful when you wish to copy part of the code of
the Library into a program that is not a library.

  4. You may copy and distribute the Library (or a portion or
derivative of it, under Section 2) in object code or executable form
under the terms of Sections 1 and 2 above provided that you accompany
it with the complete corresponding machine-readable source code, which
must be distributed under the terms of Sections 1 and 2 above on a
medium customarily used for software interchange.

  If distribution of object code is made by offering access to copy
from a designated place, then offering equivalent access to copy the
source code from the same place satisfies the requirement to
distribute the source code, even though third parties are not
compelled to copy the source along with the object code.

  5. A program that contains no derivative of any portion of the
Library, but is designed to work with the Library by being compiled or
linked with it, is called a "work that uses the Library".  Such a
work, in isolation, is not a derivative work of the Library, and
therefore falls outside the scope of this License.

  However, linking a "work that uses the Library" with the Library
creates an executable that is a derivative of the Library (because it
contains portions of the Library), rather than a "work that uses the
library".  The executable is therefore covered by this License.
Section 6 states terms for distribution of such executables.

  When a "work that uses the Library" uses material from a header file
that is part of the Library, the object code for the work may be a
derivative work of the Library even though the source code is not.
Whether this is true is especially significant if the work can be
linked without the Library, or if the work is itself a library.  The
threshold for this to be true is not precisely defined by law.

  If such an object file uses only numerical parameters, data
structure layouts and accessors, and small macros and small inline
functions (ten lines or less in length), then the use of the object
file is unrestricted, regardless of whether it is legally a derivative
work.  (Executables containing this object code plus portions of the
Library will still fall under Section 6.)

  Otherwise, if the work is a derivative of the Library, you may
distribute the object code for the work under the terms of Section 6.
Any executables containing that work also fall under Section 6,
whether or not they are linked directly with the Library itself.


  6. As an exception to the Sections above, you may also combine or
link a "work that uses the Library" with the Library to produce a
work containing portions of the Library, and distribute that work
under terms of your choice, provided that the terms permit
modification of the work for the customer's own use and reverse
engineering for debugging such modifications.

  You must give prominent notice with each copy of the work that the
Library is used in it and that the Library and its use are covered by
this License.  You must supply a copy of this License.  If the work
during execution displays copyright notices, you must include the
copyright notice for the Library among them, as well as a reference
directing the user to the copy of this License.  Also, you must do one
of these things:

    a) Accompany the work with the complete corresponding
    machine-readable source code for the Library including whatever
    changes were used in the work (which must be distributed under
    Sections 1 and 2 above); and, if the work is an executable linked
    with the Library, with the complete machine-readable "work that
    uses the Library", as object code and/or source code, so that the
    user can modify the Library and then relink to produce a modified
    executable containing the modified Library.  (It is understood
    that the user who changes the contents of definitions files in the
    Library will not necessarily be able to recompile the application
    to use the modified definitions.)

    b) Use a suitable shared library mechanism for linking with the
    Library.  A suitable mechanism is one that (1) uses at run time a
    copy of the library already present on the user's computer system,
    rather than copying library functions into the executable, and (2)
    will operate properly with a modified version of the library, if
    the user installs one, as long as the modified version is
    interface-compatible with the version that the work was made with.

    c) Accompany the work with a written offer, valid for at
    least three years, to give the same user the materials
    specified in Subsection 6a, above, for a charge no more
    than the cost of performing this distribution.

    d) If distribution of the work is made by offering access to copy
    from a designated place, offer equivalent access to copy the above
    specified materials from the same place.

    e) Verify that the user has already received a copy of these
    materials or that you have already sent this user a copy.

  For an executable, the required form of the "work that uses the
Library" must include any data and utility programs needed for
reproducing the executable from it.  However, as a special exception,
the materials to be distributed need not include anything that is
normally distributed (in either source or binary form) with the major
components (compiler, kernel, and so on) of the operating system on
which the executable runs, unless that component itself accompanies
the executable.

  It may happen that this requirement contradicts the license
restrictions of other proprietary libraries that do not normally
accompany the operating system.  Such a contradiction means you cannot
use both them and the Library together in an executable that you
distribute.


  7. You may place library facilities that are a work based on the
Library side-by-side in a single library together with other library
facilities not covered by this License, and distribute such a combined
library, provided that the separate distribution of the work based on
the Library and of the other library facilities is otherwise
permitted, and provided that you do these two things:

    a) Accompany the combined library with a copy of the same work
    based on the Library, uncombined with any other library
    facilities.  This must be distributed under the terms of the
    Sections above.

    b) Give prominent notice with the combined library of the fact
    that part of it is a work based on the Library, and explaining
    where to find the accompanying uncombined form of the same work.

  8. You may not copy, modify, sublicense, link with, or distribute
the Library except as expressly provided under this License.  Any
attempt otherwise to copy, modify, sublicense, link with, or
distribute the Library is void, and will automatically terminate your
rights under this License.  However, parties who have received copies,
or rights, from you under this License will not have their licenses
terminated so long as such parties remain in full compliance.

  9. You are not required to accept this License, since you have not
signed it.  However, nothing else grants you permission to modify or
distribute the Library or its derivative works.  These actions are
prohibited by law if you do not accept this License.  Therefore, by
modifying or distributing the Library (or any work based on the
Library), you indicate your acceptance of this License to do so, and
all its terms and conditions for copying, distributing or modifying
the Library or works based on it.

  10. Each time you redistribute the Library (or any work based on the
Library), the recipient automatically receives a license from the
original licensor to copy, distribute, link with or modify the Library
subject to these terms and conditions.  You may not impose any further
restrictions on the recipients' exercise of the rights granted herein.
You are not responsible for enforcing compliance by third parties with
this License.


  11. If, as a consequence of a court judgment or allegation of patent
infringement or for any other reason (not limited to patent issues),
conditions are imposed on you (whether by court order, agreement or
otherwise) that contradict the conditions of this License, they do not
excuse you from the conditions of this License.  If you cannot
distribute so as to satisfy simultaneously your obligations under this
License and any other pertinent obligations, then as a consequence you
may not distribute the Library at all.  For example, if a patent
license would not permit royalty-free redistribution of the Library by
all those who receive copies directly or indirectly through you, then
the only way you could satisfy both it and this License would be to
refrain entirely from distribution of the Library.

If any portion of this section is held invalid or unenforceable under any
particular circumstance, the balance of the section is intended to apply,
and the section as a whole is intended to apply in other circumstances.

It is not the purpose of this section to induce you to infringe any
patents or other property right claims or to contest validity of any
such claims; this section has the sole purpose of protecting the
integrity of the free software distribution system which is
implemented by public license practices.  Many people have made
generous contributions to the wide range of software distributed
through that system in reliance on consistent application of that
system; it is up to the author/donor to decide if he or she is willing
to distribute software through any other system and a licensee cannot
impose that choice.

This section is intended to make thoroughly clear what is believed to
be a consequence of the rest of this License.

  12. If the distribution and/or use of the Library is restricted in
certain countries either by patents or by copyrighted interfaces, the
original copyright holder who places the Library under this License may add
an explicit geographical distribution limitation excluding those countries,
so that distribution is permitted only in or among countries not thus
excluded.  In such case, this License incorporates the limitation as if
written in the body of this License.

  13. The Free Software Foundation may publish revised and/or new
versions of the Lesser General Public License from time to time.
Such new versions will be similar in spirit to the present version,
but may differ in detail to address new problems or concerns.

Each version is given a distinguishing version number.  If the Library
specifies a version number of this License which applies to it and
"any later version", you have the option of following the terms and
conditions either of that version or of any later version published by
the Free Software Foundation.  If the Library does not specify a
license version number, you may choose any version ever published by
the Free Software Foundation.


  14. If you wish to incorporate parts of the Library into other free
programs whose distribution conditions are incompatible with these,
write to the author to ask for permission.  For software which is
copyrighted by the Free Software Foundation, write to the Free
Software Foundation; we sometimes make exceptions for this.  Our
decision will be guided by the two goals of preserving the free status
of all derivatives of our free software and of promoting the sharing
and reuse of software generally.

                            NO WARRANTY

  15. BECAUSE THE LIBRARY IS LICENSED FREE OF CHARGE, THERE IS NO
WARRANTY FOR THE LIBRARY, TO THE EXTENT PERMITTED BY APPLICABLE LAW.
EXCEPT WHEN OTHERWISE STATED IN WRITING THE COPYRIGHT HOLDERS AND/OR
OTHER PARTIES PROVIDE THE LIBRARY "AS IS" WITHOUT WARRANTY OF ANY
KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
PURPOSE.  THE ENTIRE RISK AS TO THE QUALITY AND PERFORMANCE OF THE
LIBRARY IS WITH YOU.  SHOULD THE LIBRARY PROVE DEFECTIVE, YOU ASSUME
THE COST OF ALL NECESSARY SERVICING, REPAIR OR CORRECTION.

  16. IN NO EVENT UNLESS REQUIRED BY APPLICABLE LAW OR AGREED TO IN
WRITING WILL ANY COPYRIGHT HOLDER, OR ANY OTHER PARTY WHO MAY MODIFY
AND/OR REDISTRIBUTE THE LIBRARY AS PERMITTED ABOVE, BE LIABLE TO YOU
FOR DAMAGES, INCLUDING ANY GENERAL, SPECIAL, INCIDENTAL OR
CONSEQUENTIAL DAMAGES ARISING OUT OF THE USE OR INABILITY TO USE THE
LIBRARY (INCLUDING BUT NOT LIMITED TO LOSS OF DATA OR DATA BEING
RENDERED INACCURATE OR LOSSES SUSTAINED BY YOU OR THIRD PARTIES OR A
FAILURE OF THE LIBRARY TO OPERATE WITH ANY OTHER SOFTWARE), EVEN IF
SUCH HOLDER OR OTHER PARTY HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH
DAMAGES.

                     END OF TERMS AND CONDITIONS


           How to Apply These Terms to Your New Libraries

  If you develop a new library, and you want it to be of the greatest
possible use to the public, we recommend making it free software that
everyone can redistribute and change.  You can do so by permitting
redistribution under these terms (or, alternatively, under the terms of the
ordinary General Public License).

  To apply these terms, attach the following notices to the library.  It is
safest to attach them to the start of each source file to most effectively
convey the exclusion of warranty; and each file should have at least the
"copyright" line and a pointer to where the full notice is found.

    <one line to give the library's name and a brief idea of what it does.>
    Copyright (C) <year>  <name of author>

    This library is free software; you can redistribute it and/or
    modify it under the terms of the GNU Lesser General Public
    License as published by the Free Software Foundation; either
    version 2.1 of the License, or (at your option) any later version.

    This library is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
    Lesser General Public License for more details.

    You should have received a copy of the GNU Lesser General Public
    License along with this library; if not, see <https://www.gnu.org/licenses/>.

Also add information on how to contact you by electronic and paper mail.

You should also get your employer (if you work as a programmer) or your
school, if any, to sign a "copyright disclaimer" for the library, if
necessary.  Here is a sample; alter the names:

  Yoyodyne, Inc., hereby disclaims all copyright interest in the
  library `Frob' (a library for tweaking knobs) written by James Random Hacker.

  <signature of Moe Ghoul>, 1 April 1990
  Moe Ghoul, President of Vice

That's all there is to it!
