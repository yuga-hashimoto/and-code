package com.yugahashimoto.andcode.core.diagnostics

import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageError
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodePart
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStallDiagnosisTest {
    @Test
    fun `an unreachable runtime outranks everything else the probe found`() {
        val diagnosis =
            diagnoseStall(
                StallEvidence(
                    silentForMillis = 200_000L,
                    runtimeReachable = false,
                    runtimeError = "connection refused",
                    awaitingPermission = true,
                    transcript = RunSignals(runningTool = "bash"),
                ),
            )

        assertEquals(StallReason.RUNTIME_UNREACHABLE, diagnosis.reason)
        assertEquals("connection refused", diagnosis.detail)
        assertEquals(200_000L, diagnosis.silentForMillis)
    }

    @Test
    fun `a recorded provider failure is reported with the provider's own words`() {
        val diagnosis =
            diagnoseStall(
                StallEvidence(
                    silentForMillis = 200_000L,
                    transcript = RunSignals(providerFailed = true, providerError = "rate limited"),
                ),
            )

        assertEquals(StallReason.PROVIDER_ERROR, diagnosis.reason)
        assertEquals("rate limited", diagnosis.detail)
        assertTrue(diagnosis.isTerminal)
    }

    @Test
    fun `a turn the transcript shows as finished means the completion went missing`() {
        val diagnosis =
            diagnoseStall(
                StallEvidence(
                    silentForMillis = 200_000L,
                    streamConnected = false,
                    transcript = RunSignals(turnCompleted = true),
                ),
            )

        assertEquals(StallReason.COMPLETION_MISSED, diagnosis.reason)
        assertTrue(diagnosis.isTerminal)
    }

    @Test
    fun `an unanswered approval is named ahead of a dead stream`() {
        val diagnosis =
            diagnoseStall(
                StallEvidence(
                    silentForMillis = 200_000L,
                    streamConnected = false,
                    awaitingPermission = true,
                ),
            )

        assertEquals(StallReason.AWAITING_PERMISSION, diagnosis.reason)
        assertFalse(diagnosis.isTerminal)
    }

    @Test
    fun `an unanswered question is named ahead of a dead stream`() {
        val diagnosis =
            diagnoseStall(
                StallEvidence(
                    silentForMillis = 200_000L,
                    streamConnected = false,
                    awaitingQuestion = true,
                ),
            )

        assertEquals(StallReason.AWAITING_QUESTION, diagnosis.reason)
    }

    @Test
    fun `a dead stream explains a run that may still be working`() {
        val diagnosis =
            diagnoseStall(
                StallEvidence(
                    silentForMillis = 200_000L,
                    streamConnected = false,
                    streamError = "unexpected end of stream",
                    transcript = RunSignals(runningTool = "bash"),
                ),
            )

        assertEquals(StallReason.STREAM_DISCONNECTED, diagnosis.reason)
        assertEquals("unexpected end of stream", diagnosis.detail)
    }

    @Test
    fun `a tool still in flight is named as what the run is waiting on`() {
        val diagnosis =
            diagnoseStall(
                StallEvidence(silentForMillis = 200_000L, transcript = RunSignals(runningTool = "gradle test")),
            )

        assertEquals(StallReason.TOOL_RUNNING, diagnosis.reason)
        assertEquals("gradle test", diagnosis.detail)
    }

    @Test
    fun `a reachable runtime producing nothing is the plain stall`() {
        val diagnosis = diagnoseStall(StallEvidence(silentForMillis = 200_000L))

        assertEquals(StallReason.NO_OUTPUT, diagnosis.reason)
        assertNull(diagnosis.detail)
    }

    @Test
    fun `inspectRun reads a completed turn off the last assistant message`() {
        val signals = inspectRun(listOf(assistantMessage(completed = 42L)))

        assertTrue(signals.turnCompleted)
        assertNull(signals.providerError)
        assertNull(signals.runningTool)
    }

    @Test
    fun `inspectRun does not call a turn complete while a tool call is still in flight`() {
        val signals =
            inspectRun(
                listOf(assistantMessage(completed = 42L, tool = "bash" to "running")),
            )

        assertFalse(signals.turnCompleted)
        assertEquals("bash", signals.runningTool)
    }

    @Test
    fun `inspectRun prefers the tool's own title when it has one`() {
        val signals =
            inspectRun(
                listOf(assistantMessage(tool = "bash" to "running", toolTitle = "./gradlew test")),
            )

        assertEquals("./gradlew test", signals.runningTool)
    }

    @Test
    fun `inspectRun treats a turn the user stopped as over, tool call and all`() {
        val signals =
            inspectRun(
                listOf(
                    assistantMessage(
                        error = OpenCodeMessageError(name = "MessageAbortedError"),
                        tool = "bash" to "running",
                    ),
                ),
            )

        assertNull(signals.providerError)
        assertFalse(signals.providerFailed)
        // The stop left the tool call sitting in `running`; reporting that as work in flight would
        // put a "still running" warning on a turn the user ended.
        assertNull(signals.runningTool)
        assertTrue(signals.turnCompleted)
    }

    @Test
    fun `a failure with nothing to quote is still a failure`() {
        val signals = inspectRun(listOf(assistantMessage(error = OpenCodeMessageError())))

        assertTrue(signals.providerFailed)
        assertNull(signals.providerError)

        val diagnosis = diagnoseStall(StallEvidence(silentForMillis = 200_000L, transcript = signals))

        assertEquals(StallReason.PROVIDER_ERROR, diagnosis.reason)
        assertNull(diagnosis.detail)
    }

    @Test
    fun `inspectRun reports nothing for a transcript that ends on the user's prompt`() {
        val messages =
            listOf(
                assistantMessage(completed = 1L),
                OpenCodeMessage(info = OpenCodeMessageInfo(id = "m2", sessionId = "s1", role = "user")),
            )

        assertEquals(RunSignals(), inspectRun(messages))
    }

    @Test
    fun `inspectRun falls back to the error name when the provider gave no message`() {
        val signals = inspectRun(listOf(assistantMessage(error = OpenCodeMessageError(name = "ApiError"))))

        assertEquals("ApiError", signals.providerError)
    }

    private fun assistantMessage(
        completed: Long? = null,
        tool: Pair<String, String>? = null,
        toolTitle: String? = null,
        error: OpenCodeMessageError? = null,
    ): OpenCodeMessage =
        OpenCodeMessage(
            info =
                OpenCodeMessageInfo(
                    id = "m1",
                    sessionId = "s1",
                    role = "assistant",
                    time = OpenCodeTime(created = 1L, completed = completed),
                    error = error,
                ),
            parts =
                listOfNotNull(
                    tool?.let { (name, status) ->
                        OpenCodePart(
                            id = "p1",
                            type = "tool",
                            tool = name,
                            state =
                                buildMap {
                                    put("status", JsonPrimitive(status))
                                    toolTitle?.let { put("title", JsonPrimitive(it)) }
                                },
                        )
                    },
                ),
        )
}
