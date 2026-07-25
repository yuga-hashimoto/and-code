package com.opencode.android.feature.chat

import android.speech.tts.TextToSpeech
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.android.core.api.OpenCodeEvent
import com.opencode.android.core.api.OpenCodeMessage
import com.opencode.android.core.api.OpenCodePart
import com.opencode.android.core.api.PermissionRequest
import com.opencode.android.core.api.PromptRequest
import com.opencode.android.runtime.OpenCodeBackend
import com.opencode.android.runtime.PermissionResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

enum class ToolStatus { PENDING, RUNNING, COMPLETED, ERROR, UNKNOWN }

sealed interface ChatPart {
    val id: String

    data class Text(override val id: String, val text: String) : ChatPart
    data class Reasoning(override val id: String, val text: String) : ChatPart
    data class Tool(
        override val id: String,
        val name: String,
        val status: ToolStatus,
        val title: String? = null,
        val input: String? = null,
        val output: String? = null,
        val outputTruncated: Boolean = false,
        val error: String? = null
    ) : ChatPart
    data class Patch(override val id: String, val files: List<String>) : ChatPart
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val isUser: Boolean,
    val parts: List<ChatPart> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false
) {
    val text: String
        get() = parts.filterIsInstance<ChatPart.Text>().joinToString("") { it.text }

    /**
     * Cheap fingerprint of the rendered content. Changes whenever a part is added or grows,
     * so the chat list can keep following a message that is still streaming.
     */
    fun contentSignature(): String = buildString {
        append(id)
        append(':')
        append(isStreaming)
        parts.forEach { part ->
            append('|')
            append(part.id)
            when (part) {
                is ChatPart.Text -> append(part.text.length)
                is ChatPart.Reasoning -> append(part.text.length)
                is ChatPart.Tool -> {
                    append(part.status)
                    append(part.output?.length ?: 0)
                }
                is ChatPart.Patch -> append(part.files.size)
            }
        }
    }
}

private const val MAX_TOOL_OUTPUT_CHARS = 4000

private fun OpenCodePart.toChatPart(): ChatPart? {
    val partId = id ?: return null
    val stateMap = state.orEmpty()
    return when (type) {
        "text" -> ChatPart.Text(partId, text.orEmpty())
        "reasoning" -> ChatPart.Reasoning(partId, text.orEmpty())
        "tool" -> {
            val inputText = formatToolInput(stateMap["input"] as? Map<*, *>)
            val rawOutput = stateMap["output"] as? String
            val truncated = rawOutput != null && rawOutput.length > MAX_TOOL_OUTPUT_CHARS
            ChatPart.Tool(
                id = partId,
                name = tool ?: "tool",
                status = parseToolStatus(stateMap["status"]),
                title = (stateMap["title"] as? String)?.takeIf { it.isNotBlank() }
                    ?: inputText?.lineSequence()?.firstOrNull(),
                input = inputText,
                output = if (truncated) rawOutput?.takeLast(MAX_TOOL_OUTPUT_CHARS) else rawOutput,
                outputTruncated = truncated,
                error = stateMap["error"] as? String
            )
        }
        "patch" -> ChatPart.Patch(partId, extractPatchFiles(stateMap))
        else -> null
    }
}

private fun parseToolStatus(value: Any?): ToolStatus = when (value as? String) {
    "pending" -> ToolStatus.PENDING
    "running" -> ToolStatus.RUNNING
    "completed" -> ToolStatus.COMPLETED
    "error" -> ToolStatus.ERROR
    else -> ToolStatus.UNKNOWN
}

private fun formatToolInput(input: Map<*, *>?): String? {
    if (input.isNullOrEmpty()) return null
    (input["command"] as? String)?.let { return it }
    (input["filePath"] as? String)?.let { path ->
        val extra = input.entries.filterNot { it.key == "filePath" }
        return if (extra.isEmpty()) {
            path
        } else {
            path + "\n" + extra.joinToString("\n") { (key, value) -> "$key: $value" }
        }
    }
    return input.entries.joinToString("\n") { (key, value) -> "$key: $value" }
}

