package com.yugahashimoto.andcode.feature.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BargeInPolicyTest {
    @Test
    fun `with no session running the wake word opens one`() {
        assertEquals(
            WakeWordOutcome.START_SESSION,
            BargeInPolicy.outcomeFor(sessionActive = false, speaking = false, bargeInEnabled = true),
        )
        assertEquals(
            WakeWordOutcome.START_SESSION,
            BargeInPolicy.outcomeFor(sessionActive = false, speaking = false, bargeInEnabled = false),
        )
    }

    @Test
    fun `the wake word cuts the assistant off mid-sentence`() {
        assertEquals(
            WakeWordOutcome.INTERRUPT_SPEECH,
            BargeInPolicy.outcomeFor(sessionActive = true, speaking = true, bargeInEnabled = true),
        )
    }

    @Test
    fun `with barge-in switched off speech is left to finish`() {
        assertEquals(
            WakeWordOutcome.IGNORE,
            BargeInPolicy.outcomeFor(sessionActive = true, speaking = true, bargeInEnabled = false),
        )
    }

    @Test
    fun `a session that is not speaking is never interrupted`() {
        // Nothing to cut off, and a second session on top of the running one is not what the user
        // asked for by saying the wake word.
        assertEquals(
            WakeWordOutcome.IGNORE,
            BargeInPolicy.outcomeFor(sessionActive = true, speaking = false, bargeInEnabled = true),
        )
    }

    @Test
    fun `during a session the microphone is only taken while the assistant speaks`() {
        // The session's own recogniser owns the microphone the rest of the time, and two readers
        // of one microphone is how wake-word capture starts failing on real devices.
        assertTrue(BargeInPolicy.shouldListenDuringSession(speaking = true, bargeInEnabled = true))
        assertFalse(BargeInPolicy.shouldListenDuringSession(speaking = false, bargeInEnabled = true))
        assertFalse(BargeInPolicy.shouldListenDuringSession(speaking = true, bargeInEnabled = false))
    }
}
