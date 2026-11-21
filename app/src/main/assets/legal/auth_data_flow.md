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
- **Update lookup:** newer releases are found by `LocalRuntimeReleaseClient`, which asks GitHub's
  `https://api.github.com/repos/anomalyco/opencode/releases/latest` for the newest release. That
  request is made when you open **Settings → Agents → OpenCode** (and again each time a start,
  stop, restart, update or rollback started from that screen finishes), when you tap its update
  check, and when the local runtime starts with an installed version older than the one bundled in
  this app build. The update archive is the asset
  GitHub names in that response, downloaded over HTTPS and installed only if it matches the
  SHA-256 digest GitHub reports for it. See [Release and update lookups](#release-and-update-lookups).
- **Install location:** extracted into the app's private on-device Alpine rootfs by
  `LocalRuntimeInstaller`/`LocalOpenCodeBackend`, after SHA-256 verification.
- **Launch:** started as a local HTTP server bound to `127.0.0.1:4097` inside the Alpine PRoot
  sandbox (`LocalOpenCodeBackend`); AndCode talks to it over `OpenCodeApiClient` like any OpenCode
  client would. `OPENCODE_DISABLE_AUTOUPDATE=true` is set so version updates stay app-controlled
  instead of OpenCode updating itself. For **remote OpenCode**, AndCode instead connects to an OpenCode server you already
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
- **Release lookups:** there is no version lookup in AndCode's own code for this agent. Installing
  or updating Claude Code (**Settings → Agents → Claude Code**, the setup guide, or a runtime
  install that includes it) runs `wget` and `apk` inside the PRoot sandbox: `wget` downloads the
  signing key (on every update, not only at install), and `apk update` / `apk add` read the package
  index and download the package from `https://downloads.claude.ai/claude-code/apk/latest`. Because
  `apk update` refreshes every repository the sandbox has configured, the Alpine package
  repositories the sandbox is set up with (`dl-cdn.alpinelinux.org`) are contacted in the same
  step, and a first install also fetches `util-linux` and `jq` from them. These requests come from
  the sandbox's own tools, not from AndCode's HTTP client, so they carry those tools' own
  `User-Agent`, not `AndCode`. See [Release and update lookups](#release-and-update-lookups).
- **Install location:** installed inside the on-device Alpine Linux rootfs (the same rootfs OpenCode
  uses), via `apk add`.
- **Launch:** run as a child process inside the PRoot sandbox by `ClaudeSandboxLauncher`, with a PTY
  when interactive input/output is needed (for example, during sign-in).
  `CLAUDE_CODE_DISABLE_AUTOUPDATER=1` is set so version updates stay app-controlled instead of the
  CLI updating itself.
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
  `https://github.com/google-antigravity/antigravity-cli/releases/download/<version>/agy_cli_linux_<arch>.tar.gz`.
  The newest release is resolved at install/update time from GitHub's
  `https://api.github.com/repos/google-antigravity/antigravity-cli/releases/latest`
  (`AntigravityReleaseClient`); the SHA-256 digest GitHub reports for the asset is checked by
  `VerifiedRuntimeDownloader`. If that lookup fails, the release pinned in `AntigravityManifest`
  (with hashes compiled into the app) is used instead. The lookup is made only when Antigravity is
  installed or updated (**Settings → Agents → Antigravity**, the setup guide, or a runtime install
  that includes it) — there is no background check. See
  [Release and update lookups](#release-and-update-lookups).
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

## OpenAI Codex

- **Binary source:** the official `@openai/codex` package from the public npm registry. Codex has
  no Alpine package and no GitHub release, so `CodexReleaseClient` makes two lookups against
  `https://registry.npmjs.org/`: `@openai/codex/latest` (the newest version), then
  `@openai/codex/<version>-linux-arm64` or `@openai/codex/<version>-linux-x64` (that platform's
  tarball URL and its SHA-512 `integrity` string). `CodexInstaller` downloads the tarball from the
  URL the registry reports (HTTPS required; in practice `registry.npmjs.org/@openai/codex/-/…`),
  checks it against that SHA-512 integrity, and extracts only the `codex` and
  `codex-code-mode-host` binaries. The lookups are made only when Codex is installed or
  reinstalled (**Settings → Agents → Codex**, the setup guide, or a runtime install that includes
  it) — there is no background check. See [Release and update lookups](#release-and-update-lookups).
- **Install location:** `usr/local/bin` inside the on-device Alpine rootfs — the same rootfs
  OpenCode and Claude Code use (Codex's native binary targets musl, so no second rootfs is needed);
  see `CodexInstaller`.
- **Launch:** `codex app-server` runs as one long-lived child process inside the PRoot sandbox
  (`CodexSandboxLauncher`, `CodexRuntime`) with `CODEX_HOME=/root/.codex`; AndCode talks to it over
  JSON-RPC 2.0 on stdio (`CodexJsonRpcClient`). Unlike OpenCode, Claude Code and Antigravity,
  AndCode sets no flag to switch off Codex's own update behavior; whatever network access the Codex
  CLI makes by itself is the CLI's own and not something AndCode relays or controls.
- **Authentication start:** the same provider dialog OpenCode uses offers two methods
  (`CodexTarget.providerAuthMethods`). **ChatGPT account:** `account/login/start` returns a sign-in
  URL, which AndCode opens in the Android browser; the browser then lands on a callback Codex
  itself serves on `localhost:1455`, and completion arrives as an `account/login/completed`
  notification — nothing has to be pasted back. **API key:** the key you type is written to the
  stdin of `codex login --with-api-key`.
- **Where the credential is stored:** inside Codex's own credential store under `CODEX_HOME`
  (`/root/.codex`) in the on-device Alpine rootfs, written by the CLI itself. AndCode's code does
  not read, parse, or copy it. An API key typed into the dialog lives only in that dialog's
  in-memory state until it is handed to `codex login`; it is not saved to
  `EncryptedSharedPreferences`.
- **What AndCode reads:** the JSON-RPC stream (threads, turns, items, the model list, approval
  requests), and the result of `account/read` only to tell whether an account is signed in.
- **What AndCode does not read:** the credential file's contents, or the token exchange itself
  (that happens entirely inside the `codex` process).
- **Prompt/response data flow:** prompts and the file/tool context they need go from the `codex`
  process directly to OpenAI (or whichever provider the Codex CLI itself is configured for), and
  responses stream back to AndCode's chat UI over the same process's stdout. No AndCode-operated
  server sits in between.
- **Logout:** `logout()` runs `codex logout` inside the runtime — the official CLI's own sign-out,
  which removes the CLI's local credential.
- **AndCode-owned server:** none.

## Release and update lookups

AndCode's own HTTP client (not the agent CLIs) asks the sources below which release to install, and
downloads it. Each row names the code that does it; the agent sections above say when.

| Agent | Destination | What is requested | Verified against |
|---|---|---|---|
| OpenCode | `api.github.com` | `GET /repos/anomalyco/opencode/releases/latest` (`LocalRuntimeReleaseClient`), then the release asset GitHub names (served from `github.com`) | SHA-256 digest GitHub reports for the asset |
| Antigravity | `api.github.com` | `GET /repos/google-antigravity/antigravity-cli/releases/latest` (`AntigravityReleaseClient`), then the release asset GitHub names (served from `github.com`) | SHA-256 digest GitHub reports for the asset; falls back to the build's pinned release and compiled-in hashes if the lookup fails |
| Codex | `registry.npmjs.org` | `GET /@openai/codex/latest`, `GET /@openai/codex/<version>-linux-<arch>` (`CodexReleaseClient`), then the tarball URL the registry reports | SHA-512 `integrity` the registry records |
| Claude Code | `downloads.claude.ai` (and the sandbox's Alpine package repositories) | `wget` of `keys/claude-code.rsa.pub`, then `apk update` / `apk add` against `claude-code/apk/latest` — run by the sandbox's own tools, not AndCode's HTTP client | Anthropic's APK signing key |

For the requests AndCode's own HTTP client makes (OpenCode, Antigravity and Codex):

- **What is sent:** an unauthenticated HTTPS `GET` with `User-Agent: AndCode` (plus GitHub's
  `Accept: application/vnd.github+json` media type for the two GitHub lookups) and the standard
  headers HTTP libraries add. There is no `Authorization` header — the GitHub token used for pull
  requests (**Settings → GitHub**) is not attached to them, even when it is connected — and no
  prompt, code, workspace path, account identifier, or credential.
- **What the destination can see:** as with any HTTPS request, the operator of the server (GitHub,
  npm, Anthropic) sees your IP address and the time of the request, and that a client calling itself
  `AndCode` asked for that release. The `apk`/`wget` requests made for Claude Code show those tools'
  own `User-Agent` instead.
- **Redirects:** GitHub release downloads (`github.com/<owner>/<repo>/releases/download/…`) may be
  redirected by GitHub to its own asset-storage hosts, which GitHub operates.
- **Failure behavior:** Antigravity falls back to its pinned release when its lookup fails. For
  OpenCode and Codex a failed lookup is reported as a failed check or install; no other
  destination is tried.
- **No AndCode server:** none of these requests go to a server AndCode operates.
- **When:** only on the actions the agent sections describe (there is no periodic background job)
  — except the OpenCode check, which also runs on opening **Settings → Agents → OpenCode** and when
  the local runtime starts on a version older than the one bundled with the app.

## Common properties across all four agents

- AndCode does not run its own authentication server, token-issuing service, or API proxy for any
  of the four agents. Every browser hand-off is a plain Android `Intent.ACTION_VIEW`; every code
  submission writes into the *existing* CLI process's own stdin/PTY.
- OAuth tokens for Claude Code, Codex and Antigravity never leave the rootfs they were written
  into. They are not copied into Android `SharedPreferences`/`EncryptedSharedPreferences`, not sent
  to a server, and not shared between agents (an Antigravity credential cannot be reused to
  authenticate Claude Code, Codex or OpenCode, and vice versa).
- Full authorization URLs (which can carry a `code`/`state` query parameter) are not written to
  persistent logs; see `SecretRedaction.redactUrlQuery` and `AntigravityAuthParser.redact`.
