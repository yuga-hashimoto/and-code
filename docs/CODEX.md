# Codex local runtime

Support for OpenAI's Codex CLI, run inside the same shared Alpine/PRoot sandbox as OpenCode and
Claude Code (`CodexRuntime`, `CodexTarget`, `CodexInstaller`, `CodexSandboxLauncher`,
`CodexJsonRpcClient`, `CodexItemParser`, `CodexModels`).

## Status: registered, installable from Settings, verified on an emulator (unauthenticated)

`codexTarget` is registered in `RuntimeRegistry` (`AndCodeApplication.kt`, and `di/AppModule.kt` reusing
the same instance) so it appears in the runtime picker and the drawer's agent switcher. It is offered
only once installed: `CodexController.refresh` always calls `CodexTarget.connect()`, which leaves the
target `Unavailable` while Codex is missing, and the drawer hides `Unavailable` targets.

The UI surface is `CodexController` (install / sign-in status), `CodexCard`, `CodexSignInViewModel`, and
`CodexAgentSettingsScreen` reached from Settings > Agents > Codex, plus a Codex option in the setup guide.
`LocalRuntimeInstaller` treats Codex like the other agents - it is installed into the staging rootfs and
recorded in the runtime metadata (`components`), so a later install that rebuilds the sandbox keeps it; a
Codex-only selection provisions the shared environment itself (`CodexController.install`). The sign-in check starts the resident app-server, so it runs when the settings screen
is opened, not at app launch.

### Verified on an emulator (API 36, arm64, 2026-09-21, unauthenticated)

- Install from Settings > Agents > Codex on a fresh install with the local runtime already set up:
  download, SHA-512 check and extraction succeed; `codex-cli 0.155.1` runs inside PRoot.
- The status card reports "Sign-in required" and the version; the app-server starts and answers
  `account/read`.
- Selecting Codex in the drawer, then sending a message creates a thread, and the 401 retry loop
  surfaces as a red "Reconnecting... 5/5" message (see "Protocol notes" below).
- Uninstalled Codex is absent from the drawer; reinstalling from the UI works; force-stop leaves no
  orphaned `codex` process.

### Verified on a physical device (Xiaomi, Android 16, arm64) - and what it found

Install and the ChatGPT browser sign-in were run on a real phone. The browser did reach Codex's callback
(`localhost:1455`), which confirms the loopback approach works there, but the token exchange then failed:
`error sending request for url (https://auth.openai.com/oauth/token)`, with `is_connect=true`.

Cause, isolated by running the same DNS/HTTPS check inside the phone's PRoot sandbox: with the app **in the
background** (the browser in front) every DNS write failed with `EPERM`; with the app **in the foreground** the
same commands resolved and connected. Codex is a child process of the app, so Android's per-app network
rule applies to it, and the ChatGPT sign-in is the one flow that needs the network *while the browser has the
screen*. OpenCode's sign-in is unaffected because `LocalRuntimeService` keeps the app in the foreground;
Codex has no such service of its own.

Fix: `CodexKeepAliveService` (a `specialUse` foreground service, one low-importance notification) runs while
`CodexRuntime.needsForeground` is true - a ChatGPT sign-in waiting on the browser, or a turn in flight - and
`AndCodeApplication` starts and stops it from that flag. On the emulator it starts when the sign-in begins
and stops on Cancel (with the callback port released). Re-run on the phone: the token exchange then succeeded (`codex login status`: "Logged in using ChatGPT"),
which exposed the missing-`params` bug above.

### Setups without OpenCode

Two startup paths only knew about OpenCode, which broke a Codex-only setup (and, for the first, any setup that
picked only Claude Code or Antigravity):

- `hasUsableRuntimeSetup` judged "is setup done" from OpenCode's status alone, so every restart of a
  Codex-only install went back to the welcome screen. It now also counts any installed local agent.
