package com.opencode.android.core.api

import com.opencode.android.data.connection.ConnectionProfile
import com.opencode.android.runtime.remote.RemoteOpenCodeBackend
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Guards the live-reply path at the point it actually broke before: decoding.
 *
 * The chat drops any streamed part whose session, message or part id fails to decode, and it ends
 * a run off the assistant message's completion time. A regression in any of those fields produces
 * a chat that looks connected, accepts prompts, and simply never updates — so each is pinned here
 * against the wire format the OpenCode server really emits.
 */
class ChatStreamDecodingTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun backend(): RemoteOpenCodeBackend {
        val profile =
            ConnectionProfile(
                id = "remote",
                name = "Remote",
                baseUrl = server.url("/").toString(),
                username = "opencode",
            )
        val http =
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
        return RemoteOpenCodeBackend(profile, OpenCodeApiClient(profile, http))
    }

    @Test
    fun `streamed parts arrive over a real event stream with their identity intact`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(
                        "data: " +
                            """{"type":"message.part.updated","properties":{"part":{"id":"prt_1",""" +
                            """"sessionID":"ses_1","messageID":"msg_a","type":"text","text":"Hello"}}}""" +
                            "\n\n" +
                            "data: " +
                            """{"type":"session.idle","properties":{"sessionID":"ses_1"}}""" +
                            "\n\n",
                    ),
            )

            val events =
                withTimeout(10_000) {
                    backend().events().take(2).toList()
                }

            val part = (events[0] as OpenCodeEvent.MessagePartUpdated).part
            assertEquals("ses_1", part.sessionId)
            assertEquals("msg_a", part.messageId)
            assertEquals("prt_1", part.id)
            assertEquals("Hello", part.text)
            assertEquals("ses_1", (events[1] as OpenCodeEvent.SessionIdle).sessionId)
        }

    @Test
    fun `tool part keeps the state the chat renders from`() {
        val event =
            OpenCodeEventParser().parse(
                """{"type":"message.part.updated","properties":{"part":{"id":"prt_2",""" +
                    """"sessionID":"ses_1","messageID":"msg_a","type":"tool","tool":"bash",""" +
                    """"callID":"call_1","state":{"status":"running","input":{"command":"ls -la"}}}}}""",
            )

        assertTrue("parsed as $event", event is OpenCodeEvent.MessagePartUpdated)
        val part = (event as OpenCodeEvent.MessagePartUpdated).part
        assertEquals("tool", part.type)
        assertEquals("bash", part.tool)
        assertTrue("tool state was dropped: ${part.state}", part.state != null)
    }

    @Test
    fun `assistant completion time survives decoding`() =
        runBlocking {
            // Ending a run is driven off this field; without it the composer sits on the stop
            // button until the fallback timeout expires.
            server.enqueue(
                MockResponse().setBody(
                    """[{"info":{"id":"msg_a","sessionID":"ses_1","role":"assistant",""" +
                        """"time":{"created":1,"completed":2}},"parts":[{"id":"prt_1",""" +
                        """"sessionID":"ses_1","messageID":"msg_a","type":"text","text":"Stored"}]}]""",
                ),
            )

            val messages = backend().listMessages("ses_1")

            assertEquals(1, messages.size)
            assertEquals("ses_1", messages.single().info.sessionId)
            assertEquals(2L, messages.single().info.time.completed)
            assertEquals("Stored", messages.single().text)
        }

    @Test
    fun `a running assistant message reports no completion time`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """[{"info":{"id":"msg_a","sessionID":"ses_1","role":"assistant",""" +
                        """"time":{"created":1}},"parts":[{"id":"prt_1","sessionID":"ses_1",""" +
                        """"messageID":"msg_a","type":"text","text":"Working"}]}]""",
                ),
            )

            val messages = backend().listMessages("ses_1")

            assertEquals(null, messages.single().info.time.completed)
        }
}
