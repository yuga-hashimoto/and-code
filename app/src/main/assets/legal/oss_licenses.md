# Third-Party Notices

AndCode includes, depends on, or downloads and runs the third-party software listed below. **This
license inventory covers those third-party components only — it is separate from, and not covered
by, AndCode's own [MIT License](LICENSE), which applies solely to this repository's Kotlin/Android
source code.** See [TRADEMARKS.md](TRADEMARKS.md) for trademark notices.

## Bundled native runtime components (PRoot / Termux-derived)

These binaries are downloaded from the official Termux package mirror at build time (pinned by
package version and SHA-256 hash of the compiled `.deb` in
[`runtime_tools/termux_assets.lock.json`](runtime_tools/termux_assets.lock.json)) and packaged into
the APK so the on-device Linux runtime can start. AndCode does not patch or modify these packages
beyond what Termux's own packaging performs. **Full license text for each of GPL-2.0, GPL-3.0 (which
LGPL-3.0 incorporates by reference), LGPL-3.0, and the BSD-3-Clause text used by
`libandroid-shmem` is bundled verbatim, unmodified, in [`THIRD_PARTY_LICENSES/`](THIRD_PARTY_LICENSES/)
in this repository and at `assets/legal/licenses/*.txt` inside the shipped APK** — not just linked.

