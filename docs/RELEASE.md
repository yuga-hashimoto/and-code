# Release guide

## Unsigned CI artifacts

GitHub Actions builds `app-release-unsigned.apk`. These are for smoke testing only.

## Signed release APK / AAB (local)

1. Create a keystore (once):

```bash
keytool -genkey -v \
  -keystore and-code-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias and-code
```

2. Add to `~/.gradle/gradle.properties` (do not commit):

```properties
ANDROID_CODE_STORE_FILE=/absolute/path/and-code-release.jks
ANDROID_CODE_STORE_PASSWORD=...
ANDROID_CODE_KEY_ALIAS=and-code
ANDROID_CODE_KEY_PASSWORD=...
```

3. Optional: wire `signingConfigs` in `app/build.gradle.kts` reading those properties, then:

```bash
./gradlew assembleGithubRelease
# or
./gradlew bundleGithubRelease
```

4. Verify:

```bash
apksigner verify --print-certs app/build/outputs/apk/github/release/app-github-release.apk
```

## Versioning

- `versionName` / `versionCode` live in `app/build.gradle.kts`
- Tag releases as `vX.Y.Z` matching `versionName`
- Update `CHANGELOG.md` (or GitHub Release notes) with user-facing changes

## Pre-release checklist

- [ ] `./gradlew testGithubDebugUnitTest lintGithubDebug assembleGithubRelease`
- [ ] Manual smoke: local install, chat, permission approve/reject, remote connect
- [ ] `THIRD_PARTY_NOTICES.md` still accurate
- [ ] No secrets in git history

## F-Droid-compatible binary repository

The repository also has a `Publish F-Droid repository` workflow. It publishes
the signed APKs from GitHub Releases as a self-hosted F-Droid binary repository on
GitHub Pages. This is not an application submission to the official F-Droid
repository and does not require an F-Droiddata review.

Before enabling the workflow, create a dedicated repository signing keystore
and add these GitHub Actions secrets:

- `F_DROID_REPO_KEYSTORE_BASE64`: base64-encoded repository keystore
- `F_DROID_REPO_KEYSTORE_PASSWORD`: keystore password
- `F_DROID_REPO_KEY_ALIAS`: repository key alias
- `F_DROID_REPO_KEY_PASSWORD`: repository key password

For example, create the keystore locally with:

```bash
keytool -genkeypair -v \
  -keystore fdroid-repo.keystore \
  -alias and-code-fdroid \
  -keyalg RSA -keysize 4096 -validity 10000
base64 fdroid-repo.keystore | tr -d '\n'
```

Put the final command's output in `F_DROID_REPO_KEYSTORE_BASE64`. The other
three values must match the keystore when it is created. Do not commit the
keystore or its passwords.

The repository key is separate from the APK signing key. Back it up securely;
changing it makes existing clients treat the repository as a new repository.

Enable GitHub Pages with `GitHub Actions` as the source. After a published
release, users can add:

```text
https://yuga-hashimoto.github.io/and-code/fdroid/repo/
```

The workflow retains the latest 100 non-draft, non-prerelease GitHub releases.

## Official F-Droid catalog (build-from-source)

The self-hosted repository above only republishes the GitHub-signed APK; it does
not put AndCode in the official F-Droid catalog (browsable by category, e.g.
"AI Chat"). That requires F-Droid's own build server to compile the app from
source, which does not accept Firebase/Google Play services.

The `app` module has a `distribution` flavor dimension for this:

- `github` — current behavior, includes Firebase Analytics/Crashlytics.
- `fdroid` — no Firebase code at all (`app/src/fdroid/.../diagnostics/`
  provides no-op `AnalyticsReporter`/`CrashReporter` in place of
  `app/src/github/.../diagnostics/`, which keeps the Firebase-backed ones).

Build it locally with:

```bash
./gradlew -Pandcode.fdroidBuild=true :app:assembleFdroidRelease
```

The `-Pandcode.fdroidBuild=true` property additionally skips applying the
`com.google.gms.google-services` / `com.google.firebase.crashlytics` Gradle
plugins outright (they process `google-services.json` project-wide regardless
of flavor, so leaving them applied would still embed inert Google project
identifiers in the fdroid build).

Submitting to the official catalog means opening a merge request against
[fdroiddata](https://gitlab.com/fdroid/fdroiddata). This has been done:
[!48005](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/48005). Current
metadata, after several rounds of review feedback:

```yaml
AntiFeatures:
  NonFreeNet:
    en-US: optional GitHub OAuth sign-in, not required for core functionality
  TetheredNet:
    en-US: the on-demand Vosk wake-word speech model is only ever fetched from alphacephei.com
Categories:
  - AI Chat
License: MIT
AuthorName: Yu-ga
SourceCode: https://github.com/yuga-hashimoto/and-code
IssueTracker: https://github.com/yuga-hashimoto/and-code/issues
Changelog: https://github.com/yuga-hashimoto/and-code/releases

AutoName: AndCode

RepoType: git
Repo: https://github.com/yuga-hashimoto/and-code
Binaries: 
  https://github.com/yuga-hashimoto/and-code/releases/download/v%v/and-code-v%v-fdroid-release.apk

Builds:
  - versionName: 1.2.22
    versionCode: 61
    commit: aa5474d0c0c0a4e1c01b436d56ea18342187e516
    subdir: app
    gradle:
      - fdroid
    prebuild: sed -i -e '/firebase/d' -e '/gms/d' {..,.}/build.gradle.kts
    gradleprops:
      - andcode.fdroidBuild=true

AllowedAPKSigningKeys: f036e07002d8c2e6a5a64000f1211398d4831ff37cf280456a9a26d2f12617df

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 1.2.22
CurrentVersionCode: 61
```

This is the exact field order/quoting `fdroid rewritemeta` produces (it also
drops YAML comments, so any explanatory comments only live in this file and
the MR's discussion thread, not in the metadata itself).

`AllowedAPKSigningKeys` is the SHA-256 of the app's signing certificate,
extracted directly from a published release APK's APK Signing Block v2 (not
from the keystore) — `keytool`/`apksigner` weren't available locally, so this
was parsed by hand from the APK's binary signing block. `Binaries:` is a URL
template (`%v` = versionName) F-Droid's build server uses to fetch the
officially-published binary and diff it against what it builds from source,
as a supply-chain check.

The `Binaries:` URL points at a `-fdroid-release.apk` asset, not the plain
`-release.apk` one — the latter is the `github` flavor (Firebase included)
and will never byte-diff-match a `fdroid` flavor build. The Release workflow
(`.github/workflows/release.yml`) now also runs
`./gradlew -Pandcode.fdroidBuild=true :app:assembleFdroidRelease` (deliberately
without `GITHUB_CLIENT_ID`, matching how F-Droid's own build server invokes
it) and publishes that APK alongside the existing assets so this comparison
has something correct to compare against. It's signed with the same release
signing config as the `github` flavor, hence the shared `AllowedAPKSigningKeys`
fingerprint above.
