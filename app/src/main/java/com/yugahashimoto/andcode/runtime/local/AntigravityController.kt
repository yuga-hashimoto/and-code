package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.runtime.LocalAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where an Antigravity install has got to, so the UI can show progress instead of a dead spinner. */
sealed interface AntigravityInstallStatus {
    data object Idle : AntigravityInstallStatus

    /** [step] is already localized: [LocalRuntimeInstaller] resolves its own string resources. */
    data class Installing(val progress: Float?, val step: String) : AntigravityInstallStatus

    data class Ready(val version: String) : AntigravityInstallStatus

    data class Failed(val message: String) : AntigravityInstallStatus
}

/**
 * What an update attempt did to the installed release.
 *
 * The mirror of `ClaudeUpdateResult`: an update that had nothing to do and one that installed a new
 * binary are indistinguishable unless the version is read on both sides of the attempt.
 */
sealed interface AntigravityUpdateResult {
    /** The version now installed, whether or not this attempt changed it. */
    val version: String

    data class Updated(val fromVersion: String, override val version: String) : AntigravityUpdateResult

    data class AlreadyLatest(override val version: String) : AntigravityUpdateResult
}

/** Classifies an update by what it did to the installed version. */
internal fun antigravityUpdateResult(
    before: String?,
    after: String,
): AntigravityUpdateResult =
    if (before.isNullOrBlank() || before == after) {
        AntigravityUpdateResult.AlreadyLatest(after)
    } else {
        AntigravityUpdateResult.Updated(before, after)
    }

data class AntigravityControllerState(
    val installed: Boolean = false,
    val version: String? = null,
    /**
     * The release this build of the app pins.
     *
     * Antigravity is not fetched from a package repository, so the newest version available to the
     * user is whatever the installed app carries — comparing it against [version] is the whole
     * update check, and it needs no network.
     */
    val bundledVersion: String = AntigravityManifest.VERSION,
    val install: AntigravityInstallStatus = AntigravityInstallStatus.Idle,
    val lastUpdate: AntigravityUpdateResult? = null,
    val auth: AntigravityAuthCoordinator.State = AntigravityAuthCoordinator.State.Idle,
    val permissionMode: AntigravityPermissionMode = AntigravityPermissionMode.DEFAULT,
) {
    /** Kept for call sites that only care whether an install is in flight. */
    val busy: Boolean get() = install is AntigravityInstallStatus.Installing

    /** Kept for call sites that only care about the last failure message. */
    val error: String? get() = (install as? AntigravityInstallStatus.Failed)?.message

    /**
     * True when the installed release differs from the one this app carries.
     *
     * An install whose version was never recorded reports the pinned version (see
     * [AntigravityRuntime.version]), so it reads as up to date rather than prompting an update on a
     * guess.
     */
    val updateAvailable: Boolean get() = installed && version != null && version != bundledVersion
}

