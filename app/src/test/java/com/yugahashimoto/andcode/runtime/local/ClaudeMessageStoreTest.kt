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
}
