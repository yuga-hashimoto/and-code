package com.yugahashimoto.andcode.feature.browser

import java.net.URI

/**
 * Whether [raw] is an http(s) URL served from the device itself.
 *
 * The inline preview embeds a live WebView inside the conversation, so it stays on the loopback
 * interface: an arbitrary public page is never loaded into a chat bubble. Dev servers started in
 * the guest runtime answer on loopback, which is the case this feature exists for.
 */
fun isLoopbackPreviewUrl(raw: String): Boolean {
    val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
    val host = uri.host?.lowercase()?.trim('[', ']') ?: return false
    return host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "0:0:0:0:0:0:0:1"
}
