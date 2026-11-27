# Build and release investigation

The Play flavor is experimental and must not be uploaded or published. From the repository root:

```sh
./gradlew :app:assemblePlayDebug :app:assemblePlayRelease :app:bundlePlayRelease \
  :app:testPlayDebugUnitTest :app:lintPlayDebug
./gradlew detekt spotlessCheck
./gradlew :app:testGithubDebugUnitTest :app:testFdroidDebugUnitTest
```

For artifacts that need GitHub sign-in, set the public OAuth App client ID as `GITHUB_CLIENT_ID`
in the build environment (or as an untracked Gradle property) before building. The Play workflow
uses the repository variable `AND_CODE_GITHUB_CLIENT_ID`, as the existing GitHub release workflow
does. If it is absent, the build still works for experimentation but GitHub sign-in and the
current GitHub-based reporting flow are unavailable; CI emits a warning. Verify the chosen OAuth
application and device-flow authorization for the Play distribution before release.

The Play build downloads the mandatory Alpine packages from the official CDN and verifies every
archive against `runtime_tools/play-apk-cache.lock.json`; checked-in signed indexes are also
verified. Alpine mirrors can remove older package builds. If a pinned URL or checksum stops working,
refresh both ABI locks from verified signed indexes, audit the changed source and licenses, and
regenerate the in-app inventory with `python3 scripts/generate_play_apk_inventory.py`. Do not
silently fall back to unpinned packages or network installation on the device.

Set `ANDROID_HOME` to the installed SDK if necessary. The existing GitHub/F-Droid signing configuration uses `AND_CODE_STORE_FILE`,
`AND_CODE_STORE_PASSWORD`, `AND_CODE_KEY_ALIAS`, and `AND_CODE_KEY_PASSWORD`. The Play release
uses only `AND_CODE_PLAY_STORE_FILE`, `AND_CODE_PLAY_STORE_PASSWORD`, `AND_CODE_PLAY_KEY_ALIAS`,
and `AND_CODE_PLAY_KEY_PASSWORD`; with no Play credentials, it remains unsigned even when the
existing release credentials are present. Supply all four through the local environment or
untracked Gradle properties; do not commit credentials. Use a dedicated upload key after deciding
Play App Signing and keep the app-signing key and upload key distinct. Verify the actual signer.


The experimental workflow runs on pull requests and fails on 64-bit ELF alignment errors and reports 32-bit ELF alignment as warnings; independently run the strict checker before any release decision.
Use `bundletool validate --bundle=<path>` and build split APKs; verify manifests, ELF program
headers and `zipalign -c -P 16 -v 4 <apk>` and test on both ABI/page-size combinations. Bundletool
validation alone does not prove policy or runtime compatibility. No automatic Play upload is set up.

### Optional private report endpoint

The default experimental build uses the explicitly labeled public GitHub issue flow. The optional
private, accountless receiver is implemented in `services/play-report-worker` but is not deployed.
An operator with a reviewed Cloudflare deployment and moderation process can build Play with
`AND_CODE_PLAY_REPORT_ENDPOINT=https://example.invalid/v1/reports`; substitute the actual service
host. The URL is public configuration, not a secret. The build rejects non-HTTPS endpoints and URLs
containing credentials, a query, or a fragment. A failed private submission does not fall back to a
public report. See the receiver README for retention, operational requirements and local tests.
