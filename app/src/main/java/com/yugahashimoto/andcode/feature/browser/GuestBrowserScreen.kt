package com.yugahashimoto.andcode.feature.browser

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R

/** In-app browser for pages served inside the guest runtime (e.g. http://127.0.0.1:PORT/). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuestBrowserScreen(
    initialUrl: String,
    onBack: () -> Unit,
) {
    var urlInput by rememberSaveable { mutableStateOf(initialUrl) }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var webViewUnavailable by remember { mutableStateOf(false) }

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
            if (!webViewUnavailable) {
                GuestBrowserUrlBar(
                    urlInput = urlInput,
                    onUrlChange = { urlInput = it },
                    onGo = { webView?.loadUrl(normalizeGuestUrl(urlInput)) },
                )
            }
            if (progress in 1..99) {
                LinearProgressIndicator(progress = { progress / 100f })
            }
            if (webViewUnavailable) {
                WebViewUnavailableNotice(modifier = Modifier.weight(1f).fillMaxWidth())
            } else {
                GuestWebContent(
                    // WebView can draw its background outside its measured bounds, covering the URL
                    // field even while that field still accepts focus and keyboard input.
                    url = initialUrl,
                    modifier = Modifier.weight(1f).clipToBounds(),
                    onWebViewCreated = { webView = it },
                    onProgress = { progress = it },
                    onNavigationStateChange = { url, back, forward ->
                        urlInput = url
                        canGoBack = back
                        canGoForward = forward
                    },
                    onUnavailable = { webViewUnavailable = true },
                )
            }
        }
    }
}

/** Shown instead of the page when the system WebView cannot start, which the app cannot repair. */
@Composable
private fun WebViewUnavailableNotice(modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.guest_browser_webview_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
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
