package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.feature.browser.PreviewTarget
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionPreviewAvailabilityTest {
    @Test
    fun `only reachable loopback targets are available`() =
        runTest {
            val targets =
                listOf(
                    PreviewTarget.Url("http://localhost:3000/path"),
                    PreviewTarget.Url("http://127.0.0.1:5173"),
                    PreviewTarget.Url("https://example.com"),
                    PreviewTarget.Static("/project", "index.html"),
                )

            assertEquals(listOf(targets[0]), runningSessionPreviewTargets(targets) { it == 3000 })
        }

    @Test
    fun `default http and https ports are probed when omitted`() =
        runTest {
            val targets =
                listOf(
                    PreviewTarget.Url("http://localhost/app"),
                    PreviewTarget.Url("https://127.0.0.1/secure"),
                )

            assertEquals(targets, runningSessionPreviewTargets(targets) { it == 80 || it == 443 })
        }

    @Test
    fun `each listening port is probed at most once`() =
        runTest {
            val probedPorts = mutableListOf<Int>()
            val targets =
                listOf(
                    PreviewTarget.Url("http://localhost:3000/a"),
                    PreviewTarget.Url("http://127.0.0.1:3000/b"),
                    PreviewTarget.Url("http://localhost:5173/c"),
                )

            val result =
                runningSessionPreviewTargets(targets) { port ->
                    probedPorts += port
                    port == 3000
                }

            assertEquals(listOf(targets[0], targets[1]), result)
            assertEquals(listOf(3000, 5173), probedPorts)
        }
}