| Package | Version | License (verified against upstream source, not guessed) | Copyright | Corresponding source (content-addressed) |
|---|---|---|---|---|
| `proot` | 5.1.107.89 | [GPL-2.0](THIRD_PARTY_LICENSES/GPL-2.0.txt), per [`TERMUX_PKG_LICENSE`](runtime_tools/termux-packaging-recipes/proot.build.sh) | The PRoot contributors | Upstream source archive `v5.1.107.89.zip`, SHA-256 `e1240f63de03e6da536d74041c7937ddd8737ab27743857d79285724b948eca8` (from [github.com/termux/proot](https://github.com/termux/proot/archive/v5.1.107.89.zip), tagged release, not `master`) |
| `libandroid-shmem` | 0.7 | [BSD-3-Clause](THIRD_PARTY_LICENSES/BSD-3-Clause-libandroid-shmem.txt), per [`TERMUX_PKG_LICENSE`](runtime_tools/termux-packaging-recipes/libandroid-shmem.build.sh) and the project's own `LICENSE` file | Copyright (c) 2013 Sergii Pylypenko; Copyright (c) 2017 Fredrik Fornwall | Upstream source archive `v0.7.tar.gz`, SHA-256 `1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867` (from [github.com/termux/libandroid-shmem](https://github.com/termux/libandroid-shmem/archive/refs/tags/v0.7.tar.gz), tagged release, not `master`) |
| `libtalloc` | 2.4.3 | [LGPL-3.0-or-later](THIRD_PARTY_LICENSES/LGPL-3.0.txt) for the actual runtime library. Termux's own packaging metadata tags the *package* `GPL-3.0` (a coarser, package-level tag), but the shared library source itself (`talloc.c`/`talloc.h`, the only files that become `libtalloc.a`/`libtalloc.so`) carries its own header: *"the following LGPL license applies to the talloc library. This does NOT imply that all of Samba is released under the LGPL"* — version 3 or later. Resolved; no longer `REQUIRES_LICENSE_REVIEW`. | Copyright (C) Andrew Tridgell 2004; Copyright (C) Stefan Metzmacher 2006 | Upstream source archive `talloc-2.4.3.tar.gz`, SHA-256 `dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd` (from [samba.org/ftp/talloc](https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz), versioned release path, not a mutable branch) |

**Packaging recipes, mirrored (not just linked):** the exact `TERMUX_PKG_*` build recipe used for
each package above — the thing that actually produces the `.deb` pinned by hash in
`termux_assets.lock.json` — is copied verbatim into
[`runtime_tools/termux-packaging-recipes/`](runtime_tools/termux-packaging-recipes/) in this
repository, as retrieved from `termux/termux-packages` on 2026-08-02. This exists specifically so
the corresponding-source reference does not depend on the upstream `termux-packages` repository's
mutable `master` branch continuing to show the same content in the future; the recipe as it existed
at the time these exact binaries were built is preserved here.

**Source availability — what is provided today, and what is `REQUIRES_LICENSE_REVIEW`:**

- *Provided:* the exact, versioned, content-addressed upstream source archive for each package
  (verifiable by the SHA-256 shown, independent of any branch or tag being later force-moved), and
  the exact `TERMUX_PKG_*` recipe file for each package, mirrored as retrieved from
  `termux/termux-packages` on 2026-08-02.
- *Not yet provided, and not claimed to be complete:* the recipe files above are not self-contained.
  They call into the broader `termux-packages` **build framework** — `termux_step_*` helper
  functions, environment set up by the framework's own scripts, the `termux-chroot` template file
  `proot`'s recipe references, and the cross-compilation toolchain — none of which is vendored into
  this repository. Nor has this project independently confirmed that the `termux-packages` `master`
  commit these recipes were retrieved from on 2026-08-02 is the *exact* commit that produced the
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
| OpenCode | `github.com/anomalyco/opencode` releases (musl Linux binary; URL/version pinned in [`app/src/main/assets/local-runtime-manifest.json`](app/src/main/assets/local-runtime-manifest.json)) | See [anomalyco/opencode](https://github.com/anomalyco/opencode) for the current license | AndCode integrates OpenCode as an independent third-party coding agent runtime; not affiliated with the OpenCode project |
| Claude Code | `downloads.claude.ai/claude-code/apk/latest` (Anthropic's official Alpine package repository, signature-verified) | Proprietary; governed by Anthropic's own Claude Code terms | Official CLI, unmodified; AndCode does not fork or re-host it |
| Google Antigravity CLI | `github.com/google-antigravity/antigravity-cli` releases (pinned in `AntigravityManifest.kt`, currently 1.1.7) | Proprietary; governed by Google Antigravity's own terms | Official CLI, unmodified; AndCode does not fork or re-host it |

## Base Linux root filesystems

| Component | Source | License | Notes |
|---|---|---|---|
| Alpine Linux minirootfs | `dl-cdn.alpinelinux.org` (version pinned in [`local-runtime-manifest.json`](app/src/main/assets/local-runtime-manifest.json), currently 3.24.1) | Each Alpine package keeps its own upstream license (mix of MIT, BSD, GPL, and others); see [alpinelinux.org](https://alpinelinux.org/) and each package's `APKINDEX`/`.PKGINFO` metadata | Used as the rootfs for OpenCode and Claude Code |
| Debian Bookworm rootfs (bootstrap + packages) | Official Debian mirrors (`deb.debian.org`, `security.debian.org`), fetched via `apt` at setup time — see `DebianRootfsInstaller.kt` | Each Debian package keeps its own upstream license (mix of GPL, LGPL, MIT, BSD, and others); see [debian.org/legal](https://www.debian.org/legal/) | Used only for the Antigravity CLI, which requires glibc |

## Gradle / Android dependencies

The `releaseRuntimeClasspath` Gradle configuration — everything actually resolved into a release
build, direct **and** transitive — has ~190 distinct artifacts as of this writing. Rather than a
hand-maintained table (which drifted from reality before: it previously listed Gson as a direct
dependency, which it isn't, and omitted TensorFlow Lite, `tensorflow-lite-support`, and
`org.tukaani:xz`, which are actually resolved), the authoritative, generated list is committed at
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
| **`org.tensorflow:tensorflow-lite`, `tensorflow-lite-api`** (wake-word inference engine) | `2.16.1` | Apache License 2.0 — [github.com/tensorflow/tensorflow](https://github.com/tensorflow/tensorflow) |
| **`org.tensorflow:tensorflow-lite-support`, `tensorflow-lite-support-api`** | `0.4.4` | Apache License 2.0 — [github.com/tensorflow/tflite-support](https://github.com/tensorflow/tflite-support) |
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

## Wake-word models (bundled ML models, not Gradle dependencies)

The on-device wake-word feature bundles three pre-trained model files from the
[openWakeWord](https://github.com/dscripka/openWakeWord) project (`app/src/main/assets/wakeword/`).
**openWakeWord's code is Apache-2.0, but its pre-trained models — including all three files bundled
here — are licensed separately, under
[CC BY-NC-SA 4.0](THIRD_PARTY_LICENSES/CC-BY-NC-SA-4.0.txt) (Attribution-NonCommercial-ShareAlike),
per the project's own documentation: *"All of the included pre-trained models are licensed under the
Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International license due to the inclusion
of datasets with unknown or restrictive licensing as part of the training data."***

| File | SHA-256 | Role |
|---|---|---|
| `hey_mycroft_v0.1.tflite` | `bf9e43136afd3ca323698820a6e32a47f885ef4c30a3b8b577ec71688a9d64d8` | The "hey mycroft" wake-word classification model |
| `melspectrogram.tflite` | `a3224c82810abc2a3586ea5bc68fd452d4fa74ad5972ae80f3075eda7fb0ff60` | Shared audio pre-processing (mel-spectrogram) model used by all openWakeWord wake words |
| `embedding_model.tflite` | `ca2b63f83378d8b1d20bc05ee4a0381e0f30a49b0639f4b9482133dc38ce45b9` | Shared feature-extraction backbone model used by all openWakeWord wake words |

The `hey_mycroft_v0.1.tflite` hash above was independently verified against the file bundled in this
repository and matches the file distributed by the openWakeWord project. **CC BY-NC-SA 4.0 is a
NonCommercial, ShareAlike license** — beyond attribution, it restricts commercial use of the
licensed material itself and requires any adaptation to be shared under the same license. AndCode's
own source is MIT-licensed and separate from these model files, but distributing the model files
themselves is subject to CC BY-NC-SA 4.0's terms, including its NonCommercial condition; this
project has not independently confirmed which party's use (a from-source builder's, versus an app
store distribution, versus any downstream commercial packaging) would or would not qualify as
"NonCommercial" under that license, so **this is flagged as `REQUIRES_LICENSE_REVIEW`** rather than
asserted as resolved — anyone building on or distributing this project for a commercial purpose
should independently evaluate whether their specific use is compatible with CC BY-NC-SA 4.0, or
replace these three files with wake-word models under a commercially-compatible license before
doing so. Attribution: openWakeWord, © the openWakeWord project and contributors
([github.com/dscripka/openWakeWord](https://github.com/dscripka/openWakeWord)), licensed
CC BY-NC-SA 4.0 for the model files specifically (full text bundled at
[`THIRD_PARTY_LICENSES/CC-BY-NC-SA-4.0.txt`](THIRD_PARTY_LICENSES/CC-BY-NC-SA-4.0.txt) and
`assets/legal/licenses/CC-BY-NC-SA-4.0.txt`).

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
The full GPL-2.0, GPL-3.0, LGPL-3.0, and BSD-3-Clause license texts referenced above are bundled at
`assets/legal/licenses/*.txt` inside the same APK (extractable with e.g. `unzip -p app.apk
assets/legal/licenses/GPL-2.0.txt`), and at [`THIRD_PARTY_LICENSES/`](THIRD_PARTY_LICENSES/) in this
source repository.
