package com.yugahashimoto.andcode.feature.browser

// Broad on purpose: the host check is done by isLoopbackPreviewUrl, so this only has to find
// candidate URLs and stop at punctuation that usually follows a link in prose.
private val URL_CANDIDATE_REGEX = Regex("""https?://[^\s<>"'`]+""")
private const val URL_TRAILING_PUNCTUATION = ".,;:!?)]}'\"`"

/** Loopback http(s) URLs mentioned in [text], newest-first callers decide order, order kept. */
fun loopbackUrlsIn(text: String): List<String> =
    URL_CANDIDATE_REGEX
        .findAll(text)
        .map { match -> match.value.trimEnd { it in URL_TRAILING_PUNCTUATION } }
        .filter(::isLoopbackPreviewUrl)
        .distinct()
        .toList()
