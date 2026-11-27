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
