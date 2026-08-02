package com.yugahashimoto.andcode.feature.wakeword

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class WakeWordDetection(
    val phrase: String,
    val confidence: Float,
)

/**
 * Decides whether a Vosk result was the wake word.
 *
 * Vosk returns `{"text": ...}`, and - when the recogniser is asked for them - a per-word
 * `result` array carrying a confidence each. The words are what sensitivity is judged on: the
 * text alone is either the phrase or not, which would leave the sensitivity slider with nothing
 * to do.
 */
object WakeWordMatcher {
    fun detect(
        resultJson: String,
        phrase: String,
        sensitivity: Float,
    ): WakeWordDetection? {
        val normalized = WakeWordGrammar.normalize(phrase)
        val root =
            runCatching { Json.parseToJsonElement(resultJson).jsonObject }.getOrNull() ?: return null
        val text = runCatching { root["text"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
        if (text.isBlank() || !containsPhrase(text, normalized)) return null

        val confidence = confidenceOf(root, normalized)
        return if (confidence >= sensitivity) WakeWordDetection(normalized, confidence) else null
    }

    /**
     * Word-boundary containment, so the phrase surrounded by absorbed speech still counts while a
     * phrase that merely happens to be a substring of a longer word does not.
     */
    private fun containsPhrase(
        text: String,
        phrase: String,
    ): Boolean {
        val words = text.split(' ').filter(String::isNotBlank)
        val target = phrase.split(' ').filter(String::isNotBlank)
        if (target.isEmpty() || target.size > words.size) return false
        return (0..words.size - target.size).any { start ->
            words.subList(start, start + target.size) == target
        }
    }

    /**
     * The weakest word of the phrase. One word the recogniser was unsure of makes the whole
     * phrase a guess, and an average would let confident neighbours carry it.
     *
     * A result without per-word scores counts as certain: grammar mode does not always report
     * them, and treating their absence as zero would mean never firing at all.
     */
    private fun confidenceOf(
        root: kotlinx.serialization.json.JsonObject,
        phrase: String,
    ): Float {
        val target = phrase.split(' ').filter(String::isNotBlank).toSet()
        val scores =
            runCatching {
                root["result"]?.jsonArray
                    ?.map { it.jsonObject }
                    ?.filter { it["word"]?.jsonPrimitive?.content in target }
                    ?.mapNotNull { it["conf"]?.jsonPrimitive?.content?.toFloatOrNull() }
                    .orEmpty()
            }.getOrDefault(emptyList())
        return scores.minOrNull() ?: 1.0f
    }
}
