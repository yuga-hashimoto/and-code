package com.yugahashimoto.andcode.runtime.local

import android.util.Log
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File

/**
 * Chat history for the local Antigravity agent, kept in memory and flushed to disk.
 *
 * Like [ClaudeMessageStore], the history is read and written as a stream. The runtime used to load
 * it with `readText()` and serialize the whole map with `encodeToString()` on every message, which
 * held the entire file as a String on top of the decoded messages and could exhaust the heap on a
 * long history (issue #392). The write also goes to a temporary file first, so an allocation
 * failure or a killed process mid-write cannot truncate the history already on disk.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class AntigravityMessageStore(
    private val file: File,
    private val json: Json,
) {
    private val messages = linkedMapOf<String, MutableList<OpenCodeMessage>>()
    private var loaded = false
    private var dirty = false

    init {
        // No history yet is normal, not a read failure worth warning about.
        loaded =
            !file.exists() ||
            runCatching {
                file.inputStream().buffered().use { json.decodeFromStream<Map<String, List<OpenCodeMessage>>>(it) }
            }.mapCatching { decoded ->
                decoded.forEach { (sessionId, sessionMessages) ->
                    // A history written before tool output was bounded can hold megabytes per
                    // tool call; bound it as it loads, and let the next flush shrink the file.
                    messages[sessionId] =
                        sessionMessages
                            .map { message ->
                                message.withBoundedToolOutput().also { if (it !== message) dirty = true }
                            }.toMutableList()
                }
            }.onFailure { Log.w(TAG, "Could not read the Antigravity history", it) }
                .isSuccess
    }

    @Synchronized
    fun list(sessionId: String): List<OpenCodeMessage> = messages[sessionId].orEmpty().toList()

    @Synchronized
    fun append(
        sessionId: String,
        message: OpenCodeMessage,
    ) {
        messages.getOrPut(sessionId) { mutableListOf() } += message.withBoundedToolOutput()
        dirty = true
    }

    @Synchronized
    fun remove(sessionId: String): Boolean {
        val removed = messages.remove(sessionId) != null
        if (removed) dirty = true
        return removed
    }

    @Synchronized
    fun flush() {
        if (!dirty) return
        runCatching {
            file.parentFile?.mkdirs()
            // A failed load (corrupt JSON, or an OOM while decoding a long history) used to leave an
            // empty map behind, so the next flush silently replaced the real history with only the
            // messages written since. Keep the unreadable file instead of destroying it.
            if (!loaded) {
                // The backup name is fixed: a second unreadable history replaces the first, which
                // keeps one copy for manual recovery without piling up files on every failure.
                file.takeIf(File::exists)?.let { unreadable ->
                    val backup = File(unreadable.parentFile, "${unreadable.name}.unreadable")
                    if (backup.exists()) backup.delete()
                    check(unreadable.renameTo(backup)) { "Cannot preserve unreadable ${unreadable.name}" }
                }
                loaded = true
            }
            val temporary = File(file.parentFile, ".${file.name}.tmp")
            temporary.outputStream().buffered().use { output ->
                json.encodeToStream(messages.mapValues { it.value.toList() }, output)
            }
            if (!temporary.renameTo(file)) {
                temporary.delete()
                error("Could not replace ${file.name}")
            }
            dirty = false
        }.onFailure { Log.w(TAG, "Could not write the Antigravity history", it) }
    }

    private companion object {
        const val TAG = "AntigravityMessageStore"
    }
}
