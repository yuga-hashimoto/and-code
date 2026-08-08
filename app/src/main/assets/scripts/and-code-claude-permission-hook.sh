#!/bin/sh
# AndCode PermissionRequest hook for Claude Code.
# Reads hook JSON on stdin, asks the Android app via the file bridge, prints a decision.

set -eu

BRIDGE="${ANDCODE_CLAUDE_BRIDGE:-/root/.andcode/claude-bridge}"
PENDING="$BRIDGE/pending"
RESPONSES="$BRIDGE/responses"
ALWAYS="$BRIDGE/always-rules.json"
TIMEOUT_SEC="${ANDCODE_PERMISSION_TIMEOUT_SEC:-300}"
SLEEP_SEC=0.25

mkdir -p "$PENDING" "$RESPONSES"

INPUT=$(cat)
if [ -z "$INPUT" ]; then
  exit 0
fi

# Prefer jq; fall back to a tiny python helper when present.
if command -v jq >/dev/null 2>&1; then
  TOOL_NAME=$(printf '%s' "$INPUT" | jq -r '.tool_name // .toolName // empty')
  SESSION_ID=$(printf '%s' "$INPUT" | jq -r '.session_id // .sessionId // empty')
  TOOL_INPUT=$(printf '%s' "$INPUT" | jq -c '.tool_input // .toolInput // {}')
  HOOK_EVENT=$(printf '%s' "$INPUT" | jq -r '.hook_event_name // .hookEventName // "PermissionRequest"')
else
  TOOL_NAME=$(printf '%s' "$INPUT" | sed -n 's/.*"tool_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)
  SESSION_ID=$(printf '%s' "$INPUT" | sed -n 's/.*"session_id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)
  TOOL_INPUT='{}'
  HOOK_EVENT="PermissionRequest"
fi

if [ -z "$TOOL_NAME" ]; then
  TOOL_NAME="Tool"
fi

# Auto-allow remembered rules without waking the UI.
if [ -f "$ALWAYS" ] && command -v jq >/dev/null 2>&1; then
  CMD=$(printf '%s' "$TOOL_INPUT" | jq -r '.command // empty')
  MATCH=$(jq -r --arg t "$TOOL_NAME" --arg c "$CMD" '
    .rules[]? | select(.toolName == $t) |
    if (.commandPrefix == null or .commandPrefix == "") then "yes"
    elif ($c | startswith(.commandPrefix)) then "yes"
    else empty end
  ' "$ALWAYS" 2>/dev/null | head -n1 || true)
  if [ "$MATCH" = "yes" ]; then
    printf '%s\n' "{\"hookSpecificOutput\":{\"hookEventName\":\"PermissionRequest\",\"permissionDecision\":\"allow\",\"permissionDecisionReason\":\"AndCode always-allow rule\"}}"
    exit 0
  fi
fi

KIND="permission"
if [ "$TOOL_NAME" = "AskUserQuestion" ]; then
  KIND="question"
fi

REQUEST_ID=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || date +%s%N)
ANDROID_SESSION="${ANDCODE_ANDROID_SESSION_ID:-$SESSION_ID}"
if [ -z "$ANDROID_SESSION" ]; then
  ANDROID_SESSION="unknown"
fi

LABEL="$TOOL_NAME"
if command -v jq >/dev/null 2>&1; then
  DESC=$(printf '%s' "$TOOL_INPUT" | jq -r '.description // .command // empty' 2>/dev/null || true)
  if [ -n "$DESC" ]; then
    LABEL="$TOOL_NAME: $DESC"
  fi
fi

REQUEST_FILE="$PENDING/$REQUEST_ID.json"
RESPONSE_FILE="$RESPONSES/$REQUEST_ID.json"

if command -v jq >/dev/null 2>&1; then
  jq -n \
    --arg kind "$KIND" \
    --arg requestId "$REQUEST_ID" \
    --arg androidSessionId "$ANDROID_SESSION" \
    --arg claudeSessionId "$SESSION_ID" \
    --arg toolName "$TOOL_NAME" \
    --arg permissionLabel "$LABEL" \
    --argjson toolInput "$TOOL_INPUT" \
    --argjson createdAtMs "$(date +%s000)" \
    '{v:1,kind:$kind,requestId:$requestId,androidSessionId:$androidSessionId,claudeSessionId:$claudeSessionId,toolName:$toolName,toolInput:$toolInput,permissionLabel:$permissionLabel,createdAtMs:$createdAtMs}' \
    >"$REQUEST_FILE"
else
  printf '%s\n' "{\"v\":1,\"kind\":\"$KIND\",\"requestId\":\"$REQUEST_ID\",\"androidSessionId\":\"$ANDROID_SESSION\",\"claudeSessionId\":\"$SESSION_ID\",\"toolName\":\"$TOOL_NAME\",\"toolInput\":{},\"permissionLabel\":\"$LABEL\",\"createdAtMs\":0}" >"$REQUEST_FILE"
fi

elapsed=0
while [ "$elapsed" -lt "$TIMEOUT_SEC" ]; do
  if [ -f "$RESPONSE_FILE" ]; then
    if command -v jq >/dev/null 2>&1; then
      DECISION=$(jq -r '.decision // "deny"' "$RESPONSE_FILE")
      MESSAGE=$(jq -r '.message // empty' "$RESPONSE_FILE")
      UPDATED=$(jq -c '.updatedInput // empty' "$RESPONSE_FILE")
    else
      DECISION=$(sed -n 's/.*"decision"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$RESPONSE_FILE" | head -n1)
      MESSAGE=""
      UPDATED=""
    fi
    rm -f "$REQUEST_FILE" "$RESPONSE_FILE" 2>/dev/null || true
    if [ "$DECISION" = "allow" ]; then
      if [ -n "$UPDATED" ] && [ "$UPDATED" != "null" ] && [ "$UPDATED" != "" ]; then
        if command -v jq >/dev/null 2>&1; then
          jq -n --argjson updated "$UPDATED" \
            '{hookSpecificOutput:{hookEventName:"PermissionRequest",permissionDecision:"allow",updatedInput:$updated}}'
        else
          printf '%s\n' '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","permissionDecision":"allow"}}'
        fi
      else
        printf '%s\n' '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","permissionDecision":"allow"}}'
      fi
      exit 0
    fi
    REASON=${MESSAGE:-User rejected this action}
    if command -v jq >/dev/null 2>&1; then
      jq -n --arg reason "$REASON" \
        '{hookSpecificOutput:{hookEventName:"PermissionRequest",permissionDecision:"deny",permissionDecisionReason:$reason}}'
    else
      printf '%s\n' "{\"hookSpecificOutput\":{\"hookEventName\":\"PermissionRequest\",\"permissionDecision\":\"deny\",\"permissionDecisionReason\":\"$REASON\"}}"
    fi
    exit 0
  fi
  # shellcheck disable=SC2039
  sleep "$SLEEP_SEC" 2>/dev/null || sleep 1
  elapsed=$((elapsed + 1))
done

rm -f "$REQUEST_FILE" 2>/dev/null || true
printf '%s\n' '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","permissionDecision":"deny","permissionDecisionReason":"User did not respond in time"}}'
exit 0
