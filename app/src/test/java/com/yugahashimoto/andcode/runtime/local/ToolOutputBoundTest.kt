package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodePart
import com.yugahashimoto.andcode.feature.chat.ChatPart
import com.yugahashimoto.andcode.feature.chat.toChatPart
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolOutputBoundTest {
    @Test
    fun `a message whose tool output fits is returned unchanged`() {
        val message = message(toolPart("t1", JsonPrimitive("done")))

        assertSame(message, message.withBoundedToolOutput())
    }

    @Test
    fun `a long string output keeps its tail`() {
        val output = "head-" + "x".repeat(100_000) + "-tail"
        val bounded = message(toolPart("t1", JsonPrimitive(output))).withBoundedToolOutput(maxChars = 1_000)

        val kept = (bounded.parts.single().state?.get("output") as JsonPrimitive).content
        assertEquals(1_000, kept.length)
        assertTrue(kept.endsWith("-tail"))
        assertEquals(output.takeLast(1_000), kept)
    }

    @Test
    fun `the tail does not start inside a surrogate pair`() {
        // Each emoji is two chars, so an odd bound lands between the halves of a pair.
        val output = "😀".repeat(10)
        val bounded = message(toolPart("t1", JsonPrimitive(output))).withBoundedToolOutput(maxChars = 5)

        val kept = (bounded.parts.single().state?.get("output") as JsonPrimitive).content
        assertEquals("😀".repeat(2), kept)
    }

    @Test
    fun `other state and other parts are left alone`() {
        val big = "y".repeat(50_000)
        val text = OpenCodePart(id = "text", type = "text", text = big)
        val tool =
            toolPart("t1", JsonPrimitive(big)).copy(
                state =
                    mapOf(
                        "status" to JsonPrimitive("completed"),
                        "input" to JsonPrimitive(big),
                        "output" to JsonPrimitive(big),
                    ),
            )
        val bounded = message(text, tool).withBoundedToolOutput(maxChars = 1_000)

        assertSame(text, bounded.parts[0])
        val state = bounded.parts[1].state.orEmpty()
        assertEquals(JsonPrimitive("completed"), state["status"])
        assertEquals(JsonPrimitive(big), state["input"])
        assertEquals(1_000, (state["output"] as JsonPrimitive).content.length)
    }

    @Test
    fun `a large structured result is dropped and a small one is kept`() {
        val small = JsonObject(mapOf("content" to JsonArray(listOf(JsonPrimitive("ok")))))
        val large = JsonObject(mapOf("content" to JsonArray(listOf(JsonPrimitive("z".repeat(50_000))))))

        val keptSmall = message(toolPart("t1", small)).withBoundedToolOutput(maxChars = 1_000)
        val droppedLarge = message(toolPart("t2", large)).withBoundedToolOutput(maxChars = 1_000)

        assertEquals(small, keptSmall.parts.single().state?.get("output"))
        assertNull(droppedLarge.parts.single().state?.get("output"))
        assertEquals(JsonPrimitive("completed"), droppedLarge.parts.single().state?.get("status"))
    }

    @Test
    fun `the chat shows the same output and the same truncation flag after bounding`() {
        val output = (1..30_000).joinToString("\n") { "line $it" }
        val original = toolPart("t1", JsonPrimitive(output))
        val bounded = message(original).withBoundedToolOutput().parts.single()

        val before = original.toChatPart("m1") as ChatPart.Tool
        val after = bounded.toChatPart("m1") as ChatPart.Tool

        assertTrue(before.outputTruncated)
        assertNotNull(after.output)
        assertEquals(before.output, after.output)
        assertEquals(before.outputTruncated, after.outputTruncated)
    }

    @Test
    fun `an output the chat would flag as truncated is still longer than the chat limit once bounded`() {
        val bounded =
            message(toolPart("t1", JsonPrimitive("a".repeat(MAX_STORED_TOOL_OUTPUT_CHARS * 4))))
                .withBoundedToolOutput()
                .parts
                .single()

        val chat = bounded.toChatPart("m1") as ChatPart.Tool
        assertTrue(chat.outputTruncated)
    }

    private fun message(vararg parts: OpenCodePart) =
        OpenCodeMessage(
            info = OpenCodeMessageInfo(id = "m1", sessionId = "s1", role = "assistant"),
            parts = parts.toList(),
        )

    private fun toolPart(
        id: String,
        output: kotlinx.serialization.json.JsonElement,
    ) = OpenCodePart(
        id = id,
        sessionId = "s1",
        messageId = "m1",
        type = "tool",
        tool = "bash",
        state = mapOf("status" to JsonPrimitive("completed"), "output" to output),
    )
}
