package com.opencode.android.core.api

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

data class OpenCodeHealth(
    val healthy: Boolean,
    val version: String
)

data class OpenCodeTime(
    val created: Long = 0L,
    val updated: Long? = null,
    val completed: Long? = null
)

data class OpenCodeSession(
    val id: String,
    val slug: String? = null,
    @SerializedName("projectID") val projectId: String? = null,
    val directory: String? = null,
    val path: String? = null,
    val title: String = "",
    val version: String? = null,
    val time: OpenCodeTime = OpenCodeTime()
)

data class OpenCodeModelReference(
    @SerializedName("providerID") val providerId: String,
    @SerializedName("modelID") val modelId: String
)

data class OpenCodeMessageInfo(
    val id: String,
    @SerializedName("sessionID") val sessionId: String,
    val role: String,
    val time: OpenCodeTime = OpenCodeTime(),
    val agent: String? = null,
    val model: OpenCodeModelReference? = null
)

data class OpenCodePart(
    val id: String? = null,
    @SerializedName("sessionID") val sessionId: String? = null,
    @SerializedName("messageID") val messageId: String? = null,
    val type: String,
    val text: String? = null,
    val tool: String? = null,
    val callID: String? = null,
    val state: Map<String, Any?>? = null
)

data class OpenCodeMessage(
    val info: OpenCodeMessageInfo,
    val parts: List<OpenCodePart> = emptyList()
) {
    val text: String
        get() = parts.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("")
}

data class OpenCodeModel(
    val id: String,
    @SerializedName("providerID") val providerId: String? = null,
    val name: String = id,
    val status: String? = null
)

data class OpenCodeProvider(
    val id: String,
    val name: String = id,
    val models: Map<String, OpenCodeModel> = emptyMap()
)

data class ProviderCatalog(
    val all: List<OpenCodeProvider> = emptyList(),
    val default: Map<String, String> = emptyMap(),
    val connected: List<String> = emptyList()
)

data class OpenCodeAgent(
    val name: String,
    val description: String? = null,
    val mode: String? = null,
    val native: Boolean = false
)

data class OpenCodeProject(
    val id: String,
    val worktree: String,
    val name: String? = null
)

data class OpenCodePathInfo(
    val home: String,
    val state: String,
    val config: String,
    val worktree: String,
    val directory: String
)

data class OpenCodeFileNode(
    val name: String,
    val path: String,
    val absolute: String,
    val type: String,
    val ignored: Boolean = false
)

data class OpenCodePatchHunk(
    val oldStart: Int,
    val oldLines: Int,
    val newStart: Int,
    val newLines: Int,
    val lines: List<String> = emptyList()
)

data class OpenCodeFilePatch(
    val oldFileName: String,
    val newFileName: String,
    val oldHeader: String? = null,
    val newHeader: String? = null,
    val hunks: List<OpenCodePatchHunk> = emptyList(),
    val index: String? = null
)

data class OpenCodeFileContent(
    val type: String,
    val content: String,
    val diff: String? = null,
    val patch: OpenCodeFilePatch? = null,
    val encoding: String? = null,
    val mimeType: String? = null
)

data class OpenCodeSearchText(
    val text: String
)

data class OpenCodeSearchSubmatch(
    val match: OpenCodeSearchText,
    val start: Int,
    val end: Int
)

data class OpenCodeSearchMatch(
    val path: OpenCodeSearchText,
    val lines: OpenCodeSearchText,
    @SerializedName("line_number") val lineNumber: Int,
    @SerializedName("absolute_offset") val absoluteOffset: Int,
    val submatches: List<OpenCodeSearchSubmatch> = emptyList()
)

data class OpenCodeFileChange(
    val file: String? = null,
    val path: String? = null,
    val patch: String? = null,
    val additions: Double = 0.0,
    val deletions: Double = 0.0,
    val added: Int = 0,
    val removed: Int = 0,
    val status: String? = null
) {
    val displayPath: String
        get() = file ?: path.orEmpty()
}

data class OpenCodeTodo(
    val content: String,
    val status: String,
    val priority: String
)

data class OpenCodeVcsInfo(
    val branch: String? = null,
    @SerializedName("default_branch") val defaultBranch: String? = null
)

data class PromptRequest(
    val text: String,
    val providerId: String? = null,
    val modelId: String? = null,
    val agent: String? = null,
    val noReply: Boolean = false
)

data class PermissionRequest(
    val id: String,
    val sessionId: String,
    val permission: String,
    val patterns: List<String> = emptyList(),
    val metadata: Map<String, JsonElement> = emptyMap()
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
        val delta: String
    ) : OpenCodeEvent
    data class PermissionAsked(val request: PermissionRequest) : OpenCodeEvent
    data class PermissionReplied(val sessionId: String, val requestId: String) : OpenCodeEvent
    data class SessionIdle(val sessionId: String) : OpenCodeEvent

    /** Replacement for the deprecated `session.idle`: status is `idle`, `busy` or `retry`. */
    data class SessionStatusChanged(val sessionId: String, val status: String) : OpenCodeEvent
    data class SessionError(val sessionId: String?, val message: String?) : OpenCodeEvent
    data class Unknown(val type: String, val rawJson: String) : OpenCodeEvent
}

class OpenCodeApiException(
    val statusCode: Int,
    message: String
) : Exception(message)
