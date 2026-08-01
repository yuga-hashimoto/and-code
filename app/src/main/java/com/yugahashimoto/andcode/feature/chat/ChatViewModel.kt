package com.yugahashimoto.andcode.feature.chat

import android.graphics.Bitmap
import android.speech.tts.TextToSpeech
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.core.api.ConnectionQuality
import com.yugahashimoto.andcode.core.api.ConnectionQualityMonitor
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodePart
import com.yugahashimoto.andcode.core.api.PermissionRequest
import com.yugahashimoto.andcode.core.api.PromptAttachment
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.QuestionPrompt
import com.yugahashimoto.andcode.core.api.QuestionRequest
import com.yugahashimoto.andcode.core.util.safeMessage
import com.yugahashimoto.andcode.data.settings.Draft
import com.yugahashimoto.andcode.data.settings.DraftRepository
import com.yugahashimoto.andcode.runtime.OpenCodeBackend
import com.yugahashimoto.andcode.runtime.PermissionResponse
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

enum class ToolStatus { PENDING, RUNNING, COMPLETED, ERROR, UNKNOWN }

data class TodoItem(
    val content: String,
    val status: String,
    val priority: String,
)

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
        val error: String? = null,
        val todos: List<TodoItem> = emptyList(),
    ) : ChatPart

    data class Patch(override val id: String, val files: List<String>) : ChatPart
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val isUser: Boolean,
    val parts: List<ChatPart> = emptyList(),
    val attachments: List<PromptAttachment> = emptyList(),
    val imagePreviews: List<Bitmap> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val isStreaming: Boolean = false,
) {
    val text: String
        get() = parts.filterIsInstance<ChatPart.Text>().joinToString("") { it.text }
}

data class PendingQuestionUi(
    val request: QuestionRequest,
    val selectedAnswers: List<List<String>>,
    val isSubmitting: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean
        get() =
            request.questions.indices.all { index ->
                val prompt = request.questions[index]
                sanitizeQuestionAnswer(
                    prompt = prompt,
                    answers = selectedAnswers.getOrElse(index) { emptyList() },
                    multiple = prompt.multiple,
                ).isNotEmpty()
            }

    companion object {
        fun from(request: QuestionRequest) =
            PendingQuestionUi(
                request = request,
                selectedAnswers = request.questions.map { emptyList() },
            )
    }
}

private const val MAX_TOOL_OUTPUT_CHARS = 4000
private const val RESPONSE_POLL_INTERVAL_MS = 3000L
private const val RESPONSE_POLL_TIMEOUT_MS = 120_000L
private const val TRANSIENT_RECOVERY_DELAY_MS = 5000L
private const val HEALTH_CHECK_ATTEMPTS = 15
private const val HEALTH_CHECK_DELAY_MS = 2000L

private fun OpenCodePart.toChatPart(): ChatPart? {
    val partId = id ?: return null
    val stateMap = state.orEmpty()
    return when (type) {
        "text" -> ChatPart.Text(partId, text.orEmpty())
        "reasoning" -> ChatPart.Reasoning(partId, text.orEmpty())
        "tool" -> {
            val inputText = formatToolInput(stateMap["input"])
            val rawOutput = stateMap["output"]?.jsonPrimitiveOrNull()
            val truncated = rawOutput != null && rawOutput.length > MAX_TOOL_OUTPUT_CHARS
            val toolName = tool ?: "tool"
            val parsedTodos = if (toolName == "todowrite") parseTodosFromInput(stateMap["input"]) else emptyList()
            ChatPart.Tool(
                id = partId,
                name = toolName,
                status = parseToolStatus(stateMap["status"]?.jsonPrimitiveOrNull()),
                title =
                    stateMap["title"]?.jsonPrimitiveOrNull()?.takeIf { it.isNotBlank() }
                        ?: inputText?.lineSequence()?.firstOrNull(),
                input = inputText,
                output = if (truncated) rawOutput?.takeLast(MAX_TOOL_OUTPUT_CHARS) else rawOutput,
                outputTruncated = truncated,
                error = stateMap["error"]?.jsonPrimitiveOrNull(),
                todos = parsedTodos,
            )
        }
        "patch" -> ChatPart.Patch(partId, extractPatchFiles(stateMap))
        else -> null
    }
}

private fun JsonElement.jsonPrimitiveOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull ?: (this as? JsonPrimitive)?.content

private fun parseToolStatus(value: String?): ToolStatus =
    when (value) {
        "pending" -> ToolStatus.PENDING
        "running" -> ToolStatus.RUNNING
        "completed" -> ToolStatus.COMPLETED
        "error" -> ToolStatus.ERROR
        else -> ToolStatus.UNKNOWN
    }

private fun formatToolInput(input: JsonElement?): String? {
    val obj = input as? JsonObject ?: return null
    if (obj.isEmpty()) return null
    obj["command"]?.jsonPrimitiveOrNull()?.let { return it }
    obj["filePath"]?.jsonPrimitiveOrNull()?.let { path ->
        val extra = obj.entries.filterNot { it.key == "filePath" }
        return if (extra.isEmpty()) {
            path
        } else {
            path + "\n" + extra.joinToString("\n") { (key, value) -> "$key: ${value.jsonPrimitiveOrNull() ?: value}" }
        }
    }
    return obj.entries.joinToString("\n") { (key, value) -> "$key: ${value.jsonPrimitiveOrNull() ?: value}" }
}

private fun parseTodosFromInput(input: JsonElement?): List<TodoItem> {
    val obj = input as? JsonObject ?: return emptyList()
    val todosArray = obj["todos"] as? JsonArray ?: return emptyList()
    return todosArray.mapNotNull { element ->
        val todoObj = element as? JsonObject ?: return@mapNotNull null
        TodoItem(
            content = todoObj["content"]?.jsonPrimitiveOrNull() ?: return@mapNotNull null,
            status = todoObj["status"]?.jsonPrimitiveOrNull() ?: "pending",
            priority = todoObj["priority"]?.jsonPrimitiveOrNull() ?: "medium",
        )
    }
}

