package com.yugahashimoto.andcode.feature.chat

private const val HANDOFF_HEADER =
    "Below is a summary of a conversation handed over from another session. Continue from where it left off."

fun buildHandoffPrompt(
    messages: List<ChatMessage>,
    maxChars: Int = 6000,
): String {
    val lines =
        messages.mapNotNull { message ->
            val text = message.text.trim()
            if (text.isEmpty()) return@mapNotNull null
            val role = if (message.isUser) "User" else "Assistant"
            "$role: $text"
        }
    if (lines.isEmpty()) return HANDOFF_HEADER

    val kept = ArrayDeque<String>()
    var length = HANDOFF_HEADER.length + 2
    for (line in lines.asReversed()) {
        val addedLength = line.length + 2
        if (kept.isNotEmpty() && length + addedLength > maxChars) break
        kept.addFirst(line)
        length += addedLength
    }

    return buildString {
        append(HANDOFF_HEADER)
        append("\n\n")
        append(kept.joinToString("\n\n"))
    }
}
