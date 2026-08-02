# Terms of Use

_Last updated: 2026-08-02_

## 1. What AndCode is

AndCode is an independent, open-source (MIT-licensed) Android application. It is a **local-first
graphical front end** that installs and/or launches supported third-party command-line tools
(OpenCode, Claude Code, Google Antigravity) inside a Linux environment on your device, or connects
to an OpenCode server you run yourself. AndCode does not sell, resell, sublicense, or otherwise
provide access to Anthropic's, Google's, or any other AI provider's services, subscriptions, or
model usage rights. It is not affiliated with, endorsed by, sponsored by, or officially supported
by OpenCode, Anthropic, or Google.

## 2. Your accounts and provider access

To use Claude Code, Antigravity, or a model provider inside OpenCode, you need your own usable
account, subscription, or API key with that provider. You are responsible for:

- Complying with the current terms of service, acceptable-use policy, and plan/quota conditions of
  every third-party service you connect (Anthropic, Google, GitHub, your chosen model providers,
  and any MCP server or voice provider you configure).
- Not using AndCode to share accounts, redistribute or resell access, reuse authentication
  credentials outside the tool that issued them, or attempt to evade a provider's rate limits or
  quotas. Using multiple official CLIs from one app does not grant you rights beyond what each
  provider's own terms allow.
- Any cost, billing, or quota consumption incurred with a third-party provider as a result of your
  use of AndCode.

## 3. Reviewing what an agent does

Coding agents run through AndCode can read, create, modify, and delete files, and can execute shell
commands inside the Linux/Debian runtime, subject to the permission mode you select. You are
responsible for reviewing the changes and commands an agent proposes before accepting them,
especially when:

- You select a **Full Access** / `bypassPermissions` / `--dangerously-skip-permissions` mode, or use
  OpenCode's "always allow" response to a tool-permission prompt, any of which let the agent act
  without asking again.
- You grant **all-files access**, which lets an agent reach files anywhere the folder picker can see
  on your device, not only inside AndCode's own storage.
- You enable a **scheduled task**, which runs a prompt unattended, using whatever permission mode
  and workspace were configured at the time.
- You add a **third-party MCP server**, which can read prompts/files passed to it and, depending on
  the server, take actions on your behalf.

PRoot, the compatibility runtime AndCode uses to run Linux binaries on Android, is not a full
security sandbox. Treat any of the settings above as a meaningful increase in what a session can do
without further confirmation, and keep backups of anything important.

## 4. No warranty

AndCode is provided **"as is"**, without warranty of any kind, express or implied, to the maximum
extent permitted by law, including without limitation any warranty of merchantability, fitness for
a particular purpose, or non-infringement. The maintainers are not liable for any damages, data
loss, unintended agent actions, or third-party costs arising from use of this software. See the
[MIT License](LICENSE) for the full disclaimer that governs AndCode's own source code.

## 5. Third-party services can change

AndCode's on-device or remote agent features depend on official third-party CLIs, binaries, APIs,
and services outside this project's control. Those providers can change their terms, pricing,
authentication flow, API, or availability at any time, which can break or limit a feature in
AndCode without notice. AndCode is updated on a best-effort basis to track such changes; there is no
guarantee that a given agent or provider integration will keep working.

## 6. Scope of the MIT license

AndCode's own source code in this repository is licensed under the [MIT License](LICENSE). That
license applies only to AndCode's Kotlin/Android source code. It does **not** extend to, relicense,
or grant any rights to the third-party CLIs, binaries, runtimes, or packages AndCode installs or
launches (Claude Code, Google Antigravity, OpenCode, PRoot, Alpine/Debian packages, and others),
each of which remains subject to its own upstream license and terms. See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [TRADEMARKS.md](TRADEMARKS.md).

## 7. Changes to these terms

These terms may be updated as the project evolves. Continued use of the app after an update
constitutes acceptance of the revised terms. Material changes will be reflected in this file's
"Last updated" date and, where practical, in release notes.
