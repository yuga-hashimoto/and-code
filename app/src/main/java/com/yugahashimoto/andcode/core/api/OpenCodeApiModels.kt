package com.yugahashimoto.andcode.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class OpenCodeHealth(
    val healthy: Boolean,
    val version: String,
)

@Serializable
data class OpenCodeTime(
    val created: Long = 0L,
    val updated: Long? = null,
    val completed: Long? = null,
)

@Serializable
data class OpenCodeSession(
    val id: String,
    val slug: String? = null,
    @SerialName("projectID") val projectId: String? = null,
    @SerialName("parentID") val parentId: String? = null,
    val directory: String? = null,
    val path: String? = null,
    val title: String = "",
    val version: String? = null,
    val time: OpenCodeTime = OpenCodeTime(),
    val tokens: OpenCodeSessionTokens? = null,
    val share: OpenCodeSessionShare? = null,
)

@Serializable
data class OpenCodeSessionShare(
    val url: String,
)

@Serializable
data class OpenCodeSessionTokens(
    val input: Long = 0L,
    val output: Long = 0L,
    val reasoning: Long = 0L,
    val cache: OpenCodeCacheTokens? = null,
) {
    val contextUsed: Long
        get() = input + (cache?.read ?: 0L)
}

@Serializable
data class OpenCodeCacheTokens(
    val read: Long = 0L,
    val write: Long = 0L,
)

@Serializable
data class OpenCodeModelReference(
    @SerialName("providerID") val providerId: String,
    @SerialName("modelID") val modelId: String,
)

@Serializable
data class OpenCodeMessageInfo(
    val id: String,
    @SerialName("sessionID") val sessionId: String,
    val role: String,
    val time: OpenCodeTime = OpenCodeTime(),
    val agent: String? = null,
    val model: OpenCodeModelReference? = null,
    val tokens: OpenCodeSessionTokens? = null,
    /** Provider/session failure recorded on the message, e.g. `ApiError` with `statusCode` 429. */
    val error: OpenCodeMessageError? = null,
)

/** A typed message error, e.g. `ApiError`; rendered in the chat when a turn fails. */
@Serializable
data class OpenCodeMessageError(
    val name: String? = null,
    val data: Map<String, JsonElement>? = null,
) {
    val message: String?
        get() =
            data?.get("message")
                ?.takeIf { it is JsonPrimitive }
                ?.let { (it as JsonPrimitive).content }
                ?.takeIf { it.isNotBlank() }

    /** OpenCode records a stop the user asked for as an error; it is not a failure to report. */
    val isAbort: Boolean
        get() = name == "MessageAbortedError" || name == "AbortError"
}

@Serializable
data class OpenCodePart(
    val id: String? = null,
    @SerialName("sessionID") val sessionId: String? = null,
    @SerialName("messageID") val messageId: String? = null,
    val type: String,
    val text: String? = null,
    val filename: String? = null,
    val mime: String? = null,
    val url: String? = null,
    val tool: String? = null,
    val callID: String? = null,
    val state: Map<String, JsonElement>? = null,
)

@Serializable
data class OpenCodeMessage(
    val info: OpenCodeMessageInfo,
    val parts: List<OpenCodePart> = emptyList(),
) {
    val text: String
        get() = parts.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("")
}

