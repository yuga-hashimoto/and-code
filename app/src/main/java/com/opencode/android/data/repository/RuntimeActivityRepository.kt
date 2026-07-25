package com.opencode.android.data.repository

import com.opencode.android.core.api.OpenCodeEvent
import com.opencode.android.core.api.PermissionRequest
import com.opencode.android.runtime.RuntimeRegistry
import com.opencode.android.runtime.RuntimeState
import com.opencode.android.runtime.RuntimeTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RuntimeEventLog(
    val timestamp: Long = System.currentTimeMillis(),
    val title: String,
    val detail: String? = null,
    val sessionId: String? = null,
)

/** Persists which chats finished without the user having read them. */
interface UnreadSessionStore {
    var unreadSessionIds: Set<String>
}

data class RuntimeActivityState(
    val activeSessionIds: Set<String> = emptySet(),
    val completedSessionIds: Set<String> = emptySet(),
    val permissions: List<PermissionRequest> = emptyList(),
    val logs: List<RuntimeEventLog> = emptyList(),
    val streamError: String? = null,
)

class RuntimeActivityRepository(
    registry: RuntimeRegistry,
    scope: CoroutineScope,
    private val retryDelayMillis: Long = 2_000L,
    private val maxRetryDelayMillis: Long = 30_000L,
    private val onPermissionAsked: ((PermissionRequest) -> Unit)? = null,
    private val onSessionIdle: ((String) -> Unit)? = null,
    private val onSessionError: ((String?, String?) -> Unit)? = null,
    private val unreadStore: UnreadSessionStore? = null,
) {
    init {
        require(retryDelayMillis >= 0L)
        require(maxRetryDelayMillis >= retryDelayMillis)
    }

    // Unread markers outlive the process: a chat that finished while the app was closed is still
    // unread the next time the drawer is opened.
    private val mutableState =
        MutableStateFlow(
            RuntimeActivityState(completedSessionIds = unreadStore?.unreadSessionIds.orEmpty()),
        )
    val state: StateFlow<RuntimeActivityState> = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<OpenCodeEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<OpenCodeEvent> = mutableEvents.asSharedFlow()

    init {
        scope.launch {
            registry.selected.collectLatest selected@{ target ->
                mutableState.value =
                    RuntimeActivityState(completedSessionIds = mutableState.value.completedSessionIds)
                if (target == null) return@selected

                // The stream follows the selected runtime, not its connection state.
                //
                // It used to only open once the runtime reported Connected. Every REST call
                // (sending a prompt, loading a transcript) works regardless of that flag, so a
                // runtime whose state never reached Connected still looked completely functional
                // while silently never delivering a single event: no live reply, no drawer
                // activity, no idle notification. Reconnection is handled by the retry below, so
                // opening eagerly and retrying is both simpler and strictly more robust.
                coroutineScope {
                    launch { streamEvents(target) }

                    // Losing the runtime clears in-flight state, but never the unread markers:
                    // a dropped connection says nothing about what the user has read.
                    target.state.collect { runtimeState ->
                        if (runtimeState is RuntimeState.Connected || runtimeState is RuntimeState.Connecting) return@collect
                        mutableState.update {
                            it.copy(activeSessionIds = emptySet(), permissions = emptyList())
                        }
                    }
                }
            }
        }
    }

    private suspend fun streamEvents(target: RuntimeTarget) {
        flow { emitAll(target.events()) }
            .retryWhen { error, attempt ->
                mutableState.update {
                    it.copy(
                        streamError = error.message ?: "OpenCodeイベント接続に失敗しました",
                    )
                }
                if (retryDelayMillis > 0L) {
                    val backoff =
                        (retryDelayMillis * (1L shl attempt.toInt().coerceAtMost(4)))
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

    fun resolvePermission(permissionId: String) {
        mutableState.update { current ->
            current.copy(permissions = current.permissions.filterNot { it.id == permissionId })
        }
    }

    fun markSessionRead(sessionId: String) {
        mutableState.update { current ->
            current.copy(completedSessionIds = current.completedSessionIds - sessionId)
        }
        persistUnread()
    }

    /**
     * Records a run the app started itself.
     *
     * Session state used to be derived purely from stream events, so with no event stream every
     * chat sat on the grey idle dot even while it was plainly working. The chat reports its own
     * runs here so the drawer stays truthful whether or not events are flowing.
     */
    fun markSessionRunning(sessionId: String) {
        if (sessionId.isBlank()) return
        mutableState.update { current ->
            current.copy(
                activeSessionIds = current.activeSessionIds + sessionId,
                completedSessionIds = current.completedSessionIds - sessionId,
            )
        }
        persistUnread()
    }

    /** Records that a run finished, leaving the chat unread until it is opened. */
    fun markSessionFinished(
        sessionId: String,
        unread: Boolean,
    ) {
        if (sessionId.isBlank()) return
        mutableState.update { current ->
            current.copy(
                activeSessionIds = current.activeSessionIds - sessionId,
                completedSessionIds =
                    if (unread) {
                        current.completedSessionIds + sessionId
                    } else {
                        current.completedSessionIds - sessionId
                    },
            )
        }
        persistUnread()
    }

    private fun persistUnread() {
        unreadStore?.unreadSessionIds = mutableState.value.completedSessionIds
    }

    private fun handle(event: OpenCodeEvent) {
        when (event) {
            OpenCodeEvent.ServerConnected -> appendLog("イベント接続", "OpenCodeのリアルタイムイベントへ接続しました")
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
                        activeSessionIds = current.activeSessionIds + event.request.sessionId,
                    )
                }
                appendLog("承認待ち", event.request.permission, event.request.sessionId)
                onPermissionAsked?.invoke(event.request)
            }
            is OpenCodeEvent.SessionIdle -> {
                mutableState.update { current ->
                    current.copy(
                        activeSessionIds = current.activeSessionIds - event.sessionId,
                        completedSessionIds = current.completedSessionIds + event.sessionId,
                    )
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
            is OpenCodeEvent.QuestionAsked -> {
                mutableState.update { current ->
                    current.copy(activeSessionIds = current.activeSessionIds + event.request.sessionId)
                }
                appendLog("質問", event.request.questions.firstOrNull()?.question, event.request.sessionId)
            }
            is OpenCodeEvent.Unknown -> appendLog("未対応イベント", event.type)
        }
    }

    private fun appendLog(
        title: String,
        detail: String? = null,
        sessionId: String? = null,
    ) {
        mutableState.update { current ->
            current.copy(
                logs =
                    (listOf(RuntimeEventLog(title = title, detail = detail, sessionId = sessionId)) + current.logs)
                        .take(MAX_LOGS),
            )
        }
    }

    companion object {
        private const val MAX_LOGS = 100
    }
}
