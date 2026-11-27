# Test report — incomplete

Baseline: origin/main `22d633ea`; date: 2026-11-26. The first table describes the earlier build;
its artifact size and toolchain are superseded by the additional work below. An artifact build is not a policy or device
compatibility approval. SDK location was set via `ANDROID_HOME`; Java 21 was used locally.

| Check | Result |
|---|---|
| `./gradlew detekt spotlessCheck --offline --no-daemon` | `spotlessCheck` PASS; root `detekt` was `NO-SOURCE` and did not analyze app code. |
| `:app:assemblePlayDebug :app:assemblePlayRelease :app:bundlePlayRelease :app:testPlayDebugUnitTest :app:lintPlayDebug` | PASS together (5m 18s); AGP 8.5.2 warns compileSdk 36 is beyond its tested range. |
| `:app:testGithubDebugUnitTest :app:testFdroidDebugUnitTest --offline` | PASS; this does not prove release builds, reproducibility, or device behavior. |
| `python3 -m unittest discover -s scripts/tests -p 'test_check_android_elf_alignment.py'` | PASS (4 tests) |
| Play debug merged manifest | package `com.yugahashimoto.andcode.play`, target 36; still contains all-files, special-use and exact-alarm permissions. |
| AAB | ~16 MiB; unsigned (`jarsigner -verify`); bundletool 1.18.2 `validate` PASS; config reports `PAGE_ALIGNMENT_16K`. |
| Debug APK zip alignment | `zipalign -c -P 16 -v 4` PASS; compressed libraries are not proof of ELF alignment. |
| APK and AAB ELF LOAD alignment | All inspected 64-bit objects report >= 0x4000. Both packaged `libopencode_android_proot_loader32.so` ELF32 executables report 0x1000. The strict script fails. Impact on 16 KB devices and applicable 32-bit behavior remain unverified; do not declare compatibility. |
| 16 KB emulator smoke test | Android API 36 Google APIs arm64 image, `getconf PAGE_SIZE` = 16384: Play debug install and Activity launch PASS; Alpine/OpenCode 1.19.6 setup reached ready, and PRoot plus OpenCode processes and a listener on 127.0.0.1:4107 were observed. Agent authentication/chat and 32-bit loader execution were not tested. |
| Physical device smoke test | Android API 36, arm64, 4096-byte pages: new debug APK installation and Activity launch succeeded; permission prompt appeared. Experimental package was then removed. No setup or agent test was run. |

The updated workflow `.github/workflows/play-validation.yml` runs on pull requests. It fails on
unaligned 64-bit ELF segments and records the known 32-bit loader findings as warnings; the default
strict checker also fails on those loaders. A successful job is not release approval. The updated
workflow has not yet been run on GitHub Actions.

Not yet verified: complete onboarding, other agent installs, all provider auth/chat, sessions, edits, Git, diffs, MCP,
terminal, browser/automation, remote access, schedules, background/permission denial, app/device
restart, offline recovery, downloads and updates, data migration, 16 KB execution and x86_64
device operation. No Play submission or signing-key operation performed.

## Additional work (2026-11-27)

- A Play debug APK with bundled mandatory Alpine caches was installed on a fresh API 36 arm64
  16 KB emulator. With airplane mode enabled and `Active default network: none`, OpenCode 1.19.6,
  the Alpine rootfs, and 134 required package installation operations succeeded without pre-seeding
  app storage. PRoot and OpenCode processes started. An instrumented test independently verified
  offline installation and ran `git --version` and `opencode --version`.
- For each ABI, the lock records 142 package archives and two signed repository indexes. All 284
  package archives passed Alpine `apk verify`; a separate clean download produced identical cache
  archive SHA-256 values. The base-package and package metadata are checked against the pinned
  rootfs and indexes. The app's Play-only OSS notices include the generated source-recipe inventory.
- The optional full developer toolchain was simulated separately on arm64: 111 additional package
  operations require about 628.6 MiB compressed and 1,436.4 MiB installed. It is not bundled and
  its external download remains a Play policy blocker. Codex 0.172.0 archives were separately
  downloaded from npm and verified against npm SHA-512; extracting only the two binaries still
  produced about 147.1 MiB for arm64 and 145.3 MiB for x86_64. These are measurements, not a
  decision or license clearance to redistribute those binaries.
