package com.yugahashimoto.andcode.feature.schedule

import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodePart
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val IDLE_MS = 60 * 60_000L
private const val MAX_MS = 12 * 60 * 60_000L

class ScheduleRunWatchdogTest {
    @Test
    fun `a run that keeps talking is never given up on`() {
        val outcome =
            scheduleRunTimeout(
                elapsedMs = 6 * 60 * 60_000L,
                sinceProgressMs = 30_000L,
                idleTimeoutMs = IDLE_MS,
                maxDurationMs = MAX_MS,
            )

        assertNull("a long run is not a dead run", outcome)
    }

    @Test
    fun `silence past the idle window ends the run`() {
        val outcome =
            scheduleRunTimeout(
                elapsedMs = 2 * IDLE_MS,
                sinceProgressMs = IDLE_MS,
                idleTimeoutMs = IDLE_MS,
                maxDurationMs = MAX_MS,
            )

        assertEquals(ScheduleRunTimeout.IDLE, outcome)
    }

    @Test
    fun `a run that never ends still hits the ceiling`() {
        val outcome =
            scheduleRunTimeout(
                elapsedMs = MAX_MS,
                sinceProgressMs = 1_000L,
                idleTimeoutMs = IDLE_MS,
                maxDurationMs = MAX_MS,
            )

        assertEquals(ScheduleRunTimeout.MAX_DURATION, outcome)
    }

    @Test
    fun `only the run's own session counts as progress`() {
        assertEquals("ses-1", progressSessionIdOf(OpenCodeEvent.SessionIdle("ses-1")))
        assertEquals("ses-1", progressSessionIdOf(OpenCodeEvent.MessagePartUpdated(part(sessionId = "ses-1"))))
        assertEquals("ses-1", progressSessionIdOf(OpenCodeEvent.MessageUpdated(info(sessionId = "ses-1"))))
        assertNull(progressSessionIdOf(OpenCodeEvent.ServerConnected))
        assertNull(progressSessionIdOf(OpenCodeEvent.Unknown("installation.updated", "{}")))
    }

    @Test
    fun `a subagent's birth is progress for the run that spawned it`() {
        val event = OpenCodeEvent.SessionCreated(session(id = "ses-child", parentId = "ses-1"))

        assertEquals("ses-1", progressSessionIdOf(event))
    }

    @Test
    fun `an unrelated new chat is not progress for anyone`() {
        assertNull(progressSessionIdOf(OpenCodeEvent.SessionCreated(session(id = "ses-2"))))
    }

    @Test
    fun `an unchanged transcript keeps the same mark`() {
        val messages = listOf(message(parts = listOf(part(id = "prt-1", text = "half a sen"))))

        assertEquals(transcriptProgressMarkOf(messages), transcriptProgressMarkOf(messages))
    }

    @Test
    fun `text streaming into the open part is progress`() {
        val before = listOf(message(parts = listOf(part(id = "prt-1", text = "half a sen"))))
        val after = listOf(message(parts = listOf(part(id = "prt-1", text = "half a sentence"))))

        assertNotEquals(transcriptProgressMarkOf(before), transcriptProgressMarkOf(after))
    }

    @Test
    fun `a new part in the same message is progress`() {
        val before = listOf(message(parts = listOf(part(id = "prt-1", text = "thinking"))))
        val after =
            listOf(
                message(parts = listOf(part(id = "prt-1", text = "thinking"), part(id = "prt-2", type = "tool"))),
            )

        assertNotEquals(transcriptProgressMarkOf(before), transcriptProgressMarkOf(after))
    }

    @Test
    fun `a new message is progress`() {
        val before = listOf(message(id = "msg-1"))
        val after = listOf(message(id = "msg-1"), message(id = "msg-2"))

        assertNotEquals(transcriptProgressMarkOf(before), transcriptProgressMarkOf(after))
    }

    private fun message(
        id: String = "msg-1",
        parts: List<OpenCodePart> = emptyList(),
    ) = OpenCodeMessage(info = info(id = id), parts = parts)

    private fun info(
        id: String = "msg-1",
        sessionId: String = "ses-1",
    ) = OpenCodeMessageInfo(id = id, sessionId = sessionId, role = "assistant", time = OpenCodeTime(created = 1L))

    private fun part(
        id: String = "prt-1",
        sessionId: String = "ses-1",
        type: String = "text",
        text: String? = null,
    ) = OpenCodePart(id = id, sessionId = sessionId, messageId = "msg-1", type = type, text = text)

    private fun session(
        id: String = "ses-1",
        parentId: String? = null,
    ) = OpenCodeSession(id = id, parentId = parentId, title = "run", time = OpenCodeTime(created = 1L))
}
