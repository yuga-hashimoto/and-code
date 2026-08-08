# Claude Code OpenCode-local parity Implementation Plan

> **For agentic workers:** Implemented inline from the approved design.

**Goal:** On-device Claude Code gains interactive per-tool approvals via PermissionRequest hook bridge, sessionDiff, and Ask permission mode — toward OpenCode local confidence.

**Architecture:** Guest hook writes pending requests under a bind-mounted bridge dir; Kotlin `ClaudePermissionBridge` polls and maps to existing Permission/Question UI; responses write back for the hook.

**Tech Stack:** Kotlin, Claude Code CLI hooks, PRoot bind mounts, JUnit.

## Delivered

- [x] `ClaudePermissionBridge` + tests
- [x] Guest hook script + settings merge (`ClaudePermissionHooks`)
- [x] Sandbox bind mount + env
- [x] Runtime watcher + `respondToPermission` / `answerQuestion`
- [x] `ClaudePermissionMode.ASK` + strings (8 locales)
- [x] `sessionDiff` via workspace git
- [x] Install path provisions hook + `jq`
- [x] Docs: `docs/CLAUDE_CODE.md` + design spec
