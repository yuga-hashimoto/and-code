package com.opencode.android.feature.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.android.R
import com.opencode.android.runtime.LocalRuntimeStatus
import com.opencode.android.runtime.local.AdbConnectionState
import com.opencode.android.runtime.local.LocalRuntimeDiagnostics
import com.opencode.android.runtime.local.LocalRuntimeOperationResult
import com.opencode.android.runtime.local.LocalRuntimeUpdateCheck
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LocalRuntimeManagementUiState(
    val diagnostics: LocalRuntimeDiagnostics? = null,
    val runtimeStatus: LocalRuntimeStatus = LocalRuntimeStatus.NotInstalled,
    val updateCheck: LocalRuntimeUpdateCheck? = null,
    val rollbackVersion: String? = null,
    val lastOperation: LocalRuntimeOperationResult? = null,
    val isLoading: Boolean = true,
    val isCheckingUpdate: Boolean = false,
    val isDeleting: Boolean = false,
    val showUpdateConfirmation: Boolean = false,
    val showRollbackConfirmation: Boolean = false,
    val showDeleteConfirmation: Boolean = false,
    val deleteCompleted: Boolean = false,
    val error: String? = null,
    val updateError: String? = null,
    val adbState: AdbConnectionState = AdbConnectionState.Disconnected,
    val showAdbPairDialog: Boolean = false,
    val isAdbPairing: Boolean = false,
    val isAdbConnecting: Boolean = false,
)

