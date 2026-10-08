package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodePart
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.core.api.PermissionRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Translates Codex `app-server` thread/turn/item notifications (JSON-RPC v2 "app-server protocol")
 * into this app's [OpenCodeEvent]/[OpenCodeMessage] model.
 *
 * Verified against the real protocol schema (`codex app-server generate-json-schema`, 0.155.1) and
 * against a live, unauthenticated `codex app-server` process for the shapes that do not require an
 * API key: `thread/start`, `turn/start`, `item/started`/`item/completed` for a `userMessage`, and the
 * `error` notification's retry shape. See docs/CODEX.md for the exact transcripts.
 *
 * Codex's turn is not one message the way Claude Code's is: a single turn can carry several items
 * (reasoning, a shell command, an `agentMessage`) that this app renders as one assistant bubble with
 * several parts, the same grouping [ClaudeStreamJsonParser] uses for a Claude Code turn's tool calls
 * and text. A `userMessage` item is its own message instead, keyed by the item's own id.
 */
class CodexItemParser {
    data class Parsed(
        val events: List<OpenCodeEvent> = emptyList(),
        val messages: List<OpenCodeMessage> = emptyList(),
    )

    /**
     * Assistant messages assembled so far, keyed by turn id, so a later item in the same turn merges
     * in. Evicted once the turn ends (see [forgetTurn]): the persisted truth for a finished turn is
     * Codex's own `thread/items/list`, so nothing here needs to outlive the turn it was built for.
     */
    private val assistantMessages = linkedMapOf<String, OpenCodeMessage>()

    fun handleNotification(
        sessionId: String,
        method: String,
        params: JsonObject?,
    ): Parsed {
        val body = params ?: return Parsed()
        return when (method) {
            "item/started", "item/completed" -> handleItem(sessionId, body)
            "item/agentMessage/delta" -> handleAgentMessageDelta(sessionId, body)
            "turn/completed" -> handleTurnCompleted(sessionId, body)
            "turn/started" -> Parsed(events = listOf(OpenCodeEvent.SessionStatusChanged(sessionId, "busy")))
            "error" -> handleError(sessionId, body)
            else -> Parsed()
        }
    }

    private fun handleTurnCompleted(
        sessionId: String,
        body: JsonObject,
    ): Parsed {
        // TurnCompletedNotification's schema carries a full `turn` object (not a flat `turnId` the
        // way item/error notifications do) - confirmed against `codex app-server
        // generate-json-schema`'s TurnCompletedNotification definition, see docs/CODEX.md.
        body["turn"]?.jsonObject?.string("id")?.let(::forgetTurn)
        return Parsed(events = listOf(OpenCodeEvent.SessionIdle(sessionId)))
    }

    /** Drops the in-progress assistant message this parser was assembling for [turnId], if any. */
    fun forgetTurn(turnId: String) {
        assistantMessages.remove(assistantMessageId(turnId))
    }

    private fun handleAgentMessageDelta(
        sessionId: String,
        body: JsonObject,
    ): Parsed {
        val turnId = body.string("turnId") ?: return Parsed()
        // AgentMessageDeltaNotification's schema marks itemId required (no null in its type), so a
        // missing one means a shape this parser does not recognize - dropped rather than defaulted
        // to turnId, which would key this delta's streamed part differently from the "$id-text" id
        // itemToPart's agentMessage case builds once the item itself completes, leaving two parts
        // (stale streamed text plus the final text) that never coalesce into one.
        val itemId = body.string("itemId") ?: return Parsed()
        val delta = body.string("delta") ?: return Parsed()
        val messageId = assistantMessageId(turnId)
        return Parsed(events = listOf(OpenCodeEvent.MessagePartDelta(sessionId, messageId, "$itemId-text", "text", delta)))
    }

