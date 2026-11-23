package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.runtime.RuntimeWorkTracker
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.RuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a Codex install has got to. */
sealed interface CodexInstallStatus {
    data object Idle : CodexInstallStatus

    /**
     * [progress] and [step] come from [LocalRuntimeInstaller] when this install also provisions the
     * shared environment (a setup without OpenCode); adding Codex to an existing environment is one
     * download with neither, so both are null there.
     */
    data class Installing(val progress: Float? = null, val step: String? = null) : CodexInstallStatus

    /** [message] is null when nothing more specific is known, so the UI shows its own translated default. */
    data class Failed(val message: String?) : CodexInstallStatus
}

/** What an update attempt did to the installed release, mirroring `AntigravityUpdateResult`. */
sealed interface CodexUpdateResult {
    /** The version now installed, whether or not this attempt changed it. */
    val version: String

    data class Updated(val fromVersion: String, override val version: String) : CodexUpdateResult

    data class AlreadyLatest(override val version: String) : CodexUpdateResult
}

/** Classifies an update by what it did to the installed version. */
internal fun codexUpdateResult(
    before: String?,
    after: String,
): CodexUpdateResult =
    if (before.isNullOrBlank() || before == after) {
        CodexUpdateResult.AlreadyLatest(after)
    } else {
        CodexUpdateResult.Updated(before, after)
    }

data class CodexUiState(
    val installed: Boolean = false,
    val version: String? = null,
    val signedIn: Boolean = false,
    val install: CodexInstallStatus = CodexInstallStatus.Idle,
    val lastUpdate: CodexUpdateResult? = null,
) {
    val ready: Boolean get() = installed && signedIn
}

/**
 * Single owner of the Codex install state and sign-in status.
 *
 * Much smaller than `ClaudeCodeController` because Codex has no permission-mode setting: it installs
 * verified binaries (the CLI and its code-mode host) into the shared rootfs. The sign-in itself (ChatGPT browser login or an API
 * key) runs through the provider dialog - see [com.yugahashimoto.andcode.feature.settings.CodexSignInViewModel]
 * - and calls [refresh] when it finishes.
 */
class CodexController(
    private val runtime: CodexRuntime,
    private val target: CodexTarget,
    private val installer: LocalRuntimeInstaller,
    private val abi: String,
    /** Required for the same reason as in [AntigravityController]: install is real work with no other lease. */
    private val runtimeWork: RuntimeWorkTracker,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val mutableState = MutableStateFlow(CodexUiState())
    val state: StateFlow<CodexUiState> = mutableState.asStateFlow()

    init {
        // Not the sign-in check: that starts the app-server, a resident ~75 MB process, and an app
        // launch should not pay for it when the user never opens Codex. The settings screen refreshes
        // with the check when it is opened.
        refresh(checkSignIn = false)
    }

    /** Re-reads what is installed and, with [checkSignIn], whether an account is signed in. */
    fun refresh(checkSignIn: Boolean = true) {
        // Best-effort rehydration: nothing above this launch catches what it throws.
        scope.launch { runCatching { rehydrate(checkSignIn) } }
    }

    private suspend fun rehydrate(checkSignIn: Boolean = true) {
        // Always connect, even when nothing is installed: that is what leaves the target
        // Unavailable while Codex is missing, and the drawer's agent switcher hides Unavailable
        // targets. Skipping it left an uninstalled Codex offered as a chat destination.
        target.connect()
        val version = (target.state.value as? RuntimeState.Connected)?.version
        if (version == null) {
            mutableState.update { it.copy(installed = false, version = null, signedIn = false) }
            return
        }
        val signedIn = if (checkSignIn) runtime.isSignedIn() else mutableState.value.signedIn
        mutableState.update { it.copy(installed = true, version = version, signedIn = signedIn) }
    }

    /**
     * Installs Codex, provisioning the shared Linux environment first when there is none yet.
     *
     * [agents] is what the setup guide selected: with no OpenCode among it, this is the one install
     * for the whole selection (it must stay one, because a second would race it for the same staging
     * directory), and [LocalRuntimeInstaller] provisions every agent named in it. Codex alone, from
     * Settings, is the default.
     */
    fun install(
        agents: Set<LocalAgent> = setOf(LocalAgent.CODEX),
        installFullDevelopmentTools: Boolean = false,
    ) {
        if (mutableState.value.install is CodexInstallStatus.Installing) return
        mutableState.update { it.copy(install = CodexInstallStatus.Installing()) }
        scope.launch {
            runtimeWork.withLease(INSTALL_LEASE_TAG) {
                runCatching {
                    val existing = installer.installedMetadata()
                    // Another selected agent the environment does not have yet needs the full
                    // install, which provisions the whole selection at once (and carries over what is
                    // already there); only adding Codex alone to an existing environment can skip it.
                    val othersMissing = (agents - LocalAgent.CODEX).any { existing?.has(it) != true }
                    if (installer.installedRuntime() == null || othersMissing) {
                        installer.install(agents + LocalAgent.CODEX, installFullDevelopmentTools) { progress, step, _ ->
                            mutableState.update { it.copy(install = CodexInstallStatus.Installing(progress, step)) }
                        }
                    } else {
                        if (installFullDevelopmentTools) installer.installFullDevelopmentTools()
                        runtime.install(abi)
                        // Recorded so a later install that rebuilds the sandbox keeps Codex.
                        installer.recordAgent(LocalAgent.CODEX)
                    }
                }
                    .onSuccess {
                        mutableState.update { it.copy(install = CodexInstallStatus.Idle) }
                        runCatching { rehydrate() }
                    }
                    .onFailure { error ->
                        val detail = error.cause?.message?.takeIf { it.isNotBlank() }
                        val message = listOfNotNull(error.message, detail).joinToString(": ").ifBlank { null }
                        mutableState.update { it.copy(install = CodexInstallStatus.Failed(message)) }
                    }
            }
        }
    }

    /**
     * Replaces the installed binaries with the newest npm release.
     *
     * Deliberately not [install]: an update only re-downloads the two Codex binaries into the
     * existing environment, while [install] may provision a whole one. The release is resolved at
     * press time, so whether anything newer exists is only known by comparing the version on both
     * sides of the attempt - which is also what lets the card say what happened instead of just
     * going quiet.
     */
    fun update() {
        if (mutableState.value.install is CodexInstallStatus.Installing) return
        val before = mutableState.value.version
        mutableState.update { it.copy(install = CodexInstallStatus.Installing(), lastUpdate = null) }
        scope.launch {
            runtimeWork.withLease(UPDATE_LEASE_TAG) {
                runCatching { runtime.update(abi) }
                    .onSuccess { version ->
                        // Re-reads the new binary's version and re-announces the target, so the
                        // connection quality monitor and the drawer see the release that is now on
                        // disk rather than the one this state happened to hold.
                        target.connect()
                        mutableState.update {
                            it.copy(
                                installed = true,
                                version = version,
                                install = CodexInstallStatus.Idle,
                                lastUpdate = codexUpdateResult(before, version),
                            )
                        }
                    }
                    .onFailure { error ->
                        val detail = error.cause?.message?.takeIf { it.isNotBlank() }
                        val message = listOfNotNull(error.message, detail).joinToString(": ").ifBlank { null }
                        mutableState.update { it.copy(install = CodexInstallStatus.Failed(message)) }
                    }
            }
        }
    }

    fun signOut() {
        scope.launch {
            runtime.logout()
            runCatching { rehydrate() }
        }
    }

    private companion object {
        const val INSTALL_LEASE_TAG = "codex-install"
        const val UPDATE_LEASE_TAG = "codex-update"
    }
}
