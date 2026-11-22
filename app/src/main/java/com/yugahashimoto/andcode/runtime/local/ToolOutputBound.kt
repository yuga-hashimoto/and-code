package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodePart
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * How many characters of one tool's output a local agent's history keeps.
 *
 * The chat only ever shows the last 4000 characters of a tool output, but the stores used to keep
 * all of it: one `cat` of a large file, a build log, or an MCP result carrying a base64 image sat
 * in the heap for as long as the history was loaded - and the history of every session is loaded
 * together - and was written out again on every flush. A few of those are enough to exhaust the
 * app's heap, which then surfaces as an `OutOfMemoryError` on whichever allocation fails next
 * (issue #424).
 *
 * This must stay above the chat's display limit: the chat decides whether to show "output
 * truncated" from the stored length, so a bound at or below it would hide that a tool printed more.
 * Keeping the tail rather than the head matches what the chat shows, and is where the result of a
 * command (a pull request URL, the failing test) usually is.
 */
internal const val MAX_STORED_TOOL_OUTPUT_CHARS = 16_000

/**
 * This message with the output of every tool call bounded to [maxChars], or this same instance
 * when nothing needed it - so callers can tell a message was left alone and pay nothing for it.
 */
internal fun OpenCodeMessage.withBoundedToolOutput(maxChars: Int = MAX_STORED_TOOL_OUTPUT_CHARS): OpenCodeMessage {
    var changed = false
    val bounded =
        parts.map { part ->
            part.withBoundedOutput(maxChars).also { if (it !== part) changed = true }
        }
    return if (changed) copy(parts = bounded) else this
}

private fun OpenCodePart.withBoundedOutput(maxChars: Int): OpenCodePart {
    if (type != "tool") return this
    val currentState = state ?: return this
    val output = currentState["output"] ?: return this
    return when {
        output is JsonPrimitive && output.isString -> {
            val content = output.content
            if (content.length <= maxChars) {
                this
            } else {
                copy(state = currentState + ("output" to JsonPrimitive(content.tail(maxChars))))
            }
        }
        // A structured result (a Codex MCP call's `{content: [...]}`) is never rendered - the chat
        // reads only a string output - so dropping it leaves the screen exactly as it was.
        output is JsonObject || output is JsonArray ->
            if (output.exceeds(maxChars)) copy(state = currentState - "output") else this
        else -> this
    }
}

/** The last [length] characters, without starting on the second half of a surrogate pair. */
private fun String.tail(length: Int): String {
    val tail = takeLast(length)
    return if (tail.isNotEmpty() && tail[0].isLowSurrogate()) tail.substring(1) else tail
}

/**
 * Whether this element holds more than [budget] characters of text, stopping as soon as that is
 * known. Streaming re-stores a message on every update, so measuring a large result in full each
 * time would cost more than the bound saves.
 */
private fun JsonElement.exceeds(budget: Int): Boolean {
    var remaining = budget

    fun visit(element: JsonElement): Boolean {
        when (element) {
            is JsonPrimitive -> if (element !is JsonNull) remaining -= element.content.length
            is JsonArray -> for (child in element) if (visit(child)) return true
            is JsonObject ->
                for ((key, child) in element) {
                    remaining -= key.length
                    if (remaining < 0 || visit(child)) return true
                }
        }
        return remaining < 0
    }
    return visit(this)
}
