package com.yugahashimoto.andcode.feature.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WakeWordMatcherTest {
    @Test
    fun `the phrase on its own is a hit`() {
        val detection = WakeWordMatcher.detect("""{"text":"hey and code"}""", "hey and code", sensitivity = 0.7f)

        assertNotNull(detection)
        assertEquals("hey and code", detection?.phrase)
    }

    @Test
    fun `speech that is not the phrase is not a hit`() {
        assertNull(WakeWordMatcher.detect("""{"text":"what time is it"}""", "hey and code", sensitivity = 0.7f))
    }

    @Test
    fun `the unknown bin absorbing speech is not a hit`() {
        // Everything the grammar does not cover comes back as [unk]; that is the normal state of
        // an idle room and must never open a session.
        assertNull(WakeWordMatcher.detect("""{"text":"[unk] [unk]"}""", "hey and code", sensitivity = 0.7f))
    }

    @Test
    fun `an empty or malformed result is not a hit`() {
        assertNull(WakeWordMatcher.detect("""{"text":""}""", "hey and code", sensitivity = 0.7f))
        assertNull(WakeWordMatcher.detect("not json at all", "hey and code", sensitivity = 0.7f))
        assertNull(WakeWordMatcher.detect("", "hey and code", sensitivity = 0.7f))
    }

    @Test
    fun `the phrase inside a longer utterance still counts`() {
        val detection =
            WakeWordMatcher.detect("""{"text":"[unk] hey and code [unk]"}""", "hey and code", sensitivity = 0.7f)

        assertNotNull(detection)
    }

    @Test
    fun `per-word confidence decides against the sensitivity threshold`() {
        val hesitant =
            """{"text":"hey and code","result":[
                {"conf":0.98,"word":"hey"},{"conf":0.41,"word":"and"},{"conf":0.95,"word":"code"}]}"""

        assertNull(WakeWordMatcher.detect(hesitant, "hey and code", sensitivity = 0.7f))
        assertNotNull(WakeWordMatcher.detect(hesitant, "hey and code", sensitivity = 0.4f))
    }

    @Test
    fun `the weakest word decides, not the average`() {
        // One word the recogniser was unsure of is enough to make the whole phrase a guess, and a
        // mean would let two confident words carry it.
        val detection =
            WakeWordMatcher.detect(
                """{"text":"hey and code","result":[
                    {"conf":1.0,"word":"hey"},{"conf":0.5,"word":"and"},{"conf":1.0,"word":"code"}]}""",
                "hey and code",
                sensitivity = 0.0f,
            )

        assertEquals(0.5f, detection?.confidence)
    }

    @Test
    fun `confidences for words outside the phrase are ignored`() {
        val detection =
            WakeWordMatcher.detect(
                """{"text":"[unk] hey and code","result":[
                    {"conf":0.05,"word":"[unk]"},{"conf":0.9,"word":"hey"},
                    {"conf":0.9,"word":"and"},{"conf":0.9,"word":"code"}]}""",
                "hey and code",
                sensitivity = 0.7f,
            )

        assertNotNull(detection)
        assertEquals(0.9f, detection?.confidence)
    }

    @Test
    fun `without any confidence reported the match is taken at face value`() {
        // Grammar mode does not always report per-word scores, and refusing every result that
        // lacks them would mean the wake word never fires at all.
        val detection = WakeWordMatcher.detect("""{"text":"hey and code"}""", "hey and code", sensitivity = 1.0f)

        assertEquals(1.0f, detection?.confidence)
    }
}
