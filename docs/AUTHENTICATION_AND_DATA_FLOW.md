# Authentication & Data Flow

This document describes, for each supported agent, exactly what AndCode's code does: where the
binary comes from, where it is installed, how it is launched, how sign-in works, what crosses from
the Linux/Debian runtime into the Android app, and what does not. It is written from the classes
that implement each flow, not from how the feature is expected to behave.

## OpenCode

- **Binary source:** the official OpenCode release archive, downloaded from
  `https://github.com/anomalyco/opencode/releases/download/v<version>/opencode-linux-<arch>-musl.tar.gz`,
  pinned per architecture in
  [`app/src/main/assets/local-runtime-manifest.json`](../app/src/main/assets/local-runtime-manifest.json)
  (`openCodeUrl`, `sha256`).
- **Install location:** extracted into the app's private on-device Alpine rootfs by
  `LocalRuntimeInstaller`/`LocalOpenCodeBackend`, after SHA-256 verification.
- **Launch:** started as a local HTTP server bound to `127.0.0.1:4097` inside the Alpine PRoot
  sandbox (`LocalOpenCodeBackend`); AndCode talks to it over `OpenCodeApiClient` like any OpenCode
  client would. For **remote OpenCode**, AndCode instead connects to an OpenCode server you already
  run on your own PC/Mac/Linux (`RemoteOpenCodeBackend`), given a URL/username/password you supply.
- **Authentication:** OpenCode's own provider authentication (for example, an Anthropic or OpenAI
  API key, or a provider's own OAuth flow) is handled by the OpenCode server itself. AndCode's
  `ProviderAuthDialog`/`SettingsViewModel` call the OpenCode API's `providerAuthMethods` /
  `authorizeProvider` / `completeProviderOAuth` endpoints and render whatever method, URL, or input
  prompts the server returns — the credential exchange itself happens between the OpenCode process
  and the provider, not inside AndCode.
- **Auth URL / code handling:** if a provider's auth method opens a browser URL, AndCode opens it
  via an Android `Intent.ACTION_VIEW` and forwards any code you type back to the OpenCode API. Full
  authorization URLs are not written to persistent logs (query strings are stripped by
  `SecretRedaction.redactUrlQuery` before a URL is logged).
- **Where OAuth/API-key material is stored — three distinct paths, not one:**
  1. A provider API key typed into AndCode's own UI (**Settings → Providers**) for the **local**
     on-device runtime is stored in `EncryptedSharedPreferences`, *and* AndCode's own
     `LocalProviderCredentialStore.syncToRuntime()` writes it into
     `root/.local/share/opencode/auth.json` inside the on-device Alpine rootfs — the plaintext JSON
     format the local OpenCode process reads. AndCode is the one writing that file in this path, not
     merely relaying to a process that manages it independently.
  2. Provider OAuth obtained through OpenCode's own API (flow above) is managed by the OpenCode
     process once obtained, the same way flow 1's synced file ends up managed by OpenCode afterward.
  3. For a **remote** OpenCode server, AndCode stores only the **connection profile** (server URL,
     username, password) in `EncryptedSharedPreferences` (`SecureSettingsRepository`); provider
     credentials live on the remote machine and are never synced to the Android app.
- **What AndCode reads:** session/message/tool-call data over OpenCode's REST/SSE API, so it can
  render the chat UI, and the provider list/connection status.
- **What AndCode does not read:** the contents of an already-existing `auth.json` beyond what it
  needs to merge in the managed provider keys from path 1 above; for a remote server, AndCode never
  reads that server's on-disk credential files at all.
- **Prompt/response data flow:** typed directly between the Android app and the OpenCode server
  (local loopback or your remote host) over HTTP/SSE; from there, OpenCode talks to whatever model
  provider you configured. No AndCode-operated server is in this path.
- **Logout:** disconnecting a provider (`disconnectProvider`) or a remote connection removes the
  credential from `EncryptedSharedPreferences` and/or asks OpenCode to drop the provider auth; it
  does not touch other agents' credentials.
- **AndCode-owned server:** none.

## Claude Code

- **Binary source:** the official Claude Code package repository at
  `https://downloads.claude.ai/claude-code/apk/latest`, verified against Anthropic's signing key
  (`https://downloads.claude.ai/keys/claude-code.rsa.pub`) — see `ClaudeCodeInstaller`.
- **Install location:** installed inside the on-device Alpine Linux rootfs (the same rootfs OpenCode
  uses), via `apk add`.
- **Launch:** run as a child process inside the PRoot sandbox by `ClaudeSandboxLauncher`, with a PTY
  when interactive input/output is needed (for example, during sign-in).
- **Authentication start:** `ClaudeAuthCoordinator.begin()` launches the official CLI with
  `claude auth login` — the same command you would run in a terminal. AndCode does not implement its
  own OAuth client for Claude.
- **Auth URL / code handling:** the coordinator scans the CLI's own terminal output for the
  Anthropic/Claude sign-in URL it prints, opens it in the Android browser (via `onOpenUrl` →
  `Intent.ACTION_VIEW`), and writes the confirmation code you paste back into the CLI's PTY stdin
  (`submitCode`) — exactly what happens if you typed it into a real terminal. AndCode never sees or
  needs the underlying OAuth token to do this.
