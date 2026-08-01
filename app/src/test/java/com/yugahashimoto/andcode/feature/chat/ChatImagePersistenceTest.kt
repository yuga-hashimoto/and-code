package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.PromptAttachment
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatImagePersistenceTest {
    @Test
    fun `reload keeps optimistic image when runtime omits file part`() {
        val image = PromptAttachment("photo.jpg", "image/jpeg", "data:image/jpeg;base64,AQ==")
        val optimistic =
            ChatMessage(
                id = "client-id",
                isUser = true,
                parts = listOf(ChatPart.Text("client-text", "look")),
                attachments = listOf(image),
            )
        val persisted =
            ChatMessage(
                id = "server-id",
                isUser = true,
                parts = listOf(ChatPart.Text("server-text", "look")),
            )

        val merged = mergeReloadedMessages(listOf(persisted), listOf(optimistic))

        assertEquals("server-id", merged.single().id)
        assertEquals(listOf(image), merged.single().attachments)
    }

    @Test
    fun `reload prefers runtime image part`() {
        val optimisticImage = PromptAttachment("photo.jpg", "image/jpeg", "data:image/jpeg;base64,AQ==")
        val persistedImage = PromptAttachment("photo.jpg", "image/jpeg", "data:image/jpeg;base64,Ag==")
        val optimistic = ChatMessage(isUser = true, attachments = listOf(optimisticImage))
        val persisted = ChatMessage(id = "server-id", isUser = true, attachments = listOf(persistedImage))

        val merged = mergeReloadedMessages(listOf(persisted), listOf(optimistic))

        assertEquals(listOf(persistedImage), merged.single().attachments)
    }

    @Test
    fun `reload restores only missing images from optimistic message`() {
        val first = PromptAttachment("first.jpg", "image/jpeg", "data:image/jpeg;base64,AQ==")
        val second = PromptAttachment("second.jpg", "image/jpeg", "data:image/jpeg;base64,Ag==")
        val optimistic = ChatMessage(isUser = true, attachments = listOf(first, second))
        val persisted = ChatMessage(id = "server-id", isUser = true, attachments = listOf(first))

        val merged = mergeReloadedMessages(listOf(persisted), listOf(optimistic))

        assertEquals(listOf(first, second), merged.single().attachments)
    }
}