@Serializable
data class OpenCodeModel(
    val id: String,
    @SerialName("providerID") val providerId: String? = null,
    val name: String = id,
    val status: String? = null,
    val limit: OpenCodeModelLimit? = null,
    val variants: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class OpenCodeModelLimit(
    val context: Long = 0L,
    val output: Long = 0L,
)

@Serializable
data class OpenCodeProvider(
    val id: String,
    val name: String = id,
    val models: Map<String, OpenCodeModel> = emptyMap(),
)

@Serializable
data class ProviderCatalog(
    val all: List<OpenCodeProvider> = emptyList(),
    val default: Map<String, String> = emptyMap(),
    val connected: List<String> = emptyList(),
)

@Serializable
data class OpenCodeAgent(
    val name: String,
    val description: String? = null,
    val mode: String? = null,
    val native: Boolean = false,
)

@Serializable
data class OpenCodeProject(
    val id: String,
    val worktree: String,
    val name: String? = null,
)

@Serializable
data class OpenCodePathInfo(
    val home: String,
    val state: String,
    val config: String,
    val worktree: String,
    val directory: String,
)

@Serializable
data class OpenCodeFileNode(
    val name: String,
    val path: String,
    val absolute: String,
    val type: String,
    val ignored: Boolean = false,
)

@Serializable
data class OpenCodePatchHunk(
    val oldStart: Int,
    val oldLines: Int,
    val newStart: Int,
    val newLines: Int,
    val lines: List<String> = emptyList(),
)

@Serializable
data class OpenCodeFilePatch(
    val oldFileName: String,
    val newFileName: String,
    val oldHeader: String? = null,
    val newHeader: String? = null,
    val hunks: List<OpenCodePatchHunk> = emptyList(),
    val index: String? = null,
)

@Serializable
data class OpenCodeFileContent(
    val type: String,
    val content: String,
    val diff: String? = null,
    val patch: OpenCodeFilePatch? = null,
    val encoding: String? = null,
    val mimeType: String? = null,
)

@Serializable
data class OpenCodeSearchText(
    val text: String,
)

@Serializable
data class OpenCodeSearchSubmatch(
    val match: OpenCodeSearchText,
    val start: Int,
    val end: Int,
)

@Serializable
data class OpenCodeSearchMatch(
    val path: OpenCodeSearchText,
    val lines: OpenCodeSearchText,
    @SerialName("line_number") val lineNumber: Int,
    @SerialName("absolute_offset") val absoluteOffset: Int,
    val submatches: List<OpenCodeSearchSubmatch> = emptyList(),
)

@Serializable
data class OpenCodeFileChange(
    val file: String? = null,
    val path: String? = null,
    val patch: String? = null,
    val additions: Double = 0.0,
    val deletions: Double = 0.0,
    val added: Int = 0,
    val removed: Int = 0,
    val status: String? = null,
) {
    val displayPath: String
        get() = file ?: path.orEmpty()
}

@Serializable
data class OpenCodeTodo(
    val content: String,
    val status: String,
    val priority: String,
)

@Serializable
data class OpenCodeVcsInfo(
    val branch: String? = null,
    @SerialName("default_branch") val defaultBranch: String? = null,
)

@Serializable
data class PromptRequest(
    val text: String,
    val providerId: String? = null,
    val modelId: String? = null,
    val agent: String? = null,
    val variant: String? = null,
    val attachments: List<PromptAttachment> = emptyList(),
    val noReply: Boolean = false,
)

@Serializable
data class PromptAttachment(
    val filename: String,
    val mime: String,
    val url: String,
)

@Serializable
data class PermissionRequest(
    val id: String,
    val sessionId: String,
    val permission: String,
    val patterns: List<String> = emptyList(),
    val metadata: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class QuestionOption(
    val label: String,
    val description: String? = null,
)

@Serializable
data class QuestionPrompt(
    val question: String,
    val header: String? = null,
    val options: List<QuestionOption> = emptyList(),
    val placeholder: String? = null,
    /** Whether more than one of [options] may be selected. OpenCode sets this per prompt. */
    val multiple: Boolean = false,
    /** Whether an answer outside [options] is accepted. OpenCode defaults this to true. */
    val custom: Boolean = true,
)

@Serializable
data class QuestionRequest(
    val id: String,
    @SerialName("sessionID") val sessionId: String,
    val questions: List<QuestionPrompt>,
    /**
     * Workspace the request belongs to. The question routes are scoped to a single OpenCode
     * instance, so replying needs the directory the question was asked in — it is not carried
     * by the request itself, but by the `/global/event` envelope that delivered it.
     */
    val directory: String? = null,
)

sealed interface OpenCodeEvent {
    data object ServerConnected : OpenCodeEvent

    data class MessageUpdated(val info: OpenCodeMessageInfo) : OpenCodeEvent

    data class MessagePartUpdated(val part: OpenCodePart) : OpenCodeEvent

    data class MessagePartDelta(
        val sessionId: String,
        val messageId: String,
        val partId: String,
        val field: String,
        val delta: String,
    ) : OpenCodeEvent

    data class PermissionAsked(val request: PermissionRequest) : OpenCodeEvent

    data class PermissionReplied(val sessionId: String, val requestId: String) : OpenCodeEvent

    data class QuestionAsked(val request: QuestionRequest) : OpenCodeEvent

    data class SessionIdle(val sessionId: String) : OpenCodeEvent

    /** A new session appeared. Its [OpenCodeSession.parentId] names the session that spawned it. */
    data class SessionCreated(val session: OpenCodeSession) : OpenCodeEvent

    /** A session's metadata changed; carries the same payload as [SessionCreated]. */
    data class SessionUpdated(val session: OpenCodeSession) : OpenCodeEvent

    /** Replacement for the deprecated `session.idle`: status is `idle`, `busy` or `retry`. */
    data class SessionStatusChanged(val sessionId: String, val status: String) : OpenCodeEvent

    data class SessionError(val sessionId: String?, val message: String?) : OpenCodeEvent

    data class Unknown(val type: String, val rawJson: String) : OpenCodeEvent
}

/**
 * The session an event concerns, or null for the events that are about the server rather than one
 * conversation. Used to tell which run an event proves is still alive.
 */
fun OpenCodeEvent.sessionIdOrNull(): String? =
    when (this) {
        is OpenCodeEvent.MessageUpdated -> info.sessionId
        is OpenCodeEvent.MessagePartUpdated -> part.sessionId
        is OpenCodeEvent.MessagePartDelta -> sessionId
        is OpenCodeEvent.PermissionAsked -> request.sessionId
        is OpenCodeEvent.PermissionReplied -> sessionId
        is OpenCodeEvent.QuestionAsked -> request.sessionId
        is OpenCodeEvent.SessionIdle -> sessionId
        is OpenCodeEvent.SessionCreated -> session.id
        is OpenCodeEvent.SessionUpdated -> session.id
        is OpenCodeEvent.SessionStatusChanged -> sessionId
        is OpenCodeEvent.SessionError -> sessionId
        OpenCodeEvent.ServerConnected -> null
        is OpenCodeEvent.Unknown -> null
    }

@Serializable
data class McpServer(
    val name: String,
    val status: String? = null,
    val type: String? = null,
    val command: String? = null,
    val url: String? = null,
    val tools: List<String> = emptyList(),
    val error: String? = null,
)

@Serializable
data class McpAuthStart(
    val authorizationUrl: String,
    val oauthState: String,
)

@Serializable
data class McpAuthStatus(
    val status: String,
    val error: String? = null,
)

@Serializable
data class McpAuthRemoval(
    val success: Boolean,
)

@Serializable
data class OpenCodeConfig(
    val config: JsonElement? = null,
)

@Serializable
data class ConfiguredProvider(
    val id: String,
    val name: String = id,
    @SerialName("default_model") val defaultModel: String? = null,
    val connected: Boolean = false,
)

@Serializable
data class OpenCodeCommand(
    val name: String,
    val description: String? = null,
    val template: String? = null,
    val source: String? = null,
)

@Serializable
data class OpenCodeSkill(
    val name: String,
    val description: String? = null,
    val location: String? = null,
)

class OpenCodeApiException(
    val statusCode: Int,
    message: String,
) : Exception(message)
