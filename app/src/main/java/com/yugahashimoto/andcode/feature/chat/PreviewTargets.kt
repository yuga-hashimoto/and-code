package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.feature.browser.PreviewTarget
import com.yugahashimoto.andcode.feature.browser.isLoopbackPreviewUrl
import com.yugahashimoto.andcode.feature.browser.loopbackUrlsIn
import java.net.URI

/**
 * Loopback pages mentioned across the session, newest message first, deduplicated.
 *
 * Used to rebuild the "Preview" affordance after Cancel, including after the app was restarted:
 * the chat transcript survives, so a page the agent mentioned earlier can be reopened without
 * asking the agent to request it again.
 */
fun loopbackPreviewTargets(messages: List<ChatMessage>): List<PreviewTarget.Url> =
    messages
        .asReversed()
        .asSequence()
        .flatMap { message -> loopbackUrlsIn(message.text).asSequence() }
        .map { url -> PreviewTarget.Url(url) }
        .distinctBy { target -> target.url }
        .toList()

/**
 * Filters [targets] to loopback dev-server URLs whose port is currently accepting connections.
 *
 * Only loopback URLs belong here: static workspace files have no running server, and non-loopback
 * URLs cannot be verified via local port probing. Each unique port is probed at most once.
 */
suspend fun runningSessionPreviewTargets(
    targets: List<PreviewTarget>,
    isListening: suspend (Int) -> Boolean,
): List<PreviewTarget.Url> {
    val listeningByPort = mutableMapOf<Int, Boolean>()
    val running = mutableListOf<PreviewTarget.Url>()
    for (target in targets) {
        if (target !is PreviewTarget.Url) continue
        if (!isLoopbackPreviewUrl(target.url)) continue
        val port = previewTargetPort(target.url) ?: continue
        val listening = listeningByPort.getOrPut(port) { isListening(port) }
        if (listening) running += target
    }
    return running
}

private fun previewTargetPort(rawUrl: String): Int? {
    val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return null
    if (uri.port > 0) return uri.port
    return when (uri.scheme?.lowercase()) {
        "http" -> HTTP_DEFAULT_PORT
        "https" -> HTTPS_DEFAULT_PORT
        else -> null
    }
}

private const val HTTP_DEFAULT_PORT = 80
private const val HTTPS_DEFAULT_PORT = 443
