# Claude Code → OpenCode local parity (on-device)

**Date:** 2026-08-08  
**Status:** Draft for review  
**Scope:** On-device Claude Code only. Remote PC and Antigravity are out of scope.  
**Goal:** Claude Code on Android feels as trustworthy as local OpenCode: stable stream/resume, real per-tool approvals and questions, and the same day-to-day workspace surfaces.

## Problem

OpenCode is the reference agent: HTTP + SSE, `RuntimeCapabilities(permissions=true, providerModelList=true)`, live multi-provider catalog, MCP connect/OAuth, session archive/summarize/diff.

Claude Code is a long-lived process bridge (`claude --print --input-format stream-json --output-format stream-json`). It already covers chat, attachments, git, MCP list/add, commands/skills, and session permission modes — but:

1. **Stability lag (Beta signal)** — duplicated assistant text, stuck tool spinners, activity key collisions when tool ids reuse, resume “session already in use”, failed activity not settling.
2. **No interactive permission/question channel** — stream-json has no `canUseTool` callback (that lives in the Agent SDK). Docs and code deliberately omit CLI `default` mode because unmatched tools hang or deny with no UI. `respondToPermission` / `answerQuestion` return false; `capabilities.permissions` and `questions` stay false.
3. **Surface gaps vs OpenCode local** — sessionDiff, archive/summarize, MCP connect toggle + OAuth, model catalog depth (fixed aliases), subagent session track (optional stretch).

User choice (brainstorming): **on-device first**, **Claude Code first**, success bar **C = OpenCode local equivalent** including per-tool approvals/questions.

## Non-goals

- Remote PC for Claude or Antigravity (separate product: PC-side bridge).
- Antigravity parity (follow-up after Claude).
- Replacing Claude CLI with the TypeScript/Python Agent SDK on device (Node weight, dual install, auth divergence). Prefer the installed `claude` binary.
- Full OpenCode sub-agent parent/child UI unless it falls out of stream events cheaply.
- Changing OpenCode behavior.

## Approach (chosen)

**Phase 0 — Stability** then **Phase 1 — Hook-based permission/question bridge** then **Phase 2 — Remaining OpenCode-local surfaces**.

Rejected alternatives:

| Option | Why not |
| --- | --- |
| Session modes only + shell parity | Never reaches bar C; Beta label stays honest. |
| Ship Agent SDK (Node) beside Alpine | Heavy, auth/session split, two runtimes to maintain. |
| “MCP permission tool” only | Older idea in `docs/CLAUDE_CODE.md`; Claude Code now documents **PermissionRequest hooks** as the CLI-native interactive path. Hooks are first-class for `-p` / stream-json. |

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ Android app                                                 │
│  Chat / Activity / Notifications (existing Permission UI)   │
│           ▲ PermissionAsked / QuestionAsked                 │
│           │ respondToPermission / answerQuestion            │
│  ClaudeCodeTarget (capabilities.permissions/questions=true) │
│           ▲                                                 │
│  ClaudePermissionBridge (Kotlin)                            │
│    - watches bridge dir under runtimeDirectory              │
│    - maps request JSON → PermissionRequest / QuestionRequest│
│    - writes response JSON for waiting hook                  │
└─────────────┬───────────────────────────────────────────────┘
              │ host FS (PRoot bind: runtime dir ↔ guest path)
