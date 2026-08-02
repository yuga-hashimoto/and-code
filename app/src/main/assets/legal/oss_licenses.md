# Third-Party Notices

AndCode includes, depends on, or downloads and runs the third-party software listed below. **This
license inventory covers those third-party components only — it is separate from, and not covered
by, AndCode's own [MIT License](LICENSE), which applies solely to this repository's Kotlin/Android
source code.** See [TRADEMARKS.md](TRADEMARKS.md) for trademark notices.

## Bundled native runtime components (PRoot / Termux-derived)

These binaries are downloaded from the official Termux package mirror at build time (pinned by
package version and SHA-256 hash in
[`runtime_tools/termux_assets.lock.json`](runtime_tools/termux_assets.lock.json)) and packaged into
the APK so the on-device Linux runtime can start. Versions below are current as of this file's last
update — check the lockfile for the exact pinned version shipped in a given build.

| Package | Version | License (per Termux package metadata) | Distributed by | Corresponding source |
|---|---|---|---|---|
| `proot` | 5.1.107.89 | GPL-2.0 | [Termux package mirror](https://packages.termux.dev/apt/termux-main) (`pool/main/p/proot/`) | Upstream: [github.com/termux/proot](https://github.com/termux/proot) (archive `v5.1.107.89.zip`); packaging: [termux-packages/packages/proot](https://github.com/termux/termux-packages/tree/master/packages/proot) |
| `libandroid-shmem` | 0.7 | BSD 3-Clause | [Termux package mirror](https://packages.termux.dev/apt/termux-main) (`pool/main/liba/libandroid-shmem/`) | Upstream: [github.com/termux/libandroid-shmem](https://github.com/termux/libandroid-shmem) (archive `v0.7.tar.gz`); packaging: [termux-packages/packages/libandroid-shmem](https://github.com/termux/termux-packages/tree/master/packages/libandroid-shmem) |
| `libtalloc` | 2.4.3 | GPL-3.0 (per Termux packaging metadata; upstream talloc is dual-licensed, with the `libtalloc` runtime library itself typically under LGPL-3.0 — REQUIRES_LICENSE_REVIEW to confirm which upstream license text applies to the exact binary artifact packaged here) | [Termux package mirror](https://packages.termux.dev/apt/termux-main) (`pool/main/libt/libtalloc/`) | Upstream: [talloc.samba.org](https://talloc.samba.org/talloc/doc/html/index.html) (source `talloc-2.4.3.tar.gz`); packaging: [termux-packages/packages/libtalloc](https://github.com/termux/termux-packages/tree/master/packages/libtalloc) |

Copyright for each package belongs to its respective upstream project (proot: the PRoot
contributors; libandroid-shmem: the Termux project; libtalloc/talloc: the Samba Team and
contributors). Full license text is available at each package's source link above, or via
`apt-get source <package>` against the Termux mirror. These packages are not modified by AndCode
beyond the packaging Termux itself performs.

## Coding agent binaries downloaded and run on-device

Not bundled in the APK — downloaded from the official distribution channel at first setup/update
and verified by checksum before use. See
[docs/AUTHENTICATION_AND_DATA_FLOW.md](docs/AUTHENTICATION_AND_DATA_FLOW.md) for how each is
installed and run.

| Component | Source | License | Notes |
|---|---|---|---|
| OpenCode | `github.com/anomalyco/opencode` releases (musl Linux binary; URL/version pinned in [`app/src/main/assets/local-runtime-manifest.json`](app/src/main/assets/local-runtime-manifest.json)) | See [sst/opencode](https://github.com/sst/opencode) upstream project for current license | AndCode integrates OpenCode as an independent third-party coding agent runtime; not affiliated with the OpenCode project |
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
