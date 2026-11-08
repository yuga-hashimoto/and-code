package com.yugahashimoto.andcode.feature.browser

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

private const val TAG = "GuestBrowser"

/**
 * A WebView for pages served inside the guest runtime (e.g. `http://127.0.0.1:PORT/`).
 *
 * Shared by the full-screen guest browser and the conversation's inline preview. The view is
 * destroyed when it leaves the composition so a dismissed preview does not keep a rendering
 * surface alive.
 *
 * [agentControlEnabled] is the user's explicit opt-in ("Let the agent control the Guest Browser"):
 * only then does the WebView expose its devtools socket so the in-guest agent can drive it over
 * CDP. That socket is a cross-process surface, so it stays closed by default in every build.
 */
@Composable
fun GuestWebContent(
    url: String,
    modifier: Modifier = Modifier,
    agentControlEnabled: Boolean = false,
    onWebViewCreated: (WebView?) -> Unit = {},
    interceptRequest: ((Uri) -> WebResourceResponse?)? = null,
    onProgress: (Int) -> Unit = {},
    onNavigationStateChange: (url: String, canGoBack: Boolean, canGoForward: Boolean) -> Unit = { _, _, _ -> },
    onUnavailable: (Throwable) -> Unit = {},
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    val currentOnCreated by rememberUpdatedState(onWebViewCreated)
    val currentOnProgress by rememberUpdatedState(onProgress)
    val currentOnStateChange by rememberUpdatedState(onNavigationStateChange)
    val currentOnUnavailable by rememberUpdatedState(onUnavailable)

    AndroidView(
        modifier = modifier,
        factory = { context ->
            // Only starting the system WebView provider can fail for reasons the app cannot fix;
            // everything after it runs on a WebView that exists, so a failure there is a real bug
            // and must not leave a half-built view behind.
            val created =
                viewOrNull(
                    create = {
                        // Lets the in-guest agent drive this WebView over CDP so a page can be shown
                        // to and operated by human and agent at the same time. The devtools socket is
                        // a cross-process surface, so it exists only after the user opts in.
                        WebView.setWebContentsDebuggingEnabled(agentControlEnabled)
                        WebView(context)
                    },
                    onFailure = { error ->
                        Log.w(TAG, "The system WebView could not start", error)
                        currentOnUnavailable(error)
                    },
                )
            if (created == null) {
                View(context)
            } else {
                created.apply {
                    layoutParams =
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    configure(
                        onProgress = { currentOnProgress(it) },
                        onNavigationStateChange = { pageUrl, canGoBack, canGoForward ->
                            currentOnStateChange(pageUrl, canGoBack, canGoForward)
                        },
                        interceptRequest = interceptRequest,
                    )
                    webView = this
                    currentOnCreated(this)
                    if (url.isNotBlank()) loadUrl(normalizeGuestUrl(url))
                }
            }
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                destroy()
            }
            currentOnCreated(null)
            webView = null
        }
    }
}

/** Prefixes a bare host:port with a scheme and falls back to the guest's loopback address. */
internal fun normalizeGuestUrl(raw: String): String {
    val trimmed = raw.trim()
    val candidate = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
    return candidate.takeIf { android.net.Uri.parse(it).scheme in setOf("http", "https") } ?: "http://127.0.0.1"
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configure(
    onProgress: (Int) -> Unit,
    onNavigationStateChange: (url: String, canGoBack: Boolean, canGoForward: Boolean) -> Unit,
    interceptRequest: ((Uri) -> WebResourceResponse?)? = null,
) {
    // Guest pages (dev servers, dashboards, tool UIs) are interactive web apps that need JS.
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    webChromeClient =
        object : WebChromeClient() {
            override fun onProgressChanged(
                view: WebView?,
                newProgress: Int,
            ) {
                onProgress(newProgress)
            }
        }
    webViewClient =
        object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = interceptRequest?.invoke(request.url) ?: super.shouldInterceptRequest(view, request)

            override fun onPageFinished(
                view: WebView?,
                url: String?,
            ) {
                onProgress(100)
                onNavigationStateChange(
                    url.orEmpty(),
                    view?.canGoBack() == true,
                    view?.canGoForward() == true,
                )
            }
        }
}
