# Claude Code on Android

Claude Code is a second local runtime target. It reuses the Alpine Linux rootfs, PRoot launcher,
`/workspace` bind mount, command environment and logs that the local OpenCode runtime already
installs. The APK does not contain or redistribute a Claude binary.

## Agents are selectable

The Alpine sandbox is shared, but the agents inside it are not. `LocalRuntimeMetadata.components`
records which of `opencode` / `claude-code` is provisioned, and `LocalRuntimeInstaller.install`
downloads the OpenCode binary only when OpenCode is among the requested agents — so a Claude
Code-only setup skips a download it would never use. Installing one agent later never removes the
other: requested agents are unioned with what is already recorded, and `/root` (which holds every
agent's credentials) is carried across the staging swap.

The setup guide asks for this choice in its first step; the Workspaces screen can add either agent
afterwards.

## Installation

The app adds Anthropic's official signed Alpine repository key and stable repository inside the
existing rootfs, then installs the package:

```sh
wget -qO /etc/apk/keys/claude-code.rsa.pub https://downloads.claude.ai/keys/claude-code.rsa.pub
# https://downloads.claude.ai/claude-code/apk/stable appended to /etc/apk/repositories, once
/sbin/apk update
/sbin/apk fix
/sbin/apk add --no-cache claude-code util-linux
```

Two details are load-bearing and were wrong in earlier revisions:

- **`apk` is invoked by absolute path.** The sandbox's `/etc/profile.d/and-code.sh` narrows
  `PATH` to `/usr/local/bin:/usr/bin:/bin`, which excludes `/sbin` where `apk` lives. The install
  script also runs under `sh -c` rather than `sh -lc` so that profile never applies.
- **The package installs `claude` to `/usr/bin`, not `/usr/local/bin`** (where the OpenCode binary is
  copied). `ClaudeCodeInstaller.CLAUDE_BINARY` is the single source of truth for the path.

Updates use `apk add --no-cache --upgrade claude-code`. `USE_BUILTIN_RIPGREP=0` is set because the
bundled ripgrep is a glibc build that cannot run on musl; the sandbox provides Alpine's ripgrep.

### Broken packages poison every later apk run

A package whose files or scripts failed to extract keeps an `f:f` / `f:s` flag in
`/lib/apk/db/installed` — which is how a package broken by PRoot's hard-link emulation was recorded.
apk counts one error per flagged package in **every** transaction it commits afterwards, even a `-s`
simulation and even when the flagged package has nothing to do with the request. The transaction then
exits non-zero having printed only:

```text
1 error; 2322.8 MiB in 392 packages
```

That is why an up-to-date sandbox could still fail to update, with no error naming anything. Two
consequences for these scripts:

- **`apk fix` runs with no arguments**, so it reinstalls exactly the flagged packages. `apk fix <pkg>`
  reinstalls that one and still trips over everybody else's flag, so it can never clear the failure —
  and under `set -e` its own exit code aborted the update before the upgrade was attempted.
- **A non-zero `apk` status is not by itself a failure.** The scripts verify what was asked for
  instead: `apk info -e` for a fresh install, and `apk version -q -l '<' claude-code` (a read-only
  query, so broken flags cannot skew it) for an update. Only if that check fails, or `claude
  --version` does not run, is the operation reported as failed.

The failure diagnostics list the flagged packages, since apk names a package when it breaks it but
never when it later refuses to work because of the flag.

### The card reports which version an update landed on

`apk` upgrades in place and reports nothing about the version, so an update that had nothing to do
and one that installed a new build were indistinguishable — the button stopped spinning either way.
`ClaudeCodeTarget.update` therefore reads `claude --version` on both sides of the upgrade and returns
a `ClaudeUpdateResult`: `Updated(from, to)` when the version moved, `AlreadyLatest(version)` when it
did not. The card shows the installed version next to the update button and the outcome underneath.

"Already up to date" is a claim the update script has verified, not an assumption: the script only
succeeds once `apk version -q -l '<' claude-code` reports nothing pending, so a version that did not
move means the repository has nothing newer.

## Execution

Prompts run through Claude Code's streaming-JSON protocol rather than its terminal UI:

```text
claude --print --input-format stream-json --output-format stream-json --verbose \
       --include-partial-messages --permission-mode <mode> \
       (--session-id <uuid> | --resume <claude-session-id>)
```

One process stays alive per chat session and exchanges newline-delimited JSON over stdin/stdout.
`system`, `assistant`, `user`, `stream_event` and `result` messages are mapped onto the existing chat
event model, so assistant text, reasoning, tool calls, tool results and streaming deltas render in
the normal UI. Conversation state lives in Claude Code's own session store, so a process that exits
is relaunched with `--resume` and history survives.

The interactive TUI is deliberately not scraped: it is a full-screen renderer whose output has no
stable line structure, and the heuristics needed to read it misfire on ordinary assistant prose.

## Permissions

Streaming-JSON mode has no channel for answering an individual tool prompt without hosting an MCP
permission tool, so permissions are decided per session via `--permission-mode`:

| Mode | CLI value | Effect |
| --- | --- | --- |
| Plan only | `plan` | Reads and plans; never edits or runs commands |
| Accept edits (default) | `acceptEdits` | May edit files in the workspace |
| Full access | `bypassPermissions` | Runs any command without asking |

The CLI's own `default` mode is not offered: with no prompt channel it can only deny, which is
indistinguishable from a hang. `ClaudeCodeTarget.respondToPermission` therefore returns false rather
than pretending a prompt was answered.

## Sign-in

`claude auth login` is interactive: it prints an authorization URL, waits for browser approval, then
reads back the code the browser shows. There is no browser inside PRoot, so the app plays that role.
`ClaudeAuthCoordinator` runs the command under a pseudo-terminal (Alpine `util-linux`'s `script`,
required because the CLI only prints a pasteable URL when it believes a human is watching), captures
the URL, hands it to Android via `ACTION_VIEW`, and writes the pasted code back to the CLI's stdin.
Success is confirmed against `claude auth status`, not inferred from the exit code.

The app never creates, stores or handles OAuth tokens itself — credentials stay in Claude Code's own
store under `/root` inside the sandbox.

## History and Events

Session metadata and normalized Claude messages are stored in the app-private runtime directory.
Writes are coalesced to turn boundaries rather than issued per streamed line.
