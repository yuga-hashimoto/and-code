package com.yugahashimoto.andcode.feature.wakeword

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * The phrase list a Vosk recogniser is constrained to.
 *
 * Restricting the recogniser to the wake word plus a bin for everything else is what makes this
 * cheap enough to leave running: it has one thing to decide rather than a whole language to
 * transcribe. It is also why any phrase works, which the previous per-phrase trained models could
 * not do - they only ever shipped "hey mycroft".
 */
object WakeWordGrammar {
    /** Vosk's own token for "speech that is not in the grammar". Without it nothing else parses. */
    const val UNKNOWN = "[unk]"

    const val DEFAULT_PHRASE = "hey and code"

    /**
     * Lower-cased and collapsed to single spaces, because that is the form the model's dictionary
     * is in - "Hey  And Code" would simply never match.
     */
    fun normalize(phrase: String): String = phrase.trim().lowercase().replace(WHITESPACE, " ").ifBlank { DEFAULT_PHRASE }

    /**
     * The individual words the recogniser will be asked for, which is what the model's dictionary
     * is checked against - a phrase is only recognisable if every word of it is.
     */
    fun words(phrase: String): List<String> = normalize(phrase).split(' ').filter(String::isNotBlank)

    fun grammarFor(phrase: String): String {
        val normalized = normalize(phrase)
        val entries = if (normalized == UNKNOWN) listOf(UNKNOWN) else listOf(normalized, UNKNOWN)
        return Json.encodeToString(JsonArray.serializer(), JsonArray(entries.map(::JsonPrimitive)))
    }

    private val WHITESPACE = Regex("\\s+")
}
