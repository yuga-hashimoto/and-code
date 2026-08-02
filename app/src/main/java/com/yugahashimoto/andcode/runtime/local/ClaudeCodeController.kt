package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.LocalAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a Claude Code install has got to, so the UI can show progress instead of a dead button. */
sealed interface ClaudeInstallStatus {
    data object Idle : ClaudeInstallStatus

    /** [step] is a string resource id describing the current stage. */
    data class Installing(val step: Int) : ClaudeInstallStatus

    data class Ready(val version: String) : ClaudeInstallStatus

    data class Failed(val message: String) : ClaudeInstallStatus
}

/**
 * What an update attempt actually did.
 *
 * `apk` upgrades in place and says nothing about the version it landed on, so an update that had
 * nothing to do and an update that installed a new build looked identical from the outside — the
 * button simply stopped spinning. Comparing the version before and after is the only thing that
 * tells them apart, and the card reports whichever it was.
 */
sealed interface ClaudeUpdateResult {
    /** The version now installed, whether or not this attempt changed it. */
    val version: String

    data class Updated(val fromVersion: String, override val version: String) : ClaudeUpdateResult

    data class AlreadyLatest(override val version: String) : ClaudeUpdateResult
}

/** Classifies an update by what it did to the installed version. */
internal fun claudeUpdateResult(
    before: String?,
    after: String,
): ClaudeUpdateResult =
    if (before.isNullOrBlank() || before == after) {
        ClaudeUpdateResult.AlreadyLatest(after)
    } else {
        ClaudeUpdateResult.Updated(before, after)
    }

data class ClaudeCodeUiState(
    val installed: Boolean = false,
    val version: String? = null,
    val install: ClaudeInstallStatus = ClaudeInstallStatus.Idle,
    val lastUpdate: ClaudeUpdateResult? = null,
    val auth: ClaudeAuthCoordinator.State = ClaudeAuthCoordinator.State.Idle,
    val signedInAccount: String? = null,
    val permissionMode: ClaudePermissionMode = ClaudePermissionMode.DEFAULT,
)

/**
 * Single owner of the Claude Code install and sign-in flows.
 *
 * Both the setup guide and the Workspaces screen drive Claude Code, and previously each did so by
 * calling the runtime directly — which is how a successful install could leave the card still
 * reading "not installed". Routing everything through one observable state removes that class of
 * bug: whoever triggers the work, every screen sees the same result.
 */
class ClaudeCodeController(
    private val target: ClaudeCodeTarget,
    private val runtime: ClaudeCodeRuntime,
    private val installer: LocalRuntimeInstaller,
    private val scope: CoroutineScope,
    private val messages: ClaudeMessages = ClaudeMessages,
) {
    private val mutableState = MutableStateFlow(ClaudeCodeUiState())

    val state: StateFlow<ClaudeCodeUiState> =
        combine(mutableState, target.auth.state, target.defaultPermissionMode) { base, auth, mode ->
            base.copy(auth = auth, permissionMode = mode)
        }.stateIn(scope, SharingStarted.Eagerly, ClaudeCodeUiState())

    private var installJob: Job? = null

    init {
        // Without this the state starts empty and stays that way until something triggers a
        // refresh, so a sandbox that already has Claude Code reads as "not installed" on every
        // launch.
        refresh()
    }

    /** Reads the sandbox to find out whether Claude Code is installed and signed in. */
    fun refresh() {
        if (installJob?.isActive == true) return
        // Reading a damaged environment on disk must not reach the uncaught-exception handler and
        // take the app down; this refresh is best-effort, so a failure leaves the state as is.
        scope.launch { runCatching { refreshBlocking() } }
    }

    /**
     * Provisions the Linux sandbox if needed, then installs Claude Code.
     *
     * Safe to call when only OpenCode is installed: the sandbox is shared, so this adds a package
     * rather than reinstalling everything.
     */
    fun install() {
        if (installJob?.isActive == true) return
        installJob =
            scope.launch(Dispatchers.IO) {
                runCatching {
                    if (installer.installedRuntime() == null) {
                        report(ClaudeInstallStatus.Installing(R.string.claude_step_preparing_runtime))
                        installer.install(agents = setOf(LocalAgent.CLAUDE_CODE)) { _, _, _ -> }
                    }
                    report(ClaudeInstallStatus.Installing(R.string.claude_step_adding_repository))
                    target.install { step ->
                        report(
                            ClaudeInstallStatus.Installing(
                                when (step) {
                                    ClaudeCodeInstaller.Step.ADDING_REPOSITORY ->
                                        R.string.claude_step_adding_repository
                                    ClaudeCodeInstaller.Step.DOWNLOADING_PACKAGE ->
                                        R.string.claude_step_downloading_package
                                    ClaudeCodeInstaller.Step.VERIFYING ->
                                        R.string.claude_step_verifying
                                },
                            ),
                        )
                    }.getOrThrow()
                    // Recorded so a later OpenCode install or a reinstall keeps Claude Code.
                    installer.recordAgent(LocalAgent.CLAUDE_CODE)
                }.onSuccess {
                    refreshBlocking()
                }.onFailure { error ->
                    report(ClaudeInstallStatus.Failed(error.message ?: messages.installFailed))
                }
            }
    }

    fun update() {
        if (installJob?.isActive == true) return
        installJob =
            scope.launch(Dispatchers.IO) {
                mutableState.value = mutableState.value.copy(lastUpdate = null)
                report(ClaudeInstallStatus.Installing(R.string.claude_step_downloading_package))
                target.update()
                    .onSuccess { result ->
                        refreshBlocking()
                        // After the refresh, so the version the card shows and the outcome it
                        // reports come from the same read of the sandbox.
                        mutableState.value = mutableState.value.copy(lastUpdate = result)
                    }
                    .onFailure { error -> report(ClaudeInstallStatus.Failed(error.message ?: messages.updateFailed)) }
            }
    }

    fun setPermissionMode(
        mode: ClaudePermissionMode,
        sessionId: String? = null,
    ) = target.setPermissionMode(mode, sessionId)

    fun beginSignIn() = target.auth.begin()

    fun submitSignInCode(code: String) = target.auth.submitCode(code)

    fun cancelSignIn() = target.auth.reset()

    fun signOut() {
        scope.launch(Dispatchers.IO) {
            target.auth.signOut()
            refreshBlocking()
        }
    }

    private suspend fun refreshBlocking() =
        withContext(Dispatchers.IO) {
            val version = runtime.version()
            if (version != null) runtime.loadResolvedModels()
            val account = if (version != null) target.auth.signedInAccount() else null
            target.health()
            mutableState.value =
                mutableState.value.copy(
                    installed = version != null,
                    version = version,
                    signedInAccount = account,
                    install =
                        when {
                            version != null -> ClaudeInstallStatus.Ready(version)
                            // A failure stays on screen until the user retries, so the reason for an
                            // empty card is never lost to a background refresh.
                            mutableState.value.install is ClaudeInstallStatus.Failed -> mutableState.value.install
                            else -> ClaudeInstallStatus.Idle
                        },
                )
        }

    private fun report(status: ClaudeInstallStatus) {
        mutableState.value = mutableState.value.copy(install = status)
    }
}
