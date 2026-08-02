package com.yugahashimoto.andcode.feature.assistant

/** Keeps recognition segments together while Android rotates its speech session. */
internal class SpeechTranscriptAccumulator {
    private var value = ""

    val text: String
        get() = value

    fun append(segment: String) {
        value = combine(value, segment)
    }

    fun preview(segment: String): String = combine(value, segment)

    private fun combine(
        prefix: String,
        segment: String,
    ): String {
        val next = segment.trim()
        if (next.isEmpty()) return prefix
        if (prefix.isEmpty()) return next

        val separator =
            if (prefix.last().isWhitespace() || next.first().isWhitespace() ||
                prefix.last().isCjk() || next.first().isCjk() ||
                prefix.last() in NO_SPACE_BEFORE_PUNCTUATION || next.first() in OPENING_PUNCTUATION
            ) {
                ""
            } else {
                " "
            }
        return prefix + separator + next
    }

    private fun Char.isCjk(): Boolean = this in '\u3040'..'\u30ff' || this in '\u3400'..'\u4dbf' || this in '\u4e00'..'\u9fff'

    private companion object {
        val NO_SPACE_BEFORE_PUNCTUATION =
            setOf('、', '。', '，', '．', ',', '.', '!', '?', '！', '？', ':', ';', '：', '；')
        val OPENING_PUNCTUATION = setOf('(', '[', '{', '（', '「', '『', '【')
    }
}
