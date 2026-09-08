package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.PromptAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatAttachmentPreviewIndexTest {
    private fun image(name: String) = PromptAttachment(name, "image/jpeg", "data:image/jpeg;base64,AQ==")

    private fun file(name: String) = PromptAttachment(name, "text/plain", "file:///tmp/$name")

    @Test
    fun `preview index counts only images before the attachment`() {
        val attachments = listOf(file("notes.txt"), image("first.jpg"), file("log.txt"), image("second.jpg"))

        assertEquals(0, previewIndexForAttachment(attachments, 1))
        assertEquals(1, previewIndexForAttachment(attachments, 3))
    }

    @Test
    fun `non-image attachment has no preview`() {
        val attachments = listOf(image("first.jpg"), file("notes.txt"))

        assertNull(previewIndexForAttachment(attachments, 1))
    }

    @Test
    fun `out of range attachment has no preview`() {
        assertNull(previewIndexForAttachment(listOf(image("first.jpg")), 4))
        assertNull(previewIndexForAttachment(emptyList(), 0))
    }

    @Test
    fun `thumbnail maps back to the attachment it stands for`() {
        val attachments = listOf(file("notes.txt"), image("first.jpg"), file("log.txt"), image("second.jpg"))

        assertEquals(1, attachmentIndexForPreview(attachments, 0))
        assertEquals(3, attachmentIndexForPreview(attachments, 1))
        assertNull(attachmentIndexForPreview(attachments, 2))
    }

    @Test
    fun `mappings are inverses of each other`() {
        val attachments = listOf(image("a.jpg"), file("b.txt"), image("c.png"), image("d.webp"))

        attachments.indices
            .mapNotNull { previewIndexForAttachment(attachments, it)?.to(it) }
            .forEach { (previewIndex, attachmentIndex) ->
                assertEquals(attachmentIndex, attachmentIndexForPreview(attachments, previewIndex))
            }
    }
}
