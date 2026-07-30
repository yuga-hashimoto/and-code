package com.yugahashimoto.andcode.feature.wakeword

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenWakeWordDetectorTest {
    @Test
    fun `default bundled model listens for hey Mycroft`() {
        assertEquals("hey_mycroft", OpenWakeWordDetector.DEFAULT_MODEL)
        assertEquals("Hey mycroft", OpenWakeWordDetector.keywordForModel(OpenWakeWordDetector.DEFAULT_MODEL))
    }

    @Test
    fun `PCM16 amplitudes are not normalized before feature extraction`() {
        val converted = OpenWakeWordDetector.pcm16ToFeatureInput(shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE))

        assertArrayEquals(floatArrayOf(-32768f, 0f, 32767f), converted, 0f)
    }

    @Test
    fun `all new mel frames are retained in the rolling window`() {
        val existing = Array(76) { floatArrayOf(it.toFloat()) }
        val incoming = Array(8) { floatArrayOf((100 + it).toFloat()) }

        val result = OpenWakeWordDetector.appendFeatureFrames(existing, incoming, 76)

        assertEquals(76, result.size)
        assertArrayEquals(floatArrayOf(8f), result.first(), 0f)
        assertArrayEquals(floatArrayOf(107f), result.last(), 0f)
    }
}
