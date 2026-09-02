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
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) with metadata resembling:

```yaml
Categories:
  - AI Chat
License: MIT
RepoType: git
Repo: https://github.com/yuga-hashimoto/and-code
SourceCode: https://github.com/yuga-hashimoto/and-code
IssueTracker: https://github.com/yuga-hashimoto/and-code/issues
Changelog: https://github.com/yuga-hashimoto/and-code/releases

Builds:
  - versionName: "1.2.16"
    versionCode: 55
    commit: v1.2.16
    subdir: app
    gradle:
      - fdroid
    gradleprops:
      - andcode.fdroidBuild=true
    # google-services/firebase-crashlytics still appear (apply false) in both
    # build.gradle.kts files; allowlist those known-safe lines rather than
    # have the scanner flag them.
    scanignore:
      - build.gradle.kts
      - app/build.gradle.kts

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: "1.2.16"
CurrentVersionCode: 55
```

This recipe was dry-run locally with `fdroid build --test` (pip-installed
`fdroidserver`, no Docker/buildserver VM available) against this repo's actual
`fdroid` flavor and confirmed to work end-to-end: clone at a pinned commit,
`clean`, source scan, and `assembleFdroidRelease` all succeeded, producing an
APK whose embedded versionName/versionCode matched the metadata. Both
`Repo`/`RepoType` and the two-line `scanignore` above were only discovered as
necessary through that dry run (without them, the build never even reaches the
Gradle step). It has not been submitted as a merge request yet — remaining
before that:

- decide whether any remaining dependency (e.g. the Vosk speech model,
  downloaded on first use from alphacephei.com rather than bundled — the
  models themselves are Apache-2.0, so this is likely not an `AntiFeature`,
  but F-Droid reviewers may still ask about it) needs an `AntiFeature` tag
- decide how to describe the GitHub OAuth sign-in dependency, since GitHub
  itself is a non-free network service (used for optional sign-in, not core
  functionality, so likely not `NonFreeNet`, but again a reviewer question)
- a real dry run against F-Droid's actual buildserver (`fdroid build --test
  --server`), which needs the Debian/Docker buildserver image this sandbox
  does not have
