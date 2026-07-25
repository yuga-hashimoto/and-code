package com.opencode.android.data.repository

import com.opencode.android.core.api.OpenCodeEvent
import com.opencode.android.core.api.PermissionRequest
import com.opencode.android.runtime.RuntimeRegistry
import com.opencode.android.runtime.RuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RuntimeEventLog(
    val timestamp: Long = System.currentTimeMillis(),
    val title: String,
    val detail: String? = null,
    val sessionId: String? = null
)

data class RuntimeActivityState(
    val activeSessionIds: Set<String> = emptySet(),
    val permissions: List<PermissionRequest> = emptyList(),
    val logs: List<RuntimeEventLog> = emptyList(),
    val streamError: String? = null
)

class RuntimeActivityRepository(
    registry: RuntimeRegistry,
    scope: CoroutineScope,
    private val retryDelayMillis: Long = 2_000L,
    private val maxRetryDelayMillis: Long = 30_000L,
    private val onPermissionAsked: ((PermissionRequest) -> Unit)? = null,
    private val onSessionIdle: ((String) -> Unit)? = null,
    private val onSessionError: ((String?, String?) -> Unit)? = null
) {
    init {
        require(retryDelayMillis >= 0L)
        require(maxRetryDelayMillis >= retryDelayMillis)
    }

    private val mutableState = MutableStateFlow(RuntimeActivityState())
    val state: StateFlow<RuntimeActivityState> = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<OpenCodeEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<OpenCodeEvent> = mutableEvents.asSharedFlow()

    init {
        scope.launch {
            registry.selected.collectLatest selected@ { target ->
                mutableState.value = RuntimeActivityState()
                if (target == null) return@selected

                // `connect()` briefly flips the target to Connecting on every catalog refresh.
                // Treating Connecting as still-streamable keeps the event subscription alive
                // instead of tearing it down and losing events on each refresh.
                target.state
                    .map { it is RuntimeState.Connected || it is RuntimeState.Connecting }
                    .distinctUntilChanged()
                    .collectLatest state@ { streamable ->
                        mutableState.update {
                            it.copy(
                                activeSessionIds = emptySet(),
                                permissions = emptyList(),
                                streamError = null
                            )
                        }
                        if (!streamable) return@state

                        flow { emitAll(target.events()) }
                            .retryWhen { error, attempt ->
                                mutableState.update {
                                    it.copy(
                                        streamError = error.message
                                            ?: "OpenCodeイベント接続に失敗しました"
                                    )
                                }
                                if (retryDelayMillis > 0L) {
                                    val backoff = (retryDelayMillis * (1L shl attempt.toInt().coerceAtMost(4)))
                                        .coerceAtMost(maxRetryDelayMillis)
                                    delay(backoff)
                                }
                                true
                            }
                            .collect { event ->
                                mutableState.update { it.copy(streamError = null) }
                                mutableEvents.emit(event)
                                handle(event)
                            }
                    }
            }
        }
    }

    fun resolvePermission(permissionId: String) {
        mutableState.update { current ->
            current.copy(permissions = current.permissions.filterNot { it.id == permissionId })
        }
    }

    private fun handle(event: OpenCodeEvent) {
        when (event) {
            OpenCodeEvent.ServerConnected -> appendLog("イベント接続", "OpenCodeのリアルタイムイベントへ接続しました")
            is OpenCodeEvent.MessageUpdated -> {
                mutableState.update { current ->
                    current.copy(activeSessionIds = current.activeSessionIds + event.info.sessionId)
                }
            }
            is OpenCodeEvent.MessagePartUpdated -> {
                val sessionId = event.part.sessionId ?: return
                mutableState.update { current ->
                    current.copy(activeSessionIds = current.activeSessionIds + sessionId)
                }
                when (event.part.type) {
                    "tool", "tool-invocation" -> appendLog("ツール実行", event.part.tool, sessionId)
                    "reasoning" -> appendLog("推論", null, sessionId)
                }
            }
            is OpenCodeEvent.MessagePartDelta -> {
                mutableState.update { current ->
                    current.copy(activeSessionIds = current.activeSessionIds + event.sessionId)
                }
            }
            is OpenCodeEvent.PermissionAsked -> {
                mutableState.update { current ->
                    current.copy(
                        permissions = current.permissions.filterNot { it.id == event.request.id } + event.request,
                        activeSessionIds = current.activeSessionIds + event.request.sessionId
                    )
                }
                appendLog("承認待ち", event.request.permission, event.request.sessionId)
                onPermissionAsked?.invoke(event.request)
            }
            is OpenCodeEvent.SessionStatusChanged -> {
                // Notifications stay driven by session.idle so a run is never announced twice.
                mutableState.update { current ->
                    current.copy(
                        activeSessionIds = if (event.status == "idle") {
                            current.activeSessionIds - event.sessionId
                        } else {
                            current.activeSessionIds + event.sessionId
                        }
                    )
                }
            }
            is OpenCodeEvent.PermissionReplied -> {
                mutableState.update { current ->
                    current.copy(permissions = current.permissions.filterNot { it.id == event.requestId })
                }
            }
            is OpenCodeEvent.SessionIdle -> {
                mutableState.update { current ->
                    current.copy(activeSessionIds = current.activeSessionIds - event.sessionId)
                }
                appendLog("実行完了", null, event.sessionId)
                onSessionIdle?.invoke(event.sessionId)
            }
            is OpenCodeEvent.SessionError -> {
                event.sessionId?.let { sessionId ->
                    mutableState.update { current ->
                        current.copy(activeSessionIds = current.activeSessionIds - sessionId)
                    }
                }
                appendLog("実行エラー", event.message, event.sessionId)
                onSessionError?.invoke(event.sessionId, event.message)
            }
            is OpenCodeEvent.Unknown -> appendLog("未対応イベント", event.type)
        }
    }

    private fun appendLog(title: String, detail: String? = null, sessionId: String? = null) {
        mutableState.update { current ->
            current.copy(
                logs = (listOf(RuntimeEventLog(title = title, detail = detail, sessionId = sessionId)) + current.logs)
                    .take(MAX_LOGS)
            )
        }
    }

    companion object {
        private const val MAX_LOGS = 100
    }
}