┌─────────────▼───────────────────────────────────────────────┐
│ Alpine guest                                                │
│  claude --print stream-json --permission-mode <mode>        │
│       │ PermissionRequest / AskUserQuestion                 │
│       ▼                                                     │
│  and-code-claude-permission-hook.sh  (command hook)         │
│    - stdin: hook JSON                                       │
│    - write request file, poll response file (timeout)       │
│    - stdout: hookSpecificOutput allow/deny (+ answers)      │
│  ~/.claude/settings.json  (AndCode-managed hooks block)     │
└─────────────────────────────────────────────────────────────┘
```

### Why hooks, not SDK `canUseTool`

AndCode already drives the **CLI** with stream-json stdin/stdout. Interactive approvals for that path are documented as:

- **`PermissionRequest` hook** — fires when a tool needs a permission decision; handler returns allow/deny.
- **`AskUserQuestion`** — appears as a tool; can be approved with `updatedInput` containing `answers` (same shape as SDK docs).
- **`Elicitation`** — MCP user input; map if present, otherwise deny with reason.

The hook runs inside the guest as a shell command. It cannot call Kotlin directly; it uses a **file bridge** on a directory both sides already share (same pattern as transcript/session records under the runtime directory).

### Bridge protocol (v1)

Directory (guest): `/root/.andcode/claude-bridge/<androidSessionId>/`  
(Host path via existing runtime directory mapping.)

**Request file** (hook writes, exclusive create):  
`pending/<requestId>.json`

```json
{
  "v": 1,
  "kind": "permission",
  "requestId": "uuid",
  "androidSessionId": "…",
  "claudeSessionId": "…",
  "toolName": "Bash",
  "toolInput": { "command": "git status", "description": "…" },
  "createdAtMs": 0
}
```

`kind` values: `permission` | `question` | `elicitation`.

**Response file** (app writes):  
`responses/<requestId>.json`

```json
{
  "v": 1,
  "decision": "allow",
  "remember": false,
  "message": null,
  "updatedInput": null,
  "answers": null
}
```

- `decision`: `allow` | `deny` | `timeout` (app may write timeout; hook also times out client-side).
- `remember: true` → map to OpenCode “always” / apply a session-scoped allow rule when CLI supports `updatedPermissions` from hooks; if not, app records allow-list in bridge and hook short-circuits matching tools (see Remember).
- Questions: `decision: allow` + `answers` object + pass-through `questions` in `updatedInput`.

**Hook behavior:**

1. Parse stdin JSON; detect event (`PermissionRequest` vs tool name `AskUserQuestion`).
2. Create request file; optionally touch `notify` for inotify-less poll.
3. Poll for response file up to **N seconds** (default 300; configurable). Spinner via hook `statusMessage` if supported.
4. On allow: emit hook JSON with `permissionDecision: "allow"` (and `updatedInput` when needed).
5. On deny/timeout: `permissionDecision: "deny"` + reason string Claude can read.
6. Always clean up request/response pair (best-effort).

**Concurrency:** one pending request per session is normal; multiple parallel tool calls may spawn parallel hooks. Bridge uses unique `requestId`s; app shows a queue (existing `permissions: List` already supports multiple).

### Permission modes after the bridge

| Mode | CLI value | With bridge |
| --- | --- | --- |
| Plan | `plan` | Unchanged; writes still need approval via bridge when CLI asks |
| **Ask (new default for interactive)** | `default` | Unmatched tools → PermissionRequest → Android UI |
| Accept edits | `acceptEdits` | Auto file ops; Bash/network still ask via bridge |
| Full access | `bypassPermissions` | No prompts (existing dangerous mode + warning) |

- Add **`DEFAULT` / Ask** to `ClaudePermissionMode` and make it the product default once bridge health-checks pass.
- Keep Accept edits and Full access.
- If bridge fails to install or hook errors at session start, **fall back** to current Accept-edits + `allowedTools` behavior and set `capabilities.permissions=false` for that session so UI does not show dead approval chrome.
- Update `docs/CLAUDE_CODE.md` Permissions section: replace “no channel” with hook bridge description.

### Mapping to existing Android types

| Bridge | App |
| --- | --- |
| `kind=permission` | `OpenCodeEvent.PermissionAsked(PermissionRequest)` |
| `kind=question` | `OpenCodeEvent.QuestionAsked(QuestionRequest)` |
| User Once | `respondToPermission(..., ONCE, remember=false)` → allow |
| User Always | `remember=true` → allow + persist rule |
| User Reject | deny + message |
| Notification actions | existing `RuntimeNotificationHelper` paths |

`ClaudeCodeTarget.respondToPermission` becomes real: resolve pending bridge entry, write response file.  
`answerQuestion` same for questions.

`RuntimeCapabilities` for Claude:

```kotlin
RuntimeCapabilities(
    permissions = bridgeReady,
    questions = bridgeReady,
    toolEvents = true, // already true via stream
    resume = true,
)
```

### Remember (always allow)

Preferred: if PermissionRequest hook output supports permission rule updates (CLI version-dependent), pass them through.

Fallback (must work on pinned Alpine package):

- App stores `always` rules per workspace: `toolName` + optional command prefix.
- Hook checks `always-rules.json` before prompting; matching calls auto-allow without Android round-trip.

### Install / lifecycle

On Claude install and every session process start:

1. Ensure `jq` (or pure shell JSON) available in guest — prefer `jq` via apk if missing.
2. Install hook script to fixed path under guest AndCode share dir (not project `.claude/` only — works for any workspace).
3. Merge hooks into **user** settings `~/.claude/settings.json` under a namespaced marker so AndCode can re-merge without clobbering user hooks:

```json
{
  "hooks": {
    "PermissionRequest": [ /* and-code matcher group */ ],
    "PreToolUse": [ /* optional: only AskUserQuestion if PermissionRequest insufficient */ ]
  }
}
```

4. Do not disable user/project hooks; merge arrays.
5. Diagnostics: Claude agent settings card shows “Interactive approvals: ready / degraded”.

## Phase 0 — Stability (before or parallel with bridge UI)

Must land before claiming parity:

1. **Stream parser** — keep #224-class fixes: tool_result routed to originating assistant message; no duplicate text parts; settle open tools on `result` / error / process death.
2. **Activity keys** — unique group keys when Claude reuses tool call ids (#226).
3. **Resume ids** — single source of truth for `--session-id` vs `--resume`; never double-create; clear “session already in use” on relaunch after crash.
4. **Process death** — emit SessionError + idle; clear stuck spinners; no orphan busy state.
5. **Regression tests** — golden stream-json fixtures for: partial text + tool_use + tool_result; reused tool ids; result after kill; resume handshake.

## Phase 2 — Remaining OpenCode-local surfaces

After bridge + stability:

| Feature | Plan |
| --- | --- |
| **sessionDiff** | Shell `git diff` / ClaudeWorkspaceGit extended; same models as OpenCode UI expects |
| **Session archive / delete bulk** | App-side session store flags (Claude has no server archive API) |
| **Session summarize** | Optional one-shot `claude -p` summarize of transcript; or hide action when unsupported |
| **MCP connect toggle** | If `claude mcp` supports enable/disable, wire it; else document delete-only and hide toggle (`supportsConnectToggle=false` stays) |
| **MCP OAuth** | `claude mcp login` with same PTY URL+code pattern as auth login when CLI ≥ required version |
| **Models** | Keep aliases; if CLI exposes model list in `system/init`, parse and populate picker; no fake multi-provider auth UI |
| **Subagent text** | Optional `--forward-subagent-text` when version supports; display nested tools under parent |
| **In-app maturity** | When Phase 0+1 acceptance pass on device matrix, drop README Beta for Claude or change to “Stable (on-device)” |

## Error handling

| Failure | Behavior |
| --- | --- |
| Hook timeout | Deny tool with “User did not respond in time”; toast on Android |
| Bridge dir not writable | Degrade capabilities; log; do not start in `default` mode |
| Malformed hook stdin | exit 0 no decision only if safe; prefer deny with reason for PermissionRequest |
| App killed mid-prompt | Hook times out → deny; on relaunch no stale pending UI |
| User force-stops Claude process | Abort pending bridge requests as deny |

## Testing

**Unit**

- Bridge request/response serialization.
- Hook script with fixture stdin → writes request; with injected response → correct stdout JSON (run under host shell in CI where possible; guest script syntax-checked).
- `ClaudeStreamJsonParser` fixtures (Phase 0).
- `ClaudeCodeTarget.respondToPermission` writes response and clears pending.

**Instrumented / device**

- Sign-in smoke (existing).
- Prompt that triggers Bash under Ask mode → notification + chat chip → Allow once → command runs.
- Reject → Claude sees denial and continues without hang.
- AskUserQuestion path if model emits it in plan mode.
- Resume after process kill mid-turn.
- Accept edits mode still auto-edits without prompt.
- Full access still skips bridge prompts.

**Non-goals for test**

- Full multi-provider OpenCode catalog parity.
- Remote.

## Rollout

1. Land Phase 0 behind no flag (bugfixes).
2. Land bridge + Ask mode; default remains Accept edits until device validation checklist green, then switch default to Ask.
3. Phase 2 incrementally; each feature gated by capability flags.
4. Update README agent table and `docs/CLAUDE_CODE.md` when acceptance criteria met.
5. Antigravity follow-up reuses bridge pattern only if `agy` gains equivalent hooks (likely different design).

## Acceptance criteria (done means)

- [ ] No known class of stuck tool spinner / duplicated assistant bubble on fixture suite + manual smoke.
- [ ] Resume after force-stop works without “session already in use”.
- [ ] Ask permission mode: dangerous Bash prompts Android UI; Allow / Always / Reject work; notifications work.
- [ ] `capabilities.permissions == true` and `questions == true` when bridge ready.
- [ ] Plan / Accept edits / Full access still available and documented.
- [ ] sessionDiff available for Claude workspace (or explicit unsupported UI, not crash).
- [ ] `docs/CLAUDE_CODE.md` matches implementation.
- [ ] Device validation notes for arm64 + emulator in `docs/DEVICE_VALIDATION.md` or Claude section.

## Key files (expected touch list)

- `runtime/local/ClaudeCodeRuntime.kt`, `ClaudeStreamJsonParser.kt`, `ClaudeCodeTarget.kt`
- `runtime/local/ClaudePermissionMode.kt`, new `ClaudePermissionBridge.kt`, guest hook script under `assets` or `runtime_tools`
- `runtime/RuntimeCapabilities.kt` usage sites
- `feature/chat/*` only if question UI needs Claude-specific fields
- `docs/CLAUDE_CODE.md`, README agent table
- Tests under `app/src/test/.../runtime/local/`

## Open risks

1. **Pinned `claude-code` apk version** may lag docs (PermissionRequest hook shape). Mitigation: version-gate bridge; probe with a dry-run hook; degrade gracefully.
2. **Hook timeout vs long user away** — 300s default; align with notification; optional extend.
3. **Parallel PermissionRequest hooks** — file bridge must be race-safe (unique ids, atomic create).
4. **User `~/.claude/settings.json` merge** — must not destroy existing hooks; use idempotent merge keyed by AndCode command path.
5. **jq dependency** — add to Claude install package set or write minimal JSON with python/node if present; prefer apk `jq`.

## Implementation order (for writing-plans)

1. Phase 0 parser/resume/activity tests + fixes  
2. Bridge protocol + hook script + settings merge  
3. Wire Target capabilities + respond/answer  
4. Mode enum + default switch strategy  
5. sessionDiff + MCP OAuth/toggle as available  
6. Docs + device validation + README maturity  
