package com.yugahashimoto.andcode.feature.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTranscriptAccumulatorTest {
    @Test
    fun `concatenates Japanese recognition segments without adding spaces`() {
        val accumulator = SpeechTranscriptAccumulator()

        accumulator.append("前半")
        accumulator.append("後半")

        assertEquals("前半後半", accumulator.text)
    }

    @Test
    fun `keeps a word boundary between Latin recognition segments`() {
        val accumulator = SpeechTranscriptAccumulator()

        accumulator.append("hello")
        accumulator.append("world")

        assertEquals("hello world", accumulator.text)
    }
}
