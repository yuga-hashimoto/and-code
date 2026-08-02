# Third-Party Services

AndCode integrates with the third-party services below. AndCode does not operate a server that
sits between you and these services — each row states who actually handles authentication and
network traffic, and where any credential is stored. See [PRIVACY.md](PRIVACY.md) and
[docs/AUTHENTICATION_AND_DATA_FLOW.md](docs/AUTHENTICATION_AND_DATA_FLOW.md) for more detail on
Claude Code, Antigravity, and OpenCode specifically.

| Service | Used for in AndCode | Who authenticates | Who talks to the service | Where the credential is stored | Routed through an AndCode server? | Terms that apply | How to disable / remove | Affiliation |
|---|---|---|---|---|---|---|---|---|
| **OpenCode** (`anomalyco/opencode`) | Local or remote coding-agent runtime; model access via whatever provider you connect inside OpenCode | You (via OpenCode's own provider auth, or an API key) | The OpenCode process (on-device or on your PC) talks directly to the provider you configured | Three distinct paths — see [OpenCode credential paths](#opencode-credential-paths) below, not a single answer | No | [OpenCode terms/license](https://github.com/anomalyco/opencode) | Delete the local runtime, disconnect the provider in **Settings → Providers**, or remove the connection in **Settings → Remote connection** | Independent third-party project; not affiliated with AndCode |
| **Anthropic Claude Code** | Official CLI installed and run on-device to chat with Claude | You, via the official `claude auth login` browser flow | The Claude Code CLI process talks directly to Anthropic (or your configured provider) | Inside the Claude Code CLI's own credential store, inside the on-device Alpine rootfs — not read or copied by AndCode | No | [Anthropic Consumer/Commercial Terms](https://www.anthropic.com/legal) and Claude Code's own terms | **Sign out** in **Settings → Agents → Claude Code**, or delete the local runtime | Anthropic's official CLI; AndCode is not affiliated with or endorsed by Anthropic |
| **Google Antigravity** | Official, unmodified `agy` CLI installed and run on-device to chat with Gemini/Claude/GPT-OSS models via Antigravity | You, via the official `agy` first-launch Google OAuth flow | The `agy` CLI process talks directly to Google | Inside `agy`'s own guest token store, at `root/.gemini/antigravity-cli/antigravity-oauth-token` inside the on-device Debian rootfs — not read or copied by AndCode | No | [Google Antigravity terms](https://antigravity.google/) | **Sign out** in **Settings → Agents → Antigravity**, or delete the local runtime | Google's official CLI; AndCode is not affiliated with or endorsed by Google |
| **GitHub** | Optional: create/browse pull requests, "Star on GitHub" prompt, `gh` CLI inside the Linux runtime | You, via GitHub's OAuth device-flow login | The app calls the GitHub API directly with your token; the in-runtime `gh` CLI does the same | GitHub token stored in `EncryptedSharedPreferences` (`SecureSettingsRepository`) | No | [GitHub Terms of Service](https://docs.github.com/site-policy/github-terms/github-terms-of-service) | **Disconnect** in **Settings → GitHub** | Independent; not affiliated with AndCode |
| **OpenAI** | Optional text-to-speech voice (if you choose the OpenAI TTS provider and enter your own API key) | You, via your own OpenAI API key | The app sends the text to be spoken directly to the OpenAI API | API key stored in `EncryptedSharedPreferences` | No | [OpenAI Terms of Use](https://openai.com/policies/terms-of-use) | Switch TTS provider or clear the key in **Settings → Voice** | Independent; not affiliated with AndCode |
| **ElevenLabs** | Optional text-to-speech voice (if you choose the ElevenLabs TTS provider and enter your own API key) | You, via your own ElevenLabs API key | The app sends the text to be spoken directly to the ElevenLabs API | API key stored in `EncryptedSharedPreferences` | No | [ElevenLabs Terms of Service](https://elevenlabs.io/terms) | Switch TTS provider or clear the key in **Settings → Voice** | Independent; not affiliated with AndCode |
| **MCP servers you configure** | Optional tool/context servers you add for an agent to call | Depends on the server (local command or remote URL you provide) | The agent CLI connects to the server you configured | Any server credentials go wherever you configure them (e.g., server-side env vars); AndCode does not manage server-side secrets | No | Whichever terms the specific MCP server operator sets | Remove the server in **Settings → MCP** | Third-party servers you choose to add; not affiliated with AndCode |
| **Alpine Linux** (`dl-cdn.alpinelinux.org`) | Minimal Linux root filesystem the on-device runtime runs in | N/A (unauthenticated download) | The app downloads the official Alpine minirootfs archive over HTTPS | N/A | No | [Alpine Linux license terms](https://alpinelinux.org/) (per-package) | Delete the local runtime | Independent open-source project |
| **Debian** (`deb.debian.org`, `security.debian.org`) | Bookworm root filesystem used only for the glibc-dependent Antigravity CLI | N/A (unauthenticated `apt` mirrors) | `apt` inside the Debian rootfs fetches packages over the configured mirrors | N/A | No | [Debian's licenses](https://www.debian.org/legal/) (per-package) | Delete the local runtime | Independent open-source project |
| **Termux package mirror** (`packages.termux.dev`) | Source of the `proot`, `libandroid-shmem`, and `libtalloc` binaries bundled into the APK at build time | N/A (build-time download, pinned by hash) | Build machine only, not the end-user's device at runtime | N/A | No | Upstream licenses vary — see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) | N/A (bundled at build time) | Independent open-source project |
| **Firebase Crashlytics** (Google) | Crash and non-fatal error reporting in release builds | N/A (app-level Firebase project, no end-user login) | The app sends crash reports and small diagnostic breadcrumbs directly to Firebase; custom log lines/keys AndCode's own code adds are redacted on a best-effort basis (see PRIVACY.md §7), but automatic fatal-crash collection by the Crashlytics SDK itself is not routed through that redaction | N/A (no user credential; app-level Firebase config) | This *is* a Google-operated collection service, not an AndCode server | [Firebase Terms of Service](https://firebase.google.com/terms) / [Google Privacy Policy](https://policies.google.com/privacy) | Disabled automatically in debug builds; cannot currently be toggled per-user in release builds | Independent Google service; not affiliated with the AI-agent integrations above |

## OpenCode credential paths

"OpenCode" in the table above actually covers three different credential flows, depending on how
you use it:

1. **A provider API key you type into AndCode's own UI, used with the local (on-device) OpenCode
   runtime.** Entered in **Settings → Providers**, this is stored in `EncryptedSharedPreferences`
   *and* — via `LocalProviderCredentialStore.syncToRuntime()` — written into
   `root/.local/share/opencode/auth.json` inside the on-device Alpine rootfs, in the plaintext JSON
   format the local OpenCode process reads. That file is protected only by the app's private
   filesystem permissions, not by `EncryptedSharedPreferences`' additional at-rest encryption.
2. **Provider OAuth started through OpenCode's own API** (`providerAuthMethods`/`authorizeProvider`,
   used for providers that support an OAuth flow rather than a pasted key). The resulting credential
   is managed by the OpenCode process itself, the same as flow 1's synced `auth.json` once obtained;
   AndCode's role is limited to relaying the browser URL and any code you enter, the same as it does
   for Claude Code and Antigravity sign-in.
3. **Remote OpenCode** (a server you run on your own PC/Mac/Linux). AndCode stores only the
   *connection profile* to that server (name, URL, username, password) in
   `EncryptedSharedPreferences`; whatever provider credentials that remote OpenCode server uses are
   managed on that machine, not synced to or stored by the Android app.

## Notes

- "Routed through an AndCode server" is **No** for every row except Crashlytics, which is a
  telemetry pipeline, not a proxy for your prompts, files, or credentials.
- Rows for Claude Code, Antigravity, and OpenCode describe the *official* CLI's own network and
  credential behavior, not something AndCode re-implements — see
  [docs/AUTHENTICATION_AND_DATA_FLOW.md](docs/AUTHENTICATION_AND_DATA_FLOW.md).
- If a service you use with AndCode is not listed here (for example, a different MCP server or
  model provider you connected through OpenCode), it is still subject to its own terms, not
  AndCode's.
