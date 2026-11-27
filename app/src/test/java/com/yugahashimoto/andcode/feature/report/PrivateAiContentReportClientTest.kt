package com.yugahashimoto.andcode.feature.report

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateAiContentReportClientTest {
    @Test
    fun `submits only the reviewed excerpt and reason without credentials`() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(201))
                val client = PrivateAiContentReportClient(server.url("/v1/reports").toString())
                assertTrue(client.submit(AiReportReason.OTHER, "  reviewed text  ").isSuccess)
                val request = server.takeRequest()
                assertNull(request.getHeader("Authorization"))
                assertNull(request.getHeader("Cookie"))
                val body = JSONObject(request.body.readUtf8())
                assertEquals(setOf("reason", "excerpt"), body.keys().asSequence().toSet())
                assertEquals("OTHER", body.getString("reason"))
                assertEquals("reviewed text", body.getString("excerpt"))
            }
        }

    @Test
    fun `rejects invalid input and redirects without forwarding`() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/elsewhere")))
                val client = PrivateAiContentReportClient(server.url("/v1/reports").toString())
                assertTrue(client.submit(AiReportReason.OTHER, "  ").isFailure)
                assertTrue(client.submit(AiReportReason.OTHER, "sk-abcdefghijklmnopqrstuv").isFailure)
                assertTrue(client.submit(AiReportReason.OTHER, "x".repeat(4001)).isFailure)
                assertTrue(client.submit(AiReportReason.OTHER, "reviewed text").isFailure)
                assertEquals(1, server.requestCount)
            }
        }

    @Test
    fun `refuses insecure or credential-bearing endpoints`() {
        for (url in listOf(
            "http://example.org/v1/reports",
            "https://user:pass@example.org/v1/reports",
            "https://example.org/v1/reports?token=foo",
            "https://example.org/v1/reports#fragment",
        )) {
            assertThrows(IllegalArgumentException::class.java) { PrivateAiContentReportClient(url) }
        }
    }
}