/** Single owner for install/update/auth state; UI can observe this without owning a process. */
class AntigravityController(
    private val installer: LocalRuntimeInstaller,
    private val target: AntigravityTarget,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val mutableState = MutableStateFlow(AntigravityControllerState())

    val state: StateFlow<AntigravityControllerState> =
        combine(mutableState, target.auth.state, target.defaultPermissionMode) { base, auth, mode ->
            base.copy(auth = auth, permissionMode = mode)
        }.stateIn(scope, SharingStarted.Eagerly, AntigravityControllerState())

    init {
        refresh()
        scope.launch {
            target.auth.state.collect { auth ->
                // A model list fetched while signed out is just the placeholder; refresh once
                // sign-in actually completes so the picker shows the real catalog.
                if (auth is AntigravityAuthCoordinator.State.SignedIn) target.runtime.invalidateModels()
            }
        }
    }

    /**
     * Re-reads whether the CLI is installed, from the metadata and rootfs on disk.
     *
     * Called on construction to rehydrate after process death, and again whenever an install this
     * controller did not run itself may have provisioned Antigravity - the setup guide's single
     * install goes through the runtime service, so without this the guide would sit on "installing"
     * forever even though the binary is already in the guest.
     */
    fun refresh() {
        // Reading the environment on disk can fail on a half-installed or damaged runtime, and
        // this launch has nothing to catch what it throws: an unhandled exception here takes the
        // whole app down. Rehydration is best-effort, so a failure just leaves the state as is.
        scope.launch { runCatching { rehydrate() } }
    }

    private suspend fun rehydrate() {
        val installed = installer.installedRuntime()
        val rootfs = installed?.antigravityRootfs
        val binaryInstalled = rootfs?.resolve("usr/local/bin/agy")?.let { it.isFile && it.canExecute() } == true
        if (!binaryInstalled) return
        val version = target.runtime.version()
        mutableState.value =
            mutableState.value.copy(
                installed = true,
                version = version,
                install = version?.let(AntigravityInstallStatus::Ready) ?: AntigravityInstallStatus.Idle,
            )
        // The token lives in the guest rootfs, so a restarted app is still signed in even though
        // the in-memory coordinator starts at Idle. `models()` answers that and fills the picker's
        // catalogue in the same launch - asking twice would mean two agy runs, and two of those
        // overlapping is what previously hung both of them.
        if (target.runtime.models().isNotEmpty()) target.auth.markSignedIn()
    }

    /**
     * [agents] lets the setup guide provision its whole selection in this one install when OpenCode
     * is not among it. Two installs would race each other for the same staging directory, so the
     * guide has exactly one entry point per run and this is it whenever Antigravity is selected.
     */
    fun install(agents: Set<LocalAgent> = setOf(LocalAgent.ANTIGRAVITY)) {
        if (mutableState.value.install is AntigravityInstallStatus.Installing) return
        mutableState.value = mutableState.value.copy(install = AntigravityInstallStatus.Installing(0f, ""))
        scope.launch {
            runCatching {
                installer.install(agents + LocalAgent.ANTIGRAVITY) { progress, step, _ ->
                    mutableState.value = mutableState.value.copy(install = AntigravityInstallStatus.Installing(progress, step))
                }
            }
                .onSuccess {
                    target.runtime.invalidateVersion()
                    target.connect()
                    val version =
                        target.state.value.let { (it as? com.yugahashimoto.andcode.runtime.RuntimeState.Connected)?.version }
                    mutableState.value =
                        AntigravityControllerState(
                            installed = true,
                            version = version,
                            install = version?.let(AntigravityInstallStatus::Ready) ?: AntigravityInstallStatus.Idle,
                        )
                }
                .onFailure { error ->
                    mutableState.value =
                        AntigravityControllerState(install = AntigravityInstallStatus.Failed(error.message ?: "Install failed"))
                }
        }
    }

    /**
     * Installs the release this build of the app pins, over whatever is in the guest.
     *
     * Deliberately not [install]: that provisions a whole new environment directory, while an
     * Antigravity update only ever replaces one verified binary. The version is read on both sides
     * so the card can say which release the update landed on instead of just going quiet.
     */
    fun update() {
        if (mutableState.value.install is AntigravityInstallStatus.Installing) return
        val before = mutableState.value.version
        mutableState.value =
            mutableState.value.copy(
                install = AntigravityInstallStatus.Installing(0f, ""),
                lastUpdate = null,
            )
        scope.launch {
            runCatching {
                installer.updateAntigravity { progress ->
                    mutableState.value =
                        mutableState.value.copy(install = AntigravityInstallStatus.Installing(progress, ""))
                }
            }.onSuccess { version ->
                target.runtime.invalidateVersion()
                mutableState.value =
                    mutableState.value.copy(
                        installed = true,
                        version = version,
                        install = AntigravityInstallStatus.Ready(version),
                        lastUpdate = antigravityUpdateResult(before, version),
                    )
            }.onFailure { error ->
                mutableState.value =
                    mutableState.value.copy(
                        install = AntigravityInstallStatus.Failed(error.message ?: "Update failed"),
                    )
            }
        }
    }

    /** [AntigravityAuthCoordinator.logout] blocks on the guest CLI, so this runs off the caller's thread. */
    fun logout() {
        scope.launch {
            target.auth.logout()
            // The cached catalog belongs to the account that just signed out.
            target.runtime.invalidateModels()
        }
    }

    /**
     * [AntigravityAuthCoordinator.start] blocks briefly while it holds [AntigravityProcessGate], so
     * this must never run on the caller's thread - it is only safe to call from `onClick`-style UI
     * code because it is dispatched onto [scope] here.
     */
    fun beginAuth() {
        // Both of these go through the coordinator: [state] takes its auth from the coordinator's
        // own flow, so setting it on this controller's state is overwritten on the next emission.
        // The failure below used to do exactly that, which is why a sign-in that could not start at
        // all - no Linux environment, or the process gate still busy - said nothing.
        target.auth.markStarting()
        scope.launch {
            runCatching { target.auth.start() }
                .onFailure { error -> target.auth.markFailed(error.message ?: "Unable to start sign-in") }
        }
    }

    fun submitAuthCode(code: String) = target.auth.submitCode(code)

    /**
     * Off the caller's thread, for the same reason as [beginAuth].
     *
     * [AntigravityAuthCoordinator.cancel] takes the same lock [AntigravityAuthCoordinator.start]
     * holds through its whole pre-flight, so cancelling a sign-in that is still starting up would
     * otherwise block the UI thread for as long as that takes.
     */
    fun cancelAuth() {
        scope.launch { target.auth.cancel() }
    }

    /** Mirrors [ClaudeCodeController.setPermissionMode]: the open chat changes with the default. */
    fun setPermissionMode(
        mode: AntigravityPermissionMode,
        sessionId: String? = null,
    ) = target.setPermissionMode(mode, sessionId)
}
