package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Chat history for the local Claude Code agent, kept in memory and flushed to disk.
 *
 * Streaming a single answer produces hundreds of message updates, so writes are coalesced: the
 * in-memory map is authoritative and [flush] is called at turn boundaries rather than per update.
 */
class ClaudeMessageStore(
    private val file: File,
    private val json: Json,
) {
    private val messages = linkedMapOf<String, MutableList<OpenCodeMessage>>()
    private var dirty = false

    init {
        runCatching {
            json.decodeFromString<Map<String, List<OpenCodeMessage>>>(file.readText())
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
            file.writeText(json.encodeToString(messages.mapValues { it.value.toList() }))
            dirty = false
        }
    }
}
