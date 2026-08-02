package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream

/**
 * Which sign-in attempt is allowed to speak for the coordinator.
 *
 * Killing the guest process tree is asynchronous, so a cancelled or superseded attempt is still
 * running - and still on its way to reporting its own death - while the state it used to own has
 * already moved on. On device that death arrived as "Antigravity sign-in stopped (exit code 137)",
 * 137 being the coordinator's own SIGKILL: pressing Cancel produced an error, and pressing Sign in a
 * second time failed the attempt that press had just started.
 */
class AntigravityAuthCoordinatorTest {
    private val coordinator =
        AntigravityAuthCoordinator(
            runtimeDirectory = File("/nonexistent"),
            installedRuntimeProvider = { null },
        )

    @Test
    fun `cancelling leaves sign-in idle when the kill it sent lands`() {
        val attempt = coordinator.adopt(AntigravityAuthCoordinator.Attempt(FakeProcess()))

        coordinator.cancel()
        coordinator.onProcessExit(attempt, SIGKILL_EXIT)

        assertEquals(AntigravityAuthCoordinator.State.Idle, coordinator.state.value)
    }

    @Test
    fun `an attempt replaced by a newer one cannot fail the newer one`() {
        val first = coordinator.adopt(AntigravityAuthCoordinator.Attempt(FakeProcess()))
        coordinator.adopt(AntigravityAuthCoordinator.Attempt(FakeProcess()))

        coordinator.onProcessExit(first, SIGKILL_EXIT)

        assertEquals(AntigravityAuthCoordinator.State.Idle, coordinator.state.value)
    }

    /** The guard above must not swallow the failure the user actually needs to see. */
    @Test
    fun `the live attempt dying is still reported`() {
        val attempt = coordinator.adopt(AntigravityAuthCoordinator.Attempt(FakeProcess()))

        coordinator.onProcessExit(attempt, SIGKILL_EXIT)

        assertTrue(coordinator.state.value is AntigravityAuthCoordinator.State.Failed)
    }

    /** A superseded attempt keeps draining its buffer; none of it belongs to the live transcript. */
    @Test
    fun `transcripts do not leak between attempts`() {
        val abandoned = AntigravityAuthCoordinator.Attempt(FakeProcess())
        val live = AntigravityAuthCoordinator.Attempt(FakeProcess())

        abandoned.append("output of a sign-in the user cancelled")
        live.append("Select login method")

        assertEquals("Select login method", live.clean())
    }

    private class FakeProcess : Process() {
        override fun getOutputStream(): OutputStream =
            object : OutputStream() {
                override fun write(b: Int) = Unit
            }

        override fun getInputStream() = ByteArrayInputStream(ByteArray(0))

        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))

        override fun waitFor() = SIGKILL_EXIT

        override fun exitValue() = SIGKILL_EXIT

        override fun destroy() = Unit

        override fun isAlive() = false
    }

    private companion object {
        const val SIGKILL_EXIT = 137
    }
}
