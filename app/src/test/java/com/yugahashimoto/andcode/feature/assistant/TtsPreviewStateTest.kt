package com.yugahashimoto.andcode.feature.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPreviewStateTest {
    @Test
    fun `playback progress maps onto what the button has to show`() {
        assertEquals(TtsPreviewState.PREPARING, TtsPreviewState.from(TTSState.Preparing))
        assertEquals(TtsPreviewState.SPEAKING, TtsPreviewState.from(TTSState.Speaking))
        assertEquals(TtsPreviewState.IDLE, TtsPreviewState.from(TTSState.Done))
        assertEquals(TtsPreviewState.FAILED, TtsPreviewState.from(TTSState.Error("no engine")))
    }

    @Test
    fun `a press starts playback when nothing is running`() {
        assertEquals(TtsPreviewAction.SPEAK, TtsPreviewState.IDLE.pressAction())
        assertEquals(TtsPreviewAction.SPEAK, TtsPreviewState.FAILED.pressAction())
    }

    @Test
    fun `a press stops playback that is already under way`() {
        // Including PREPARING: a cloud provider can take seconds to return audio, and a second
        // press during that wait means "cancel", not "queue another one".
        assertEquals(TtsPreviewAction.STOP, TtsPreviewState.PREPARING.pressAction())
        assertEquals(TtsPreviewAction.STOP, TtsPreviewState.SPEAKING.pressAction())
    }

    @Test
    fun `only a run in progress counts as busy`() {
        assertTrue(TtsPreviewState.PREPARING.isRunning)
        assertTrue(TtsPreviewState.SPEAKING.isRunning)
        assertFalse(TtsPreviewState.IDLE.isRunning)
        assertFalse(TtsPreviewState.FAILED.isRunning)
    }
}