- Nothing selected a default runtime: auto-start only ever selects the OpenCode-local target, so the chat
  had no backend and a send did nothing. `AndCodeApplication` now fills an empty selection with Codex once
  its target connects (`selectIfUnset`, so a user's own choice is never overridden).
- The drawer read each target's `state.value` once instead of observing it, so a Codex target that
  connected after first composition stayed hidden; it now collects the states.

Verified on the emulator (Codex-only, signed out): launch opens the chat on `codex` with a Codex model
selected, and a send creates a thread and surfaces the 401 from the API.
Verified on the physical device (Codex-only, signed in with ChatGPT, 2026-09-23): launch opens the chat on
`codex` with a Codex model selected, and a real turn returns a reply.

### Generated images and chats opened before Codex was usable

- `image_gen` results arrive as an `imageGeneration` item (`result`: the PNG as base64, `savedPath`:
  `/root/.codex/generated_images/<thread>/<id>.png`, verified on a device). `CodexItemParser` turns a
  completed one into a `file` part with an image MIME type pointing at `savedPath`, which the chat resolves
  into the rootfs and renders like other agents' generated images (a data URI only when no file was
  saved). While it is still generating it is an `image_gen` tool part carrying just the prompt.
- A chat opened while Codex was unusable (not installed yet, or installed without the code-mode host)
  checked health a few times, failed, and stayed disconnected: a message sent there sat in the offline
  queue. `CodexTarget.connect` now emits `ServerConnected` when Codex becomes usable, which the chat
  already handles by reconnecting and sending the queue, and the application refreshes the agent and
  model lists so the composer stops showing another runtime's ("build").

### Not verified

API-key sign-in and sign-out through the UI, approval prompts, abort, image attachments on a device
(implemented from the schema, not yet run against a live, signed-in account), MCP servers on a physical
device (verified on the emulator only), and
whether threads listed after an app restart include chats whose only turn failed.

### MCP servers

Settings > Agents > Codex > MCP servers uses the same screen as the other agents. Codex keeps servers in
`~/.codex/config.toml` next to the rest of its configuration, so the app never edits that file itself:
`CodexMcp` runs `codex mcp list --json` / `add` / `remove` (output shape captured from codex-cli 0.142.5 with an isolated
`CODEX_HOME`, fixtures in `CodexMcpTest`; adding and removing a server through the app was then checked on
the emulator against the installed 0.155.1, where `config.toml` gained and lost the entry), and a running app-server is told to re-read them with
`config/mcpServer/reload` (params `null` per the schema). Like Claude Code and Antigravity, a configured
server is always used, so the screen offers removal rather than a connect toggle.

## Why this is more tractable than Antigravity's integration

Antigravity's CLI is a full-screen terminal program with no scriptable protocol, so that integration
drives it through a PTY and scrapes `--output-format stream-json`. Codex is different in two ways
that matter here:

- **libc**: its native binary targets `aarch64-unknown-linux-musl` / `x86_64-unknown-linux-musl` -
  the same musl this app's shared Alpine rootfs is already built on. Unlike Antigravity (glibc,
  needs `gcompat` and its own Debian-based rootfs), Codex runs directly in the rootfs OpenCode and
  Claude Code already share. No second rootfs is provisioned for it.
- **Protocol**: `codex app-server` speaks a genuine JSON-RPC 2.0 protocol over stdio (newline-
  delimited JSON, no `Content-Length` framing) with a documented schema
  (`codex app-server generate-json-schema`) covering session (`thread/*`), turn (`turn/*`) and item
  (`item/*`) lifecycles, plus server-initiated approval requests. This is much closer to how this app
  already talks to a *remote* OpenCode server than to Antigravity's TUI-scraping approach - one
  long-lived process multiplexes every open thread, instead of one process per chat.

## Distribution

Codex has no Alpine package (unlike Claude Code, installed via `apk add`) and no GitHub release
archive (unlike Antigravity). It ships as the npm package `@openai/codex`, whose native binary lives
in a per-platform *optional dependency* published under the same package name at a synthetic version
(`<version>-linux-arm64`, `<version>-linux-x64`) - confirmed by hand:

