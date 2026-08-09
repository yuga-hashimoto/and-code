---
name: pre-pr-review
description: Mandatory review gate before opening a pull request in this repository. Runs local CI gates and the repo-reviewer subagent on the full branch diff, iterates until the reviewer approves, and records the verdict in the PR description. Use whenever you are about to create a PR.
license: MIT
---

# Pre-PR review (mandatory)

This repository requires an approved review BEFORE any pull request is created. The reviewer is
the `repo-reviewer` subagent, which carries the repository's architecture map and hard rules. The
gate is also enforced in CI (`.github/workflows/pr-review-gate.yml`): a PR whose description does
not contain an approved review report fails the check.

## Workflow

1. **Sync and scope the diff**

   ```bash
   git fetch origin main
   git diff --stat origin/main...HEAD
   ```

   If the branch has no commits ahead of `origin/main`, stop: there is nothing to review.

2. **Run the local gates first** (cheap failures should never reach the reviewer):

   ```bash
   ./gradlew detekt spotlessCheck
   ```

   If `app/src/main/res/values*/strings.xml` changed, also run the key-parity check from
   `.github/workflows/i18n-check.yml` (every source key must exist in every locale file).
   A full Gradle build may be impossible on-device (x86_64 aapt2 on an arm64 device); that is
   expected — CI compiles. Fix everything that can run locally.

3. **Invoke the reviewer** with the task tool, subagent type `repo-reviewer`. Prompt it with:

   ```
   このブランチ（<branch name>、base: origin/main）のPR前レビューをお願いします。
   変更概要: <one or two sentences describing the change and why>
   ```

   The subagent reviews `git diff origin/main...HEAD` itself; do not paste the whole diff into the
   prompt.

4. **Handle the verdict**

   - `REQUEST_CHANGES`: fix every ブロッカー, then re-run from step 3. Repeat until `APPROVE`.
     Never open the PR while a ブロッカー is outstanding.
   - `APPROVE`: proceed. Consider 提案（非ブロッキング） items; apply the cheap, safe ones.

5. **Record the verdict in the PR description.** The PR body must contain the reviewer's report
   verbatim inside the marker block below — the CI gate looks for the first line:

   ```markdown
   <!-- pre-pr-review: approved -->
   ## Pre-PR review

   <paste the reviewer's final report here>
   ```

   If a later push changes the branch materially, re-run this skill and update the block
   (`pre-pr-review: approved` must stay truthful for the HEAD commit).

## Rules

- Do not skip, summarize away, or forge the reviewer report; the block must be the subagent's
  actual output for the current HEAD.
- If the `repo-reviewer` subagent is unavailable, say so and stop — do not open the PR silently.
- Trivial bot PRs (e.g. Weblate translation sync) are exempt and are skipped by the CI gate.
