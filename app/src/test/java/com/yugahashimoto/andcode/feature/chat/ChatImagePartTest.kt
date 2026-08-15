package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.OpenCodePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatImagePartTest {
    @Test
    fun `parses a base64 png data uri`() {
        val parsed = parseDataImageUri("data:image/png;base64,iVBORw0KGgo=")

        assertEquals("image/png", parsed?.mime)
        assertEquals("iVBORw0KGgo=", parsed?.base64)
    }

    @Test
    fun `parses a base64 jpeg data uri`() {
        val parsed = parseDataImageUri("data:image/jpeg;base64,/9j/4AAQ")

        assertEquals("image/jpeg", parsed?.mime)
        assertEquals("/9j/4AAQ", parsed?.base64)
    }

    @Test
    fun `rejects a non image mime`() {
        assertNull(parseDataImageUri("data:text/plain;base64,abc"))
    }

    @Test
    fun `rejects a non data uri`() {
        assertNull(parseDataImageUri("https://example.com/shot.png"))
    }

    @Test
    fun `rejects a data uri without base64 suffix`() {
        assertNull(parseDataImageUri("data:image/png,abc"))
    }

    @Test
    fun `rejects a data uri without a comma`() {
        assertNull(parseDataImageUri("data:image/png;base64"))
    }

    @Test
    fun `maps an image file part to a chat image part`() {
        val part =
            OpenCodePart(
                id = "p1",
                type = "file",
                mime = "image/png",
                url = "data:image/png;base64,abc",
                filename = "shot.png",
            )

        val chat = part.toChatPart() as ChatPart.Image
        assertEquals("p1", chat.id)
        assertEquals("image/png", chat.mime)
        assertEquals("data:image/png;base64,abc", chat.url)
        assertEquals("shot.png", chat.filename)
    }

    @Test
    fun `drops an image file part that has no url`() {
        val part = OpenCodePart(id = "p2", type = "file", mime = "image/png", url = null)

        assertNull(part.toChatPart())
    }

    @Test
    fun `drops a non image file part`() {
        val part =
            OpenCodePart(
                id = "p3",
                type = "file",
                mime = "text/plain",
                url = "data:text/plain;base64,abc",
                filename = "log.txt",
            )

        assertNull(part.toChatPart())
    }

    @Test
    fun `infers image mime when generated file part omits mime`() {
        val part =
            OpenCodePart(
                id = "p4",
                type = "file",
                url = "/workspace/generated-image.png",
                filename = "generated-image.png",
            )

        assertEquals("image/png", (part.toChatPart() as ChatPart.Image).mime)
    }

    @Test
    fun `accepts image part type with a data uri`() {
        val part =
            OpenCodePart(
                id = "p5",
                type = "image",
                url = "data:image/webp;base64,abc",
            )

        assertEquals("image/webp", (part.toChatPart() as ChatPart.Image).mime)
    }

    @Test
    fun `uses wildcard mime for image part without metadata`() {
        val part = OpenCodePart(id = "p6", type = "image", url = "generated-image")

        assertNull(part.toChatPart())
    }
}
