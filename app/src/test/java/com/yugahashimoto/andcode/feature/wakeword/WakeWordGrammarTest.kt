package com.yugahashimoto.andcode.feature.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordGrammarTest {
    @Test
    fun `what the user types is folded to what the recogniser expects`() {
        assertEquals("hey and code", WakeWordGrammar.normalize("  Hey   And Code  "))
        assertEquals("jarvis", WakeWordGrammar.normalize("JARVIS"))
    }

    @Test
    fun `a blank phrase falls back rather than arming an empty grammar`() {
        // An empty grammar matches nothing at all, which would look exactly like a broken
        // microphone from the outside.
        assertEquals(WakeWordGrammar.DEFAULT_PHRASE, WakeWordGrammar.normalize(""))
        assertEquals(WakeWordGrammar.DEFAULT_PHRASE, WakeWordGrammar.normalize("   "))
    }

    @Test
    fun `the grammar carries the phrase and a bin for everything else`() {
        val grammar = WakeWordGrammar.grammarFor("hey and code")

        assertEquals("""["hey and code","[unk]"]""", grammar)
    }

    @Test
    fun `quotes in a phrase cannot break out of the grammar`() {
        // The phrase is free text, so it reaches this as whatever was typed.
        val grammar = WakeWordGrammar.grammarFor("""say "hi"""")

        assertTrue(grammar, grammar.startsWith("["))
        assertTrue(grammar, grammar.contains("\\\""))
    }

    @Test
    fun `the fallback bin is never listed twice`() {
        val grammar = WakeWordGrammar.grammarFor(WakeWordGrammar.UNKNOWN)

        assertEquals("""["[unk]"]""", grammar)
    }
}
