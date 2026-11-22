package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodePart
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /** A huge tool output must not stay in the heap, or on disk, in full (#424). */
    @Test
    fun `upsert keeps only the bounded tail of a huge tool output`() {
        val directory = java.nio.file.Files.createTempDirectory("claude-message-store").toFile()
        val file = File(directory, "messages.json")
        val json = Json { encodeDefaults = true }
        val store = ClaudeMessageStore(file, json)

        store.upsert("s1", messageWithOutput("m1", "a".repeat(MAX_STORED_TOOL_OUTPUT_CHARS * 5) + "END"))
        store.flush()

        val kept = outputOf(store.list("s1").single())
        assertEquals(MAX_STORED_TOOL_OUTPUT_CHARS, kept.length)
        assertTrue(kept.endsWith("END"))
        assertEquals(kept, outputOf(ClaudeMessageStore(file, json).list("s1").single()))
        assertTrue(file.length() < MAX_STORED_TOOL_OUTPUT_CHARS * 2L)
        directory.deleteRecursively()
    }

    /** A history written before the bound existed is shrunk as it loads, on disk too. */
    @Test
    fun `an oversized history on disk is bounded on load and rewritten smaller`() {
        val directory = java.nio.file.Files.createTempDirectory("claude-message-store").toFile()
        val file = File(directory, "messages.json")
        val json = Json { encodeDefaults = true }
        file.writeText(
            json.encodeToString(
                mapOf("s1" to listOf(messageWithOutput("m1", "b".repeat(MAX_STORED_TOOL_OUTPUT_CHARS * 20) + "END"))),
            ),
        )
        val bloatedSize = file.length()

        val store = ClaudeMessageStore(file, json)

        assertEquals(MAX_STORED_TOOL_OUTPUT_CHARS, outputOf(store.list("s1").single()).length)
        // Nothing has flushed yet: loading alone must already have shrunk the file.
        assertTrue(file.length() < bloatedSize / 10)
        assertTrue(outputOf(ClaudeMessageStore(file, json).list("s1").single()).endsWith("END"))
        directory.deleteRecursively()
    }

    /** Streaming re-stores a message under the same id on every update; each version is bounded. */
    @Test
    fun `upsert bounds a message that replaces an earlier version of the same id`() {
        val directory = java.nio.file.Files.createTempDirectory("claude-message-store").toFile()
        val store = ClaudeMessageStore(File(directory, "messages.json"), Json { encodeDefaults = true })

        store.upsert("s1", messageWithOutput("m1", "short"))
        store.upsert("s1", messageWithOutput("m1", "c".repeat(MAX_STORED_TOOL_OUTPUT_CHARS * 3) + "END"))

        val kept = outputOf(store.list("s1").single())
        assertEquals(MAX_STORED_TOOL_OUTPUT_CHARS, kept.length)
        assertTrue(kept.endsWith("END"))
        directory.deleteRecursively()
    }

    private fun messageWithOutput(
        id: String,
        output: String,
    ) = OpenCodeMessage(
        info = OpenCodeMessageInfo(id, "s1", "assistant"),
        parts =
            listOf(
                OpenCodePart(
                    id = "$id-tool",
                    sessionId = "s1",
                    messageId = id,
                    type = "tool",
                    tool = "Bash",
                    state = mapOf("status" to JsonPrimitive("completed"), "output" to JsonPrimitive(output)),
                ),
            ),
    )

    private fun outputOf(message: OpenCodeMessage): String = (message.parts.single().state?.get("output") as JsonPrimitive).content
}
