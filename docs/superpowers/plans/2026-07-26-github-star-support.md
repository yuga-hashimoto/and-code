# GitHub Star Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add non-blocking GitHub Star support across first-run onboarding, settings/About, post-success reminders, optional Star verification, cached repository metadata, and tests.

**Architecture:** Keep Star support independent from existing GitHub git authentication. Public repository metadata is fetched without authentication; Star verification is attempted only when an existing GitHub token is already available. Persistent prompt/caching state is stored alongside the existing first-run settings, while UI callbacks continue to be injected through the current Compose/navigation architecture.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, OkHttp, kotlinx.serialization, existing encrypted settings repository, JUnit/MockWebServer where practical.

## Global Constraints

- Star is never required to use the app or unlock features.
- Do not automatically Star through the API; the primary action opens the repository page for explicit user action.
- Existing GitHub git authentication remains separate and is never required only for Star support.
- Automatic prompts appear at most twice: first-run prompt, then one post-success reminder only when the first prompt was deferred.
- GitHub/API failures must never block onboarding, chat, settings, or runtime use.
- Reuse existing localization and navigation patterns; add English and Japanese copy at minimum and safe translations/fallbacks for existing locales.

---

### Task 1: Star state and project links

**Files:**
- Create: `app/src/main/java/com/opencode/android/core/ProjectLinks.kt`
- Modify: `app/src/main/java/com/opencode/android/data/connection/SecureSettingsRepository.kt`
- Test: `app/src/test/java/com/opencode/android/feature/settings/GitHubStarSupportTest.kt`

**Interfaces:**
- Produces `ProjectLinks.GITHUB_REPOSITORY`, `ProjectLinks.GITHUB_ISSUES`, `ProjectLinks.GITHUB_RELEASES`.
- Produces persistent booleans/timestamps/count for Star prompt and cache state.

- [ ] **Step 1: Write failing tests** covering default prompt state, persisted defer/second-prompt/thank-you state, and cached metadata values.
- [ ] **Step 2: Run the targeted unit test and confirm it fails because the new state/API is missing.**
- [ ] **Step 3: Add `ProjectLinks` and persistent properties:** `githubStarPromptShown`, `githubStarPromptDeferred`, `githubStarSecondPromptShown`, `githubStarThankYouShown`, `githubStarredCache`, `githubStarStatusCheckedAt`, `githubStarCountCache`, and `githubStarCountCheckedAt`.
- [ ] **Step 4: Run the targeted unit test and confirm it passes.**

### Task 2: GitHub repository metadata and optional Star verification

**Files:**
- Modify: `app/src/main/java/com/opencode/android/feature/settings/GitHubAuthRepository.kt`
- Modify: `app/src/main/java/com/opencode/android/feature/settings/SettingsViewModel.kt`
- Test: `app/src/test/java/com/opencode/android/feature/settings/GitHubStarSupportTest.kt`

**Interfaces:**
- Produces `GitHubRepoSupportStatus(stargazersCount: Int?, starred: Boolean?)`.
- Produces ViewModel state for Star count/status and refresh actions.

- [ ] **Step 1: Add failing tests** for unauthenticated repository Star count fetch, authenticated `/user/starred/...` verification, 404 = not starred, and network/API failure = unknown without throwing into UI.
- [ ] **Step 2: Run tests and verify expected failures.**
- [ ] **Step 3: Implement repository metadata fetch and optional Star verification using the existing OkHttp client.**
- [ ] **Step 4: Add cache-aware refresh logic to `SettingsViewModel`; verify Star status only when an existing GitHub token is present.**
- [ ] **Step 5: Run targeted tests and verify green.**

### Task 3: First-run Star prompt

**Files:**
- Modify: `app/src/main/java/com/opencode/android/feature/onboarding/OnboardingChoiceScreen.kt`
- Modify: `app/src/main/java/com/opencode/android/ui/OpenCodeApp.kt`
- Modify: localization files under `app/src/main/res/values*/strings.xml`
- Test: existing/new Compose test under `app/src/androidTest/java/com/opencode/android/feature/onboarding/`

**Interfaces:**
- `OnboardingChoiceScreen` consumes Star prompt visibility, Star count, `onStarRepository`, and `onDeferStar` callbacks.

- [ ] **Step 1: Add failing Compose tests** proving the Star request is shown on first run, Star and defer actions are both available, and deferring does not block execution-path selection.
- [ ] **Step 2: Run the targeted Compose test and verify expected failure.**
- [ ] **Step 3: Add the non-blocking Star request card/dialog at the start of onboarding and wire state persistence in `OpenCodeApp`.**
- [ ] **Step 4: Add localized copy and accessibility descriptions.**
- [ ] **Step 5: Re-run the Compose test and verify green.**

### Task 4: Settings and About support section

**Files:**
- Modify: `app/src/main/java/com/opencode/android/feature/settings/SettingsScreenV2.kt`
- Modify: `app/src/main/java/com/opencode/android/ui/navigation/SettingsNavGraph.kt`
- Modify: localization files under `app/src/main/res/values*/strings.xml`
- Test: Compose settings test or new targeted test.

**Interfaces:**
- Settings consumes Star count/status and callbacks for repository, Star action, Issues, and Releases/licenses as appropriate.

- [ ] **Step 1: Add failing Compose tests** for the support section, Star/thank-you state, repository link, issue link, and About content.
- [ ] **Step 2: Run and verify failure.**
- [ ] **Step 3: Add a `Support OpenCode Android`/`このアプリを応援` area to settings and expand About with Star, repository, issue, license/version links.**
- [ ] **Step 4: Wire callbacks through `SettingsNavGraph` using `ProjectLinks` and `ACTION_VIEW`.**
- [ ] **Step 5: Re-run tests and verify green.**

### Task 5: One post-success reminder and thank-you feedback

**Files:**
- Modify: `app/src/main/java/com/opencode/android/ui/OpenCodeApp.kt`
- Modify: relevant chat completion state handling only as needed.
- Modify: localization files under `app/src/main/res/values*/strings.xml`
- Test: targeted state/UI tests.

**Interfaces:**
- Consumes the existing chat run transition to detect the first successful completed task.
- Produces a one-time second Star request only for users who deferred the initial prompt.

- [ ] **Step 1: Add failing tests** proving the second request appears once after a successful run only when the first prompt was deferred, and never becomes a third automatic request.
- [ ] **Step 2: Run and verify failure.**
- [ ] **Step 3: Implement the one-time reminder and persist `githubStarSecondPromptShown`.**
- [ ] **Step 4: When the app resumes/refetches after the repository was opened and authenticated verification confirms Star, show a one-time thank-you Snackbar and persist `githubStarThankYouShown`.**
- [ ] **Step 5: Re-run tests and verify green.**

### Task 6: Full verification and PR readiness

**Files:**
- Review all modified files.

- [ ] **Step 1: Run `./gradlew testDebugUnitTest` and fix any failures.**
- [ ] **Step 2: Run `./gradlew lintDebug assembleDebug` and fix any failures.**
- [ ] **Step 3: Run relevant Compose/instrumentation tests where the CI environment supports them.**
- [ ] **Step 4: Confirm no Star API write endpoint was added, no feature gating exists, and API failures are non-blocking.**
- [ ] **Step 5: Review the final diff, create the pull request, and verify GitHub Actions status.**
