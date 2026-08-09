package com.yugahashimoto.andcode.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHandoffTest {
    private fun userMessage(text: String) =
        ChatMessage(
            isUser = true,
            parts = listOf(ChatPart.Text(id = "u-${text.hashCode()}", text = text)),
        )

    private fun assistantMessage(text: String) =
        ChatMessage(
            isUser = false,
            parts = listOf(ChatPart.Text(id = "a-${text.hashCode()}", text = text)),
        )

    @Test
    fun `includes header and formats roles`() {
        val messages =
            listOf(
                userMessage("Hello there"),
                assistantMessage("Hi, how can I help?"),
            )

        val prompt = buildHandoffPrompt(messages)

        assertTrue(
            prompt.startsWith(
                "Below is a summary of a conversation handed over from another session. Continue from where it left off.",
            ),
        )
        assertTrue(prompt.contains("User: Hello there"))
        assertTrue(prompt.contains("Assistant: Hi, how can I help?"))
    }

    @Test
    fun `skips messages with no text parts`() {
        val messages =
            listOf(
                userMessage("Real question"),
                ChatMessage(
                    isUser = false,
                    parts =
                        listOf(
                            ChatPart.Tool(id = "t1", name = "bash", status = ToolStatus.COMPLETED),
                        ),
                ),
                assistantMessage("Answer"),
            )

        val prompt = buildHandoffPrompt(messages)

        assertTrue(prompt.contains("User: Real question"))
        assertTrue(prompt.contains("Assistant: Answer"))
        assertFalse(prompt.contains("bash"))
    }

    @Test
    fun `skips blank text messages`() {
        val messages =
            listOf(
                userMessage("   "),
                assistantMessage("Kept"),
            )

        val prompt = buildHandoffPrompt(messages)

        assertFalse(prompt.contains("User:"))
        assertTrue(prompt.contains("Assistant: Kept"))
    }

    @Test
    fun `truncates from the oldest side and keeps most recent messages`() {
        val messages =
            (1..20).map { index ->
                if (index % 2 == 1) {
                    userMessage("user message number $index with some padding text")
                } else {
                    assistantMessage("assistant message number $index with some padding text")
                }
            }

        val prompt = buildHandoffPrompt(messages, maxChars = 300)

        assertTrue(prompt.length <= 300 + 200)
        assertTrue(prompt.contains("message number 20"))
        assertFalse(prompt.contains("message number 1 "))
    }

    @Test
    fun `always keeps at least the most recent message even if it alone exceeds maxChars`() {
        val longText = "x".repeat(500)
        val messages =
            listOf(
                userMessage("short earlier message"),
                assistantMessage(longText),
            )

        val prompt = buildHandoffPrompt(messages, maxChars = 50)

        assertTrue(prompt.contains(longText))
        assertFalse(prompt.contains("short earlier message"))
    }

    @Test
    fun `returns just the header when there is nothing to transcribe`() {
        val prompt = buildHandoffPrompt(emptyList())

        assertEquals(
            "Below is a summary of a conversation handed over from another session. Continue from where it left off.",
            prompt,
        )
    }
}
