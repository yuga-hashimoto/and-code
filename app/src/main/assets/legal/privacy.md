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
- These values are redacted before being written to logs or crash reports (see §7). `toString()`
  on the in-memory connection/credential data classes used in the codebase does not print secret
  fields in plain text.

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
  The built-in Android TTS engine and the on-device wake-word/speech-recognition models do not send
  audio or text off-device for that purpose.

## 5. Microphone, camera, and speech recognition

- **Microphone (`RECORD_AUDIO`)** is used for push-to-talk voice input and, if enabled, wake-word
  detection. Wake-word detection runs on-device using bundled TensorFlow Lite models
  (`app/src/main/assets/wakeword/`). Speech-to-text uses Android's built-in `SpeechRecognizer`
  unless you have configured a different provider.
- **Camera** is used only to scan a connection QR code (via the bundled ZXing scanner) when adding
  a remote OpenCode connection. AndCode does not otherwise access or store camera images.
- **Text-to-speech (TTS)** reads agent responses aloud, either via the Android system TTS engine
  (on-device) or, if you configure one, an external TTS provider as described in §4.

## 6. File access

AndCode requests **all-files access** (`MANAGE_EXTERNAL_STORAGE`) so that, once you grant it from
system settings, the on-device agent can open repositories and folders anywhere on your device (for
example, under `Download` or a folder synced from a PC) in place, without copying them into app
storage first. Nothing outside AndCode's own storage is read until you grant this permission and
explicitly open a folder or workspace. AndCode only reads/writes files inside folders you have
opened as a workspace or pointed the agent at.

## 7. Diagnostics and crash reporting

AndCode uses **Firebase Crashlytics** (a Google service) for crash and non-fatal error reporting in
release builds (`CrashReporter` in `core/diagnostics`). It:

- Sends the app version, build type, and OS version as custom keys.
- Sends crash stack traces and short diagnostic log lines you did not enter directly.
- Runs log/error messages and custom key values through redaction (`SecretRedaction`,
  `CrashReportSanitizer`) that strips patterns resembling API keys, tokens, `Authorization`/`Cookie`
  headers, and passwords before they are attached to a report, and truncates length.
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
