package com.yugahashimoto.andcode.data.repository

import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.PermissionRequest
import com.yugahashimoto.andcode.core.api.QuestionRequest
import com.yugahashimoto.andcode.core.api.sessionIdOrNull
import com.yugahashimoto.andcode.core.diagnostics.RunSignals
import com.yugahashimoto.andcode.core.diagnostics.StallDiagnosis
import com.yugahashimoto.andcode.core.diagnostics.StallEvidence
import com.yugahashimoto.andcode.core.diagnostics.StallReason
import com.yugahashimoto.andcode.core.diagnostics.diagnoseStall
import com.yugahashimoto.andcode.core.diagnostics.inspectRun
import com.yugahashimoto.andcode.core.diagnostics.provesRunProgress
import com.yugahashimoto.andcode.core.util.safeMessage
import com.yugahashimoto.andcode.runtime.RuntimeRegistry
import com.yugahashimoto.andcode.runtime.RuntimeState
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import kotlinx.coroutines.CancellationException
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
    val mutedSessionIds: Set<String> = emptySet(),
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
    /**
     * Reports a run that has stopped producing events, with the runtime's answer for why. Also
     * what turns the stall watchdog on: without a listener there is nobody to tell, so the poll
     * loop (which a virtual test clock would advance through forever) never starts.
     */
    private val onSessionStalled: ((String, String?, StallDiagnosis, String) -> Unit)? = null,
    /**
     * How long an active session may produce nothing before the watchdog asks the runtime what
     * happened. Well past the chat's own [com.yugahashimoto.andcode.feature.chat.STALL_THRESHOLD_MS]
     * threshold: this one interrupts the user with a notification, so it waits until a slow tool
     * call is no longer a plausible explanation.
     */
    private val stallThresholdMillis: Long = 300_000L,
    private val stallCheckIntervalMillis: Long = 60_000L,
    private val now: () -> Long = System::currentTimeMillis,
    private val unreadStore: UnreadSessionStore? = null,
    private val messages: RuntimeActivityMessages = RuntimeActivityMessages,
) {
    init {
        require(retryDelayMillis >= 0L)
        require(maxRetryDelayMillis >= retryDelayMillis)
        require(stallThresholdMillis > 0L)
        require(stallCheckIntervalMillis > 0L)
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

    /**
     * When each running session last produced something. Written from the stream collector and
     * from the chat (via [markSessionRunning]), read by the watchdog, so it is guarded like the
     * parent map above.
     */
    private val lastActivityAt = mutableMapOf<String, Long>()

    /** Sessions already reported as stalled, so one dead run is announced once, not every minute. */
    private val reportedStalls = mutableSetOf<String>()
    private val activityLock = Any()

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
                    if (onSessionStalled != null) launch { watchForStalls(target) }

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
                if (event.provesRunProgress()) event.sessionIdOrNull()?.let(::recordActivity)
                mutableEvents.emit(event)
                handle(target, event)
            }
    }

    /**
     * Watches every running session for one that has gone quiet.
     *
     * A run that dies while the app is in the background produces no event at all, which is exactly
     * what a run that is thinking hard produces: the drawer keeps its spinner, no notification ever
     * arrives, and the user finds out by giving up and looking. This asks the runtime what became
     * of such a session, settles it when it turns out to be over, and otherwise says out loud that
     * it is stuck.
     */
    private suspend fun watchForStalls(target: RuntimeTarget) {
        while (true) {
            delay(stallCheckIntervalMillis)
            val active = mutableState.value.activeSessionIds
            synchronized(activityLock) {
                reportedStalls.retainAll(active)
                lastActivityAt.keys.retainAll(active)
            }
            for (sessionId in active) {
                if (now() - lastActivitySince(sessionId) < stallThresholdMillis) continue
                if (synchronized(activityLock) { sessionId in reportedStalls }) continue
                try {
                    diagnoseSilentSession(target, sessionId)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    // This watchdog shares a scope with the event stream. A diagnosis that throws —
                    // a runtime call, or the notification the verdict posts — would otherwise take
                    // the whole runtime's event handling down with it, which is a wildly
                    // disproportionate price for a session that could not be diagnosed.
                    //
                    // The claim on the session is given back, so "reported once" does not come to
                    // mean "reported never" for a stall whose notification happened to fail.
                    synchronized(activityLock) { reportedStalls -= sessionId }
                    appendLog(messages.eventStalled, error.safeMessage(), sessionId)
                }
            }
        }
    }

    private suspend fun diagnoseSilentSession(
        target: RuntimeTarget,
        sessionId: String,
    ) {
        val health = runCatching { target.health() }
        val transcript = runCatching { target.listMessages(sessionId) }
        val session = runCatching { target.session(sessionId) }.getOrNull()
        // Asked rather than remembered: the repository never learns that a question was answered,
        // so a set of its own would keep blaming a question the user has long since dealt with.
        val openQuestions =
            runCatching { target.pendingQuestions(session?.directory) }.getOrDefault(emptyList())
        // The probe takes a moment, in which the session may well have come back to life. The last
        // word on that and the claim on the session are taken together, so an event landing between
        // them cannot leave a session both freshly active and reported stalled.
        if (sessionId !in mutableState.value.activeSessionIds) return
        val silentFor =
            synchronized(activityLock) {
                val silence = now() - lastActivityAt.getOrPut(sessionId) { now() }
                if (silence < stallThresholdMillis) return
                reportedStalls += sessionId
                silence
            }
        val diagnosis =
            diagnoseStall(
                StallEvidence(
                    silentForMillis = silentFor,
                    runtimeReachable = health.getOrNull()?.healthy == true,
                    runtimeError = health.exceptionOrNull()?.safeMessage(),
                    streamConnected = mutableState.value.streamError == null,
                    streamError = mutableState.value.streamError,
                    awaitingPermission = mutableState.value.permissions.any { it.sessionId == sessionId },
                    awaitingQuestion = openQuestions.any { it.sessionId == sessionId },
                    transcript = transcript.map(::inspectRun).getOrDefault(RunSignals()),
                ),
            )
        when (diagnosis.reason) {
            // The run is over and only the news went missing, so replay the event that never came:
            // the chat is settled and the completion is announced exactly as usual. These replays
            // are handled here rather than emitted on [events]; the open chat runs a watchdog of
            // its own on a shorter threshold, so it has already settled the turn by now.
            StallReason.COMPLETION_MISSED -> handle(target, OpenCodeEvent.SessionIdle(sessionId))
            StallReason.PROVIDER_ERROR -> handle(target, OpenCodeEvent.SessionError(sessionId, diagnosis.detail))
            else -> {
                appendLog(messages.eventStalled, diagnosis.reason.name, sessionId)
                // Only top-level runs are announced, as completions are: a wedged subagent takes
                // its parent down with it, so the parent is reported anyway, and it is the one
                // whose completion later takes the notice back down. A session that could not be
                // read at all is still announced — being unable to name a run is no reason to go
                // quiet about it, which is the very failure this exists to break.
                if (session?.parentId == null) {
                    onSessionStalled?.invoke(
                        sessionId,
                        session?.title?.trim()?.takeIf(String::isNotEmpty),
                        diagnosis,
                        target.id,
                    )
                }
            }
        }
    }

    /** When [sessionId] last produced something; sessions never seen count as silent from now. */
    private fun lastActivitySince(sessionId: String): Long = synchronized(activityLock) { lastActivityAt.getOrPut(sessionId) { now() } }

    private fun recordActivity(sessionId: String) {
        if (sessionId.isBlank()) return
        synchronized(activityLock) {
            lastActivityAt[sessionId] = now()
            reportedStalls -= sessionId
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
        recordActivity(sessionId)
        mutableState.update { current ->
            current.copy(
                activeSessionIds = current.activeSessionIds + sessionId,
                completedSessionIds = current.completedSessionIds - sessionId,
                settledSessionIds = current.settledSessionIds - sessionId,
                mutedSessionIds = current.mutedSessionIds - sessionId,
            )
        }
        persistUnread()
    }

    fun markSessionAborted(sessionId: String) {
        if (sessionId.isBlank()) return
        mutableState.update { current ->
            current.copy(
                activeSessionIds = current.activeSessionIds - sessionId,
                mutedSessionIds = current.mutedSessionIds + sessionId,
            )
        }
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
                var muted = false
                mutableState.update { current ->
                    muted = event.sessionId in current.mutedSessionIds
                    current.copy(
                        activeSessionIds = current.activeSessionIds - event.sessionId,
                        completedSessionIds = current.completedSessionIds + event.sessionId,
                        settledSessionIds = current.settledSessionIds + event.sessionId,
                        mutedSessionIds = current.mutedSessionIds - event.sessionId,
                    )
                }
                appendLog(messages.eventCompleted, null, event.sessionId)
                if (muted) return
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
                        mutedSessionIds = current.mutedSessionIds - event.sessionId,
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
                            mutedSessionIds = current.mutedSessionIds + sessionId,
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
            // A parent blocked on the task tool emits nothing of its own for the subagent's whole
            // run. Its child's events are the proof it is still working, so they count as the
            // parent's activity too — otherwise the watchdog would call every long subagent run a
            // stalled parent.
            //
            // Every event that reaches here counts, including the `busy` status that
            // provesRunProgress() refuses for the session it names. A child claiming to be busy is
            // no proof about the child, but it is proof that the parent is waiting on something —
            // and a child that has genuinely wedged is still caught on its own clock.
            recordActivity(ancestorId)
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
