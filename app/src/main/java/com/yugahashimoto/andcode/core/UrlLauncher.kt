package com.yugahashimoto.andcode.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import java.util.Locale

/**
 * Single choke point for opening URLs in an external app.
 *
 * Chat messages render agent-authored Markdown, and a link such as
 * `[workspace](file:///workspace/my-workspace)` used to reach Compose's default
 * [UriHandler], which fires `ACTION_VIEW` with the raw `file://` URI. Android 7+
 * rejects that with [android.os.FileUriExposedException] and the app crashed
 * (issue #300). Only `http`/`https` URLs are ever handed to another app now;
 * anything else is ignored.
 */
object UrlLauncher {
    private const val TAG = "UrlLauncher"
    private val SCHEME_REGEX = Regex("[a-zA-Z][a-zA-Z0-9+.-]*")

    // Pure-Kotlin scheme check (no android.net.Uri, so plain JVM tests can cover it).
    // Only http/https are ever handed to another app; file://, content://, intent://,
    // javascript:, data: and bare paths are all refused.
    fun isOpenableUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        val colon = trimmed.indexOf(':')
        if (colon <= 0) return false
        val scheme = trimmed.substring(0, colon)
        if (!scheme.matches(SCHEME_REGEX)) return false
        val normalized = scheme.lowercase(Locale.ROOT)
        if (normalized != "http" && normalized != "https") return false
        // Require the authority form ("http://..."), not "http:foo".
        return trimmed.regionMatches(colon + 1, "//", 0, 2, ignoreCase = false)
    }

    /**
     * Opens [url] in an external app when it is an http(s) URL.
     *
     * @return true when an external activity was started, false otherwise.
     */
    fun openUrl(
        context: Context,
        url: String,
    ): Boolean {
        if (!isOpenableUrl(url)) {
            Log.w(TAG, "Refusing to open non-http(s) URL")
            return false
        }
        return runCatching {
            val intent =
                Intent(Intent.ACTION_VIEW, Uri.parse(url.trim())).apply {
                    // Callers pass activity and application contexts alike.
                    if (context !is Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
            context.startActivity(intent)
            true
        }.onFailure { error ->
            Log.w(TAG, "Unable to open URL", error)
        }.getOrDefault(false)
    }
}

/**
 * A [UriHandler] that only opens http(s) URLs, guarding every
 * `LinkAnnotation.Url` click rendered from agent-authored Markdown.
 */
private class SafeUriHandler(
    private val context: Context,
) : UriHandler {
    override fun openUri(uri: String) {
        UrlLauncher.openUrl(context, uri)
    }
}

/**
 * Wraps [content] so that Markdown link clicks anywhere below only ever open
 * http(s) URLs externally instead of crashing on `file://` URIs.
 */
@Composable
fun ProvideSafeUriHandler(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val handler = remember(context) { SafeUriHandler(context) }
    CompositionLocalProvider(LocalUriHandler provides handler, content = content)
}
