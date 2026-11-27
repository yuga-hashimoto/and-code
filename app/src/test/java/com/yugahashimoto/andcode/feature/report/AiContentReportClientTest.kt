package com.yugahashimoto.andcode.feature.report

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AiContentReportClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `rejects non-loopback plain HTTP endpoints`() {
        assertThrows(IllegalArgumentException::class.java) {
            AiContentReportClient({ "token" }, baseUrl = "http://example.com")
        }
    }

    @Test
    fun `sends only explicitly supplied public excerpt and reason`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
            val client = AiContentReportClient({ "user-token" }, baseUrl = server.url("/").toString().trimEnd('/'))
            assertTrue(client.submit(AiReportReason.HARMFUL, "User reviewed excerpt").isSuccess)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/repos/yuga-hashimoto/and-code/issues", request.path)
            assertEquals("Bearer user-token", request.getHeader("Authorization"))
            val body = JSONObject(request.body.readUtf8())
            assertTrue(body.getString("body").contains("User reviewed excerpt"))
            assertTrue(body.getString("body").contains("HARMFUL"))
            assertFalse(body.getString("body").contains("user-token"))
        }

    @Test
    fun `requires an account and rejects blank or oversized excerpts without sending`() =
        runBlocking {
            val missingAccount = AiContentReportClient({ null }, baseUrl = server.url("/").toString().trimEnd('/'))
            assertTrue(missingAccount.submit(AiReportReason.OTHER, "excerpt").isFailure)
            val client = AiContentReportClient({ "token" }, baseUrl = server.url("/").toString().trimEnd('/'))
            assertTrue(client.submit(AiReportReason.OTHER, "  ").isFailure)
            assertTrue(client.submit(AiReportReason.OTHER, "x".repeat(4001)).isFailure)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `server error is a failed report`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(403))
            val client = AiContentReportClient({ "token" }, baseUrl = server.url("/").toString().trimEnd('/'))
            assertTrue(client.submit(AiReportReason.OTHER, "excerpt").isFailure)
        }

    @Test
    fun `requires the issue creation status`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val client = AiContentReportClient({ "token" }, baseUrl = server.url("/").toString().trimEnd('/'))
            assertTrue(client.submit(AiReportReason.OTHER, "excerpt").isFailure)
        }

    @Test
    fun `does not follow redirects while posting a report`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/other").toString()))
            server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
            val client = AiContentReportClient({ "token" }, baseUrl = server.url("/").toString().trimEnd('/'))
            assertTrue(client.submit(AiReportReason.OTHER, "excerpt").isFailure)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `rejects recognized credentials and the active account token`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
            val client = AiContentReportClient({ "private-credential-value" }, baseUrl = server.url("/").toString().trimEnd('/'))
            assertTrue(client.submit(AiReportReason.OTHER, "key=sk-abcdefghijklmnopqrstuv").isFailure)
            assertTrue(client.submit(AiReportReason.OTHER, "private-credential-value").isFailure)
            assertEquals(0, server.requestCount)
        }
}
