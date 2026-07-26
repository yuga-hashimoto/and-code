#!/usr/bin/env bash
set -euo pipefail

COMMIT_MSG_FILE="$1"
COMMIT_MSG=$(head -1 "$COMMIT_MSG_FILE")

PATTERN='^(feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert)(\(.+\))?!?: .{1,}'

if [[ "$COMMIT_MSG" =~ ^Merge ]]; then
  exit 0
fi

if ! echo "$COMMIT_MSG" | grep -qE "$PATTERN"; then
  echo "ERROR: Commit message does not follow Conventional Commits."
  echo ""
  echo "  Format: <type>(<scope>): <description>"
  echo "  Types:  feat, fix, docs, style, refactor, perf, test, build, ci, chore, revert"
  echo ""
  echo "  Examples:"
  echo "    feat: add voice input support"
  echo "    fix(chat): resolve SSE reconnection loop"
  echo "    chore: bump dependencies"
  echo ""
  echo "  Your message: \"$COMMIT_MSG\""
  exit 1
fi