    private fun handleError(
        sessionId: String,
        body: JsonObject,
    ): Parsed {
        val error = body["error"]?.jsonObject
        val message = error?.string("message") ?: "Codex reported an error"
        val willRetry = (body["willRetry"] as? JsonPrimitive)?.content == "true"
        // A retryable transport hiccup ("Reconnecting... 2/5") is not the end of the turn - only
        // report it, and let a later `turn/completed` (success) or a non-retrying `error` (failure)
        // decide whether the chat goes idle. Ending the turn here on every reconnect attempt would
        // flip a still-running turn back to idle mid-retry.
        if (willRetry) return Parsed(events = listOf(OpenCodeEvent.SessionError(sessionId, message)))
        body.string("turnId")?.let(::forgetTurn)
        return Parsed(events = listOf(OpenCodeEvent.SessionError(sessionId, message), OpenCodeEvent.SessionIdle(sessionId)))
    }

    private fun handleItem(
        sessionId: String,
        body: JsonObject,
    ): Parsed {
        val item = body["item"]?.jsonObject ?: return Parsed()
        val turnId = body.string("turnId") ?: return Parsed()
        return when (item.string("type")) {
            "userMessage" -> handleUserMessage(sessionId, item)
            else -> handleAssistantItem(sessionId, turnId, item)
        }
    }

    private fun handleUserMessage(
        sessionId: String,
        item: JsonObject,
    ): Parsed {
        val id = item.string("id") ?: return Parsed()
        val content = item["content"] as? JsonArray
        val text = content?.joinToString("") { (it as? JsonObject)?.string("text").orEmpty() }.orEmpty()
        val textPart = OpenCodePart(id = "$id-text", sessionId = sessionId, messageId = id, type = "text", text = text)
        val parts =
            buildList {
                add(textPart)
                content?.forEachIndexed { index, element ->
                    val contentItem = element as? JsonObject ?: return@forEachIndexed
                    userInputImagePart(id, sessionId, index, contentItem)?.let(::add)
                }
            }
        val message =
            OpenCodeMessage(
                info = OpenCodeMessageInfo(id = id, sessionId = sessionId, role = "user", time = now()),
                parts = parts,
            )
        // Only the text part is emitted as a live event. ChatViewModel reconciles Codex's echoed
        // userMessage into the optimistic bubble the composer already added by matching its text, so
        // a live image part carries no text to match: on an attachment-only send (blank text) it
        // would surface as a second, streamed message's image until the next reload. The file parts
        // still ride in `messages`, which is what the store persists and a reload reads back.
        return Parsed(events = listOf(OpenCodeEvent.MessagePartUpdated(textPart)), messages = listOf(message))
    }

    /**
     * The `file` part for an image in a `userMessage` item's `content`, so an image the user attached
     * shows in the echoed transcript (and survives a reload) instead of being dropped because it
     * carries no `text`. The app sends images as `{"type":"image","url":"data:..."}` (see
     * `codexTurnInput`); a `localImage` path is resolved by the chat the same way
     * [imageGenerationPart]'s `savedPath` is. Any other content type (text, audio, skill, mention)
     * has no image to render and is ignored.
     */
    private fun userInputImagePart(
        messageId: String,
        sessionId: String,
        index: Int,
        contentItem: JsonObject,
    ): OpenCodePart? {
        val mime: String
        val url: String
        when (contentItem.string("type")) {
            "image" -> {
                url = contentItem.string("url")?.takeIf(String::isNotBlank) ?: return null
                mime =
                    url.substringAfter("data:", "").substringBefore(';')
                        .takeIf { it.startsWith("image/") }
                        ?: "image/png"
            }
            "localImage" -> {
                url = contentItem.string("path")?.takeIf(String::isNotBlank) ?: return null
                mime = imageMimeForPath(url)
            }
            else -> return null
        }
        val extension =
            when (mime) {
                "image/jpeg" -> "jpg"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                else -> "png"
            }
        return OpenCodePart(
            id = "$messageId-file-$index",
            sessionId = sessionId,
            messageId = messageId,
            type = "file",
            mime = mime,
            url = url,
            filename = "attachment-$index.$extension",
        )
    }

