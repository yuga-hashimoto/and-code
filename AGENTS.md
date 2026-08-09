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

## Session Todo

When working on a multi-step task, use the todo feature to track progress.

**Keep the todo list in sync with reality — update it as you go:**

- Mark items `in_progress` immediately before starting them, not after finishing.
- Mark items `completed` as soon as the required work (including verification) is done.
- Update the todo list at every natural checkpoint: after each tool-call batch, after finishing a sub-task, and before replying to the user.
- Do not leave todo items `pending` or stale `in_progress` when the session message ends. The final state of the todo list must always reflect what was actually done.

## PR Workflow

When work is complete:

1. **Run the mandatory pre-PR review.** Load the `pre-pr-review` skill and follow it exactly:
   the `repo-reviewer` subagent must review the full branch diff against `origin/main`, and every
   blocking finding must be fixed and re-reviewed until the verdict is `APPROVE`. NEVER open a PR
   without this. The CI `PR Review Gate` check fails any PR whose description does not contain the
   approved review report (`<!-- pre-pr-review: approved -->`).
2. Create a pull request against `main`, including the reviewer's report as the skill describes.
3. Wait for CI to pass.
4. Merge the PR once CI passes.