private fun extractPatchFiles(state: Map<String, Any?>): List<String> {
    return when (val files = state["files"]) {
        is List<*> -> files.mapNotNull { entry ->
            when (entry) {
                is String -> entry
                is Map<*, *> -> (entry["path"] ?: entry["file"] ?: entry["filename"])?.toString()
                else -> null
            }
        }
        is Map<*, *> -> files.keys.mapNotNull { it?.toString() }
        else -> emptyList()
    }
}

data class ChatUiState(
    val backendName: String = "",
    val sessionId: String? = null,
    val sessionTitle: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val permissions: List<PermissionRequest> = emptyList(),
    val isConnected: Boolean = false,
    val isRunning: Boolean = false,
    val isLoadingHistory: Boolean = false,
    val isListening: Boolean = false,
    val isThinking: Boolean = false,
    val isSpeaking: Boolean = false,
    val partialText: String = "",
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val selectedAgentId: String? = null,
    val selectedWorkspacePath: String? = null,
    val error: String? = null
)

/** How often the chat re-reads the session from the server while a run is in flight. */
private const val SYNC_INTERVAL_MILLIS = 1_500L

/** Upper bound on a single run's polling loop so a never-idling session cannot poll forever. */
private const val MAX_SYNC_POLLS = 1_200

/** How many unchanged polls end a run that was only inferred to be in flight when opened. */
private const val ADOPTED_RUN_STALL_POLLS = 20

private const val EVENT_RETRY_BASE_MILLIS = 1_000L
private const val EVENT_RETRY_MAX_MILLIS = 15_000L

