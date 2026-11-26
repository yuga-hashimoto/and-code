# Google Play research (2026-11-26)

This is a working assessment, not a legal opinion or a Play approval. The experimental Play
variant is **not submission-ready**. Observed public listings are evidence of distribution, not
approval of the same implementation in AndCode.

## Official requirements and applicability

| Topic | Official source | Effect on AndCode and action |
|---|---|---|
| Native code, updates, interpreter exception | [Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/16559646?hl=en) | Play apps may not self-update outside Play or download executable code outside Play; the stated exception is code running in an interpreter or VM with indirect Android API access. PRoot is not itself evidence of that exception. Rootfs, downloaded ELF executables, native agents, and package-manager downloads need a supported distribution design and Google assessment. Hash checks establish integrity, not policy permission. |
| Dynamic loading | [Android dynamic code loading](https://developer.android.com/privacy-and-security/risks/dynamic-code-loading) | Avoid remote native code; model packages, scripts, native binaries and app updates separately. Do not mistake a linker workaround for policy compliance. |
| All files | [All files access](https://support.google.com/googleplay/android-developer/answer/10467955?hl=en) | Restricted permission; a core purpose and SAF insufficiency must be justified and declared. Current Play manifest inherits it; no approval is assumed. |
| Foreground/specialUse | [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [Play foreground service requirements](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en) | Three `specialUse` services need accurate declaration, user-visible notification and case-by-case Play assessment. |
| Background, microphone, boot | [FGS restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [Android 15](https://developer.android.com/about/versions/15/behavior-changes-15) | While-in-use microphone and boot restrictions apply; test wake word, denial, boot, schedule and service restarts on actual OS versions. |
| Exact alarms | [Schedule exact alarms](https://developer.android.com/develop/background-work/services/alarms/schedule) | `SCHEDULE_EXACT_ALARM` is special access, not guaranteed. `ScheduleManager` falls back to inexact alarms; verify UX and declarations. |
| AI content | [AI-generated content](https://support.google.com/googleplay/android-developer/answer/13985936?hl=en) | In-app reporting to the developer without leaving the app and prevention of restricted content are required for covered apps. No verified reporting pipeline exists. A link to an external issue or a local-only flag is not a completed report. |
| Browser automation | [User Data](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en), [Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/16559646?hl=en) | Audit user consent, credentials, sensitive pages, unauthorized actions and screenshot capture. The browser handoff is a useful control, not proof of complete protection. |
| Privacy, data safety | [User Data](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en), [Data safety](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en) | Map third-party provider/CLI traffic and SDKs, update public privacy policy and complete accurate disclosure for the final build. |
| OAuth | [OAuth for native apps](https://developers.google.com/identity/protocols/oauth2/native-app) | Use each provider's permitted user authorization flow; provider-specific terms still apply. |
| Target API | [Target API requirements](https://developer.android.com/google/play/requirements/target-sdk) | At research time new phone apps/updates must target API 36 or above; verify again at submission. |
| 16 KB | [16 KB page sizes](https://developer.android.com/guide/practices/page-sizes) | Check every ABI and every native dependency, APK ZIP alignment and device behavior. Downloaded Linux userland also needs runtime tests; neither AGP version nor successful build proves compatibility. |
| Bundle/signing | [Android App Bundles](https://developer.android.com/guide/app-bundle), [App signing](https://developer.android.com/studio/publish/app-signing) | New apps normally require AAB and Play App Signing. Distinguish upload and app signing keys; never upload existing secrets during this work. |
| Billing | [Payments](https://support.google.com/googleplay/android-developer/answer/9858738?hl=en) | Paid digital functionality in Play apps generally requires Play Billing, subject to applicable exceptions/programs. Separate provider subscriptions and external links require fact-specific review. |
| IP / brands | [Intellectual Property](https://support.google.com/googleplay/android-developer/answer/9888072?hl=en), [Impersonation](https://support.google.com/googleplay/android-developer/answer/9888374?hl=en) | Audit trademarks, affiliations, redistribution and attribution for every bundled component and store listing. |

## Repository findings

* `app/build.gradle.kts` originally targets 35 and packages PRoot, loader, talloc and
  android-shmem via `prepare_android_runtime_native_libs.py`. GitHub includes Firebase;
  F-Droid has no-op reporters. `app/src/main/AndroidManifest.xml` declares all-files access,
  microphone, camera, notification, boot, exact alarm, wake lock and special-use services.
* `LocalRuntimeInstaller` downloads Alpine/OpenCode from the pinned manifest, installs additional
  tools, and invokes agent installers. `DebianRootfsInstaller`, `ClaudeCodeInstaller`,
  `CodexInstaller`, `AntigravityInstaller` and the update controllers introduce other executable
  sources. Some are version-resolved dynamically. See `docs/AUTHENTICATION_AND_DATA_FLOW.md`.
* `VerifiedRuntimeDownloader` and `RuntimeArchive` verify and extract many assets; Codex uses npm
  SHA-512 and Claude uses apk repository signing. Do not generalize one mechanism to every download.
* `LocalOpenCodeBackend` binds locally; remote servers and provider accounts have separate data
  flows. `BrowserCommandWatcher` and `BrowserHandoff` implement foreground browser handoff;
  further secrets/exfiltration and authorization review is needed.
* `ScheduleManager` falls back to an inexact alarm when exact access is missing.
  `RuntimeAutoStartReceiver` and `ScheduleAlarmReceiver` require Android 16 device testing.
* Existing GitHub auth uses device flow; app-managed provider keys can be copied into the local
  OpenCode rootfs as plaintext for the CLI. `SecretRedaction` exists but a complete log audit is
  still needed. Existing `PRIVACY.md` describes GitHub Firebase opt-in, not this Play variant.
* No direct app APK installer was found in the inspected paths; a complete self-update/link audit is
  still required. Runtime updates are separate from app updates.

## Precedents (as observed, not authorization)

| Product | Evidence | Established / unknown |
|---|---|---|
| Termux Play | [Play source](https://github.com/termux-play-store/termux-apps), [exec wrapper](https://github.com/termux-play-store/termux-exec), [upstream discussion](https://github.com/termux/termux-app/discussions/4000) | Separate source, linker interception for W^X; upstream labels Play source a fork. Exact Play policy correspondence and current approval conditions are unknown. |
| Acode | [Play listing](https://play.google.com/store/apps/details?id=com.foxdebug.acode), [terminal docs](https://github.com/Acode-Foundation/acode-plugin-docs/blob/main/user-guide/terminal.md) | Documents Alpine/PRoot/apk and foreground service; says all-files terminal access is unavailable on Play. Detailed shipped source and review history require further comparison. |
| NativeCode | [Play listing](https://play.google.com/store/apps/details?id=com.zenithblue.nativecode) | Listing advertises local Linux and multiple agents. Runtime source, review, ABI and policies not verified. |
| MobileCLI | [Play listing](https://play.google.com/store/apps/details?id=com.mobilecli.play) | Listing advertises local terminal, packages and provider accounts; implementation and review are unknown. |
| UserLAnd | [Play listing](https://play.google.com/store/apps/details?id=tech.ula), [source](https://github.com/CypherpunkArmory/UserLAnd) | Listing describes downloaded Linux distributions; current Play-specific code, target, 16 KB and review remain unverified. |

## Provider constraints

* [OpenCode](https://opencode.ai/docs/) publishes Linux binaries and provider configuration; confirm
  the exact pinned version's license and redistribution rights, and each connected provider's terms.
* [Anthropic Claude Code](https://code.claude.com/docs/en/legal-and-compliance) permits certain
  hosted uses of unmodified Claude Code with each user authenticating and paying directly, and
  restricts third-party Claude.ai login, credential intermediation, and branding. Evaluate AndCode's
  CLI flow against all current conditions; do not claim blanket authorization.
* [Codex authentication](https://developers.openai.com/codex/auth) documents CLI authentication;
  verify current OpenAI terms for this specific third-party integration, redistribution and marks.
* Antigravity's current installer uses `github.com/google-antigravity/antigravity-cli`; independent
  verification of ownership, license, redistribution, authentication terms and trademark rights is
  required before describing this source as officially authorized.
* None of these sources establishes blanket permission for Android PRoot, all OAuth uses, or
  bundling every proprietary CLI into an AAB.
