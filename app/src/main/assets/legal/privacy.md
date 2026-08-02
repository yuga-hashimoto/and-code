# Privacy Policy

_Last updated: 2026-08-02_

This document describes what AndCode stores, what it sends off-device, and to whom. It describes
the app as implemented in this repository, not aspirational behavior — where something is handled
entirely by a third-party official CLI rather than by AndCode, that is called out explicitly.

AndCode is an independent, local-first Android app. **AndCode does not operate a server that your
conversations, prompts, code, or files pass through.** There is no AndCode-owned backend between
you and the AI provider you configure.

## 1. What AndCode stores on your device

- **App preferences** (theme, language, font size, selected model/agent, UI toggles, permission
  mode selection, and similar settings) are stored in local app preferences / `SharedPreferences`.
- **Connection profiles** for remote OpenCode servers (name, URL, username, password, LAN/TLS
  options) and other sensitive settings (provider API keys, GitHub token, TTS provider API keys)
  are stored using `EncryptedSharedPreferences` backed by the Android Keystore
  (`AES256_GCM`/`AES256_SIV`), in `SecureSettingsRepository`. These values never leave the device
  except when they are used to make the specific connection or API call they were entered for
  (e.g., the OpenCode connection password is sent only to the OpenCode server it authenticates to;
  the GitHub token is sent only to GitHub's API).
- **Session/chat history, schedules, and workspace metadata** are stored locally in the app's own
  storage (SQLite/local catalog) so sessions survive an app restart.
- **Claude Code and Antigravity credentials are not stored by AndCode at all.** Each official CLI
  manages its own OAuth/token storage inside the on-device Linux (Alpine) or Debian rootfs it runs
  in — for example, Antigravity's guest token lives at `root/.gemini/antigravity-cli/antigravity-oauth-token`
  inside its rootfs, and Claude Code keeps its own credential store the same way it would on a
  desktop Linux machine. AndCode does not read, copy, or mirror the contents of these files into
  Android app preferences, and does not transmit them anywhere. See
  [docs/AUTHENTICATION_AND_DATA_FLOW.md](docs/AUTHENTICATION_AND_DATA_FLOW.md) for the full flow.

## 2. API keys, connection passwords, and GitHub tokens

- Provider API keys (entered through **Settings → Providers**), remote OpenCode connection
  passwords, and the GitHub personal access/OAuth token obtained through GitHub's device-flow login
  are stored with `EncryptedSharedPreferences` as described above.
- **If you use OpenCode's local (on-device) runtime,** a provider API key you enter is stored in two
  places: `EncryptedSharedPreferences` (as above) and — via `LocalProviderCredentialStore.syncToRuntime()`
  — synced in plaintext into `root/.local/share/opencode/auth.json` inside the on-device Alpine
  rootfs, because that is the file format the local OpenCode process itself reads to authenticate to
  the provider. That file is only as protected as the app's private rootfs directory (not additionally
  encrypted at rest the way `EncryptedSharedPreferences` is). See
  [OpenCode credential paths](THIRD_PARTY_SERVICES.md#opencode-credential-paths) in
  THIRD_PARTY_SERVICES.md for all three distinct OpenCode credential paths.
- These values are redacted on a best-effort basis before being written to logs or crash reports
  (see §7 — this is not a guarantee of complete removal in every code path). `toString()` on the
  in-memory connection/credential data classes used in the codebase does not print secret fields in
  plain text.

## 3. Data sent to AI services you configure

When you send a prompt, the resulting request — including the prompt text, relevant file contents,
and tool output the agent needs — is sent directly from the on-device CLI (or, for remote OpenCode,
from your PC's OpenCode server) to whichever AI provider you have configured (for example,
Anthropic for Claude Code, Google for Antigravity, or the model provider you connected inside
OpenCode). AndCode does not intercept, log, or relay this traffic through a server it operates.
Each provider's own privacy policy and terms of service apply to that traffic — see
[THIRD_PARTY_SERVICES.md](THIRD_PARTY_SERVICES.md).

## 4. MCP servers and voice services you configure

- If you add a Model Context Protocol (MCP) server (local command or remote URL) in **Settings →
  MCP**, AndCode/the agent CLI sends requests to that server as part of a session. Only the servers
  you explicitly configure are contacted; nothing is added automatically.
- If you enable a non-default text-to-speech provider (OpenAI TTS or ElevenLabs, configured in
  **Settings → Voice**) with your own API key, the text to be spoken is sent to that provider's API.

## 5. Microphone, camera, and speech recognition

- **Wake-word detection** runs fully on-device using bundled TensorFlow Lite models
  (`app/src/main/assets/wakeword/`); no audio leaves the device for this step.
- **Speech-to-text (push-to-talk and voice input)** is implemented with Android's standard
  `SpeechRecognizer.createSpeechRecognizer()` API (`SpeechRecognizerManager`), which delegates
  recognition to **whatever speech-recognition service is set as the device's default** — this is
  commonly a cloud-backed service (e.g. Google's), and audio and/or the recognized text may be sent
  to that service's servers depending on which service is installed and configured, and on that
  service's own settings (an on-device/offline recognizer, if one is installed and selected, would
  not need to). AndCode does not choose or restrict which recognition service handles this, and does
  not request the platform's "prefer offline" recognition flag.
- **Camera (`CAMERA`)** is used only to scan a connection QR code (via the bundled
  ZXing scanner) when adding a remote OpenCode connection. AndCode does not otherwise access or
  store camera images.
- **Text-to-speech (TTS)** reads agent responses aloud. By default this uses the Android system TTS
  engine and whichever voice you or the device has selected for it — some system TTS engines and
  voices are on-device, and others are network-backed (the platform, not AndCode, controls this), so
  the text being read aloud may be sent to that engine/voice provider's servers. If you instead
  configure the OpenAI or ElevenLabs TTS provider in **Settings → Voice**, the text is always sent to
  that provider's API as described above.

## 6. File access

AndCode requests **all-files access** (`MANAGE_EXTERNAL_STORAGE`) so that repositories and folders
anywhere on your device (for example, under `Download` or a folder synced from a PC) can be opened
in place, without copying them into app storage first. Nothing outside AndCode's own storage is
reachable until you grant this permission from system settings.

**Once granted, the boundary is at the OS permission, not at the folder you pick.** `DeviceStorage`
bind-mounts the *entire* shared storage volume (`/sdcard`) and any additional storage volumes (SD
cards, USB drives, as `/storage`) into every local agent's Linux/Debian sandbox as soon as the
permission is held — not only the specific workspace folder you open. The workspace you select in
the folder picker is the agent's default working directory, **not an OS-level access boundary**: a
CLI running with a permission mode that allows arbitrary commands (see the in-app risk warning
before enabling Full Access) could read or write other mounted files reachable from `/sdcard` or
`/storage`, not only the ones under the folder you chose. Revoke all-files access from Android's app
settings to remove these mounts.

## 7. Diagnostics and crash reporting

AndCode uses **Firebase Crashlytics** (a Google service) for crash and non-fatal error reporting in
release builds (`CrashReporter` in `core/diagnostics`). It:

- Sends the app version, build type, and OS version as custom keys that `CrashReporter.install()`
  sets explicitly.
- Sends crash stack traces and short diagnostic log lines. Custom log lines and custom-key values
  that AndCode's own code passes through `CrashReporter.log()`/`recordException()` are run through
  redaction (`SecretRedaction`, `CrashReportSanitizer`) that strips *patterns resembling* API keys,
  tokens, `Authorization`/`Cookie` headers, and passwords before they are attached to a report, and
  truncates length. **This is a best-effort pattern match, not a guarantee that every possible
  credential shape is caught** — for example, some provider key formats not covered by the current
  patterns, or a key with no recognizable label, could slip through if it ever ended up in a log
  line. Fatal crashes and their exception messages/stack traces that Crashlytics' own SDK collects
  automatically (rather than through `CrashReporter`) are **not** run through this redaction, since
  they are captured by Crashlytics itself before AndCode's code runs.
- The Crashlytics SDK also automatically collects standard device/app/installation diagnostics
  independent of anything AndCode's code sends explicitly — for example, device model, OS version,
  app version, a Firebase installation identifier, and free/total storage and RAM at crash time. See
  Firebase's own documentation for the current complete list, since AndCode's code does not control
  this baseline collection.
- Is disabled in debug builds (`setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)`).

Crashlytics data is governed by Google's and Firebase's own privacy terms; see
[THIRD_PARTY_SERVICES.md](THIRD_PARTY_SERVICES.md).

## 8. Scheduled tasks and background processing

Scheduled prompts (**Schedules**) run through a foreground service
(`ScheduleExecutionService`) and Android's `AlarmManager`/exact-alarm scheduling so a run can start
even if the app isn't in the foreground. A scheduled run sends the same prompt data to the same
agent/provider a manual chat message would; nothing about scheduling itself changes where data goes
(see §3). Voice/wake-word processing may similarly run as a foreground service
(`FOREGROUND_SERVICE_MICROPHONE`) while wake-word detection is enabled.

## 9. Deleting your data

- **Sign out** of Claude Code or Antigravity from their respective agent settings screens to remove
  their locally stored credentials (this deletes the token file inside that CLI's own rootfs).
- **Disconnect** a provider or GitHub from **Settings → Providers** / **Settings → GitHub** to
  remove the stored API key/token from `EncryptedSharedPreferences`.
- **Delete a local runtime** from **Settings → Local runtime** to remove the on-device Linux/Debian
  rootfs and everything installed inside it, including agent credentials and installed packages.
- **Uninstalling the app** removes all app-local storage, including encrypted preferences, cached
  sessions/schedules, and the on-device runtime directories. It does not affect accounts or data
  held by third-party services themselves (e.g., your Anthropic, Google, or GitHub account).

## 10. Third-party services

Any AI provider, MCP server, GitHub, or optional voice provider you connect to AndCode is a
separate service governed by its own privacy policy and terms. See
[THIRD_PARTY_SERVICES.md](THIRD_PARTY_SERVICES.md) for the current list and what each one receives.

## 11. Contact

This project is maintained on GitHub at
[yuga-hashimoto/and-code](https://github.com/yuga-hashimoto/and-code). Open an issue there with
privacy questions or data-deletion requests related to this repository's code.
