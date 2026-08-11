package com.yugahashimoto.andcode.feature.browser

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yugahashimoto.andcode.BuildConfig
import com.yugahashimoto.andcode.R

/** In-app browser for pages served inside the guest runtime (e.g. http://127.0.0.1:PORT/). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuestBrowserScreen(
    initialUrl: String,
    onBack: () -> Unit,
) {
    var urlInput by remember { mutableStateOf(initialUrl) }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) {
            view.goBack()
        } else {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            GuestBrowserTopBar(
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                onBack = onBack,
                onHistoryBack = { webView?.goBack() },
                onForward = { webView?.goForward() },
                onReload = { webView?.reload() },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            GuestBrowserUrlBar(
                urlInput = urlInput,
                onUrlChange = { urlInput = it },
                onGo = { webView?.loadUrl(normalizeUrl(urlInput)) },
            )
            if (progress in 1..99) {
                LinearProgressIndicator(progress = { progress / 100f })
            }
            AndroidView(
                modifier = Modifier.weight(1f),
                factory = { context ->
                    // Lets the in-guest agent drive this WebView over CDP (the devtools abstract
                    // socket is reachable from the guest, which shares the app's UID), so a page
                    // can be shown to the user and operated by human and agent at the same time.
                    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
                    WebView(context).apply {
                        layoutParams =
                            ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        configure(
                            onProgress = { progress = it },
                            onNavigationStateChange = { url, back, forward ->
                                urlInput = url
                                canGoBack = back
                                canGoForward = forward
                            },
                        )
                        webView = this
                        if (initialUrl.isNotBlank()) {
                            loadUrl(normalizeUrl(initialUrl))
                        }
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuestBrowserTopBar(
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onHistoryBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.guest_browser_title)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.nav_back),
                )
            }
        },
        actions = {
            IconButton(enabled = canGoBack, onClick = onHistoryBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.guest_browser_back),
                )
            }
            IconButton(enabled = canGoForward, onClick = onForward) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.guest_browser_forward),
                )
            }
            IconButton(onClick = onReload) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.guest_browser_reload),
                )
            }
        },
    )
}

@Composable
private fun GuestBrowserUrlBar(
    urlInput: String,
    onUrlChange: (String) -> Unit,
    onGo: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = urlInput,
            onValueChange = onUrlChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.guest_browser_url_hint)) },
        )
        Button(onClick = onGo, modifier = Modifier.padding(start = 8.dp)) {
            Text(stringResource(R.string.guest_browser_go))
        }
    }
}

private fun normalizeUrl(raw: String): String {
    val trimmed = raw.trim()
    val candidate = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
    return candidate.takeIf { android.net.Uri.parse(it).scheme in setOf("http", "https") } ?: "http://127.0.0.1"
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configure(
    onProgress: (Int) -> Unit,
    onNavigationStateChange: (url: String, canGoBack: Boolean, canGoForward: Boolean) -> Unit,
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
