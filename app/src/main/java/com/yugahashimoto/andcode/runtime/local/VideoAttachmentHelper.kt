package com.yugahashimoto.andcode.runtime.local

/**
 * Pure helpers for turning video attachments into model-sendable image frames.
 *
 * Most LLM providers behind OpenCode reject video file parts
 * ("'file part media type video/mp4' functionality not supported"), which used to
 * fail the whole turn. Instead of sending the raw video bytes, the app extracts
 * a few representative JPEG frames and sends those as image attachments.
 */
object VideoAttachmentHelper {
    const val MAX_FRAMES = 3
    const val MAX_SIDE_PX = 1280

    fun isVideoMime(mime: String): Boolean = mime.startsWith("video/", ignoreCase = true)

    /**
     * Representative timestamps (microseconds) for [durationUs].
     * Returns 0-us, middle and near-end so a short screen recording still yields
     * beginning/middle/end context. Unknown or zero durations yield a single 0-us frame.
     */
    fun frameTimestampsUs(
        durationUs: Long,
        maxFrames: Int = MAX_FRAMES,
    ): List<Long> {
        if (durationUs <= 0L || maxFrames <= 1) return listOf(0L)
        val fractions =
            when {
                maxFrames >= 3 -> listOf(0.0, 0.5, 0.9)
                else -> listOf(0.0, 0.5).take(maxFrames)
            }
        return fractions
            .take(maxFrames)
            .map { (durationUs * it).toLong().coerceAtLeast(0L) }
            .distinct()
    }

    /**
     * Scales [width]x[height] down so the longest side is at most [maxSide],
     * preserving aspect ratio. Never upscales.
     */
    fun scaledDimensions(
        width: Int,
        height: Int,
        maxSide: Int = MAX_SIDE_PX,
    ): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        val longest = maxOf(width, height)
        if (longest <= maxSide) return width to height
        val scale = maxSide.toDouble() / longest.toDouble()
        val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
        return scaledWidth to scaledHeight
    }

    fun frameFilename(
        baseFilename: String,
        index: Int,
    ): String {
        val base = baseFilename.substringBeforeLast('.', missingDelimiterValue = baseFilename)
        val sanitized =
            base
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .ifBlank { "video" }
        return "$sanitized.frame-$index.jpg"
    }
}