private fun extractPatchFiles(state: Map<String, JsonElement>): List<String> {
    return when (val files = state["files"]) {
        is JsonArray ->
            files.mapNotNull { entry ->
                when (entry) {
                    is JsonPrimitive -> entry.content
                    is JsonObject ->
                        entry["path"]?.jsonPrimitiveOrNull()
                            ?: entry["file"]?.jsonPrimitiveOrNull()
                            ?: entry["filename"]?.jsonPrimitiveOrNull()
                    else -> null
                }
            }
        is Map<*, *> -> files.keys.mapNotNull { it?.toString() }
        else -> emptyList()
    }
}

/** The session a subagent conversation was started from, used to walk back to the main agent. */
data class ParentSessionRef(
    val id: String,
    val title: String = "",
)

data class ChatUiState(
    val backendName: String = "",
    val sessionId: String? = null,
    val sessionTitle: String = "",
    /** Non-null while the open session is a subagent session spawned by [ParentSessionRef.id]. */
    val parentSession: ParentSessionRef? = null,
    val messages: List<ChatMessage> = emptyList(),
    val permissions: List<PermissionRequest> = emptyList(),
    val pendingQuestions: List<PendingQuestionUi> = emptyList(),
    val isConnected: Boolean = false,
    val isRunning: Boolean = false,
    val isLoadingHistory: Boolean = false,
    val isListening: Boolean = false,
    val isSpeechProcessing: Boolean = false,
    val isThinking: Boolean = false,
    val isSpeaking: Boolean = false,
    val partialText: String = "",
    val autoAcceptPermissions: Boolean = false,
    val contextTokensUsed: Long = 0L,
    val selectedVariant: String? = null,
    val attachments: List<PromptAttachment> = emptyList(),
    val imagePreviews: List<Bitmap> = emptyList(),
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val selectedAgentId: String? = null,
    val selectedWorkspacePath: String? = null,
    val offlineQueue: List<String> = emptyList(),
    val isOfflineQueued: Boolean = false,
    val connectionQuality: ConnectionQuality? = null,
    val error: String? = null,
)

