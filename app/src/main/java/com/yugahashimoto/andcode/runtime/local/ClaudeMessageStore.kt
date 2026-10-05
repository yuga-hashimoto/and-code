package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Chat history for the local Claude Code agent, kept in memory and flushed to disk.
 *
 * Streaming a single answer produces hundreds of message updates, so writes are coalesced: the
 * in-memory map is authoritative and [flush] is called at turn boundaries rather than per update.
 *
 * The history is read and written as a stream. Going through `readText`/`encodeToString` held the
 * whole file as a String on top of the decoded messages, several times the size of the history in
 * heap at once, which is enough to hit the app's heap limit on a long history (issue #350). The
 * write also goes to a temporary file first, so an allocation failure or a killed process
 * mid-write cannot truncate the history that was already on disk.
 */
@OptIn(ExperimentalSerializationApi::class)
class ClaudeMessageStore(
    private val file: File,
    private val json: Json,
) {
    private val messages = linkedMapOf<String, MutableList<OpenCodeMessage>>()
    private var dirty = false

    init {
        runCatching {
            file.inputStream().buffered().use { json.decodeFromStream<Map<String, List<OpenCodeMessage>>>(it) }
        }.getOrNull()?.forEach { (sessionId, sessionMessages) ->
            messages[sessionId] = sessionMessages.toMutableList()
        }
    }

    @Synchronized
    fun list(sessionId: String): List<OpenCodeMessage> = messages[sessionId].orEmpty().toList()

    /**
     * Converts tool calls left in an in-flight state by a process that disappeared into a
     * terminal error. This is also applied to history loaded after an app restart, where there is
     * no stream event left to do the cleanup.
     */
    @Synchronized
    fun settleRunningTools(
        sessionId: String,
        error: String,
    ): List<OpenCodeMessage> {
        val existing = messages[sessionId].orEmpty()
        val updated =
            existing.map { message ->
                message.copy(
                    parts =
                        message.parts.map { part ->
                            val status = part.state?.get("status")?.jsonPrimitive?.contentOrNull
                            if (part.type == "tool" && status in setOf("running", "pending")) {
                                part.copy(
                                    state =
                                        part.state.orEmpty() +
                                            mapOf(
                                                "status" to JsonPrimitive("error"),
                                                "error" to JsonPrimitive(error),
                                            ),
                                )
                            } else {
                                part
                            }
                        },
                )
            }
        if (updated != existing) {
            messages[sessionId] = updated.toMutableList()
            dirty = true
            flush()
        }
        return updated.toList()
    }

    /** Adds [message], replacing any earlier version of the same message id. */
    @Synchronized
    fun upsert(
        sessionId: String,
        message: OpenCodeMessage,
    ) {
        val sessionMessages = messages.getOrPut(sessionId) { mutableListOf() }
        val existing = sessionMessages.indexOfFirst { it.info.id == message.info.id }
        if (existing >= 0) sessionMessages[existing] = message else sessionMessages += message
        dirty = true
    }

    @Synchronized
    fun remove(sessionId: String) {
        if (messages.remove(sessionId) != null) dirty = true
        flush()
    }

    @Synchronized
    fun flush() {
        if (!dirty) return
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.outputStream().buffered().use { output ->
                json.encodeToStream(messages.mapValues { it.value.toList() }, output)
            }
            if (!temporary.renameTo(file)) {
                temporary.delete()
                error("Could not replace ${file.name}")
            }
            dirty = false
        }
    }
}