- **Where the OAuth token is stored:** entirely inside the Claude Code CLI's own credential store,
  inside the on-device Alpine rootfs, written by the CLI itself the same way it would be on any
  Linux machine. AndCode's code does not read, parse, or copy this file.
- **What AndCode reads:** the CLI's terminal output (to detect the sign-in URL, confirmation code
  prompt, and success/failure), and the result of `claude auth status --text` to display which
  account is signed in (`signedInAccount()`).
- **What AndCode does not read:** the token file's contents, or any request Claude Code itself makes
  to Anthropic once sign-in is complete.
- **Prompt/response data flow:** once signed in, Claude Code is driven per-session as a child process
  in streaming-JSON mode; prompts and the file/tool context they need go from that process directly
  to Anthropic (or your configured provider), and responses stream back to AndCode's chat UI over
  the same process's stdout. No AndCode-operated server sits in between.
- **Logout:** `signOut()` runs `claude auth logout` inside the runtime, which is the official CLI's
  own sign-out — it removes the CLI's local credential, not something AndCode manages separately.
- **AndCode-owned server:** none.

## Google Antigravity

- **Binary source:** the official `agy` CLI release archive, downloaded from
  `https://github.com/google-antigravity/antigravity-cli/releases/download/<version>/agy_cli_linux_<arch>.tar.gz`,
  pinned in `AntigravityManifest` with a SHA-256 hash checked by `VerifiedRuntimeDownloader`.
- **Install location:** extracted into a dedicated **Debian Bookworm** rootfs at
  `usr/local/bin/agy` (Antigravity needs glibc, unlike OpenCode/Claude Code's Alpine/musl
  environment) — see `AntigravityInstaller`, `DebianRootfsInstaller`.
- **Launch:** run as a PTY child process inside the Debian PRoot sandbox by
  `AntigravitySandboxLauncher`. `AGY_CLI_DISABLE_AUTO_UPDATE=1` is set so version updates stay
  app-controlled instead of the CLI updating itself.
- **Authentication start:** `AntigravityAuthCoordinator.start()` launches the official `agy` binary
  with no arguments (its documented first-run flow), waits for its login chooser TUI, and sends
  Enter to accept the preselected "Google OAuth" option — again, the same choice you would make
  running `agy` in a terminal yourself.
- **Auth URL / code handling:** the coordinator parses the CLI's TUI output (via
  `AntigravityAuthParser`) for the `accounts.google.com` sign-in URL, opens it in the Android
  browser, and writes the code you paste back into the CLI's PTY. Everything painted by the TUI
  *after* the code field becomes visible is treated as potentially containing that code and is never
  surfaced to the UI or a log (`codeFieldLive` gate in `AntigravityAuthCoordinator`); diagnostic
  transcript text is passed through `AntigravityAuthParser.redact` before being shown or logged.
- **Where the OAuth token is stored:** inside the guest `$HOME` of the Debian rootfs, at
  `root/.gemini/antigravity-cli/antigravity-oauth-token` — written by the official CLI itself.
  AndCode's sign-out (`logout()`) deletes this file directly (it does not extract or read its
  contents first) because the interactive `/logout` TUI command proved unreliable to drive
  automatically; this still only removes the local credential and copies nothing anywhere.
- **What AndCode reads:** the CLI's TUI transcript (to detect the login chooser, the sign-in URL,
  and the awaiting-code state), and the output of `agy models` (used as an out-of-band signed-in
  check, since the CLI process stays running after a successful exchange rather than exiting).
- **What AndCode does not read:** the OAuth token file's contents, or Google's response payload
  during the token exchange itself (that exchange happens entirely inside the `agy` process).
- **Prompt/response data flow:** once signed in, `agy` is driven per-session as a child process;
  prompts and responses flow directly between that process and Google. No AndCode-operated server is
  in this path.
- **Logout:** `logout()` deletes the guest token file described above, then calls
  `AntigravityGuestSettings.repair()` to restore the guest config to a consistent state, and sets the
  in-app state directly to `Idle`. It does **not** run an `agy models` check to confirm the CLI now
  reports itself signed out (that out-of-band check is only used during sign-in, in `verifyModels()`,
  described above) — logout is confirmed by removing the credential file, not by re-querying the CLI.
- **AndCode-owned server:** none.

## Common properties across all three agents

- AndCode does not run its own authentication server, token-issuing service, or API proxy for any
  of the three agents. Every browser hand-off is a plain Android `Intent.ACTION_VIEW`; every code
  submission writes into the *existing* CLI process's own stdin/PTY.
- OAuth tokens for Claude Code and Antigravity never leave the rootfs they were written into. They
  are not copied into Android `SharedPreferences`/`EncryptedSharedPreferences`, not sent to a
  server, and not shared between agents (an Antigravity credential cannot be reused to authenticate
  Claude Code or OpenCode, and vice versa).
- Full authorization URLs (which can carry a `code`/`state` query parameter) are not written to
  persistent logs; see `SecretRedaction.redactUrlQuery` and `AntigravityAuthParser.redact`.
