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
    private val onSessionIdle: ((String, String?, String) -> Unit)? = null,
    private val onSessionError: ((String?, String?, String) -> Unit)? = null,
    private val onQuestionAsked: ((QuestionRequest, String?, String) -> Unit)? = null,
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

    /**
     * Child session id to the session that spawned it, learned from `session.created` /
     * `session.updated` events and resolved through the runtime API when the stream missed the
     * creation (app restart mid-run, reconnected stream). A key present with a null value means
     * the session is known to have no parent, so it is resolved only once.
     *
     * Events arrive on the stream collector while [markSessionRunning] arrives from the UI, so
     * both maps are guarded by [parentLock].
     */
    private val parentIds = mutableMapOf<String, String?>()
    private val parentLock = Any()

    /**
     * Sessions the runtime itself reported as no longer running. Subagent events must not
     * resurrect a parent whose own turn already ended (a background subagent outlives it), but
     * must resurrect one that was only settled locally by [markSessionFinished] and is in fact
     * still blocked on the subagent.
     */
    private val runtimeIdleSessionIds = mutableSetOf<String>()

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
        synchronized(parentLock) { runtimeIdleSessionIds.remove(sessionId) }
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
            is OpenCodeEvent.SessionCreated -> rememberParent(event.session.id, event.session.parentId)
            is OpenCodeEvent.SessionUpdated -> rememberParent(event.session.id, event.session.parentId)
            is OpenCodeEvent.MessagePartUpdated -> {
                val sessionId = event.part.sessionId ?: return
                activateSession(target, sessionId)
                when (event.part.type) {
                    "tool", "tool-invocation" -> appendLog(messages.eventTool, event.part.tool, sessionId)
                    "reasoning" -> appendLog(messages.eventReasoning, null, sessionId)
                }
            }
            is OpenCodeEvent.MessagePartDelta -> activateSession(target, event.sessionId)
            is OpenCodeEvent.PermissionAsked -> {
                // A live request proves the session is waiting, settled or not.
                activateSession(target, event.request.sessionId, force = true)
                mutableState.update { current ->
                    current.copy(permissions = current.permissions.filterNot { it.id == event.request.id } + event.request)
                }
                appendLog(messages.eventPermission, event.request.permission, event.request.sessionId)
                onPermissionAsked?.invoke(
                    event.request,
                    sessionTitle(target, event.request.sessionId),
                    target.id,
                )
            }
            is OpenCodeEvent.SessionIdle -> {
                markRuntimeIdle(event.sessionId)
                mutableState.update { current ->
                    current.copy(
                        activeSessionIds = current.activeSessionIds - event.sessionId,
                        completedSessionIds = current.completedSessionIds + event.sessionId,
                        settledSessionIds = current.settledSessionIds + event.sessionId,
                    )
                }
                appendLog(messages.eventCompleted, null, event.sessionId)
                parentResolutionOf(target, event.sessionId).onSuccess { parentId ->
                    if (parentId == null) {
                        onSessionIdle?.invoke(event.sessionId, sessionTitle(target, event.sessionId), target.id)
                    }
                }
            }
            is OpenCodeEvent.MessageUpdated -> activateSession(target, event.info.sessionId)
            is OpenCodeEvent.PermissionReplied -> {
                mutableState.update { current ->
                    current.copy(permissions = current.permissions.filterNot { it.id == event.requestId })
                }
                mutableResolvedPermissions.tryEmit(event.requestId)
                onPermissionResolved?.invoke(event.requestId)
            }
            is OpenCodeEvent.SessionStatusChanged -> {
                if (event.status == "idle") {
                    markRuntimeIdle(event.sessionId)
                } else {
                    markRuntimeRunning(event.sessionId)
                }
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
                if (event.status != "idle") {
                    activateAncestors(target, event.sessionId)
                }
            }
            is OpenCodeEvent.SessionError -> {
                event.sessionId?.let { sessionId ->
                    markRuntimeIdle(sessionId)
                    mutableState.update { current ->
                        current.copy(
                            activeSessionIds = current.activeSessionIds - sessionId,
                            settledSessionIds = current.settledSessionIds + sessionId,
                        )
                    }
                }
                appendLog(messages.eventError, event.message, event.sessionId)
                onSessionError?.invoke(event.sessionId, event.message, target.id)
            }
            is OpenCodeEvent.QuestionAsked -> {
                activateSession(target, event.request.sessionId, force = true)
                appendLog(messages.eventQuestion, event.request.questions.firstOrNull()?.question, event.request.sessionId)
                onQuestionAsked?.invoke(
                    event.request,
                    sessionTitle(target, event.request.sessionId),
                    target.id,
                )
            }
            is OpenCodeEvent.Unknown -> appendLog(messages.eventUnknown, event.type)
        }
    }

    /**
     * Marks a session active unless it was settled, and reports the same for every ancestor.
     *
     * A session blocked on the task tool emits nothing while its subagent works, so the runtime
     * events of the child are the only signal that the parent's turn is still in flight. Without
     * forwarding them, a parent the user navigated away from (which [markSessionFinished] then
     * settles) sat on the grey idle dot in the drawer for the subagent's entire run.
     */
    private suspend fun activateSession(
        target: RuntimeTarget,
        sessionId: String,
        force: Boolean = false,
    ) {
        if (sessionId.isBlank()) return
        mutableState.update { current ->
            if (!force && (sessionId in current.settledSessionIds || sessionId in current.completedSessionIds)) {
                current
            } else {
                current.copy(activeSessionIds = current.activeSessionIds + sessionId)
            }
        }
        activateAncestors(target, sessionId)
    }

    /**
     * Walks up the parent chain keeping every ancestor marked running. An ancestor the runtime
     * already reported idle ends the walk: its turn no longer waits on this subagent chain (only
     * experimental background subagents outlive their parent's turn).
     */
    private suspend fun activateAncestors(
        target: RuntimeTarget,
        sessionId: String,
    ) {
        var parentId = parentIdOf(target, sessionId)
        while (parentId != null) {
            if (isRuntimeIdle(parentId)) break
            val ancestorId = parentId
            mutableState.update { current ->
                current.copy(
                    activeSessionIds = current.activeSessionIds + ancestorId,
                    completedSessionIds = current.completedSessionIds - ancestorId,
                    settledSessionIds = current.settledSessionIds - ancestorId,
                )
            }
            parentId = parentIdOf(target, ancestorId)
        }
    }

    private fun rememberParent(
        sessionId: String,
        parentId: String?,
    ) {
        if (sessionId.isBlank()) return
        synchronized(parentLock) { parentIds[sessionId] = parentId }
    }

    /**
     * Parent of a subagent session. A successful lookup is cached (a top-level session caches a
     * null parent and is resolved only once); a failed lookup is not, so a transient error is
     * retried on the next event instead of permanently disabling propagation for that session.
     */
    private suspend fun parentIdOf(
        target: RuntimeTarget,
        sessionId: String,
    ): String? = parentResolutionOf(target, sessionId).getOrNull()

    private suspend fun parentResolutionOf(
        target: RuntimeTarget,
        sessionId: String,
    ): Result<String?> {
        synchronized(parentLock) {
            if (sessionId in parentIds) return Result.success(parentIds[sessionId])
        }
        // Misses the creation event when the stream reconnected mid-run; ask the runtime instead.
        return runCatching { target.session(sessionId).parentId }
            .onSuccess { parentId -> synchronized(parentLock) { parentIds[sessionId] = parentId } }
    }

    private fun markRuntimeIdle(sessionId: String) {
        synchronized(parentLock) { runtimeIdleSessionIds.add(sessionId) }
    }

    private fun markRuntimeRunning(sessionId: String) {
        synchronized(parentLock) { runtimeIdleSessionIds.remove(sessionId) }
    }

    private fun isRuntimeIdle(sessionId: String): Boolean = synchronized(parentLock) { sessionId in runtimeIdleSessionIds }

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
