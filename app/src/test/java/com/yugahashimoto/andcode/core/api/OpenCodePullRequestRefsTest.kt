package com.yugahashimoto.andcode.core.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodePullRequestRefsTest {
    @Test
    fun `reads a pull request out of assistant text`() {
        val messages =
            listOf(
                message(
                    part(
                        type = "text",
                        text = "Opened https://github.com/o/r/pull/7 for review.",
                    ),
                ),
            )

        assertEquals(listOf(PullRequestRef("o", "r", 7)), pullRequestRefsIn(messages))
    }

    @Test
    fun `reads a pull request out of tool output and errors`() {
        val messages =
            listOf(
                message(part(type = "tool", state = mapOf("output" to JsonPrimitive("https://github.com/o/r/pull/2")))),
                message(part(type = "tool", state = mapOf("error" to JsonPrimitive("reported https://github.com/o/r/pull/3")))),
            )

        assertEquals(
            listOf(PullRequestRef("o", "r", 3), PullRequestRef("o", "r", 2)),
            pullRequestRefsIn(messages),
        )
    }

    @Test
    fun `ignores a part that lists several pull requests`() {
        val messages =
            listOf(
                message(
                    part(
                        type = "tool",
                        state =
                            mapOf(
                                "output" to
                                    JsonPrimitive(
                                        "https://github.com/o/r/pull/12\nhttps://github.com/o/r/pull/11",
                                    ),
                            ),
                    ),
                ),
            )

        assertTrue(pullRequestRefsIn(messages).isEmpty())
    }

    @Test
    fun `keeps only the newest few pull requests, newest first`() {
        val messages = (1..8).map { number -> message(part(type = "text", text = "https://github.com/o/r/pull/$number")) }

        assertEquals(
            listOf(8, 7, 6, 5, 4).map { PullRequestRef("o", "r", it) },
            pullRequestRefsIn(messages),
        )
    }

    private fun message(vararg parts: OpenCodePart): OpenCodeMessage =
        OpenCodeMessage(
            info = OpenCodeMessageInfo(id = "m-${parts.first().id}", sessionId = "s", role = "assistant"),
            parts = parts.toList(),
        )

    private fun part(
        type: String,
        text: String? = null,
        state: Map<String, JsonElement>? = null,
    ): OpenCodePart =
        OpenCodePart(
            id = "part-${text.orEmpty()}-${state.orEmpty()}",
            sessionId = "s",
            messageId = "m",
            type = type,
            text = text,
            state = state,
        )
}