class ChatViewModel(
    private val backend: OpenCodeBackend? = null,
    private val eventFlow: Flow<OpenCodeEvent>? = null,
    private val onPermissionResolved: (String) -> Unit = {},
    private val syncIntervalMillis: Long = SYNC_INTERVAL_MILLIS
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        ChatUiState(backendName = backend?.displayName.orEmpty())
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var eventJob: Job? = null
    private var syncJob: Job? = null
    private var tts: TextToSpeech? = null
    private val streamedParts = mutableMapOf<String, LinkedHashMap<String, ChatPart>>()
    private val messageRoles = mutableMapOf<String, String>()

    /** Ids of user bubbles rendered optimistically, before the server echoed them back. */
    private val optimisticUserMessageIds = mutableSetOf<String>()

    /** Messages already on screen when the current run started; used to detect run completion. */
    private var runBaselineMessageIds: Set<String> = emptySet()

    init {
        if (backend != null) {
            // The event stream is the fast path. It reconnects on its own, and every reconnect
            // is followed by a full re-read so nothing that happened while it was down is lost.
            eventJob = viewModelScope.launch {
                (eventFlow ?: flow { emitAll(backend.events()) })
                    .retryWhen { error, attempt ->
                        _uiState.update { it.copy(error = error.safeMessage()) }
                        val backoff = (EVENT_RETRY_BASE_MILLIS shl attempt.toInt().coerceAtMost(4))
                            .coerceAtMost(EVENT_RETRY_MAX_MILLIS)
                        delay(backoff)
                        resyncMessages()
                        true
                    }
                    .collect(::handleEvent)
            }
            viewModelScope.launch {
                runCatching { backend.health() }
                    .onSuccess { health ->
                        _uiState.update {
                            it.copy(
                                isConnected = health.healthy,
                                backendName = "${backend.displayName} · ${health.version}"
                            )
                        }
                    }
                    .onFailure { error ->
                        _uiState.update { it.copy(error = error.safeMessage()) }
                    }
            }
        }
    }

    fun selectWorkspace(path: String?) {
        if (_uiState.value.sessionId != null) return
        _uiState.update { it.copy(selectedWorkspacePath = path) }
    }

    fun selectConfiguration(providerId: String?, modelId: String?, agentId: String?) {
        _uiState.update {
            it.copy(
                selectedProviderId = providerId,
                selectedModelId = modelId,
                selectedAgentId = agentId
            )
        }
    }

    fun openSession(sessionId: String, title: String = "") {
        val currentBackend = backend ?: return
        resetSessionCaches()
        _uiState.update {
            it.copy(
                sessionId = sessionId,
                sessionTitle = title,
                isLoadingHistory = true,
                messages = emptyList(),
                permissions = emptyList(),
                isRunning = false,
                isThinking = false,
                error = null
            )
        }
        viewModelScope.launch {
            runCatching { currentBackend.listMessages(sessionId) }
                .onSuccess { messages ->
                    _uiState.update { it.copy(isLoadingHistory = false) }
                    applyServerMessages(sessionId, messages)
                    adoptInFlightRun(sessionId, messages)
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoadingHistory = false, error = error.safeMessage())
                    }
                }
        }
    }

    fun newSession() {
        resetSessionCaches()
        syncJob?.cancel()
        _uiState.update {
            it.copy(
                sessionId = null,
                sessionTitle = "",
                messages = emptyList(),
                permissions = emptyList(),
                isRunning = false,
                isThinking = false,
                isListening = false,
                partialText = "",
                error = null
            )
        }
    }

    fun sendMessage(text: String) {
        val normalized = text.trim()
        if (normalized.isEmpty()) return
        val currentBackend = backend
        if (currentBackend == null) {
            _uiState.update { it.copy(error = "OpenCode connection is not configured") }
            return
        }

        val userMessage = ChatMessage(
            isUser = true,
            parts = listOf(ChatPart.Text(id = UUID.randomUUID().toString(), text = normalized))
        )
        optimisticUserMessageIds += userMessage.id
        runBaselineMessageIds = _uiState.value.messages.map { it.id }.toSet()
        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                isRunning = true,
                isThinking = true,
                partialText = "",
                error = null
            )
        }

        viewModelScope.launch {
            runCatching {
                val existingSessionId = _uiState.value.sessionId
                val session = if (existingSessionId == null) {
                    currentBackend.createSession(
                        title = normalized.take(60),
                        directory = _uiState.value.selectedWorkspacePath
                    )
                } else {
                    null
                }
                val targetSessionId = existingSessionId ?: requireNotNull(session).id
                if (session != null) {
                    _uiState.update {
                        it.copy(sessionId = session.id, sessionTitle = session.title)
                    }
                }
                currentBackend.sendMessage(
                    targetSessionId,
                    PromptRequest(
                        text = normalized,
                        providerId = _uiState.value.selectedProviderId,
                        modelId = _uiState.value.selectedModelId,
                        agent = _uiState.value.selectedAgentId
                    )
                )
                syncJob?.cancel()
                startSyncLoop()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        isThinking = false,
                        error = error.safeMessage()
                    )
                }
            }
        }
    }

    fun respondToPermission(
        permissionId: String,
        response: PermissionResponse,
        remember: Boolean
    ) {
        val currentBackend = backend ?: return
        val permission = _uiState.value.permissions.firstOrNull { it.id == permissionId } ?: return
        viewModelScope.launch {
            runCatching {
                currentBackend.respondToPermission(
                    permission.sessionId,
                    permission.id,
                    response,
                    remember
                )
            }.onSuccess { accepted ->
                if (accepted) {
                    _uiState.update { state ->
                        state.copy(permissions = state.permissions.filterNot { it.id == permissionId })
                    }
                    onPermissionResolved(permissionId)
                }
            }.onFailure { error ->
                _uiState.update { it.copy(error = error.safeMessage()) }
            }
        }
    }

    fun abort() {
        val currentBackend = backend ?: return
        val sessionId = _uiState.value.sessionId ?: return
        viewModelScope.launch {
            runCatching { currentBackend.abortSession(sessionId) }
                .onSuccess {
                    syncJob?.cancel()
                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            isThinking = false,
                            messages = it.messages.map { message ->
                                if (message.isStreaming) message.copy(isStreaming = false) else message
                            }
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(error = error.safeMessage()) }
                }
        }
    }

    private fun handleEvent(event: OpenCodeEvent) {
        val activeSession = _uiState.value.sessionId
        when (event) {
            OpenCodeEvent.ServerConnected -> {
                _uiState.update { it.copy(isConnected = true, error = null) }
                // A fresh stream means we may have missed events: re-read the session.
                resyncMessages()
            }
            is OpenCodeEvent.MessageUpdated -> {
                if (event.info.sessionId != activeSession) return
                messageRoles[event.info.id] = event.info.role
            }
            is OpenCodeEvent.MessagePartUpdated -> {
                val part = event.part
                if (part.sessionId != null && part.sessionId != activeSession) return
                val messageId = part.messageId ?: part.id ?: return
                if (messageRoles[messageId] == "user") return
                val partId = part.id ?: messageId
                val chatPart = part.toChatPart() ?: return
                val messageParts = streamedParts.getOrPut(messageId) { linkedMapOf() }
                messageParts[partId] = chatPart
                updateStreamingMessage(messageId, messageParts.values.toList())
            }
            is OpenCodeEvent.MessagePartDelta -> {
                if (event.sessionId != activeSession || event.field != "text") return
                if (messageRoles[event.messageId] == "user") return
                val messageParts = streamedParts.getOrPut(event.messageId) { linkedMapOf() }
                val existing = messageParts[event.partId]
                val updatedPart = when (existing) {
                    is ChatPart.Text -> existing.copy(text = existing.text + event.delta)
                    is ChatPart.Reasoning -> existing.copy(text = existing.text + event.delta)
                    // A delta can arrive before the part itself when the stream reconnects
                    // mid-message; start the part here instead of dropping the text.
                    null -> ChatPart.Text(id = event.partId, text = event.delta)
                    else -> return
                }
                messageParts[event.partId] = updatedPart
                updateStreamingMessage(event.messageId, messageParts.values.toList())
            }
            is OpenCodeEvent.PermissionAsked -> {
                if (event.request.sessionId != activeSession) return
                _uiState.update { state ->
                    state.copy(
                        permissions = state.permissions.filterNot { it.id == event.request.id } + event.request,
                        isThinking = false
                    )
                }
            }
            is OpenCodeEvent.PermissionReplied -> {
                // Answered elsewhere (another device, or the TUI): drop the stale card.
                if (event.sessionId != activeSession) return
                _uiState.update { state ->
                    state.copy(permissions = state.permissions.filterNot { it.id == event.requestId })
                }
            }
            is OpenCodeEvent.SessionIdle -> {
                if (event.sessionId != activeSession) return
                finishRun()
                resyncMessages()
            }
            is OpenCodeEvent.SessionStatusChanged -> {
                if (event.sessionId != activeSession) return
                when (event.status) {
                    "idle" -> {
                        finishRun()
                        resyncMessages()
                    }
                    // A run can also be started from another client; follow it here as well.
                    "busy", "retry" -> {
                        if (!_uiState.value.isRunning) {
                            _uiState.update { it.copy(isRunning = true) }
                        }
                        startSyncLoop()
                    }
                }
            }
            is OpenCodeEvent.SessionError -> {
                if (event.sessionId != null && event.sessionId != activeSession) return
                syncJob?.cancel()
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        isThinking = false,
                        messages = it.messages.map { message ->
                            if (message.isStreaming) message.copy(isStreaming = false) else message
                        },
                        error = event.message ?: "OpenCode session failed"
                    )
                }
            }
            is OpenCodeEvent.Unknown -> Unit
        }
    }

    private fun updateStreamingMessage(messageId: String, parts: List<ChatPart>) {
        _uiState.update { state ->
            val index = state.messages.indexOfFirst { it.id == messageId }
            val updated = if (index >= 0) {
                state.messages.toMutableList().apply {
                    this[index] = this[index].copy(parts = parts, isStreaming = true)
                }
            } else {
                state.messages + ChatMessage(
                    id = messageId,
                    isUser = false,
                    parts = parts,
                    isStreaming = true
                )
            }
            state.copy(
                messages = updated,
                isRunning = true,
                isThinking = false
            )
        }
    }

    private fun finishRun() {
        syncJob?.cancel()
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { message ->
                    if (message.isStreaming) message.copy(isStreaming = false) else message
                },
                isRunning = false,
                isThinking = false
            )
        }
    }

    private fun resetSessionCaches() {
        streamedParts.clear()
        messageRoles.clear()
        optimisticUserMessageIds.clear()
        runBaselineMessageIds = emptySet()
    }

    /**
     * Polls the session while a run is in flight. The event stream is the primary source of
     * realtime updates, but it can be dropped by a reconnect, a backgrounded process or a
     * flaky link — this loop guarantees the chat still converges on what the server has.
     */
    private fun startSyncLoop(stallLimitPolls: Int = MAX_SYNC_POLLS) {
        if (backend == null) return
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            var polls = 0
            var stalledPolls = 0
            var lastSignature: String? = null
            while (isActive && polls < MAX_SYNC_POLLS) {
                delay(syncIntervalMillis)
                polls++
                val state = _uiState.value
                val sessionId = state.sessionId
                if (sessionId == null || !state.isRunning) break
                fetchAndApply(sessionId)

                val signature = _uiState.value.messages.lastOrNull()?.contentSignature()
                stalledPolls = if (signature == lastSignature) stalledPolls + 1 else 0
                lastSignature = signature
                if (stalledPolls >= stallLimitPolls) {
                    finishRun()
                    break
                }
            }
        }
    }

    /**
     * When a session is opened while the assistant is still working, pick the run up mid-flight:
     * mark it running, keep the trailing message streaming and start polling.
     */
    private fun adoptInFlightRun(sessionId: String, serverMessages: List<OpenCodeMessage>) {
        if (_uiState.value.sessionId != sessionId) return
        val last = serverMessages.lastOrNull() ?: return
        if (last.info.role == "user" || last.info.time.completed != null) return

        runBaselineMessageIds = serverMessages
            .map { it.info.id }
            .filterTo(mutableSetOf()) { it != last.info.id }
        _uiState.update { state ->
            state.copy(
                isRunning = true,
                messages = state.messages.map { message ->
                    if (message.id == last.info.id) message.copy(isStreaming = true) else message
                }
            )
        }
        // We are guessing that this session is live from a missing completion timestamp, so give
        // up quickly if the server produces nothing new — an aborted run also looks like this.
        startSyncLoop(stallLimitPolls = ADOPTED_RUN_STALL_POLLS)
    }

    /** One-shot re-read, used after a reconnect or when a run ends. */
    private fun resyncMessages() {
        if (backend == null) return
        val sessionId = _uiState.value.sessionId ?: return
        viewModelScope.launch { fetchAndApply(sessionId) }
    }

    private suspend fun fetchAndApply(sessionId: String) {
        val currentBackend = backend ?: return
        runCatching { currentBackend.listMessages(sessionId) }
            .onSuccess { applyServerMessages(sessionId, it) }
    }

    /**
     * Folds the server's view of the session into the on-screen messages. Server content wins,
     * except for streamed text that is still ahead of what the server has persisted, so the
     * merge never rewinds a message that is mid-stream.
     */
    private fun applyServerMessages(sessionId: String, serverMessages: List<OpenCodeMessage>) {
        if (_uiState.value.sessionId != sessionId) return
        if (serverMessages.isEmpty()) return

        serverMessages.forEach { messageRoles[it.info.id] = it.info.role }

        val running = _uiState.value.isRunning
        val merged = serverMessages.mapNotNull { message ->
            val cache = streamedParts.getOrPut(message.info.id) { linkedMapOf() }
            message.parts.forEach { part ->
                val chatPart = part.toChatPart() ?: return@forEach
                cache[chatPart.id] = preferMoreAdvanced(cache[chatPart.id], chatPart)
            }
            val parts = cache.values.toList()
            if (parts.isEmpty()) return@mapNotNull null
            val isUser = message.info.role == "user"
            ChatMessage(
                id = message.info.id,
                isUser = isUser,
                parts = parts,
                timestamp = message.info.time.created,
                isStreaming = !isUser && message.info.time.completed == null && running
            )
        }

        val serverIds = merged.mapTo(mutableSetOf()) { it.id }
        val serverUserTexts = merged.filter { it.isUser }.mapTo(mutableSetOf()) { it.text.trim() }
        val pending = _uiState.value.messages.filter { message ->
            if (message.id in serverIds) return@filter false
            // Drop the optimistic bubble once the server echoes the same user message back.
            !(message.id in optimisticUserMessageIds && message.text.trim() in serverUserTexts)
        }
        optimisticUserMessageIds.removeAll { id -> pending.none { it.id == id } }

        val hasAssistantContent = merged.any { !it.isUser && it.id !in runBaselineMessageIds }
        _uiState.update {
            it.copy(
                messages = merged + pending,
                isThinking = it.isThinking && !hasAssistantContent
            )
        }

        if (running && isRunComplete(serverMessages)) finishRun()
    }

    /**
     * True once every assistant message produced by the current run carries a completion
     * timestamp. Used as an event-independent fallback for `session.idle`.
     */
    private fun isRunComplete(serverMessages: List<OpenCodeMessage>): Boolean {
        val runMessages = serverMessages.filter {
            it.info.role != "user" && it.info.id !in runBaselineMessageIds
        }
        return runMessages.isNotEmpty() && runMessages.all { it.info.time.completed != null }
    }

    /**
     * Picks between the part we already show and the server's copy. The server is authoritative
     * unless what we have is strictly further along — otherwise a poll landing mid-stream would
     * visibly rewind text or knock a finished tool back to "running".
     */
    private fun preferMoreAdvanced(existing: ChatPart?, incoming: ChatPart): ChatPart = when {
        existing is ChatPart.Text && incoming is ChatPart.Text &&
            existing.text.length > incoming.text.length -> existing
        existing is ChatPart.Reasoning && incoming is ChatPart.Reasoning &&
            existing.text.length > incoming.text.length -> existing
        existing is ChatPart.Tool && incoming is ChatPart.Tool &&
            existing.status.isTerminal() && !incoming.status.isTerminal() -> existing
        else -> incoming
    }

    private fun ToolStatus.isTerminal(): Boolean =
        this == ToolStatus.COMPLETED || this == ToolStatus.ERROR

    fun setTTS(textToSpeech: TextToSpeech) {
        tts = textToSpeech
    }

    fun startListening() {
        _uiState.update { it.copy(isListening = true, partialText = "", error = null) }
    }

    fun updateSpeechPartial(text: String) {
        _uiState.update { it.copy(isListening = true, partialText = text) }
    }

    fun reportSpeechError(message: String) {
        _uiState.update { it.copy(isListening = false, partialText = "", error = message) }
    }

    fun stopListening() {
        _uiState.update { it.copy(isListening = false, partialText = "") }
    }

    fun stopSpeaking() {
        tts?.stop()
        _uiState.update { it.copy(isSpeaking = false) }
    }

    override fun onCleared() {
        eventJob?.cancel()
        syncJob?.cancel()
        tts?.stop()
        tts = null
        super.onCleared()
    }

    private fun Throwable.safeMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: "OpenCode operation failed"
}
