# AGENTS.md

## Worktree

At the start of every new session, ALWAYS create a git worktree before making any changes.

**HARD RULE: NEVER run `git worktree add` manually. NEVER create a worktree from local HEAD.**

Use the script — it fetches origin and bases the branch on `origin/main` automatically:

```bash
scripts/new-worktree.sh <branch-name>
```

Then:

1. Work exclusively within the worktree directory.
2. Never modify the main working tree directly.
3. After creating the worktree, ALL subsequent tool calls (read, edit, grep, glob, bash) MUST use the worktree absolute path as their base directory. The default working directory (`/workspace/and-code`) is the main tree and must NEVER be used for file operations after worktree creation. Verify by running `pwd` or checking paths before the first edit.
4. Once the branch's pull request is merged, clean up immediately: remove the worktree (`git worktree remove <path>`) and delete the local branch (`git branch -D <branch-name>`). Never leave a merged worktree or its branch behind.

## PR Workflow

When work is complete:

1. Create a pull request against `main`.
2. Wait for CI to pass.
3. Merge the PR once CI passes.
