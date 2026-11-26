# Test report — incomplete

Baseline: origin/main `22d633ea`; date: 2026-11-26. An artifact build is not a policy or device
compatibility approval. SDK location was set via `ANDROID_HOME`; Java 21 was used locally.

| Check | Result |
|---|---|
| `./gradlew detekt spotlessCheck --offline --no-daemon` | PASS |
| `:app:assemblePlayDebug :app:assemblePlayRelease :app:bundlePlayRelease :app:testPlayDebugUnitTest :app:lintPlayDebug` | PASS together (5m 18s); AGP 8.5.2 warns compileSdk 36 is beyond its tested range. |
| `:app:testGithubDebugUnitTest :app:testFdroidDebugUnitTest --offline` | PASS; this does not prove release builds, reproducibility, or device behavior. |
| `python3 -m unittest discover -s scripts/tests -p 'test_check_android_elf_alignment.py'` | PASS (4 tests) |
| Play debug merged manifest | package `com.yugahashimoto.andcode.play`, target 36; still contains all-files, special-use and exact-alarm permissions. |
| AAB | ~16 MiB; unsigned (`jarsigner -verify`); bundletool 1.18.2 `validate` PASS; config reports `PAGE_ALIGNMENT_16K`. |
| Debug APK zip alignment | `zipalign -c -P 16 -v 4` PASS; compressed libraries are not proof of ELF alignment. |
| APK and AAB ELF LOAD alignment | All inspected 64-bit objects report >= 0x4000. Both packaged `libopencode_android_proot_loader32.so` ELF32 executables report 0x1000. The strict script fails. Impact on 16 KB devices and applicable 32-bit behavior remain unverified; do not declare compatibility. |
| 16 KB emulator smoke test | Android API 36 Google APIs arm64 image, `getconf PAGE_SIZE` = 16384: Play debug install, Activity launch and onboarding start PASS. Full runtime install/execution results must be recorded separately. |
| Physical device smoke test | Android API 36, arm64, 4096-byte pages: new debug APK installation and Activity launch succeeded; permission prompt appeared. Experimental package was then removed. No setup or agent test was run. |

The automated workflow `.github/workflows/play-validation.yml` is manually dispatched during this
investigation. It records ELF findings as warnings and artifacts; the standalone strict checker fails on the two 32-bit loaders. A successful investigation job is not release approval. The workflow has not been run on GitHub Actions.

Not yet verified: complete fresh setup, all agent installs/auth/chat, sessions, edits, Git, diffs, MCP,
terminal, browser/automation, remote access, schedules, background/permission denial, app/device
restart, offline recovery, downloads and updates, data migration, 16 KB execution and x86_64
device operation. No Play submission or signing-key operation performed.
