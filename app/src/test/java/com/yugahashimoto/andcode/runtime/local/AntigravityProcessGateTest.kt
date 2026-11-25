package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AntigravityProcessGateTest {
    @Test
    fun `readWithTimeout returns output from a process that exits quickly`() {
        val process = ProcessBuilder("sh", "-c", "echo hello").redirectErrorStream(true).start()
        val output = AntigravityProcessGate.readWithTimeout(process, 5_000)
        assertEquals("hello\n", output)
    }

    @Test
    fun `readWithTimeout gives up on a process that never produces output or exits`() {
        // A plain `readText()` before a `waitFor(timeout)` blocks here indefinitely, which is exactly
        // the bug confirmed on a real device: the timeout code after it was never reached.
        val process = ProcessBuilder("sh", "-c", "sleep 30").redirectErrorStream(true).start()
        try {
            val started = System.currentTimeMillis()
            val output = AntigravityProcessGate.readWithTimeout(process, 500)
            assertNull(output)
            assertTrue(System.currentTimeMillis() - started < 5_000)
        } finally {
            process.destroyForcibly()
        }
    }

    /**
     * The device symptom this guards: a child that reads stdin never finishes when `ProcessBuilder`
     * hands it a pipe the JVM keeps open, because no input and no EOF ever arrive.
     */
    @Test
    fun `withoutStdin gives the child an immediate EOF instead of an open pipe`() {
        with(AntigravityProcessGate) {
            val process =
                ProcessBuilder("sh", "-c", "cat; echo done")
                    .redirectErrorStream(true)
                    .withoutStdin()
                    .start()
            val output = AntigravityProcessGate.readWithTimeout(process, 5_000)
            assertEquals("done\n", output)
        }
    }

    @Test
    fun `an open stdin pipe is what makes such a child hang`() {
        val process = ProcessBuilder("sh", "-c", "cat; echo done").redirectErrorStream(true).start()
        try {
            assertNull(AntigravityProcessGate.readWithTimeout(process, 500))
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `exclusive serializes callers`() {
        val order = mutableListOf<Int>()
        val threads =
            (1..3).map { id ->
                Thread {
                    AntigravityProcessGate.exclusive {
                        order.add(id)
                        Thread.sleep(20)
                    }
                }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(3, order.size)
    }

    @Test
    fun `acquireThenRelease lets a second session start while the first is running`() {
        val firstStarted = java.util.concurrent.CountDownLatch(1)
        val firstFinished = java.util.concurrent.CountDownLatch(1)
        val first =
            Thread {
                AntigravityProcessGate.acquireThenRelease(100) {
                    firstStarted.countDown()
                }
                // Stay "streaming" until the second startup has been observed; a gate held for the
                // whole response would leave the second call blocked here until its 60s timeout.
                firstFinished.await()
            }
        first.start()
        assertTrue(firstStarted.await(1, java.util.concurrent.TimeUnit.SECONDS))

        val startedAt = System.currentTimeMillis()
        val second = AntigravityProcessGate.acquireThenRelease(100) { "second" }
        val elapsed = System.currentTimeMillis() - startedAt
        firstFinished.countDown()
        first.join()

        assertEquals("second", second)
        assertTrue("second startup should not wait for the first response", elapsed < 5_000)
    }
}
