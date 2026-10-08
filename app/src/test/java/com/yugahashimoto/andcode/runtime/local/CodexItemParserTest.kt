package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `userMessage`/`error` fixtures below are the real notification lines a live, unauthenticated
 * `codex app-server` (0.155.1) sent back when driven directly over stdio - see docs/CODEX.md. The
 * `agentMessage`/`commandExecution` fixtures could not be captured the same way (they only appear
 * once a turn actually runs against a signed-in account), so they are built from the protocol's own
 * published JSON Schema (`codex app-server generate-json-schema`) instead.
 */
class CodexItemParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(
        sessionId: String,
        method: String,
        params: String,
    ) = CodexItemParser().handleNotification(sessionId, method, json.parseToJsonElement(params).jsonObject)

    @Test
    fun `a userMessage item becomes its own user message`() {
        val parsed =
            parse(
                "t1",
                "item/started",
                """
                {"item":{"type":"userMessage","id":"01a0baa0-2b85-7782-bb09-972122d20be2","clientId":null,
                 "content":[{"type":"text","text":"say hi","text_elements":[]}]},
                 "threadId":"t1","turnId":"01a0baa0-2b38-7a40-a2cf-14411c603b9f","startedAtMs":1789837454213}
                """.trimIndent(),
            )

        val message = parsed.messages.single()
        assertEquals("user", message.info.role)
        assertEquals("say hi", message.text)
    }

    @Test
    fun `a userMessage image becomes a file part so the echo shows the attachment`() {
        val parsed =
            parse(
                "t1",
                "item/started",
                """
                {"item":{"type":"userMessage","id":"um-1","clientId":null,
                 "content":[{"type":"text","text":"What's on the picture?","text_elements":[]},
                            {"type":"image","detail":null,"url":"data:image/png;base64,iVBORw0KGgo="}]},
                 "threadId":"t1","turnId":"turn-1","startedAtMs":1}
                """.trimIndent(),
            )

        val parts = parsed.messages.single().parts
        assertEquals(2, parts.size)
        assertEquals("text", parts[0].type)
        assertEquals("What's on the picture?", parts[0].text)
        val file = parts[1]
        assertEquals("file", file.type)
        assertEquals("image/png", file.mime)
        assertEquals("data:image/png;base64,iVBORw0KGgo=", file.url)
        assertEquals("attachment-1.png", file.filename)
        // Only the text part may stream: a live image part would be rendered as a second message's
        // image on an attachment-only send, since the echo is reconciled into the optimistic bubble
        // by matching text (see handleUserMessage). The file part is persisted for the reload.
        val streamed = parsed.events.filterIsInstance<OpenCodeEvent.MessagePartUpdated>()
        assertEquals(1, streamed.size)
        assertEquals("text", streamed.single().part.type)
    }

    @Test
    fun `a userMessage localImage resolves from its path`() {
        val parsed =
            parse(
                "t1",
                "item/started",
                """
                {"item":{"type":"userMessage","id":"um-2","clientId":null,
                 "content":[{"type":"text","text":"look","text_elements":[]},
                            {"type":"localImage","detail":null,"path":"/tmp/shot.jpeg"}]},
                 "threadId":"t1","turnId":"turn-1","startedAtMs":1}
                """.trimIndent(),
            )

        val file = parsed.messages.single().parts[1]
        assertEquals("/tmp/shot.jpeg", file.url)
        assertEquals("image/jpeg", file.mime)
    }

    @Test
    fun `a userMessage with only non-image content stays text-only`() {
        val parsed =
            parse(
                "t1",
                "item/started",
                """
                {"item":{"type":"userMessage","id":"um-3","clientId":null,
                 "content":[{"type":"text","text":"speak","text_elements":[]},
                            {"type":"audio","url":"data:audio/wav;base64,AAAA"}]},
                 "threadId":"t1","turnId":"turn-1","startedAtMs":1}
                """.trimIndent(),
            )

        val parts = parsed.messages.single().parts
        assertEquals(1, parts.size)
        assertEquals("text", parts.single().type)
    }

    @Test
    fun `a non-retrying error ends the turn`() {
        val parsed =
            parse(
                "t1",
                "error",
                """
                {"error":{"message":"fatal","codexErrorInfo":null,"additionalDetails":null,"misalignment":null},
                 "willRetry":false,"threadId":"t1","turnId":"turn-1"}
                """.trimIndent(),
            )

        assertTrue(parsed.events.any { it is OpenCodeEvent.SessionError })
        assertTrue(parsed.events.any { it is OpenCodeEvent.SessionIdle })
    }

    @Test
    fun `a retrying error does not end the turn`() {
        val parsed =
            parse(
                "t1",
                "error",
                """
                {"error":{"message":"Reconnecting... 2/5","codexErrorInfo":{"responseStreamDisconnected":{"httpStatusCode":401}},
                 "additionalDetails":"unexpected status 401","misalignment":null},
                 "willRetry":true,"threadId":"t1","turnId":"turn-1"}
                """.trimIndent(),
            )

        assertTrue(parsed.events.any { it is OpenCodeEvent.SessionError })
        assertTrue(parsed.events.none { it is OpenCodeEvent.SessionIdle })
    }

    @Test
    fun `an agentMessage item becomes assistant text, grouped by turn`() {
        val parser = CodexItemParser()
        val first =
            parser.handleNotification(
                "t1",
                "item/completed",
                json.parseToJsonElement(
                    """{"item":{"type":"agentMessage","id":"item-1","text":"Hello!","delivery":"async","phase":null,
                        "memoryCitation":null,"questions":null},"threadId":"t1","turnId":"turn-1","completedAtMs":1}""",
                ).jsonObject,
            )

        val message = first.messages.single()
        assertEquals("assistant", message.info.role)
        assertEquals("Hello!", message.text)
        assertEquals("codex-turn-turn-1", message.info.id)
    }

    @Test
    fun `an agentMessage delta streams a part id matching the item's own eventual part id`() {
        val delta =
            parse(
                "t1",
                "item/agentMessage/delta",
                """{"threadId":"t1","turnId":"turn-1","itemId":"item-1","delta":"Hel"}""",
            )

        val event = delta.events.single() as OpenCodeEvent.MessagePartDelta
        assertEquals("item-1-text", event.partId)
    }

    @Test
    fun `an agentMessage delta with no itemId is dropped rather than keyed by turnId`() {
        // itemId is a required field per AgentMessageDeltaNotification's schema; a line missing it
        // is an unrecognized shape, not one this parser should guess a key for (see docs/CODEX.md).
        val delta =
            parse(
                "t1",
                "item/agentMessage/delta",
                """{"threadId":"t1","turnId":"turn-1","delta":"Hel"}""",
            )

        assertTrue(delta.events.isEmpty())
    }

    @Test
    fun `a commandExecution item becomes a tool part with status and output`() {
        val parsed =
            parse(
                "t1",
                "item/completed",
                """
                {"item":{"type":"commandExecution","id":"cmd-1","command":"ls -la","status":"completed",
                 "aggregatedOutput":"total 0\n","exitCode":0,"durationMs":12,"cwd":null,"source":null,
                 "commandActions":null,"pluginId":null,"processId":null,"scriptPath":null},
                 "threadId":"t1","turnId":"turn-2","completedAtMs":1}
                """.trimIndent(),
            )

        val part = parsed.messages.single().parts.single()
        assertEquals("tool", part.type)
        assertEquals("bash", part.tool)
        assertEquals("completed", part.state?.get("status")?.jsonPrimitive?.content)
    }

    // Shape from a real image_gen run on a device (codex-cli 0.155.1): the v2 `imageGeneration`
    // item with the PNG base64 in `result` (shortened here) and the file Codex saved in `savedPath`.
    private fun imageGenerationItem(
        status: String,
        savedPath: String?,
        result: String = "iVBORw0KGgoAAAANSUhEUg",
    ) = """
        {"item":{"type":"imageGeneration","id":"exec-8cc1","status":"$status",
         "revisedPrompt":"A fluffy white rabbit in a meadow","result":"$result",
         "savedPath":${savedPath?.let { "\"$it\"" } ?: "null"}},
         "threadId":"t1","turnId":"turn-3","completedAtMs":1}
        """.trimIndent()

    @Test
    fun `a completed imageGeneration item becomes an image part pointing at the saved file`() {
        val path = "/root/.codex/generated_images/t1/exec-8cc1.png"
        val part = parse("t1", "item/completed", imageGenerationItem("completed", path)).messages.single().parts.single()

        assertEquals("file", part.type)
        assertEquals("image/png", part.mime)
        assertEquals(path, part.url)
        assertEquals("exec-8cc1.png", part.filename)
    }

    @Test
    fun `a completed imageGeneration with no saved file falls back to a data uri`() {
        val part = parse("t1", "item/completed", imageGenerationItem("completed", null)).messages.single().parts.single()

        assertEquals("file", part.type)
        assertEquals("data:image/png;base64,iVBORw0KGgoAAAANSUhEUg", part.url)
    }

    @Test
    fun `an imageGeneration in progress is a running tool part without the image bytes`() {
        val part =
            parse("t1", "item/started", imageGenerationItem("inProgress", null, result = "")).messages.single().parts.single()

        assertEquals("tool", part.type)
        assertEquals("image_gen", part.tool)
        assertEquals("running", part.state?.get("status")?.jsonPrimitive?.content)
        assertEquals("A fluffy white rabbit in a meadow", part.state?.get("input")?.jsonPrimitive?.content)
    }

    @Test
    fun `the image part keeps the same id from start to completion so it replaces the running tool`() {
        val parser = CodexItemParser()

        fun parseWith(
            method: String,
            body: String,
        ) = parser.handleNotification("t1", method, json.parseToJsonElement(body).jsonObject)
        val started = parseWith("item/started", imageGenerationItem("inProgress", null, result = "")).messages.single().parts.single()
        val completed =
            parseWith("item/completed", imageGenerationItem("completed", "/root/.codex/generated_images/t1/exec-8cc1.png"))
                .messages.single().parts

        assertEquals(started.id, completed.single().id)
    }

    @Test
    fun `an item type with no dedicated mapping still surfaces instead of vanishing`() {
        val parsed =
            parse(
                "t1",
                "item/completed",
                """{"item":{"type":"webSearch","id":"ws-1","query":"kotlin coroutines","action":null,"results":null},
                    "threadId":"t1","turnId":"turn-3","completedAtMs":1}""",
            )

        val part = parsed.messages.single().parts.single()
        assertEquals("tool", part.type)
        assertEquals("webSearch", part.tool)
    }

    @Test
    fun `turn completion forgets the in-progress assistant message for that turn`() {
        val parser = CodexItemParser()
        parser.handleNotification(
            "t1",
            "item/completed",
            json.parseToJsonElement(
                """{"item":{"type":"agentMessage","id":"item-1","text":"partial"},"threadId":"t1","turnId":"turn-9","completedAtMs":1}""",
            ).jsonObject,
        )
        parser.handleNotification(
            "t1",
            "turn/completed",
            json.parseToJsonElement("""{"threadId":"t1","turn":{"id":"turn-9"}}""").jsonObject,
        )

        // A later item under the same turn id (a fork or an id reused after eviction) starts a fresh
        // message rather than silently resurrecting the forgotten one's parts.
        val after =
            parser.handleNotification(
                "t1",
                "item/completed",
                json.parseToJsonElement(
                    """{"item":{"type":"agentMessage","id":"item-2","text":"fresh"},"threadId":"t1","turnId":"turn-9","completedAtMs":2}""",
                ).jsonObject,
            )

        assertEquals(1, after.messages.single().parts.size)
        assertEquals("fresh", after.messages.single().text)
    }
}
