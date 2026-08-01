package com.yugahashimoto.andcode.feature.chat

sealed interface MarkdownInline {
    val text: String

    data class Plain(override val text: String) : MarkdownInline

    data class Bold(override val text: String) : MarkdownInline

    data class Italic(override val text: String) : MarkdownInline

    data class Strikethrough(override val text: String) : MarkdownInline

    data class Code(override val text: String) : MarkdownInline

    data class Link(override val text: String, val url: String) : MarkdownInline

    data class Image(override val text: String, val url: String) : MarkdownInline
}

sealed interface MarkdownBlock {
    data class Heading(val level: Int, val inlines: List<MarkdownInline>) : MarkdownBlock

    data class Paragraph(val inlines: List<MarkdownInline>) : MarkdownBlock

    data class CodeBlock(val code: String, val language: String? = null) : MarkdownBlock

    data class BulletList(val items: List<List<MarkdownInline>>) : MarkdownBlock

    data class OrderedList(val items: List<List<MarkdownInline>>) : MarkdownBlock

    data class Blockquote(val inlines: List<MarkdownInline>) : MarkdownBlock

    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock

    data object HorizontalRule : MarkdownBlock
}

object MarkdownLite {
    private val inlineRegex =
        Regex(
            "`([^`]+)`" +
                "|\\*\\*([^*]+)\\*\\*" +
                "|~~([^~]+)~~" +
                "|!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)" +
                "|\\[([^\\]]+)\\]\\(([^)\\s]+)\\)" +
                "|\\*([^*]+)\\*" +
                "|(?<![\\w@])(https?://[A-Za-z0-9][A-Za-z0-9._~:/?#\\[\\]@!&'()*+,;=%-]*)",
        )
    private val ORDERED_REGEX = Regex("^\\d+\\.\\s+")
    private val HR_REGEX = Regex("^(-{3,}|\\*{3,}|_{3,})$")
    private val TABLE_SEP_REGEX = Regex("^\\|?[\\s:|-]*-[\\s:|-]*\\|?$")

    fun parse(source: String): List<MarkdownBlock> {
        val lines = source.split("\n")
        val blocks = mutableListOf<MarkdownBlock>()
        val paragraphBuffer = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraphBuffer.isNotEmpty()) {
                blocks += MarkdownBlock.Paragraph(parseInline(paragraphBuffer.joinToString("\n")))
                paragraphBuffer.clear()
            }
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("```") -> {
                    flushParagraph()
                    val language = trimmed.removePrefix("```").trim().ifBlank { null }
                    val codeLines = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        codeLines += lines[i]
                        i++
                    }
                    blocks += MarkdownBlock.CodeBlock(codeLines.joinToString("\n"), language)
                }
                HR_REGEX.matches(trimmed) -> {
                    flushParagraph()
                    blocks += MarkdownBlock.HorizontalRule
                }
                trimmed.startsWith("#") -> {
                    flushParagraph()
                    val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 6)
                    blocks += MarkdownBlock.Heading(level, parseInline(trimmed.drop(level).trim()))
                }
                trimmed.startsWith("> ") || trimmed == ">" -> {
                    flushParagraph()
                    val quoteLines = mutableListOf<String>()
                    while (i < lines.size && (lines[i].trimStart().startsWith("> ") || lines[i].trimStart() == ">")) {
                        quoteLines += lines[i].trimStart().removePrefix(">").trim()
                        i++
                    }
                    blocks += MarkdownBlock.Blockquote(parseInline(quoteLines.joinToString(" ")))
                    continue
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    flushParagraph()
                    val items = mutableListOf<List<MarkdownInline>>()
                    while (i < lines.size && (lines[i].trimStart().startsWith("- ") || lines[i].trimStart().startsWith("* "))) {
                        items += parseInline(lines[i].trimStart().drop(2).trim())
                        i++
                    }
                    blocks += MarkdownBlock.BulletList(items)
                    continue
                }
                ORDERED_REGEX.containsMatchIn(trimmed) -> {
                    flushParagraph()
                    val items = mutableListOf<List<MarkdownInline>>()
                    while (i < lines.size && ORDERED_REGEX.containsMatchIn(lines[i].trimStart())) {
                        items += parseInline(ORDERED_REGEX.replaceFirst(lines[i].trimStart(), "").trim())
                        i++
                    }
                    blocks += MarkdownBlock.OrderedList(items)
                    continue
                }
                trimmed.contains("|") && i + 1 < lines.size &&
                    TABLE_SEP_REGEX.matches(lines[i + 1].trim()) -> {
                    flushParagraph()
                    val headers = splitTableRow(trimmed)
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].contains("|") && lines[i].trim().isNotEmpty()) {
                        rows += splitTableRow(lines[i].trim())
                        i++
                    }
                    blocks += MarkdownBlock.Table(headers, rows)
                    continue
                }
                trimmed.isBlank() -> flushParagraph()
                else -> paragraphBuffer += line
            }
            i++
        }
        flushParagraph()
        return blocks
    }

    private fun splitTableRow(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|")
            .split("|")
            .map { it.trim() }

    internal fun parseInline(text: String): List<MarkdownInline> {
        val result = mutableListOf<MarkdownInline>()
        var lastIndex = 0
        for (match in inlineRegex.findAll(text)) {
            if (match.range.first > lastIndex) {
                result += MarkdownInline.Plain(text.substring(lastIndex, match.range.first))
            }
            val code = match.groups[1]?.value
            val bold = match.groups[2]?.value
            val strike = match.groups[3]?.value
            val imageAlt = match.groups[4]?.value
            val imageUrl = match.groups[5]?.value
            val linkText = match.groups[6]?.value
            val linkUrl = match.groups[7]?.value
            val italic = match.groups[8]?.value
            val bareUrl = match.groups[9]?.value
            when {
                code != null -> result += MarkdownInline.Code(code)
                bold != null -> result += MarkdownInline.Bold(bold)
                strike != null -> result += MarkdownInline.Strikethrough(strike)
                imageAlt != null && imageUrl != null -> result += MarkdownInline.Image(imageAlt, imageUrl)
                linkText != null && linkUrl != null -> result += MarkdownInline.Link(linkText, linkUrl)
                italic != null -> result += MarkdownInline.Italic(italic)
                bareUrl != null -> {
                    val url = trimUrlTail(bareUrl)
                    result += MarkdownInline.Link(url, url)
                    if (url.length < bareUrl.length) {
                        result += MarkdownInline.Plain(bareUrl.substring(url.length))
                    }
                }
            }
            lastIndex = match.range.last + 1
        }
        if (lastIndex < text.length) {
            result += MarkdownInline.Plain(text.substring(lastIndex))
        }
        if (result.isEmpty()) {
            result += MarkdownInline.Plain(text)
        }
        return result
    }

    private fun trimUrlTail(raw: String): String {
        var s = raw
        while (s.isNotEmpty()) {
            when {
                s.last() in ".,!?;:]}" -> s = s.dropLast(1)
                s.last() == ')' && s.count { it == '(' } < s.count { it == ')' } -> s = s.dropLast(1)
                else -> break
            }
        }
        return s
    }
}
