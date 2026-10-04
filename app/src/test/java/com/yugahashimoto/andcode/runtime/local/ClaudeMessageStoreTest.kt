package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodePart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ClaudeMessageStoreTest {
    @Test
    fun `settles persisted running tools without changing completed tools`() {
        val file = File.createTempFile("claude-message-store", ".json").also(File::delete)
        val json = Json { encodeDefaults = true }
        val store = ClaudeMessageStore(file, json)
        val sessionId = "session-1"
        val message =
            OpenCodeMessage(
                info = OpenCodeMessageInfo("message-1", sessionId, "assistant"),
                parts =
                    listOf(
                        OpenCodePart(
                            id = "running-tool",
                            sessionId = sessionId,
                            messageId = "message-1",
                            type = "tool",
                            tool = "Bash",
                            state = mapOf("status" to JsonPrimitive("running")),
                        ),
                        OpenCodePart(
                            id = "completed-tool",
                            sessionId = sessionId,
                            messageId = "message-1",
                            type = "tool",
                            tool = "Bash",
                            state = mapOf("status" to JsonPrimitive("completed")),
                        ),
                    ),
            )
        store.upsert(sessionId, message)
        store.flush()

        val reloaded = ClaudeMessageStore(file, json)
        val settled = reloaded.settleRunningTools(sessionId, "Claude Code session ended before the tool result was received")

        assertEquals(
            JsonPrimitive("error"),
            settled.single().parts.first { it.id == "running-tool" }.state?.get("status"),
        )
        assertEquals(
            JsonPrimitive("Claude Code session ended before the tool result was received"),
            settled.single().parts.first { it.id == "running-tool" }.state?.get("error"),
        )
        assertEquals(
            JsonPrimitive("completed"),
            settled.single().parts.first { it.id == "completed-tool" }.state?.get("status"),
        )
        assertEquals(settled, reloaded.list(sessionId))

        file.delete()
    }

    /** The history is written through a temporary file, which must not be left behind (#350). */
    @Test
    fun `flush replaces the history file and leaves no temporary file`() {
        val directory = java.nio.file.Files.createTempDirectory("claude-message-store").toFile()
        val file = File(directory, "messages.json")
        val json = Json { encodeDefaults = true }
        val store = ClaudeMessageStore(file, json)
        store.upsert("s1", OpenCodeMessage(info = OpenCodeMessageInfo("m1", "s1", "user")))

        store.flush()
        store.upsert("s1", OpenCodeMessage(info = OpenCodeMessageInfo("m2", "s1", "assistant")))
        store.flush()

        assertEquals(listOf("messages.json"), directory.list()?.toList())
        assertEquals(listOf("m1", "m2"), ClaudeMessageStore(file, json).list("s1").map { it.info.id })
        directory.deleteRecursively()
    }
}
