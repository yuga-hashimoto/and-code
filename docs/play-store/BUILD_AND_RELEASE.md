# Build and release investigation

The Play flavor is experimental and must not be uploaded or published. From the repository root:

```sh
./gradlew :app:assemblePlayDebug :app:assemblePlayRelease :app:bundlePlayRelease \
  :app:testPlayDebugUnitTest :app:lintPlayDebug
./gradlew detekt spotlessCheck
./gradlew :app:testGithubDebugUnitTest :app:testFdroidDebugUnitTest
```

Set `ANDROID_HOME` to the installed SDK if necessary. Existing `AND_CODE_STORE_FILE`,
`AND_CODE_STORE_PASSWORD`, `AND_CODE_KEY_ALIAS`, `AND_CODE_KEY_PASSWORD` are shared release
configuration. With none set, release outputs are unsigned. **Do not use existing production keys
for this experimental build**. Dedicated Play upload signing and protected CI secrets must be
designed before release. Verify signing on the actual artifact. Do not commit credentials.

The experimental workflow is manually dispatched and records ELF failures as warnings; independently run the strict checker before any release decision.
Use `bundletool validate --bundle=<path>` and build split APKs; verify manifests, ELF program
headers and `zipalign -c -P 16 -v 4 <apk>` and test on both ABI/page-size combinations. Bundletool
validation alone does not prove policy or runtime compatibility. No automatic Play upload is set up.