class LocalRuntimeManagementViewModel(
    private val runtimeState: StateFlow<LocalRuntimeStatus>,
    lastOperationState: StateFlow<LocalRuntimeOperationResult?>,
    private val diagnosticsProvider: suspend () -> LocalRuntimeDiagnostics,
    private val updateCheckProvider: suspend () -> Result<LocalRuntimeUpdateCheck>,
    private val rollbackVersionProvider: suspend () -> String?,
    private val repairAction: () -> Unit,
    private val updateAction: () -> Unit,
    private val rollbackAction: () -> Unit,
    private val deleteAction: () -> Unit,
    private val getString: (Int) -> String,
    private val deleteTimeoutMillis: Long = 30_000L,
    adbState: StateFlow<AdbConnectionState>? = null,
    private val adbPairAction: (suspend (Int, String) -> Result<Unit>)? = null,
    private val adbConnectAction: (suspend (Int) -> Result<Unit>)? = null,
    private val adbDisconnectAction: (suspend () -> Result<Unit>)? = null,
    private val adbStartDiscovery: (() -> Unit)? = null,
) : ViewModel() {
    init {
        require(deleteTimeoutMillis > 0L)
    }

    private val mutableState =
        MutableStateFlow(
            LocalRuntimeManagementUiState(
                runtimeStatus = runtimeState.value,
                lastOperation = lastOperationState.value,
            ),
        )
    val state: StateFlow<LocalRuntimeManagementUiState> = mutableState.asStateFlow()
    private var deleteTimeoutJob: Job? = null

    init {
        refresh()
        viewModelScope.launch {
            runtimeState.collect { status ->
                val previous = mutableState.value.runtimeStatus
                mutableState.update {
                    it.copy(
                        runtimeStatus = status,
                        diagnostics = it.diagnostics?.copy(status = status),
                    )
                }
                when {
                    status is LocalRuntimeStatus.NotInstalled && mutableState.value.isDeleting -> {
                        deleteTimeoutJob?.cancel()
                        deleteTimeoutJob = null
                        mutableState.update {
                            it.copy(
                                isDeleting = false,
                                deleteCompleted = true,
                                error = null,
                            )
                        }
                    }
                    status is LocalRuntimeStatus.Broken && mutableState.value.isDeleting -> {
                        deleteTimeoutJob?.cancel()
                        deleteTimeoutJob = null
                        mutableState.update {
                            it.copy(
                                isDeleting = false,
                                error = status.reason,
                            )
                        }
                    }
                }
                if (previous.isBusy() && status.isTerminal()) {
                    refreshAfterRuntimeOperation()
                }
            }
        }
        viewModelScope.launch {
            lastOperationState.collect { operation ->
                mutableState.update { it.copy(lastOperation = operation) }
            }
        }
        adbState?.let { adb ->
            viewModelScope.launch {
                adb.collect { state ->
                    mutableState.update { it.copy(adbState = state) }
                }
            }
        }
    }

    fun refresh() {
        mutableState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val diagnosticsResult = runCatching { diagnosticsProvider() }
            val rollbackResult = runCatching { rollbackVersionProvider() }
            diagnosticsResult
                .onSuccess { diagnostics ->
                    mutableState.update {
                        it.copy(
                            diagnostics = diagnostics,
                            runtimeStatus = diagnostics.status,
                            rollbackVersion = rollbackResult.getOrNull(),
                            isLoading = false,
                            error = null,
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            diagnostics = null,
                            rollbackVersion = rollbackResult.getOrNull(),
                            isLoading = false,
                            error =
                                error.message?.takeIf(String::isNotBlank)
                                    ?: getString(R.string.runtime_diagnostics_failed),
                        )
                    }
                }
        }
        checkForUpdate()
    }

    fun checkForUpdate() {
        if (mutableState.value.isCheckingUpdate) return
        mutableState.update { it.copy(isCheckingUpdate = true, updateError = null) }
        viewModelScope.launch {
            val result =
                runCatching { updateCheckProvider() }
                    .getOrElse { Result.failure(it) }
            result
                .onSuccess { check ->
                    mutableState.update {
                        it.copy(
                            updateCheck = check,
                            isCheckingUpdate = false,
                            updateError = null,
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            isCheckingUpdate = false,
                            updateError =
                                error.message?.takeIf(String::isNotBlank)
                                    ?: getString(R.string.runtime_update_check_failed),
                        )
                    }
                }
        }
    }

    fun repair() {
        dispatchAction(getString(R.string.runtime_repair_start_failed), repairAction)
    }

    fun requestUpdate() {
        if (mutableState.value.updateCheck !is LocalRuntimeUpdateCheck.Available) return
        if (mutableState.value.runtimeStatus.isBusy() || mutableState.value.isDeleting) return
        mutableState.update { it.copy(showUpdateConfirmation = true) }
    }

    fun dismissUpdate() {
        mutableState.update { it.copy(showUpdateConfirmation = false) }
    }

    fun confirmUpdate() {
        mutableState.update { it.copy(showUpdateConfirmation = false, error = null) }
        dispatchAction(getString(R.string.runtime_update_start_failed), updateAction)
    }

    fun requestRollback() {
        if (mutableState.value.rollbackVersion.isNullOrBlank()) return
        if (mutableState.value.runtimeStatus.isBusy() || mutableState.value.isDeleting) return
        mutableState.update { it.copy(showRollbackConfirmation = true) }
    }

    fun dismissRollback() {
        mutableState.update { it.copy(showRollbackConfirmation = false) }
    }

    fun confirmRollback() {
        mutableState.update { it.copy(showRollbackConfirmation = false, error = null) }
        dispatchAction(getString(R.string.runtime_rollback_start_failed), rollbackAction)
    }

    fun requestDelete() {
        if (mutableState.value.runtimeStatus.isBusy()) return
        mutableState.update { it.copy(showDeleteConfirmation = true) }
    }

    fun dismissDelete() {
        mutableState.update { it.copy(showDeleteConfirmation = false) }
    }

    fun confirmDelete() {
        mutableState.update {
            it.copy(
                showDeleteConfirmation = false,
                isDeleting = true,
                error = null,
            )
        }
        runCatching(deleteAction)
            .onSuccess { startDeleteTimeout() }
            .onFailure { error ->
                deleteTimeoutJob?.cancel()
                deleteTimeoutJob = null
                mutableState.update {
                    it.copy(
                        isDeleting = false,
                        error =
                            error.message?.takeIf(String::isNotBlank)
                                ?: getString(R.string.runtime_delete_failed),
                    )
                }
            }
    }

    fun consumeDeleteCompleted() {
        mutableState.update { it.copy(deleteCompleted = false) }
    }

    fun showAdbPairDialog() {
        adbStartDiscovery?.invoke()
        mutableState.update { it.copy(showAdbPairDialog = true) }
    }

    fun dismissAdbPairDialog() {
        mutableState.update { it.copy(showAdbPairDialog = false) }
    }

    fun adbPair(
        pairingPort: Int,
        pairingCode: String,
    ) {
        val action = adbPairAction ?: return
        mutableState.update { it.copy(isAdbPairing = true) }
        viewModelScope.launch {
            action(pairingPort, pairingCode)
                .onSuccess {
                    mutableState.update { it.copy(isAdbPairing = false, showAdbPairDialog = false) }
                }.onFailure { error ->
                    mutableState.update {
                        it.copy(isAdbPairing = false, error = error.message ?: "ペアリングに失敗しました")
                    }
                }
        }
    }

    fun adbConnect(port: Int) {
        val action = adbConnectAction ?: return
        mutableState.update { it.copy(isAdbConnecting = true) }
        viewModelScope.launch {
            action(port)
                .onSuccess {
                    mutableState.update { it.copy(isAdbConnecting = false) }
                }.onFailure { error ->
                    mutableState.update {
                        it.copy(isAdbConnecting = false, error = error.message ?: "接続に失敗しました")
                    }
                }
        }
    }

    fun adbDisconnect() {
        val action = adbDisconnectAction ?: return
        viewModelScope.launch { action() }
    }

    private fun refreshAfterRuntimeOperation() {
        viewModelScope.launch {
            val diagnostics = runCatching { diagnosticsProvider() }.getOrNull()
            val rollbackVersion = runCatching { rollbackVersionProvider() }.getOrNull()
            mutableState.update {
                it.copy(
                    diagnostics = diagnostics ?: it.diagnostics,
                    rollbackVersion = rollbackVersion,
                    error = if (diagnostics == null) it.error else null,
                )
            }
        }
        checkForUpdate()
    }

    private fun dispatchAction(
        fallbackMessage: String,
        action: () -> Unit,
    ) {
        runCatching(action).onFailure { error ->
            mutableState.update {
                it.copy(
                    error = error.message?.takeIf(String::isNotBlank) ?: fallbackMessage,
                )
            }
        }
    }

    private fun startDeleteTimeout() {
        deleteTimeoutJob?.cancel()
        deleteTimeoutJob =
            viewModelScope.launch {
                delay(deleteTimeoutMillis)
                if (mutableState.value.isDeleting) {
                    mutableState.update {
                        it.copy(
                            isDeleting = false,
                            error = getString(R.string.runtime_delete_timeout),
                        )
                    }
                }
                deleteTimeoutJob = null
            }
    }
}

private fun LocalRuntimeStatus.isBusy(): Boolean =
    this is LocalRuntimeStatus.Installing ||
        this is LocalRuntimeStatus.Starting ||
        this is LocalRuntimeStatus.Updating

private fun LocalRuntimeStatus.isTerminal(): Boolean =
    this is LocalRuntimeStatus.Ready ||
        this is LocalRuntimeStatus.Stopped ||
        this is LocalRuntimeStatus.Broken ||
        this is LocalRuntimeStatus.NotInstalled ||
        this is LocalRuntimeStatus.UnsupportedAbi
