package com.opencode.android.core.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeEventParserTest {
    private val parser = OpenCodeEventParser()

    @Test
    fun `parses server connected event`() {
        val event = parser.parse("""{"type":"server.connected","properties":{}}""")
        assertTrue(event is OpenCodeEvent.ServerConnected)
    }

    @Test
    fun `parses streamed text part update`() {
        val event = parser.parse(
            """{"type":"message.part.updated","properties":{"part":{"id":"p1","sessionID":"s1","messageID":"m1","type":"text","text":"Hello"}}}"""
        ) as OpenCodeEvent.MessagePartUpdated

        assertEquals("s1", event.part.sessionId)
        assertEquals("Hello", event.part.text)
    }

    @Test
    fun `parses streamed text delta`() {
        val event = parser.parse(
            """{"type":"message.part.delta","properties":{"sessionID":"s1","messageID":"m1","partID":"p1","field":"text","delta":"Hello"}}"""
        ) as OpenCodeEvent.MessagePartDelta

        assertEquals("s1", event.sessionId)
        assertEquals("m1", event.messageId)
        assertEquals("p1", event.partId)
        assertEquals("text", event.field)
        assertEquals("Hello", event.delta)
    }

    @Test
    fun `parses tool part update preserving state map`() {
        val event = parser.parse(
            """{"type":"message.part.updated","properties":{"part":{"id":"p1","sessionID":"s1","messageID":"m1","type":"tool","tool":"bash","callID":"call-1","state":{"status":"running","input":{"command":"ls -la"}}}}}"""
        ) as OpenCodeEvent.MessagePartUpdated

        assertEquals("tool", event.part.type)
        assertEquals("bash", event.part.tool)
        assertEquals("call-1", event.part.callID)
        assertEquals("running", event.part.state?.get("status"))
        val input = event.part.state?.get("input") as Map<*, *>
        assertEquals("ls -la", input["command"])
    }

    @Test
    fun `parses permission request`() {
        val event = parser.parse(
            """{"type":"permission.asked","properties":{"id":"perm1","sessionID":"s1","permission":"bash","patterns":["git status"]}}"""
        ) as OpenCodeEvent.PermissionAsked

        assertEquals("perm1", event.request.id)
        assertEquals("bash", event.request.permission)
        assertEquals(listOf("git status"), event.request.patterns)
    }

    @Test
    fun `parses session idle event`() {
        val event = parser.parse(
            """{"type":"session.idle","properties":{"sessionID":"s1"}}"""
        ) as OpenCodeEvent.SessionIdle

        assertEquals("s1", event.sessionId)
    }

    @Test
    fun `unwraps the global event stream envelope`() {
        val event = parser.parse(
            """{"directory":"/root/project","project":"prj","payload":{"id":"evt_1","type":"message.part.delta","properties":{"sessionID":"s1","messageID":"m1","partID":"p1","field":"text","delta":"Hi"}}}"""
        ) as OpenCodeEvent.MessagePartDelta

        assertEquals("s1", event.sessionId)
        assertEquals("Hi", event.delta)
    }

    @Test
    fun `unwraps a permission request from the global event stream`() {
        val event = parser.parse(
            """{"directory":"/root/project","payload":{"id":"evt_2","type":"permission.asked","properties":{"id":"perm1","sessionID":"s1","permission":"bash","patterns":["git status"],"metadata":{}}}}"""
        ) as OpenCodeEvent.PermissionAsked

        assertEquals("perm1", event.request.id)
        assertEquals("s1", event.request.sessionId)
    }

    @Test
    fun `parses message updated event`() {
        val event = parser.parse(
            """{"type":"message.updated","properties":{"sessionID":"s1","info":{"id":"m1","sessionID":"s1","role":"assistant","time":{"created":1,"completed":2}}}}"""
        ) as OpenCodeEvent.MessageUpdated

        assertEquals("m1", event.info.id)
        assertEquals("assistant", event.info.role)
        assertEquals(2L, event.info.time.completed)
    }

    @Test
    fun `parses permission replied event`() {
        val event = parser.parse(
            """{"type":"permission.replied","properties":{"sessionID":"s1","requestID":"perm1","reply":"once"}}"""
        ) as OpenCodeEvent.PermissionReplied

        assertEquals("s1", event.sessionId)
        assertEquals("perm1", event.requestId)
    }

    @Test
    fun `session error reports the readable message instead of raw json`() {
        val event = parser.parse(
            """{"type":"session.error","properties":{"sessionID":"s1","error":{"name":"ProviderAuthError","data":{"message":"missing api key"}}}}"""
        ) as OpenCodeEvent.SessionError

        assertEquals("s1", event.sessionId)
        assertEquals("ProviderAuthError: missing api key", event.message)
    }

    @Test
    fun `parses session status event`() {
        val event = parser.parse(
            """{"type":"session.status","properties":{"sessionID":"s1","status":{"type":"busy"}}}"""
        ) as OpenCodeEvent.SessionStatusChanged

        assertEquals("s1", event.sessionId)
        assertEquals("busy", event.status)
    }

    @Test
    fun `keeps unknown event without crashing`() {
        val event = parser.parse("""{"type":"future.event","properties":{"value":1}}""")
        assertTrue(event is OpenCodeEvent.Unknown)
        assertEquals("future.event", (event as OpenCodeEvent.Unknown).type)
    }
}