class ChatViewModel(
    private val backend: OpenCodeBackend? = null,
    private val eventFlow: Flow<OpenCodeEvent>? = null,
    private val onPermissionResolved: (String) -> Unit = {},
    private val onSessionCreated: () -> Unit = {},
    /**
     * Reports whether this chat is working, so the drawer shows real state even when no stream
     * events arrive. Deriving it from events alone left every chat on the idle marker.
     */
    private val onRunStateChanged: (String, Boolean) -> Unit = { _, _ -> },
    private val draftRepo: DraftRepository? = null,
    /**
     * Starts the periodic connection probe. It runs an unbounded polling loop, which a virtual
     * test clock advances through forever, so it stays off unless the real app asks for it.
     */
    private val monitorConnectionQuality: Boolean = false,
    private val resolvedPermissionFlow: Flow<String>? = null,
) : ViewModel() {
    private val _uiState =
        MutableStateFlow(
            ChatUiState(backendName = backend?.displayName.orEmpty()),
        )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _sendBehavior = MutableStateFlow("interrupt")
    val sendBehavior: StateFlow<String> = _sendBehavior.asStateFlow()

    private val _autoExpandReasoning = MutableStateFlow(false)
    val autoExpandReasoning: StateFlow<Boolean> = _autoExpandReasoning.asStateFlow()

    private val _workspaceTitleSource = MutableStateFlow("title")
    val workspaceTitleSource: StateFlow<String> = _workspaceTitleSource.asStateFlow()

    private val messageQueue = MutableStateFlow<List<String>>(emptyList())
    private val offlineMessageQueue = MutableStateFlow<List<String>>(emptyList())

    private var eventJob: Job? = null
    private var tts: TextToSpeech? = null
    private var contextLimit: Long = 0L
    private val streamedParts = mutableMapOf<String, LinkedHashMap<String, ChatPart>>()

    /** Message id to role, learned from `message.updated`, so user echoes can be skipped. */
    private val messageRoles = mutableMapOf<String, String>()
    private val connectionMonitor = ConnectionQualityMonitor(viewModelScope)

    init {
        // A permission answered from the notification (or the activity screen) never comes back
        // as an event, so without this the chat keeps showing a card for a settled request.
        resolvedPermissionFlow?.let { flow ->
            viewModelScope.launch {
                flow.collect { permissionId ->
                    _uiState.update { state ->
                        state.copy(permissions = state.permissions.filterNot { it.id == permissionId })
                    }
                }
            }
        }
        if (backend != null) {
            viewModelScope.launch {
                // Only real transitions are reported, so merely opening an idle chat is not
                // mistaken for a run that just ended.
                var runningSession: String? = null
                uiState
                    .map { it.sessionId to it.isRunning }
                    .distinctUntilChanged()
                    .collect { (sessionId, running) ->
                        if (sessionId == null) return@collect
                        // The run we last reported as active is over the moment the open chat
                        // stops running OR the client moves to another chat (it can no longer
                        // track the old one's isRunning). The previous logic keyed on the
                        // (sessionId, isRunning) pair, so a mid-turn navigation produced the
                        // transition (oldSession, true) -> (newSession, false) and matched neither
                        // branch: the old session was never reported finished and its drawer dot
                        // stayed on the spinner forever. Clear the stale run explicitly here so the
                        // drawer only reflects chats the runtime itself still reports as active.
                        runningSession?.let { previous ->
                            if (previous != sessionId || !running) {
                                onRunStateChanged(previous, false)
                                runningSession = null
                            }
                        }
                        if (running && runningSession != sessionId) {
                            runningSession = sessionId
                            onRunStateChanged(sessionId, true)
                        }
                    }
            }
            eventJob =
                viewModelScope.launch {
                    (eventFlow ?: backend.events())
                        .catch { error -> reportError(error) }
                        .collect(::handleEvent)
                }
            viewModelScope.launch {
                var lastError: String? = null
                repeat(HEALTH_CHECK_ATTEMPTS) {
                    runCatching { backend.health() }
                        .onSuccess { health ->
                            _uiState.update {
                                it.copy(
                                    isConnected = health.healthy,
                                    backendName = "${backend.displayName} · ${health.version}",
                                    error = null,
                                )
                            }
                            return@launch
                        }
                        .onFailure { error -> lastError = error.safeMessage() }
                    delay(HEALTH_CHECK_DELAY_MS)
                }
                reportError(lastError)
            }
            if (monitorConnectionQuality) {
                connectionMonitor.startMonitoring { backend.health() }
                viewModelScope.launch {
                    connectionMonitor.quality.collect { quality ->
                        _uiState.update { it.copy(connectionQuality = quality) }
                    }
                }
            }
        }
    }

    fun selectWorkspace(path: String?) {
        if (_uiState.value.sessionId != null) return
        _uiState.update { it.copy(selectedWorkspacePath = path) }
    }

    fun selectConfiguration(
        providerId: String?,
        modelId: String?,
        agentId: String?,
        contextLimit: Long = 0L,
    ) {
        this.contextLimit = contextLimit
        _uiState.update {
            it.copy(
                selectedProviderId = providerId,
                selectedModelId = modelId,
                selectedAgentId = agentId,
            )
        }
    }

    fun setAutoAcceptPermissions(enabled: Boolean) {
        _uiState.update { it.copy(autoAcceptPermissions = enabled) }
    }

    fun setSendBehavior(behavior: String) {
        _sendBehavior.value = behavior
    }

    fun setAutoExpandReasoning(enabled: Boolean) {
        _autoExpandReasoning.value = enabled
    }

    fun setWorkspaceTitleSource(source: String) {
        _workspaceTitleSource.value = source
    }

    fun saveDraft(
        sessionId: String,
        text: String,
        model: String?,
        agent: String?,
    ) {
        draftRepo?.save(sessionId, Draft(text, emptyList(), model, agent))
    }

    fun loadDraft(sessionId: String): Draft? = draftRepo?.load(sessionId)

    fun clearDraft(sessionId: String) {
        draftRepo?.clear(sessionId)
    }

    fun selectVariant(variant: String?) {
        _uiState.update { it.copy(selectedVariant = variant) }
    }

    fun addAttachment(attachment: PromptAttachment) {
        _uiState.update { it.copy(attachments = it.attachments + attachment) }
    }

    fun addImageAttachment(
        attachment: PromptAttachment,
        preview: Bitmap,
    ) {
        _uiState.update {
            it.copy(
                attachments = it.attachments + attachment,
                imagePreviews = it.imagePreviews + preview,
            )
        }
    }

    fun removeAttachment(index: Int) {
        _uiState.value.imagePreviews.getOrNull(index)?.let { if (!it.isRecycled) it.recycle() }
        _uiState.update { state ->
            state.copy(
                attachments = state.attachments.filterIndexed { i, _ -> i != index },
                imagePreviews = state.imagePreviews.filterIndexed { i, _ -> i != index },
            )
        }
    }

    private fun refreshContextUsage(sessionId: String) {
        val currentBackend = backend ?: return
        viewModelScope.launch {
            runCatching {
                val messages = currentBackend.listMessages(sessionId)
                val latestMessageTokens =
                    messages.asReversed()
                        .firstNotNullOfOrNull { message ->
                            message.info.tokens?.contextUsed
                                ?.takeIf { !message.info.role.equals("user", ignoreCase = true) }
                        }
                latestMessageTokens ?: currentBackend.session(sessionId).tokens?.contextUsed ?: 0L
            }.onSuccess { used ->
                _uiState.update { it.copy(contextTokensUsed = used) }
            }
        }
    }

    /**
     * @param parent the session this one was spawned from when the caller already knows it
     * (subagent drill-in). When null the parent is resolved from the backend so a subagent session
     * opened from anywhere else still offers a way back to the main agent.
     */
    fun openSession(
        sessionId: String,
        title: String = "",
        parent: ParentSessionRef? = null,
    ) {
        val currentBackend = backend ?: return
        // Switching away from whatever chat was previously loaded here must drop its running
        // state too — otherwise the composer opens the new chat already stuck on the stop button
        // because the old chat happened to be mid-turn when the user navigated away.
        val switchingSession = _uiState.value.sessionId != sessionId
        streamedParts.clear()
        messageRoles.clear()
        _uiState.update {
            it.copy(
                sessionId = sessionId,
                sessionTitle = title,
                parentSession = parent,
                isLoadingHistory = true,
                messages = emptyList(),
                permissions = emptyList(),
                pendingQuestions = emptyList(),
                isRunning = if (switchingSession) false else it.isRunning,
                isThinking = if (switchingSession) false else it.isThinking,
                error = null,
            )
        }
        if (parent == null) resolveParentSession(sessionId)
        refreshPendingQuestions(sessionId)
        viewModelScope.launch {
            runCatching { currentBackend.listMessages(sessionId) }
                .onSuccess { messages ->
                    val selectedModel =
                        messages.asReversed()
                            .firstNotNullOfOrNull { it.info.model }
                    _uiState.update {
                        it.copy(
                            isLoadingHistory = false,
                            messages = messages.mapNotNull(::toUiMessage),
                            selectedProviderId = selectedModel?.providerId ?: it.selectedProviderId,
                            selectedModelId = selectedModel?.modelId ?: it.selectedModelId,
                        )
                    }
                    refreshContextUsage(sessionId)
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoadingHistory = false, error = error.safeMessage("OpenCode operation failed"))
                    }
                }
        }
    }

    /** Opens a subagent session started by the open chat, remembering the session to return to. */
    fun openSubagentSession(
        sessionId: String,
        title: String = "",
    ) {
        val current = _uiState.value
        val parent = current.sessionId?.let { ParentSessionRef(it, current.sessionTitle) }
        openSession(sessionId, title, parent)
    }

    /** Returns from a subagent session to the session that spawned it. */
    fun openParentSession() {
        val parent = _uiState.value.parentSession ?: return
        openSession(parent.id, parent.title)
    }

    /**
     * Asks the backend whether the open session is a subagent session. Failures leave the return
     * affordance hidden rather than surfacing an error the user cannot act on.
     */
    private fun resolveParentSession(sessionId: String) {
        val currentBackend = backend ?: return
        viewModelScope.launch {
            val parentId =
                runCatching { currentBackend.session(sessionId) }.getOrNull()?.parentId
                    ?: return@launch
            val parentTitle =
                runCatching { currentBackend.session(parentId) }.getOrNull()?.title.orEmpty()
            _uiState.update { state ->
                if (state.sessionId != sessionId) {
                    state
                } else {
                    state.copy(parentSession = ParentSessionRef(parentId, parentTitle))
                }
            }
        }
    }

    fun newSession() {
        streamedParts.clear()
        messageRoles.clear()
        _uiState.update {
            it.copy(
                sessionId = null,
                sessionTitle = "",
                parentSession = null,
                messages = emptyList(),
                permissions = emptyList(),
                pendingQuestions = emptyList(),
                isRunning = false,
                isThinking = false,
                isListening = false,
                isSpeechProcessing = false,
                partialText = "",
                imagePreviews = emptyList(),
                error = null,
            )
        }
    }

    fun sendMessage(text: String) {
        val normalized = text.trim()
        val pendingAttachments = _uiState.value.attachments
        val messageIdsBeforeSend = _uiState.value.messages.map { it.id }.toSet()
        val pendingPreviewsByFilename =
            pendingAttachments.mapIndexedNotNull { index, attachment ->
                _uiState.value.imagePreviews.getOrNull(index)?.let { attachment.filename to it }
            }.toMap()
        if (normalized.isEmpty() && pendingAttachments.isEmpty()) return
        val currentBackend = backend
        if (currentBackend == null) {
            _uiState.update { it.copy(error = "OpenCode connection is not configured") }
            return
        }

        if (!_uiState.value.isConnected) {
            offlineMessageQueue.update { it + normalized }
            val userMessage =
                ChatMessage(
                    isUser = true,
                    parts = listOf(ChatPart.Text(id = UUID.randomUUID().toString(), text = normalized)),
                )
            _uiState.update {
                it.copy(
                    messages = it.messages + userMessage,
                    offlineQueue = it.offlineQueue + normalized,
                    isOfflineQueued = true,
                )
            }
            return
        }

        // Some runtimes (Antigravity) must always queue behind a running turn rather than interrupt
        // it - interrupting there kills the in-flight one-shot process instead of cancelling a
        // request, which surfaced as a crash. See RuntimeCapabilities.forcesQueue.
        val mustQueue = (currentBackend as? RuntimeTarget)?.capabilities?.forcesQueue == true
        if ((_sendBehavior.value == "queue" || mustQueue) && _uiState.value.isRunning) {
            messageQueue.update { it + normalized }
            return
        }

        val userMessage =
            ChatMessage(
                isUser = true,
                parts =
                    normalized.takeIf { it.isNotEmpty() }?.let {
                        listOf(ChatPart.Text(id = UUID.randomUUID().toString(), text = it))
                    }.orEmpty(),
                attachments = pendingAttachments,
                imagePreviews = _uiState.value.imagePreviews,
            )
        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                isRunning = true,
                isThinking = true,
                partialText = "",
                error = null,
            )
        }

        viewModelScope.launch {
            // Captured once the target session is known so onFailure below can tell whether the
            // failure still concerns the chat currently on screen.
            var capturedSessionId: String? = null
            runCatching {
                val existingSessionId = _uiState.value.sessionId
                val session =
                    if (existingSessionId == null) {
                        currentBackend.createSession(
                            // Let OpenCode generate the concise chat summary from the first prompt.
                            title = null,
                            directory = _uiState.value.selectedWorkspacePath,
                        )
                    } else {
                        null
                    }
                val targetSessionId = existingSessionId ?: requireNotNull(session).id
                capturedSessionId = targetSessionId
                if (session != null) {
                    _uiState.update {
                        it.copy(sessionId = session.id, sessionTitle = session.title)
                    }
                    onSessionCreated()
                }
                if (existingSessionId != null && shouldSummarizeBeforePrompt()) {
                    runCatching {
                        currentBackend.summarizeSession(
                            sessionId = targetSessionId,
                            providerId = requireNotNull(_uiState.value.selectedProviderId),
                            modelId = requireNotNull(_uiState.value.selectedModelId),
                        )
                    }
                    refreshContextUsage(targetSessionId)
                }
                currentBackend.sendMessage(
                    targetSessionId,
                    PromptRequest(
                        text = normalized,
                        providerId = _uiState.value.selectedProviderId,
                        modelId = _uiState.value.selectedModelId,
                        agent = _uiState.value.selectedAgentId,
                        variant = _uiState.value.selectedVariant,
                        attachments = pendingAttachments,
                    ),
                )
                runCatching { currentBackend.session(targetSessionId).title }
                    .onSuccess { title ->
                        if (title.isNotBlank()) _uiState.update { it.copy(sessionTitle = title) }
                    }
                _uiState.update { it.copy(attachments = emptyList(), imagePreviews = emptyList()) }
                clearDraft(targetSessionId)
                var sessionCompleted = false

                // Every update below is guarded on the chat still being on screen: if the user
                // switches to another session, this poll must not keep overwriting its transcript
                // or clearing its running state with data that belongs to the old one.
                fun isStillActive() = _uiState.value.sessionId == targetSessionId
                val pollFinished =
                    withTimeoutOrNull(RESPONSE_POLL_TIMEOUT_MS) {
                        while (isStillActive() && _uiState.value.isRunning) {
                            delay(RESPONSE_POLL_INTERVAL_MS)
                            if (!isStillActive()) return@withTimeoutOrNull
                            runCatching { currentBackend.listMessages(targetSessionId) }
                                .onSuccess { serverMessages ->
                                    if (!isStillActive()) return@onSuccess
                                    val previewsById =
                                        _uiState.value.messages
                                            .associate { it.id to it.imagePreviews }
                                    val uiMessages =
                                        serverMessages.mapNotNull(::toUiMessage).map { message ->
                                            val previews =
                                                previewsById[message.id].orEmpty().ifEmpty {
                                                    message.attachments
                                                        .mapNotNull { pendingPreviewsByFilename[it.filename] }
                                                }
                                            message.copy(imagePreviews = previews)
                                        }
                                    if (uiMessages.isNotEmpty() && uiMessages != _uiState.value.messages) {
                                        _uiState.update { it.copy(messages = uiMessages) }
                                    }
                                    // Completion is read off the transcript. A session object
                                    // carries no completion time — only assistant messages do — so
                                    // polling the session never ended the run and the composer sat
                                    // on the stop button until the 2 minute timeout expired.
                                    if (turnFinished(serverMessages, messageIdsBeforeSend)) {
                                        sessionCompleted = true
                                        _uiState.update { it.copy(isRunning = false, isThinking = false) }
                                    }
                                }
                        }
                    }
                // Some runtimes deliver the final message but drop session.idle. Do not
                // leave the UI in the running state when the bounded fallback poll ends.
                if (isStillActive() && (sessionCompleted || pollFinished == null)) {
                    runCatching { currentBackend.listMessages(targetSessionId) }
                        .onSuccess { serverMessages ->
                            if (!isStillActive()) return@onSuccess
                            val hasResponse =
                                serverMessages.any { message ->
                                    message.info.role == "assistant" && message.info.id !in messageIdsBeforeSend
                                }
                            if (!sessionCompleted && !hasResponse) return@onSuccess
                            streamedParts.clear()
                            // Re-attach the in-memory previews the same way the polling loop does:
                            // the final reload otherwise drops them, and a runtime whose attachment
                            // URLs are not inlineable data URLs would lose the just-sent image the
                            // moment the send completed.
                            val previewsById =
                                _uiState.value.messages.associate { it.id to it.imagePreviews }
                            val uiMessages =
                                serverMessages.mapNotNull(::toUiMessage).map { message ->
                                    val previews =
                                        previewsById[message.id].orEmpty().ifEmpty {
                                            message.attachments
                                                .mapNotNull { pendingPreviewsByFilename[it.filename] }
                                        }
                                    message.copy(imagePreviews = previews)
                                }
                            _uiState.update {
                                it.copy(
                                    messages = uiMessages,
                                    isRunning = false,
                                    isThinking = false,
                                )
                            }
                            // Refresh after the final assistant message is persisted. The
                            // pre-send refresh only contains the previous turn's tokens.
                            refreshContextUsage(targetSessionId)
                        }
                }
            }.onFailure { error ->
                if (capturedSessionId == null || _uiState.value.sessionId == capturedSessionId) {
                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            isThinking = false,
                        )
                    }
                }
                reportError(error)
            }
        }
    }

    /**
     * True once the assistant has answered this prompt and marked its reply finished.
     *
     * The newest message being a completed assistant turn is the signal every runtime reports;
     * requiring a reply that did not exist before the prompt keeps the previous turn's completion
     * from ending the new one instantly.
     */
    private fun turnFinished(
        messages: List<OpenCodeMessage>,
        messageIdsBeforeSend: Set<String>,
    ): Boolean {
        val newest = messages.lastOrNull()?.info ?: return false
        if (newest.role == "user") return false
        val answeredThisPrompt =
            messages.any { it.info.role != "user" && it.info.id !in messageIdsBeforeSend }
        return answeredThisPrompt && newest.time.completed != null
    }

    private fun shouldSummarizeBeforePrompt(): Boolean =
        contextLimit > 0L &&
            _uiState.value.contextTokensUsed.toDouble() / contextLimit.toDouble() >= 0.9

    fun respondToPermission(
        permissionId: String,
        response: PermissionResponse,
        remember: Boolean,
    ) {
        val currentBackend = backend ?: return
        val permission = _uiState.value.permissions.firstOrNull { it.id == permissionId } ?: return
        viewModelScope.launch {
            runCatching {
                currentBackend.respondToPermission(
                    permission.sessionId,
                    permission.id,
                    response,
                    remember,
                )
            }.onSuccess { accepted ->
                if (accepted) {
                    _uiState.update { state ->
                        state.copy(permissions = state.permissions.filterNot { it.id == permissionId })
                    }
                    onPermissionResolved(permissionId)
                } else {
                    _uiState.update { it.copy(error = "OpenCode could not apply that permission response") }
                }
            }.onFailure { error ->
                _uiState.update { it.copy(error = error.safeMessage("OpenCode operation failed")) }
            }
        }
    }

    fun selectQuestionAnswer(
        questionId: String,
        questionIndex: Int,
        answer: String,
    ) {
        _uiState.update { state ->
            state.copy(
                pendingQuestions =
                    state.pendingQuestions.map { pending ->
                        if (pending.request.id != questionId) return@map pending
                        val prompt = pending.request.questions.getOrNull(questionIndex) ?: return@map pending
                        val updatedAnswers = pending.selectedAnswers.toMutableList()
                        updatedAnswers[questionIndex] =
                            updateQuestionAnswerSelection(
                                prompt = prompt,
                                current = pending.selectedAnswers.getOrElse(questionIndex) { emptyList() },
                                answer = answer,
                                multiple = prompt.multiple,
                            )
                        pending.copy(selectedAnswers = updatedAnswers, error = null)
                    },
            )
        }
    }

    fun submitQuestion(questionId: String) {
        val currentBackend = backend ?: return
        val pendingQuestion = _uiState.value.pendingQuestions.firstOrNull { it.request.id == questionId } ?: return
        val answers =
            pendingQuestion.request.questions.indices.map { index ->
                val prompt = pendingQuestion.request.questions[index]
                sanitizeQuestionAnswer(
                    prompt = prompt,
                    answers = pendingQuestion.selectedAnswers.getOrElse(index) { emptyList() },
                    multiple = prompt.multiple,
                )
            }
        if (answers.any { it.isEmpty() }) {
            _uiState.update { state ->
                state.copy(
                    pendingQuestions =
                        state.pendingQuestions.map { pending ->
                            if (pending.request.id == questionId) {
                                pending.copy(error = "Answer required")
                            } else {
                                pending
                            }
                        },
                )
            }
            return
        }

        _uiState.update { state ->
            state.copy(
                pendingQuestions =
                    state.pendingQuestions.map { pending ->
                        if (pending.request.id == questionId) {
                            pending.copy(isSubmitting = true, error = null)
                        } else {
                            pending
                        }
                    },
            )
        }

        viewModelScope.launch {
            runCatching {
                currentBackend.answerQuestion(
                    requestId = questionId,
                    answers = answers,
                    directory = pendingQuestion.workspaceDirectory(),
                )
            }.onSuccess { accepted ->
                _uiState.update { state ->
                    if (accepted) {
                        state.copy(
                            pendingQuestions = state.pendingQuestions.filterNot { it.request.id == questionId },
                        )
                    } else {
                        state.copy(
                            pendingQuestions =
                                state.pendingQuestions.map { pending ->
                                    if (pending.request.id == questionId) {
                                        pending.copy(isSubmitting = false, error = "OpenCode question failed")
                                    } else {
                                        pending
                                    }
                                },
                        )
                    }
                }
            }.onFailure { error ->
                _uiState.update { state ->
                    state.copy(
                        pendingQuestions =
                            state.pendingQuestions.map { pending ->
                                if (pending.request.id == questionId) {
                                    pending.copy(isSubmitting = false, error = error.safeMessage("OpenCode operation failed"))
                                } else {
                                    pending
                                }
                            },
                    )
                }
            }
        }
    }

    /**
     * The directory the question routes have to be scoped to. What the event stream reported is
     * authoritative; the selected workspace is the fallback for the older per-instance `/event`
     * stream, whose frames carry no directory.
     */
    private fun PendingQuestionUi.workspaceDirectory(): String? = request.directory ?: _uiState.value.selectedWorkspacePath

    /**
     * Recovers questions that are already waiting for an answer. A question reaches the chat as an
     * event and nowhere else, so one asked while this client was not listening — before the app
     * opened the session, or across a dropped event stream — would otherwise leave the turn
     * blocked with nothing on screen to unblock it.
     */
    private fun refreshPendingQuestions(sessionId: String) {
        val currentBackend = backend ?: return
        viewModelScope.launch {
            val pending =
                runCatching { currentBackend.pendingQuestions(_uiState.value.selectedWorkspacePath) }
                    .getOrElse { return@launch }
                    .filter { it.sessionId == sessionId }
            if (pending.isEmpty()) return@launch
            _uiState.update { state ->
                if (state.sessionId != sessionId) return@update state
                val known = state.pendingQuestions.map { it.request.id }.toSet()
                state.copy(
                    pendingQuestions =
                        state.pendingQuestions + pending.filterNot { it.id in known }.map(PendingQuestionUi::from),
                )
            }
        }
    }

    /**
     * Hides the question card and leaves the turn alone, so the user can ignore the question and
     * just keep typing. The request stays open on the OpenCode side; this only affects what the
     * chat shows.
     */
    fun dismissQuestion(questionId: String) {
        _uiState.update { state ->
            state.copy(
                pendingQuestions = state.pendingQuestions.filterNot { it.request.id == questionId },
            )
        }
    }

    /**
     * Declines the question. OpenCode fails the waiting tool call with "the user dismissed this
     * question", which the agent can react to — unlike [dismissQuestion], which only clears the
     * card and leaves the request open.
     */
    fun cancelQuestion(questionId: String) {
        val pendingQuestion = _uiState.value.pendingQuestions.firstOrNull { it.request.id == questionId } ?: return
        _uiState.update { state ->
            state.copy(
                pendingQuestions = state.pendingQuestions.filterNot { it.request.id == questionId },
            )
        }
        val currentBackend = backend ?: return
        viewModelScope.launch {
            runCatching {
                currentBackend.rejectQuestion(
                    requestId = questionId,
                    directory = pendingQuestion.workspaceDirectory(),
                )
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
                    _uiState.update {
                        it.copy(isRunning = false, isThinking = false)
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(error = error.safeMessage("OpenCode operation failed")) }
                }
        }
    }

    private fun handleEvent(event: OpenCodeEvent) {
        val activeSession = _uiState.value.sessionId
        when (event) {
            OpenCodeEvent.ServerConnected -> {
                _uiState.update { it.copy(isConnected = true, error = null) }
                val session = _uiState.value.sessionId
                val currentBackend = backend
                if (session != null && currentBackend != null) {
                    viewModelScope.launch {
                        runCatching { currentBackend.listMessages(session) }
                            .onSuccess { messages ->
                                streamedParts.clear()
                                _uiState.update {
                                    it.copy(
                                        messages = messages.mapNotNull(::toUiMessage),
                                    )
                                }
                            }
                    }
                    refreshPendingQuestions(session)
                }
                drainOfflineQueue()
            }
            is OpenCodeEvent.MessageUpdated -> {
                if (event.info.sessionId != activeSession) return
                messageRoles[event.info.id] = event.info.role
            }
            is OpenCodeEvent.MessagePartUpdated -> {
                val part = event.part
                if (part.sessionId != activeSession) return
                val messageId = part.messageId ?: part.id ?: return
                // The server echoes the user's own prompt back as parts too; rendering those
                // would duplicate the bubble the composer already added.
                if (messageRoles[messageId] == "user") return
                val partId = part.id ?: messageId
                val chatPart = part.toChatPart() ?: return
                val messageParts = streamedParts.getOrPut(messageId) { linkedMapOf() }
                messageParts[partId] = chatPart
                updateStreamingMessage(messageId, messageParts.values.toList())
            }
            is OpenCodeEvent.MessagePartDelta -> {
                if (event.sessionId != activeSession || event.field != "text") return
                connectionMonitor.recordStreamToken()
                val messageParts = streamedParts.getOrPut(event.messageId) { linkedMapOf() }
                val updatedPart =
                    when (val existing = messageParts[event.partId]) {
                        is ChatPart.Text -> existing.copy(text = existing.text + event.delta)
                        is ChatPart.Reasoning -> existing.copy(text = existing.text + event.delta)
                        // A delta can outrun the part event that introduces it; start the part here
                        // rather than dropping the text on the floor.
                        null -> ChatPart.Text(event.partId, event.delta)
                        else -> return
                    }
                messageParts[event.partId] = updatedPart
                updateStreamingMessage(event.messageId, messageParts.values.toList())
            }
            is OpenCodeEvent.PermissionAsked -> {
                if (_uiState.value.autoAcceptPermissions) {
                    val request = event.request
                    val autoBackend = backend ?: return
                    viewModelScope.launch {
                        runCatching {
                            autoBackend.respondToPermission(
                                request.sessionId,
                                request.id,
                                PermissionResponse.ONCE,
                                false,
                            )
                        }.onSuccess { accepted ->
                            if (accepted) onPermissionResolved(request.id)
                        }
                    }
                    return
                }
                if (event.request.sessionId != activeSession) return
                _uiState.update { state ->
                    state.copy(
                        permissions = state.permissions.filterNot { it.id == event.request.id } + event.request,
                        isThinking = false,
                    )
                }
            }
            is OpenCodeEvent.QuestionAsked -> {
                if (event.request.sessionId != activeSession) return
                _uiState.update { state ->
                    state.copy(
                        pendingQuestions =
                            state.pendingQuestions
                                .filterNot { it.request.id == event.request.id } + PendingQuestionUi.from(event.request),
                        isThinking = false,
                    )
                }
            }
            is OpenCodeEvent.PermissionReplied -> {
                // Answered elsewhere — another device, or the TUI. Drop the stale card.
                if (event.sessionId != activeSession) return
                _uiState.update { state ->
                    state.copy(permissions = state.permissions.filterNot { it.id == event.requestId })
                }
            }
            is OpenCodeEvent.SessionStatusChanged -> {
                // session.idle is deprecated in favour of session.status; treat idle the same way,
                // and let a busy status pick up a run that was started from another client.
                if (event.sessionId != activeSession) return
                when (event.status) {
                    "idle" -> handleSessionIdle(event.sessionId)
                    "busy", "retry" ->
                        if (!_uiState.value.isRunning) {
                            _uiState.update { it.copy(isRunning = true) }
                        }
                }
            }
            is OpenCodeEvent.SessionIdle -> {
                if (event.sessionId != activeSession) return
                handleSessionIdle(event.sessionId)
            }
            is OpenCodeEvent.SessionError -> {
                if (event.sessionId != null && event.sessionId != activeSession) return
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        isThinking = false,
                        error = event.message ?: "OpenCode session failed",
                    )
                }
            }
            is OpenCodeEvent.Unknown -> Unit
        }
    }

    private fun handleSessionIdle(sessionId: String) {
        streamedParts.clear()
        _uiState.update { state ->
            state.copy(
                messages =
                    state.messages.map { message ->
                        if (message.isStreaming) message.copy(isStreaming = false) else message
                    },
                isRunning = false,
                isThinking = false,
            )
        }
        refreshContextUsage(sessionId)
        refreshMessages(sessionId)
        onSessionCreated()
        drainQueue()
    }

    private fun refreshMessages(sessionId: String) {
        val currentBackend = backend ?: return
        viewModelScope.launch {
            runCatching { currentBackend.listMessages(sessionId) }
                .onSuccess { messages ->
                    val uiMessages = messages.mapNotNull(::toUiMessage)
                    if (uiMessages.isNotEmpty()) {
                        _uiState.update { it.copy(messages = uiMessages) }
                    }
                }
        }
    }

    private fun updateStreamingMessage(
        messageId: String,
        parts: List<ChatPart>,
    ) {
        _uiState.update { state ->
            val index = state.messages.indexOfFirst { it.id == messageId }
            if (index >= 0) {
                val existing = state.messages[index]
                val updated =
                    state.messages.toMutableList().apply {
                        this[index] =
                            existing.copy(
                                parts = parts,
                                isStreaming = !existing.isUser,
                            )
                    }
                return@update state.copy(messages = updated, isRunning = true, isThinking = false)
            }

            val incomingText = parts.filterIsInstance<ChatPart.Text>().joinToString("") { it.text }
            val userEchoIndex = state.messages.indexOfLast { it.isUser && it.text == incomingText }
            if (incomingText.isNotBlank() && userEchoIndex >= 0) {
                val updated =
                    state.messages.toMutableList().apply {
                        this[userEchoIndex] = this[userEchoIndex].copy(id = messageId)
                    }
                return@update state.copy(messages = updated)
            }

            state.copy(
                messages =
                    state.messages +
                        ChatMessage(
                            id = messageId,
                            isUser = false,
                            parts = parts,
                            isStreaming = true,
                        ),
                isRunning = true,
                isThinking = false,
            )
        }
    }

    private fun toUiMessage(message: OpenCodeMessage): ChatMessage? {
        messageRoles[message.info.id] = message.info.role
        val parts = message.parts.mapNotNull { it.toChatPart() }
        val attachments =
            message.parts.mapNotNull { part ->
                if (part.type != "file") return@mapNotNull null
                PromptAttachment(
                    filename = part.filename ?: "attachment",
                    mime = part.mime ?: "application/octet-stream",
                    url = part.url ?: "",
                )
            }
        if (parts.isEmpty() && attachments.isEmpty()) return null
        return ChatMessage(
            id = message.info.id,
            isUser = message.info.role == "user",
            parts = parts,
            attachments = attachments,
            timestamp = message.info.time.created,
            completedAt = message.info.time.completed,
            isStreaming = false,
        )
    }

    fun setTTS(textToSpeech: TextToSpeech) {
        tts = textToSpeech
    }

    fun startListening() {
        _uiState.update {
            it.copy(
                isListening = true,
                isSpeechProcessing = false,
                partialText = "",
                error = null,
            )
        }
    }

    fun updateSpeechPartial(text: String) {
        _uiState.update {
            it.copy(
                isListening = true,
                isSpeechProcessing = false,
                partialText = text,
            )
        }
    }

    fun showSpeechProcessing() {
        _uiState.update {
            it.copy(
                isListening = false,
                isSpeechProcessing = true,
                partialText = it.partialText,
            )
        }
    }

    fun reportSpeechError(message: String) {
        _uiState.update {
            it.copy(
                isListening = false,
                isSpeechProcessing = false,
                error = message,
            )
        }
    }

    fun stopListening() {
        _uiState.update {
            it.copy(
                isListening = false,
                isSpeechProcessing = false,
                partialText = it.partialText,
            )
        }
    }

    fun stopSpeaking() {
        tts?.stop()
        _uiState.update { it.copy(isSpeaking = false) }
    }

    fun copyMessageContent(messageId: String): String? {
        return _uiState.value.messages.firstOrNull { it.id == messageId }?.text
    }

    private fun drainQueue() {
        val queued = messageQueue.value
        if (queued.isEmpty()) return
        messageQueue.value = emptyList()
        queued.forEach { sendMessage(it) }
    }

    private fun drainOfflineQueue() {
        val queued = offlineMessageQueue.value
        if (queued.isEmpty()) return
        offlineMessageQueue.value = emptyList()
        _uiState.update { it.copy(offlineQueue = emptyList(), isOfflineQueued = false) }
        queued.forEach { sendMessage(it) }
    }

    override fun onCleared() {
        _uiState.value.imagePreviews.forEach { if (!it.isRecycled) it.recycle() }
        _uiState.value.messages.forEach { msg ->
            msg.imagePreviews.forEach { if (!it.isRecycled) it.recycle() }
        }
        eventJob?.cancel()
        tts?.stop()
        tts = null
        super.onCleared()
    }

    private fun reportError(throwable: Throwable) {
        _uiState.update { it.copy(error = throwable.safeMessage("OpenCode operation failed")) }
        if (classifyChatError(throwable) == ChatErrorKind.TRANSIENT_CONNECTION) {
            scheduleTransientRecovery()
        }
    }

    private fun reportError(message: String?) {
        _uiState.update { it.copy(error = message) }
        if (classifyChatError(message) == ChatErrorKind.TRANSIENT_CONNECTION) {
            scheduleTransientRecovery()
        }
    }

    private fun scheduleTransientRecovery() {
        viewModelScope.launch {
            delay(TRANSIENT_RECOVERY_DELAY_MS)
            val currentBackend = backend ?: return@launch
            if (classifyChatError(_uiState.value.error) != ChatErrorKind.TRANSIENT_CONNECTION) return@launch
            runCatching { currentBackend.health() }
                .onSuccess { health ->
                    if (health.healthy) {
                        val session = _uiState.value.sessionId
                        if (session != null) {
                            runCatching { currentBackend.listMessages(session) }
                                .onSuccess { messages ->
                                    streamedParts.clear()
                                    _uiState.update {
                                        it.copy(
                                            messages = messages.mapNotNull(::toUiMessage),
                                            error = null,
                                            isConnected = true,
                                        )
                                    }
                                }
                                .onFailure { _uiState.update { s -> s.copy(error = null) } }
                        } else {
                            _uiState.update { it.copy(error = null, isConnected = true) }
                        }
                        drainOfflineQueue()
                    }
                }
        }
    }
}