```
$ npm view @openai/codex versions        # includes 0.155.1-linux-arm64, 0.155.1-linux-x64, ...
$ npm view @openai/codex@0.155.1-linux-x64 dist
{ tarball: "https://registry.npmjs.org/@openai/codex/-/codex-0.155.1-linux-x64.tgz",
  integrity: "sha512-...", shasum: "...", ... }
```

`CodexReleaseClient` resolves `@openai/codex/latest` for the current version, then fetches
`@openai/codex/<version>-<platform>` for that platform's tarball URL and its `integrity` (an SRI
SHA-512 string npm itself records). `CodexInstaller` downloads that tarball, verifies it against the
SHA-512 (the same "trust the official channel's own recorded digest over HTTPS" principle
`AntigravityReleaseClient` uses for GitHub's SHA-256, just a different registry and a stronger hash),
and extracts the two binaries in `package/vendor/<target-triple>/bin/`: `codex` and
`codex-code-mode-host`. The second is not optional: Codex runs every model tool call - image generation,
MCP tools - in "code mode", spawning `codex-code-mode-host` from the directory `codex` lives in. Installed
without it (as the first release of this integration was), each tool call failed with `failed to spawn
code-mode host /usr/local/bin/codex-code-mode-host` and image generation silently produced nothing.
`CodexInstaller.isInstalledIn` requires both, so such an install reports "Not installed" and is redone.
The sibling `codex-resources`/`codex-path` directories (voice runtime, bundled `bwrap`, bundled `ripgrep`)
are still skipped; the tarball is 100+ MB and mostly those, while the two binaries are ~250 MB and
~65 MB uncompressed.

## Sandboxing

Codex defaults to running model-issued shell commands inside its own bundled `bwrap` (bubblewrap)
sandbox, layered *inside* this app's own PRoot jail. Bubblewrap needs unprivileged Linux user
namespaces, which is very unlikely to work nested inside PRoot on Android (PRoot does not provide
real namespace isolation, and Android kernels commonly restrict `CLONE_NEWUSER` for unprivileged
processes) - this was not tested on-device, but running the real binary in this session's own
container (which also lacks a working bubblewrap setup) reproduced exactly the failure mode expected:
Codex logs `Codex could not find bubblewrap on PATH` and falls back to a bundled one; that fallback's
actual sandboxing was not verified to succeed under nested confinement.

`CodexSandboxLauncher` passes `-c sandbox_mode="danger-full-access"` **before** the `app-server`
subcommand (verified: after the subcommand, `codex` does not error but the flag's actual effect
there was not re-verified) to disable Codex's own inner sandbox and rely solely on the outer PRoot
jail - the same posture Claude Code and Antigravity already run under in this app. This does not
reduce containment versus the status quo; it removes a redundant, and inside PRoot likely
non-functional, second sandboxing layer.

## Protocol notes (verified against a live, unauthenticated process)

- Framing is one JSON object per line (NDJSON) on stdin/stdout; there is no `initialize` version
  negotiation.
- **Every request must carry `params`**, even methods with no arguments: `{"method":"account/read"}` is
  rejected with `Invalid request: missing field \`params\``, and so are `model/list` and `thread/list`,
  while `"params":{}` succeeds (verified on a device, 0.155.1). `CodexJsonRpcClient.call` always sends it.
  Before that fix every no-argument call failed silently, which showed as "not signed in" right after a
  successful ChatGPT sign-in, an empty model picker, and an empty chat list after a restart.
- A line with `method` **and** `id` is a server-initiated request expecting a reply (an approval
  prompt); a line with `method` and no `id` is a notification; a bare `id` is a response to a call
  this app made. Getting this dispatch wrong (treating every `id`-bearing line as "our" response) was
  an actual bug caught while writing `CodexJsonRpcClientTest` - a real approval request would
  otherwise have been silently dropped.
- `thread/start`, `thread/list`, `turn/start`, `turn/interrupt`, `model/list` and `account/read` all
  work without being signed in (`account/read` reports `{"account": null, "requiresOpenaiAuth":
  true}`). A `turn/start` without credentials streams `item/started`/`item/completed` for the user
  message, then repeated `error` notifications shaped like `{"willRetry": true, "error": {"message":
  "Reconnecting... N/5", "codexErrorInfo": {"responseStreamDisconnected": {"httpStatusCode": 401}}}}`
  - `CodexItemParser` treats a `willRetry: true` error as informational only, ending the turn (and
    the retry loop's terminal `willRetry: false` error, if it's ever reached) rather than every
    individual reconnect attempt.
- Sign-in goes through the same provider dialog OpenCode's providers use (`ProviderAuthDialog`), with two
  methods advertised by `CodexTarget.providerAuthMethods`:
  - **ChatGPT account** (index 0): `account/login/start` with `type: chatgpt` returns `{authUrl, loginId}`;
    `authorizeProvider` hands that URL to the dialog as an `auto` method, the browser signs in and lands on
    Codex's own callback (`http://localhost:1455/auth/callback`, served by the app-server), and the result
    arrives as an `account/login/completed` notification, kept by `CodexLoginTracker` until the dialog's next
    `completeProviderOAuth` poll. Cancelling calls `account/login/cancel`.
  - **API key** (index 1): `codex login --with-api-key` (key on stdin, per `codex login --help`), which is what
    `CodexRuntime.loginWithApiKey` runs. The API key is also the fallback if the loopback callback is ever
    blocked; the protocol also offers `type: chatgptDeviceCode`, not used here.
  Verified on an emulator (see Status): starting the ChatGPT sign-in opens `auth.openai.com/oauth/authorize`
  with `redirect_uri=http://localhost:1455/auth/callback`, Codex is listening on `127.0.0.1:1455` while it
  waits, and Cancel releases the port. Not verified: completing a real sign-in (it needs an account).
- Turn/item shapes not covered by a live, authenticated run (an actual `agentMessage`,
  `commandExecution`, `fileChange`, an approval prompt's exact resolution) are mapped from the
  protocol's own JSON Schema instead and marked so in code comments and test names. `CodexItemParser`
  falls back to a generic "tool" part carrying the raw item JSON for any item type it does not have a
  dedicated mapping for, so an unrecognized shape surfaces in the UI instead of disappearing.
- Image attachments are sent on `turn/start` as `{"type":"image","url":"data:..."}` inputs, one per
  `image/*` attachment, alongside the text input (`CodexRuntime.codexTurnInput`). The app-server's v2
  `UserInput` schema (generated with `codex app-server generate-json-schema`, matching the
  `rust-v0.155.1` source) names the URL field `url` under an `"image"` tag and accepts inline `data:`
  URLs while rejecting remote HTTP(S) ones; `UserInput` has no document/file variant, so non-image
  attachments are left out rather than sent in an unsupported shape. Not yet exercised against a
  live, signed-in account. `CodexItemParser` maps the image back out of the echoed `userMessage`
  content into a `file` part, so the attachment shows in the transcript and survives a reload.

## Testing

`CodexJsonRpcClientTest`, `CodexItemParserTest` and `CodexModelsTest` use JSON fixtures taken
verbatim from a live, unauthenticated `codex app-server` process where possible, and from the
protocol's own JSON Schema (with a note in the test) where a fixture required a signed-in account.
`CodexInstallerTest` builds small synthetic tarballs matching the real npm tarball's directory layout
to exercise extraction and SHA-512 verification without downloading the real ~140 MB archive in CI.

`CodexRuntime` itself (the process/server lifecycle, `sessionsWithTurnInFlight` crash recovery,
approval dispatch) has no dedicated unit tests yet - exercising it needs a fake process or a fake
`CodexJsonRpcClient` rather than JSON fixtures. Follow-up work, tracked alongside device acceptance
above since registering the target is the point at which this orchestration actually starts running.
