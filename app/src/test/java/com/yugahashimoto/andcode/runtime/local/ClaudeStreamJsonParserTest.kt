package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeStreamJsonParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun parser() = ClaudeStreamJsonParser("session-1", json)

    @Test
    fun `reads the session id, model and catalogue from the init line`() {
        val parsed =
            parser().parse(
                """
                {"type":"system","subtype":"init","session_id":"abc","model":"claude-sonnet-5",
                 "slash_commands":["/review","/ship"],"skills":["pdf"]}
                """.trimIndent().replace("\n", ""),
            )

        assertEquals("abc", parsed.claudeSessionId)
        assertEquals("claude-sonnet-5", parsed.resolvedModel)
        assertEquals(listOf("/review", "/ship"), parsed.slashCommands)
        assertEquals(listOf("pdf"), parsed.skills)
    }

    @Test
    fun `turns assistant text into a message part`() {
        val parsed =
            parser().parse(
                """{"type":"assistant","message":{"id":"m1","model":"claude-opus-5","content":[{"type":"text","text":"hi"}]}}""",
            )

        assertEquals("claude-opus-5", parsed.resolvedModel)
        assertEquals(1, parsed.messages.size)
        assertEquals("assistant", parsed.messages[0].info.role)
        assertEquals("hi", parsed.messages[0].parts[0].text)
    }

    @Test
    fun `reports tool results as assistant activity rather than a user turn`() {
        val parsed =
            parser().parse(
                """{"type":"user","message":{"id":"m2","content":[{"type":"tool_result","tool_use_id":"t1","content":"done"}]}}""",
            )

        assertEquals("assistant", parsed.messages.single().info.role)
        assertEquals("t1", parsed.messages.single().parts.single().callID)
    }

    @Test
    fun `takes the todo list from a TodoWrite call`() {
        val parsed =
            parser().parse(
                """
                {"type":"assistant","message":{"id":"m3","content":[{"type":"tool_use","id":"t2","name":"TodoWrite",
                 "input":{"todos":[{"content":"Write tests","status":"in_progress","priority":"high"},
                 {"content":"Ship","status":"pending"}]}}]}}
                """.trimIndent().replace("\n", ""),
            )

        val todos = requireNotNull(parsed.todos)
        assertEquals(listOf("Write tests", "Ship"), todos.map { it.content })
        assertEquals(listOf("in_progress", "pending"), todos.map { it.status })
        // Claude omits the priority; the UI still needs one.
        assertEquals("medium", todos[1].priority)
    }

    @Test
    fun `leaves the todo list untouched for other tools`() {
        val parsed =
            parser().parse("""{"type":"assistant","message":{"id":"m4","content":[{"type":"tool_use","id":"t3","name":"Read"}]}}""")

        assertNull(parsed.todos)
    }

    @Test
    fun `streams partial text against the message being written`() {
        val parser = parser()
        parser.parse("""{"type":"assistant","message":{"id":"m5","content":[{"type":"text","text":""}]}}""")

        val parsed = parser.parse("""{"type":"stream_event","event":{"delta":{"type":"text_delta","text":"chunk"}}}""")

        val delta = parsed.events.single() as OpenCodeEvent.MessagePartDelta
        assertEquals("m5", delta.messageId)
        assertEquals("chunk", delta.delta)
        assertEquals("text", delta.field)
    }

    @Test
    fun `finishes the turn on a successful result`() {
        val parsed = parser().parse("""{"type":"result","subtype":"success","session_id":"abc"}""")

        assertTrue(parsed.turnFinished)
        assertNull(parsed.errorMessage)
        assertTrue(parsed.events.single() is OpenCodeEvent.SessionIdle)
    }

    @Test
    fun `turnFinished tracks the live turn and resets on beginTurn`() {
        val parser = parser()
        parser.parse("""{"type":"result","subtype":"success","session_id":"abc"}""")
        assertTrue(parser.turnFinished)

        parser.beginTurn()
        assertFalse(parser.turnFinished)
    }

    @Test
    fun `surfaces a failed result as an error and then idles`() {
        val parsed = parser().parse("""{"type":"result","subtype":"error_during_execution","result":"boom"}""")

        assertEquals("boom", parsed.errorMessage)
        assertTrue(parsed.events.first() is OpenCodeEvent.SessionError)
        assertTrue(parsed.events.last() is OpenCodeEvent.SessionIdle)
    }

    @Test
    fun `merges the final assistant message onto the streamed text instead of duplicating it`() {
        val parser = parser()
        parser.parse("""{"type":"assistant","message":{"id":"m5","content":[{"type":"text","text":""}]}}""")
        parser.parse("""{"type":"stream_event","event":{"delta":{"type":"text_delta","text":"chunk"}}}""")

        // Claude Code replays the full text as a final "assistant" line once streaming for the
        // block is done; a text content block never carries an "id", so this must land on the same
        // synthesized part id the deltas used above rather than becoming a second, duplicate part.
        val replayed =
            parser.parse("""{"type":"assistant","message":{"id":"m5","content":[{"type":"text","text":"chunk"}]}}""")

        val parts = replayed.messages.single().parts
        assertEquals(1, parts.size)
        assertEquals("chunk", parts.single().text)
    }

    @Test
    fun `routes a tool_result back onto the message that started the tool instead of stranding it`() {
        val parser = parser()
        parser.parse(
            """{"type":"assistant","message":{"id":"m-tool","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{}}]}}""",
        )

        // The result line reports its own, unrelated message id ("m-result"), the way Claude Code's
        // CLI actually behaves; the running tool card lives on "m-tool" and must be updated there.
        val resultParsed =
            parser.parse(
                """{"type":"user","message":{"id":"m-result","content":[{"type":"tool_result","tool_use_id":"t1","content":"done"}]}}""",
            )

        val message = resultParsed.messages.single()
        assertEquals("m-tool", message.info.id)
        val tool = message.parts.single { it.id == "t1" }
        assertEquals("completed", tool.state?.get("status")?.jsonPrimitive?.content)
    }

    @Test
    fun `settles open tools when a turn ends with an error`() {
        val parser = parser()
        parser.parse(
            """{"type":"assistant","message":{"id":"m-tool","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"grep"}}]}}""",
        )

        val failed =
            parser.parse(
                """{"type":"result","subtype":"error_during_execution","result":"API disconnected","session_id":"abc"}""",
            )

        val recovered = failed.messages.single()
        val tool = recovered.parts.single()
        assertEquals("m-tool", recovered.info.id)
        assertEquals("t1", tool.id)
        assertEquals("error", tool.state?.get("status")?.jsonPrimitive?.content)
        assertEquals("API disconnected", tool.state?.get("error")?.jsonPrimitive?.content)
        assertTrue(failed.events.any { event -> event is OpenCodeEvent.MessagePartUpdated })
    }

    @Test
    fun `ignores a line that is not JSON`() {
        val parsed = parser().parse("not json at all")

        assertTrue(parsed.events.isEmpty())
        assertTrue(parsed.messages.isEmpty())
    }
}