- After upgrading to AGP 8.11.1 and Gradle 8.13, the combined Play release APK/AAB, GitHub and
  F-Droid debug APK, three debug unit-test suites, Play lint and spotless checks passed. The root
  `detekt` task was `NO-SOURCE`; it is not evidence of app static analysis.
  Each flavor reported 1,879 unit tests with zero failures or errors. The unsigned Play AAB was
  approximately 174 MiB and passed bundletool validation. All checked 64-bit release APK ELF LOAD
  segments met 16 KB alignment; both 32-bit loaders remained warnings.
- After incorporating `origin/main` at `adfcfb70`, the same build, Play lint and format gates passed
  again; each flavor reported 1,886 unit tests with zero failures or errors. The generated Play
  assets were rebuilt from an absent directory and validated, and bundletool validated the new AAB.
- `:app:assembleFdroidRelease -Pandcode.fdroidBuild=true` passed without a GitHub client ID, using
  the normal pinned-mirror native assets. This does not test the separate source-built native
  dependencies or reproduce an F-Droid build server artifact.
- Play report dialog: two Android 16 arm64 emulator UI tests passed with a mocked submit callback.
  This is not a live GitHub report and does not establish accountless reporting.
- Play Alpine: after packaging, both `.tgz` assets matched the manifest SHA-256. On an Android 16
  arm64 4 KB emulator with Wi-Fi disabled, the app copied the bundled Alpine archive to its cache
  and its SHA-256 matched; OpenCode download then failed as expected. After Wi-Fi was restored and
  Retry was tapped, OpenCode 1.19.6 and PRoot started and listened on 127.0.0.1:4107.
- A pair of dummy keystores verified separation between existing distribution and Play release
  signing; incomplete Play credentials fail configuration. No production keys were used.
- All release and policy conclusions above remain provisional after these changes.

- The bundled Alpine APK also installed successfully on an Android 16 arm64 16 KB emulator; its
  cached SHA-256 matched the manifest and PRoot/OpenCode reached ready on port 4107. The emulator
  exposes no 32-bit ABI, so this does not test the 32-bit loader.
- With both Alpine and OpenCode bundled, on the same 16 KB emulator and with airplane mode enabled
  (`Active default network: none`), both arm64 archives were copied and matched the manifest
  SHA-256. Setup then failed while resolving the additional Alpine packages, as expected. After
  reconnecting, the first package attempt had an I/O error; going back and retrying succeeded,
  and PRoot plus OpenCode 1.19.6 started. This does not establish full offline setup or Play policy
  compliance for downloaded executable packages.
- Three instrumented tests passed on the 16 KB emulator: two report-dialog tests and one test
  opening and hashing all four bundled ABI/archive assets. Report submission was mocked. After
  moving the bundle test to `androidTestPlay`, the same three tests passed again, all three flavor
  test APKs built, and their dex files confirmed that the bundle test exists only in Play.

## Continuation: private report and offline cache (2026-11-27)

- On the API 36 arm64 16 KB emulator, five Play instrumented tests passed with no default network:
  bundled asset integrity, full mandatory-package offline installation with Git/OpenCode execution,
  and three report-dialog tests including the accountless private mode. Report submission was mocked.
- The optional receiver passed six unit tests, a Wrangler dry run, and a local workerd/D1 integration
  test covering report insertion, read denial and scheduled deletion of an expired synthetic report.
  No external receiver has been deployed or configured; actual moderation and deployed end-to-end
  operation remain open. Cloudflare processes connection metadata, and provider logs/backups can
  outlive report table entries.
- The Play debug APK, Play unit tests and Play lint passed after adding the report client. Three
  dummy-key tests confirmed that Play and existing-distribution signing remain isolated. The latest
  unsigned Play AAB with both mandatory package caches measured approximately 273 MiB; the earlier
  174 MiB measurement above predates those caches.
- The x86_64 16 KB emulator job has been added to CI; its result is not established by the arm64
  device tests above. The 32-bit PRoot loader and source/license completeness remain open.

- The complete local regression command passed after the report-client change: Play release
  APK/AAB and all three debug APKs built, Play lint and formatting passed, and each flavor ran
  1,891 unit tests with no failures or errors. The root detekt task remained `NO-SOURCE`.
