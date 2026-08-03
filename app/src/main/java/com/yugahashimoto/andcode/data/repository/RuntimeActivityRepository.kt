package com.yugahashimoto.andcode.data.repository

import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.PermissionRequest
import com.yugahashimoto.andcode.core.api.QuestionRequest
import com.yugahashimoto.andcode.runtime.RuntimeRegistry
import com.yugahashimoto.andcode.runtime.RuntimeState
import com.yugahashimoto.andcode.runtime.RuntimeTarget
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
    /** Sessions whose current run has ended; late stream events must not resurrect them. */
    val settledSessionIds: Set<String> = emptySet(),
    val permissions: List<PermissionRequest> = emptyList(),
    val logs: List<RuntimeEventLog> = emptyList(),
    val streamError: String? = null,
)

class RuntimeActivityRepository(
    registry: RuntimeRegistry,
    scope: CoroutineScope,
    private val retryDelayMillis: Long = 2_000L,
    private val maxRetryDelayMillis: Long = 30_000L,
    private val onPermissionAsked: ((PermissionRequest, String?, String) -> Unit)? = null,
    private val onPermissionResolved: ((String) -> Unit)? = null,
    private val onSessionIdle: ((String, String?) -> Unit)? = null,
    private val onSessionError: ((String?, String?) -> Unit)? = null,
    private val onQuestionAsked: ((QuestionRequest, String?) -> Unit)? = null,
    private val unreadStore: UnreadSessionStore? = null,
    private val messages: RuntimeActivityMessages = RuntimeActivityMessages,
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

    /**
     * Permission ids that have been answered somewhere in the app (notification action, activity
     * screen, chat). OpenCode has no `permission.replied` event, so this is how other surfaces
     * learn that a request they are still showing is already settled.
     */
    private val mutableResolvedPermissions = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val resolvedPermissions: SharedFlow<String> = mutableResolvedPermissions.asSharedFlow()

    init {
        scope.launch {
            registry.selected.collectLatest selected@{ target ->
                // A runtime switch does not cancel work already submitted to the previous target.
                // Keep its active sessions visible until that target reports completion; clearing
                // them here made an in-flight chat look idle/completed in the drawer.
                mutableState.update { current ->
                    current.copy(permissions = emptyList(), streamError = null)
                }
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
                        streamError = error.message?.takeIf(String::isNotBlank) ?: messages.streamConnectionFailed,
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
                handle(target, event)
            }
    }

    fun resolvePermission(permissionId: String) {
        mutableState.update { current ->
            current.copy(permissions = current.permissions.filterNot { it.id == permissionId })
        }
        mutableResolvedPermissions.tryEmit(permissionId)
        onPermissionResolved?.invoke(permissionId)
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
                settledSessionIds = current.settledSessionIds - sessionId,
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
                settledSessionIds = current.settledSessionIds + sessionId,
            )
        }
        persistUnread()
    }

    private fun persistUnread() {
        unreadStore?.unreadSessionIds = mutableState.value.completedSessionIds
    }

    private suspend fun handle(
        target: RuntimeTarget,
        event: OpenCodeEvent,
    ) {
        when (event) {
            OpenCodeEvent.ServerConnected -> appendLog(messages.eventConnectedTitle, messages.eventConnectedDetail)
            is OpenCodeEvent.MessagePartUpdated -> {
                val sessionId = event.part.sessionId ?: return
                mutableState.update { current ->
                    if (sessionId in current.settledSessionIds || sessionId in current.completedSessionIds) {
                        current
                    } else {
                        current.copy(activeSessionIds = current.activeSessionIds + sessionId)
                    }
                }
                when (event.part.type) {
                    "tool", "tool-invocation" -> appendLog(messages.eventTool, event.part.tool, sessionId)
                    "reasoning" -> appendLog(messages.eventReasoning, null, sessionId)
                }
            }
            is OpenCodeEvent.MessagePartDelta -> {
                mutableState.update { current ->
                    if (event.sessionId in current.settledSessionIds || event.sessionId in current.completedSessionIds) {
                        current
                    } else {
                        current.copy(activeSessionIds = current.activeSessionIds + event.sessionId)
                    }
                }
            }
            is OpenCodeEvent.PermissionAsked -> {
                mutableState.update { current ->
                    current.copy(
                        permissions = current.permissions.filterNot { it.id == event.request.id } + event.request,
                        activeSessionIds = current.activeSessionIds + event.request.sessionId,
                    )
                }
                appendLog(messages.eventPermission, event.request.permission, event.request.sessionId)
                onPermissionAsked?.invoke(
                    event.request,
                    sessionTitle(target, event.request.sessionId),
                    target.id,
                )
            }
            is OpenCodeEvent.SessionIdle -> {
                mutableState.update { current ->
                    current.copy(
                        activeSessionIds = current.activeSessionIds - event.sessionId,
                        completedSessionIds = current.completedSessionIds + event.sessionId,
                        settledSessionIds = current.settledSessionIds + event.sessionId,
                    )
                }
                appendLog(messages.eventCompleted, null, event.sessionId)
                val isSubagent =
                    runCatching { target.session(event.sessionId).parentId != null }
                        .getOrDefault(false)
                if (!isSubagent) {
                    onSessionIdle?.invoke(event.sessionId, sessionTitle(target, event.sessionId))
                }
            }
            is OpenCodeEvent.MessageUpdated -> {
                mutableState.update { current ->
                    if (event.info.sessionId in current.settledSessionIds || event.info.sessionId in current.completedSessionIds) {
                        current
                    } else {
                        current.copy(activeSessionIds = current.activeSessionIds + event.info.sessionId)
                    }
                }
            }
            is OpenCodeEvent.PermissionReplied -> {
                mutableState.update { current ->
                    current.copy(permissions = current.permissions.filterNot { it.id == event.requestId })
                }
                mutableResolvedPermissions.tryEmit(event.requestId)
                onPermissionResolved?.invoke(event.requestId)
            }
            is OpenCodeEvent.SessionStatusChanged -> {
                mutableState.update { current ->
                    current.copy(
                        activeSessionIds =
                            if (event.status == "idle") {
                                current.activeSessionIds - event.sessionId
                            } else {
                                current.activeSessionIds + event.sessionId
                            },
                        completedSessionIds =
                            if (event.status == "idle") {
                                current.completedSessionIds + event.sessionId
                            } else if (event.sessionId in current.completedSessionIds) {
                                current.completedSessionIds - event.sessionId
                            } else {
                                current.completedSessionIds
                            },
                        settledSessionIds =
                            if (event.status == "idle") {
                                current.settledSessionIds + event.sessionId
                            } else {
                                current.settledSessionIds - event.sessionId
                            },
                    )
                }
            }
            is OpenCodeEvent.SessionError -> {
                event.sessionId?.let { sessionId ->
                    mutableState.update { current ->
                        current.copy(
                            activeSessionIds = current.activeSessionIds - sessionId,
                            settledSessionIds = current.settledSessionIds + sessionId,
                        )
                    }
                }
                appendLog(messages.eventError, event.message, event.sessionId)
                onSessionError?.invoke(event.sessionId, event.message)
            }
            is OpenCodeEvent.QuestionAsked -> {
                mutableState.update { current ->
                    current.copy(activeSessionIds = current.activeSessionIds + event.request.sessionId)
                }
                appendLog(messages.eventQuestion, event.request.questions.firstOrNull()?.question, event.request.sessionId)
                onQuestionAsked?.invoke(
                    event.request,
                    sessionTitle(target, event.request.sessionId),
                )
            }
            is OpenCodeEvent.Unknown -> appendLog(messages.eventUnknown, event.type)
        }
    }

    private suspend fun sessionTitle(
        target: RuntimeTarget,
        sessionId: String,
    ): String? =
        runCatching { target.session(sessionId).title.trim().takeIf(String::isNotEmpty) }
            .getOrNull()

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