private fun updateQuestionAnswerSelection(
    prompt: QuestionPrompt,
    current: List<String>,
    answer: String,
    multiple: Boolean,
): List<String> {
    val normalized = answer.trim()
    val optionLabels = prompt.options.map { it.label }.toSet()
    if (prompt.options.isEmpty()) {
        return normalized.takeIf { it.isNotEmpty() }?.let(::listOf).orEmpty()
    }

    val optionAnswers = current.filter { it in optionLabels }
    val fallback = current.lastOrNull { it !in optionLabels }
    if (normalized in optionLabels) {
        return if (multiple) {
            val toggled =
                if (normalized in optionAnswers) {
                    optionAnswers.filterNot { it == normalized }
                } else {
                    optionAnswers + normalized
                }
            toggled + listOfNotNull(fallback?.takeIf { it.isNotBlank() })
        } else {
            listOf(normalized)
        }
    }

    val nextFallback = normalized.takeIf { it.isNotEmpty() }
    return if (multiple) {
        optionAnswers + listOfNotNull(nextFallback)
    } else {
        listOfNotNull(nextFallback)
    }
}

private fun sanitizeQuestionAnswer(
    prompt: QuestionPrompt,
    answers: List<String>,
    multiple: Boolean,
): List<String> {
    val normalizedAnswers = answers.map(String::trim).filter { it.isNotEmpty() }
    if (prompt.options.isEmpty()) {
        return normalizedAnswers.take(1)
    }

    val optionLabels = prompt.options.map { it.label }.toSet()
    val selectedOptions = normalizedAnswers.filter { it in optionLabels }.distinct()
    // A prompt with `custom` off only accepts its own options, so never submit a typed answer.
    val fallback = normalizedAnswers.lastOrNull { it !in optionLabels }?.takeIf { prompt.custom }
    return if (multiple) {
        selectedOptions + listOfNotNull(fallback)
    } else {
        selectedOptions.firstOrNull()?.let(::listOf)
            ?: fallback?.let(::listOf)
            ?: emptyList()
    }
}
