package com.yugahashimoto.andcode.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeOperationResult
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeUpdateCheck
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OpenCodeAgentUiState(
    val status: LocalRuntimeStatus = LocalRuntimeStatus.NotInstalled,
    val updateCheck: LocalRuntimeUpdateCheck? = null,
    val rollbackVersion: String? = null,
    val lastOperation: LocalRuntimeOperationResult? = null,
    val freeBytes: Long = 0L,
    val isCheckingUpdate: Boolean = false,
    val showUpdateConfirmation: Boolean = false,
    val showRollbackConfirmation: Boolean = false,
    val updateError: String? = null,
    val error: String? = null,
) {
    /** True while an operation owns the runtime, so no other action may be dispatched. */
    val busy: Boolean
        get() =
            status is LocalRuntimeStatus.Installing ||
                status is LocalRuntimeStatus.Starting ||
                status is LocalRuntimeStatus.Updating

    val installed: Boolean
        get() = status !is LocalRuntimeStatus.NotInstalled && status !is LocalRuntimeStatus.UnsupportedAbi

    val version: String?
        get() =
            when (val current = status) {
                is LocalRuntimeStatus.Ready -> current.version
                is LocalRuntimeStatus.Starting -> current.version
                is LocalRuntimeStatus.Stopped -> current.version
                is LocalRuntimeStatus.Updating -> current.currentVersion
                else -> null
            }

    val port: Int?
        get() =
            when (val current = status) {
                is LocalRuntimeStatus.Ready -> current.port
                is LocalRuntimeStatus.Starting -> current.port
                is LocalRuntimeStatus.Stopped -> current.port
                else -> null
            }
}

/**
 * Drives the OpenCode agent settings screen.
 *
 * OpenCode's version, port, update and rollback used to live on the shared local runtime screen,
 * next to facts that belong to every agent — the container's storage, the ADB pairing, the tool
 * checks. They are OpenCode's alone, so they are read and acted on here instead. Deliberately
 * separate from [com.yugahashimoto.andcode.feature.workspace.LocalRuntimeManagementViewModel]: this
 * screen must not pay for that one's diagnostics sweep, which shells out once per required tool.
 */
class OpenCodeAgentSettingsViewModel(
    runtimeState: StateFlow<LocalRuntimeStatus>,
    lastOperationState: StateFlow<LocalRuntimeOperationResult?>,
    private val updateCheckProvider: suspend () -> Result<LocalRuntimeUpdateCheck>,
    private val rollbackVersionProvider: suspend () -> String?,
    private val freeBytesProvider: () -> Long,
    private val startAction: () -> Unit,
    private val stopAction: () -> Unit,
    private val restartAction: () -> Unit,
    private val updateAction: () -> Unit,
    private val rollbackAction: () -> Unit,
    private val getString: (Int) -> String,
) : ViewModel() {
    private val mutableState =
        MutableStateFlow(
            OpenCodeAgentUiState(
                status = runtimeState.value,
                lastOperation = lastOperationState.value,
            ),
        )
    val state: StateFlow<OpenCodeAgentUiState> = mutableState.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            runtimeState.collect { status ->
                val settled = mutableState.value.busy && status.isTerminal()
                mutableState.update { it.copy(status = status) }
                // The version and the rollback target both move with an update, so a finished
                // operation has to re-read them or the card keeps reporting the old release.
                if (settled) refresh()
            }
        }
        viewModelScope.launch {
            lastOperationState.collect { operation ->
                mutableState.update { it.copy(lastOperation = operation) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val rollbackVersion = runCatching { rollbackVersionProvider() }.getOrNull()
            val freeBytes = runCatching { freeBytesProvider() }.getOrDefault(0L)
            mutableState.update {
                it.copy(rollbackVersion = rollbackVersion, freeBytes = freeBytes.coerceAtLeast(0L))
            }
        }
        checkForUpdate()
    }

    fun checkForUpdate() {
        if (mutableState.value.isCheckingUpdate) return
        mutableState.update { it.copy(isCheckingUpdate = true, updateError = null) }
        viewModelScope.launch {
            runCatching { updateCheckProvider() }
                .getOrElse { Result.failure(it) }
                .onSuccess { check ->
                    mutableState.update {
                        it.copy(updateCheck = check, isCheckingUpdate = false, updateError = null)
                    }
                }.onFailure { error ->
                    mutableState.update {
                        it.copy(
                            isCheckingUpdate = false,
                            updateError = error.messageOr(R.string.runtime_update_check_failed),
                        )
                    }
                }
        }
    }

    fun start() {
        if (mutableState.value.busy || !mutableState.value.installed) return
        dispatch(R.string.runtime_error_start_failed, startAction)
    }

    fun stop() {
        if (!mutableState.value.installed) return
        dispatch(R.string.runtime_error_stop_failed, stopAction)
    }

    fun restart() {
        if (mutableState.value.busy || !mutableState.value.installed) return
        dispatch(R.string.runtime_error_start_failed, restartAction)
    }

    fun requestUpdate() {
        if (mutableState.value.updateCheck !is LocalRuntimeUpdateCheck.Available) return
        if (mutableState.value.busy) return
        mutableState.update { it.copy(showUpdateConfirmation = true) }
    }

    fun dismissUpdate() {
        mutableState.update { it.copy(showUpdateConfirmation = false) }
    }

    fun confirmUpdate() {
        mutableState.update { it.copy(showUpdateConfirmation = false, error = null) }
        dispatch(R.string.runtime_update_start_failed, updateAction)
    }

    fun requestRollback() {
        if (mutableState.value.rollbackVersion.isNullOrBlank()) return
        if (mutableState.value.busy) return
        mutableState.update { it.copy(showRollbackConfirmation = true) }
    }

    fun dismissRollback() {
        mutableState.update { it.copy(showRollbackConfirmation = false) }
    }

    fun confirmRollback() {
        mutableState.update { it.copy(showRollbackConfirmation = false, error = null) }
        dispatch(R.string.runtime_rollback_start_failed, rollbackAction)
    }

    private fun dispatch(
        fallbackMessageRes: Int,
        action: () -> Unit,
    ) {
        runCatching(action).onFailure { error ->
            mutableState.update { it.copy(error = error.messageOr(fallbackMessageRes)) }
        }
    }

    private fun Throwable.messageOr(fallbackRes: Int): String = message?.takeIf(String::isNotBlank) ?: getString(fallbackRes)
}

private fun LocalRuntimeStatus.isTerminal(): Boolean =
    this is LocalRuntimeStatus.Ready ||
        this is LocalRuntimeStatus.Stopped ||
        this is LocalRuntimeStatus.Broken ||
        this is LocalRuntimeStatus.NotInstalled ||
        this is LocalRuntimeStatus.UnsupportedAbi