    private fun handleAssistantItem(
        sessionId: String,
        turnId: String,
        item: JsonObject,
    ): Parsed {
        val messageId = assistantMessageId(turnId)
        val part = itemToPart(sessionId, messageId, item) ?: return Parsed()
        val existing = assistantMessages[messageId]
        val mergedParts =
            if (existing != null) {
                val byId = linkedMapOf<String?, OpenCodePart>()
                existing.parts.forEach { byId[it.id] = it }
                byId[part.id] = part
                byId.values.toList()
            } else {
                listOf(part)
            }
        val message =
            OpenCodeMessage(
                info =
                    existing?.info ?: OpenCodeMessageInfo(
                        id = messageId,
                        sessionId = sessionId,
                        role = "assistant",
                        time = now(),
                        agent = "codex",
                    ),
                parts = mergedParts,
            )
        assistantMessages[messageId] = message
        return Parsed(events = listOf(OpenCodeEvent.MessagePartUpdated(part)), messages = listOf(message))
    }

    /**
     * One [OpenCodePart] per item type this app already has a part shape for.
     *
     * `commandExecution`/`fileChange`/`mcpToolCall` reuse the "tool" part shape
     * [ClaudeStreamJsonParser] already renders (`state.status`/`input`/`output`/`error`), so the
     * existing tool-call UI needs no Codex-specific branch. Item types this app cannot yet render
     * meaningfully (`webSearch`, `subAgentActivity`, ...) fall through to a plain
     * "tool" part carrying the raw item JSON, rather than disappearing silently.
     */
    private fun itemToPart(
        sessionId: String,
        messageId: String,
        item: JsonObject,
    ): OpenCodePart? {
        val id = item.string("id") ?: return null
        return when (item.string("type")) {
            "agentMessage" ->
                OpenCodePart(
                    id = "$id-text",
                    sessionId = sessionId,
                    messageId = messageId,
                    type = "text",
                    text = item.string("text").orEmpty(),
                )
            "reasoning" ->
                OpenCodePart(
                    id = "$id-reasoning",
                    sessionId = sessionId,
                    messageId = messageId,
                    type = "reasoning",
                    text = reasoningText(item),
                )
            "plan" ->
                OpenCodePart(
                    id = "$id-plan",
                    sessionId = sessionId,
                    messageId = messageId,
                    type = "text",
                    text = item.string("text").orEmpty(),
                )
            "commandExecution" ->
                OpenCodePart(
                    id = id,
                    sessionId = sessionId,
                    messageId = messageId,
                    type = "tool",
                    tool = "bash",
                    callID = id,
                    state =
                        mapOf(
                            "status" to JsonPrimitive(mapCommandStatus(item.string("status"))),
                            "input" to JsonPrimitive(item.string("command").orEmpty()),
                            "output" to JsonPrimitive(item.string("aggregatedOutput").orEmpty()),
                        ),
                )
            "fileChange" ->
                OpenCodePart(
                    id = id,
                    sessionId = sessionId,
                    messageId = messageId,
                    type = "tool",
                    tool = "patch",
                    callID = id,
                    state =
                        mapOf(
                            "status" to JsonPrimitive(mapCommandStatus(item.string("status"))),
                            "input" to (item["changes"] ?: JsonObject(emptyMap())),
                        ),
                )
            "mcpToolCall" ->
                OpenCodePart(
                    id = id,
                    sessionId = sessionId,
                    messageId = messageId,
                    type = "tool",
                    tool = item.string("tool") ?: "mcp",
                    callID = id,
                    state =
                        mapOf(
                            "status" to JsonPrimitive(mapCommandStatus(item.string("status"))),
                            "input" to (item["arguments"] ?: JsonObject(emptyMap())),
                            "output" to (item["result"] ?: JsonPrimitive("")),
                        ),
                )
            "imageGeneration" -> imageGenerationPart(id, sessionId, messageId, item)
            // userMessage is routed to handleUserMessage before this is reached; every other item
            // type (webSearch, subAgentActivity, dynamicToolCall,
            // collabAgentToolCall, sleep, imageView, functionCallOutput, contextCompaction,
            // hookPrompt, enteredReviewMode, exitedReviewMode) has no dedicated rendering yet - carry
            // it as a generic tool part with the raw item JSON rather than dropping it.
            else ->
                OpenCodePart(
                    id = id,
                    sessionId = sessionId,
                    messageId = messageId,
                    type = "tool",
                    tool = item.string("type") ?: "codex",
                    callID = id,
                    state = mapOf("status" to JsonPrimitive("completed"), "input" to item),
                )
        }
    }

