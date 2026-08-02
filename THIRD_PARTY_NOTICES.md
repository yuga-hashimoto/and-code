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

**Source availability / written offer:** the exact upstream source for each binary shipped is the
versioned, content-addressed archive referenced in the table above (verifiable by the SHA-256 shown,
independent of any branch or tag being later force-moved), combined with the mirrored packaging
recipe in `runtime_tools/termux-packaging-recipes/`. This repository, including the license texts and
recipes above, is publicly available at no charge for as long as the project is published, which
this project treats as satisfying GPLv2 §3(a)/(b) and LGPLv3's corresponding-source requirement for
these packages. If you need a copy of this repository at a specific historical commit corresponding
to a release you have and cannot obtain it from GitHub, open an issue at
[yuga-hashimoto/and-code](https://github.com/yuga-hashimoto/and-code/issues) and one will be
provided.

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

Versions below are as declared in [`app/build.gradle.kts`](app/build.gradle.kts) at the time of
this file's last update; consult that file for the current pinned versions.

| Dependency | Version | License |
|---|---|---|
| AndroidX Core, Lifecycle, Activity Compose, Navigation Compose, Compose BOM, Security Crypto, DocumentFile, Room, Startup, Profileinstaller | `core-ktx:1.15.0`, `lifecycle:2.8.7`, `activity-compose:1.9.3`, `navigation-compose:2.8.5`, `compose-bom:2024.12.01`, `security-crypto:1.1.0-alpha06`, `documentfile:1.0.1`, `room:2.6.1` | Apache License 2.0 — [developer.android.com/jetpack](https://developer.android.com/jetpack) |
| OkHttp / OkHttp-SSE / MockWebServer (test) | `4.12.0` | Apache License 2.0 — [square.github.io/okhttp](https://square.github.io/okhttp/) |
| kotlinx.serialization (JSON) | `1.7.3` | Apache License 2.0 — [github.com/Kotlin/kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) |
| ZXing (`com.journeyapps:zxing-android-embedded`, `com.google.zxing:core`) | `zxing-android-embedded:4.3.0` | Apache License 2.0 — [github.com/journeyapps/zxing-android-embedded](https://github.com/journeyapps/zxing-android-embedded), [github.com/zxing/zxing](https://github.com/zxing/zxing) |
| Apache Commons Compress | `1.27.1` | Apache License 2.0 — [commons.apache.org/proper/commons-compress](https://commons.apache.org/proper/commons-compress/) |
| Kotlin stdlib / Kotlin Gradle plugins | `2.0.21` | Apache License 2.0 — [kotlinlang.org](https://kotlinlang.org/) |
| kotlinx.coroutines (Android, test) | `1.9.0` | Apache License 2.0 — [github.com/Kotlin/kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| Koin (`koin-android`, `koin-androidx-compose`) | `4.0.1` | Apache License 2.0 — [insert-koin.io](https://insert-koin.io/) |
| Firebase Android SDK (BOM + Crashlytics client library) | `firebase-bom:34.17.0` | Apache License 2.0 for the client SDK itself; the **Firebase Crashlytics service** it talks to is a proprietary Google service governed by the [Firebase Terms of Service](https://firebase.google.com/terms) (see [THIRD_PARTY_SERVICES.md](THIRD_PARTY_SERVICES.md)) | Used for crash/error reporting in release builds |
| AndroidX Test / Espresso (test only, not shipped in release APK) | `test.ext:junit:1.2.1`, `espresso-core:3.6.1` | Apache License 2.0 | Test-only dependency |
| JUnit 4 (tests only, not shipped in release APK) | per Gradle-resolved transitive version | Eclipse Public License 1.0 — [junit.org/junit4](https://junit.org/junit4/) | Test-only dependency |

## Generating a full SBOM

For a machine-readable inventory of every resolved Gradle dependency (including transitive ones not
itemized above):

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath > build/sbom-gradle-deps.txt
```

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