- For the 20 Alpine recipe revisions selected by GPL/LGPL/MPL metadata, 189 APKBUILD-listed
  source files matched SHA-512 and 191 aports recipe files matched both Git blob identity and
  SHA-256. A fresh download into an empty cache produced a byte-identical archive with 381 regular-file entries
  including the manifest and SHA-256 `a3fdde4698b4f06dba9d1396e64ca01c82b911a0baeebec8719af3336d8afadb`.
  This local, unhosted Alpine subset does not establish complete license compliance.
- The x86_64 16 KB CI image booted and reported a 16,384-byte page size, but the first run stopped
  before instrumentation because the emulator action executed a multiline shell loop one line at a
  time. The workflow was corrected; the actual x86_64 runtime result must be verified on a
  subsequent run.
- The expanded Alpine source bundle covers all 62 pinned recipe revisions, with 315 recipe-listed
  source files and 320 packaging files. Every source matched its APKBUILD SHA-512 and every recipe
  file matched its pinned SHA-256 and Git blob identity. A fresh empty-cache download produced the
  same 636-entry archive, SHA-256 `02be386876384273e2bff013d2a77a04dbbf405d666477c4a50a178549af5118`.
  The archive is local and unhosted; it does not include OpenCode dependencies or settle all license
  obligations. Two downloads of the OpenCode `v1.19.6` source commit matched SHA-256
  `4fe1e51fba53e229bb50cab0065aa1def7306134f0ffa8bd4876b3db51bee0d0`; its MIT license matched the
  existing bundled OpenCode license text. This is not proof of a reproducible release binary.
- The next x86_64 CI run booted the 16 KB image, but the first test saw a default network after
  Gradle installed the APKs; a subsequent instrumentation process crash also needs diagnosis. The
  workflow now installs APKs first, waits for a stable offline state, runs instrumentation directly,
  and captures logcat on failure. The same runner passed both tests locally on the 16 KB arm64 image;
  an x86_64 pass must still be established.
- On the subsequent x86_64 16 KB GitHub emulator run, connectivity stayed disabled and the asset
  integrity test passed, but the offline installer failed while executing its first Alpine command:
  PRoot reported guest termination by signal 7 (`SIGBUS`). Inspection of the pinned x86_64 Alpine
  BusyBox, `apk`, musl loader and OpenCode ELF program headers found 4 KB (`0x1000`) LOAD alignment;
  in BusyBox and musl, at least one segment's file offset and virtual address are not congruent
  modulo 16 KB. This is a plausible cause, not a confirmed faulting instruction or complete root
  cause. The x86_64 16 KB local runtime remains broken and this CI gate must remain failing until
  the binaries or execution mechanism are fixed and the same test passes.
- The Play OSS notices were expanded with the upstream Bun 1.4.4 license information and the
  JavaScriptCore LGPL-2.0 and Bun-linked LGPL-2.1 texts. The Play asset-integrity instrumented test
  checks that the generated notice is present inside the APK; this is not a complete license audit.
- As a local execution experiment only, Termux `qemu-user-x86-64` 11.1.3 (aarch64 Termux package SHA-256
  `0ab9a9b0b1587c344eea1873cb2dd8c0d63f227fc5e5c3d4271adf9cb43e913f`) was run with PRoot on
  the ARM64 16 KB emulator against the x86_64 Alpine rootfs. The 134 required package operations
  completed offline and Git ran. The x86_64 OpenCode `--version` returned `1.19.6` only after
  setting `BUN_JSC_useJIT=false`; `opencode serve` answered one health request, then crashed
  with SIGSEGV within 10 seconds. The baseline release
  archive contained the same executable bytes and also crashed. This was an ARM-host cross-ABI
  experiment, not a fix, a Play-distributed component, or a substitute for the failing x86_64 test.
- A full scan of the generated Alpine minirootfs, mandatory APK cache and OpenCode archive found
  315 ELF files per ABI: 17 in the base, 297 in packages and one OpenCode executable. All 315 arm64
  files passed the 16 KB LOAD-alignment and offset/address congruence checks; all 315 x86_64 files
  had 4 KB LOAD alignment. `scripts/check_play_guest_elf_alignment.py` reproduces this audit and
  returns a failure while the x86_64 guest binaries remain incompatible. Passing this static check
  alone would not establish runtime compatibility.
- On the x86_64 16 KB CI emulator, the regular offline install still terminated with signal 7. The first shell-UID QEMU diagnostic stopped before package installation; it therefore did not establish whether QEMU works on that host. The next diagnostic forces PRoot to route same-architecture ELFs through QEMU and collects a native PRoot trace.