    /**
     * A generated image, shown in the chat the way other agents' generated images are: a `file` part
     * with an image MIME type, which the chat renders from a data URI or from a guest path it resolves
     * into the rootfs (so `/root/.codex/generated_images/...` works). The saved file is preferred -
     * `result` is the whole PNG as base64 (megabytes), which the transcript would otherwise carry.
     *
     * Until the image exists (in progress, or failed) it is a tool part carrying only the prompt, under
     * the same id, so the finished image replaces it in place. Before this mapping the item fell to the
     * generic branch, which put the raw item - base64 included - in a tool part and showed no image.
     */
    private fun imageGenerationPart(
        id: String,
        sessionId: String,
        messageId: String,
        item: JsonObject,
    ): OpenCodePart {
        val savedPath = item.string("savedPath")?.takeIf(String::isNotBlank)
        val result = item.string("result")?.takeIf(String::isNotBlank)
        if (item.string("status") == "completed" && (savedPath != null || result != null)) {
            val mime = savedPath?.let(::imageMimeForPath) ?: "image/png"
            return OpenCodePart(
                id = id,
                sessionId = sessionId,
                messageId = messageId,
                type = "file",
                mime = mime,
                url = savedPath ?: "data:$mime;base64,$result",
                filename = savedPath?.substringAfterLast('/') ?: "$id.png",
            )
        }
        return OpenCodePart(
            id = id,
            sessionId = sessionId,
            messageId = messageId,
            type = "tool",
            tool = "image_gen",
            callID = id,
            state =
                mapOf(
                    "status" to JsonPrimitive(mapCommandStatus(item.string("status"))),
                    "input" to JsonPrimitive(item.string("revisedPrompt").orEmpty()),
                ),
        )
    }

    private fun imageMimeForPath(path: String): String =
        when (path.substringAfterLast('.').lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/png"
        }

    private fun reasoningText(item: JsonObject): String {
        val summary = item["summary"] as? JsonArray
        if (summary != null) {
            return summary.joinToString("\n") { element ->
                when (element) {
                    is JsonObject -> element.string("text").orEmpty()
                    is JsonPrimitive -> element.contentOrNull.orEmpty()
                    else -> ""
                }
            }
        }
        return item.string("content").orEmpty()
    }

    /**
     * Normalizes to exactly the values [ClaudeMessageStore.settleRunningTools] understands
     * ("running"/"pending" mean in-flight, anything else is terminal): the real in-progress status
     * string is unconfirmed (see docs/CODEX.md - these item shapes come from the schema, not a live
     * signed-in run), so an unrecognized non-completed/non-error value is treated as still running
     * rather than passed through - passing it through verbatim would leave the tool part stuck
     * showing that raw status forever if the app-server dies mid-command, since settling only
     * rewrites a part whose status is exactly "running" or "pending".
     */
    private fun mapCommandStatus(status: String?): String =
        when (status) {
            "completed" -> "completed"
            "failed", "error" -> "error"
            else -> "running"
        }

    private fun assistantMessageId(turnId: String): String = "codex-turn-$turnId"

    private fun now(): OpenCodeTime {
        val timestamp = System.currentTimeMillis()
        return OpenCodeTime(timestamp, timestamp)
    }

    companion object {
        /** Builds the [PermissionRequest] this app's permission UI shows for a command-execution approval. */
        fun commandApprovalToPermissionRequest(
            sessionId: String,
            requestId: String,
            params: JsonObject,
        ): PermissionRequest =
            PermissionRequest(
                id = requestId,
                sessionId = sessionId,
                permission = "bash",
                patterns = listOfNotNull(params.string("command")),
                metadata = params,
            )

        /** Builds the [PermissionRequest] this app's permission UI shows for a file-change approval. */
        fun fileChangeApprovalToPermissionRequest(
            sessionId: String,
            requestId: String,
            params: JsonObject,
        ): PermissionRequest =
            PermissionRequest(
                id = requestId,
                sessionId = sessionId,
                permission = "edit",
                metadata = params,
            )

        internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
