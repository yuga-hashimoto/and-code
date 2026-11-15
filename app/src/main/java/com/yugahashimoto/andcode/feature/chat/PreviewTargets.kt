package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.feature.browser.PreviewTarget
import com.yugahashimoto.andcode.feature.browser.loopbackUrlsIn

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
