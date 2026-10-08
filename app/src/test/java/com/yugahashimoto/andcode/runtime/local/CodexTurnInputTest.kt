package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.PromptAttachment
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the `turn/start` `input` shape [codexTurnInput] builds: a text input plus one
 * `{"type":"image","url":...}` input per image attachment, using the app-server's own `UserInput`
 * schema (see docs/CODEX.md).
 */
class CodexTurnInputTest {
    private fun field(
        element: kotlinx.serialization.json.JsonElement,
        key: String,
    ): String = element.jsonObject.getValue(key).jsonPrimitive.content

    @Test
    fun `text-only input is a single text item`() {
        val input = codexTurnInput("hello", emptyList())

        assertEquals(1, input.size)
        assertEquals("text", field(input[0], "type"))
        assertEquals("hello", field(input[0], "text"))
    }

    @Test
    fun `an image attachment is appended as an image input with its data url`() {
        val attachment = PromptAttachment("photo.png", "image/png", "data:image/png;base64,AQ==")

        val input = codexTurnInput("look", listOf(attachment))

        assertEquals(2, input.size)
        assertEquals("image", field(input[1], "type"))
        assertEquals("data:image/png;base64,AQ==", field(input[1], "url"))
    }

    @Test
    fun `multiple images keep their order after the text`() {
        val first = PromptAttachment("a.png", "image/png", "data:image/png;base64,AQ==")
        val second = PromptAttachment("b.jpg", "image/jpeg", "data:image/jpeg;base64,Ag==")

        val input = codexTurnInput("two", listOf(first, second))

        assertEquals(3, input.size)
        assertEquals(first.url, field(input[1], "url"))
        assertEquals(second.url, field(input[2], "url"))
    }

    @Test
    fun `a non-image attachment is left out rather than sent as an unsupported shape`() {
        val text = PromptAttachment("notes.txt", "text/plain", "data:text/plain;base64,dGVzdA==")

        val input = codexTurnInput("read", listOf(text))

        assertEquals(1, input.size)
    }

    @Test
    fun `an image with a remote url is left out because the app-server rejects it`() {
        val remote = PromptAttachment("photo.png", "image/png", "https://example.com/photo.png")

        val input = codexTurnInput("fetch", listOf(remote))

        assertEquals(1, input.size)
    }
}
