# AGENTS.md

## Worktree

At the start of every new session, ALWAYS create a git worktree before making any changes:

1. Fetch the latest refs first: `git fetch origin`
2. Create a new branch and worktree from the remote main (NOT the local HEAD): `git worktree add ../opencode-android-<branch-name> -b <branch-name> origin/main`
3. Work exclusively within the worktree directory.
4. Never modify the main working tree directly.
5. After creating the worktree, ALL subsequent tool calls (read, edit, grep, glob, bash) MUST use the worktree absolute path as their base directory. The default working directory (`/workspace/opencode-android`) is the main tree and must NEVER be used for file operations after worktree creation. Verify by running `pwd` or checking paths before the first edit.
