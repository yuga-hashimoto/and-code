package com.yugahashimoto.andcode.feature.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsTuningTest {
    @Test
    fun `rate and pitch stay inside the range the sliders offer`() {
        assertEquals(TtsTuning.MAX_RATE, TtsTuning.rate(9f), 0f)
        assertEquals(TtsTuning.MIN_RATE, TtsTuning.rate(0.1f), 0f)
        assertEquals(TtsTuning.MAX_PITCH, TtsTuning.pitch(9f), 0f)
        assertEquals(TtsTuning.MIN_PITCH, TtsTuning.pitch(0.1f), 0f)
    }

    @Test
    fun `a stored zero never reaches the engine, which rejects a non-positive rate`() {
        // TTSProviderConfig.Android requires both values to be > 0, so an unset or corrupt
        // preference would otherwise crash the voice session on construction.
        val config = TtsTuning.androidConfig(enginePackage = null, speechRate = 0f, pitch = 0f)

        assertEquals(TtsTuning.MIN_RATE, config.speechRate, 0f)
        assertEquals(TtsTuning.MIN_PITCH, config.pitch, 0f)
    }

    @Test
    fun `chosen values are passed through to the engine untouched`() {
        val config = TtsTuning.androidConfig(enginePackage = "com.example.tts", speechRate = 1.4f, pitch = 0.8f)

        assertEquals("com.example.tts", config.enginePackage)
        assertEquals(1.4f, config.speechRate, 0f)
        assertEquals(0.8f, config.pitch, 0f)
    }
}
