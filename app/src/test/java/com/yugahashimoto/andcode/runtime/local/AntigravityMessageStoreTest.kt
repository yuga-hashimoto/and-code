package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodePart
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AntigravityMessageStoreTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `messages are written through a temporary file and survive a reload`() {
        val directory = java.nio.file.Files.createTempDirectory("antigravity-message-store").toFile()
        val file = File(directory, "antigravity-messages.json")
        val store = AntigravityMessageStore(file, json)
        store.append("s1", message("m1", "s1"))
        store.flush()
        store.append("s1", message("m2", "s1"))
        store.flush()

        assertEquals(listOf("antigravity-messages.json"), directory.list()?.toList())
        assertEquals(listOf("m1", "m2"), AntigravityMessageStore(file, json).list("s1").map { it.info.id })
        directory.deleteRecursively()
    }

    @Test
    fun `removing a session deletes its messages from disk`() {
        val directory = java.nio.file.Files.createTempDirectory("antigravity-message-store").toFile()
        val file = File(directory, "antigravity-messages.json")
        val store = AntigravityMessageStore(file, json)
        store.append("kept", message("m1", "kept"))
        store.append("removed", message("m2", "removed"))
        store.flush()

        assertTrue(store.remove("removed"))
        assertFalse(store.remove("missing"))
        store.flush()

        val reloaded = AntigravityMessageStore(file, json)
        assertEquals(listOf("m1"), reloaded.list("kept").map { it.info.id })
        assertTrue(reloaded.list("removed").isEmpty())
        directory.deleteRecursively()
    }

    @Test
    fun `a corrupt history is preserved instead of being overwritten`() {
        val directory = java.nio.file.Files.createTempDirectory("antigravity-message-store").toFile()
        val file = File(directory, "antigravity-messages.json").also { it.writeText("{not json") }
        val store = AntigravityMessageStore(file, json)

        assertTrue(store.list("s1").isEmpty())
        store.append("s1", message("m1", "s1"))
        store.flush()

        assertEquals("{not json", File(directory, "antigravity-messages.json.unreadable").readText())
        assertEquals(listOf("m1"), AntigravityMessageStore(file, json).list("s1").map { it.info.id })
        directory.deleteRecursively()
    }

    /** A huge tool output must not stay in the heap, or on disk, in full (#424). */
    @Test
    fun `a huge tool output is bounded when appended and when an old history loads`() {
        val directory = java.nio.file.Files.createTempDirectory("antigravity-message-store").toFile()
        val file = File(directory, "antigravity-messages.json")
        val store = AntigravityMessageStore(file, json)
        store.append("s1", messageWithOutput("m1", "a".repeat(MAX_STORED_TOOL_OUTPUT_CHARS * 5) + "END"))
        store.flush()

        assertEquals(MAX_STORED_TOOL_OUTPUT_CHARS, outputOf(store.list("s1").single()).length)
        assertTrue(file.length() < MAX_STORED_TOOL_OUTPUT_CHARS * 2L)

        // A history written before the bound existed shrinks as it loads, on disk too.
        file.writeText(
            json.encodeToString(
                mapOf("s1" to listOf(messageWithOutput("m2", "b".repeat(MAX_STORED_TOOL_OUTPUT_CHARS * 20) + "END"))),
            ),
        )
        val bloatedSize = file.length()
        val reloaded = AntigravityMessageStore(file, json)
        assertEquals(MAX_STORED_TOOL_OUTPUT_CHARS, outputOf(reloaded.list("s1").single()).length)
        // Nothing has flushed yet: loading alone must already have shrunk the file.
        assertTrue(file.length() < bloatedSize / 10)
        assertTrue(outputOf(AntigravityMessageStore(file, json).list("s1").single()).endsWith("END"))
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
                    tool = "run_command",
                    state = mapOf("status" to JsonPrimitive("completed"), "output" to JsonPrimitive(output)),
                ),
            ),
    )

    private fun outputOf(message: OpenCodeMessage): String = (message.parts.single().state?.get("output") as JsonPrimitive).content

    private fun message(
        id: String,
        sessionId: String,
    ) = OpenCodeMessage(info = OpenCodeMessageInfo(id, sessionId, "user"))
}
