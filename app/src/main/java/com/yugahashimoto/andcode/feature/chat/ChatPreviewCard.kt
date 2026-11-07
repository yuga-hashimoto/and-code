package com.yugahashimoto.andcode.feature.chat

import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.feature.browser.GuestWebContent
import com.yugahashimoto.andcode.feature.browser.PreviewTarget
import com.yugahashimoto.andcode.feature.browser.StaticAssetInterceptor
import com.yugahashimoto.andcode.feature.browser.staticPreviewUrl
import com.yugahashimoto.andcode.feature.browser.watchWorkspaceChanges
import java.io.File

/**
 * The live preview card. Minimize tears the WebView down and hands control back to the caller
 * (which shows a floating restore button); Expand opens the full-screen guest browser for a dev
 * server, or a full-screen overlay for a static site; Cancel removes the preview entirely.
 *
 * The card owns the WebView and, therefore, the live-reload watcher: a target change or leaving
 * composition disposes both together, so no stale view is ever reloaded.
 */
@Composable
fun ChatPreviewCard(
    target: PreviewTarget,
    isForeground: Boolean,
    workspaceDir: File?,
    onMinimize: () -> Unit,
    onExpand: (PreviewTarget) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var fullscreen by remember(target) { mutableStateOf(false) }
    var webView by remember(target) { mutableStateOf<WebView?>(null) }
    var progress by remember(target) { mutableIntStateOf(0) }
    var unavailable by remember(target) { mutableStateOf(false) }

    val reloadRoot =
        when (target) {
            is PreviewTarget.Static -> File(target.rootDir)
            is PreviewTarget.Url -> workspaceDir
        }
    LaunchedEffect(target.key, reloadRoot, isForeground) {
        // Only runs while the card is open and the app is visible; minimizing, cancelling, or
        // backgrounding cancels this effect, so a hidden preview does no polling at all.
        if (isForeground && reloadRoot != null) {
            watchWorkspaceChanges(reloadRoot) { webView?.reload() }
        }
    }
    LaunchedEffect(isForeground, webView) {
        if (isForeground) webView?.onResume() else webView?.onPause()
    }

    val onCreated: (WebView?) -> Unit = { webView = it }

    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize().testTag("chat-preview-card")) {
                Column {
                    PreviewToolbar(
                        target = target,
                        onMinimize = null,
                        onExpand = { fullscreen = false },
                        onReload = { webView?.reload() },
                        onClose = onClose,
                    )
                    PreviewBody(
                        target = target,
                        modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                        progress = progress,
                        unavailable = unavailable,
                        onProgress = { progress = it },
                        onUnavailable = { unavailable = true },
                        onWebViewCreated = onCreated,
                    )
                }
            }
        }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth().testTag("chat-preview-card"),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ) {
            Column {
                PreviewToolbar(
                    target = target,
                    onMinimize = onMinimize,
                    onExpand = {
                        when (target) {
                            is PreviewTarget.Static -> fullscreen = true
                            is PreviewTarget.Url -> onExpand(target)
                        }
                    },
                    onReload = { webView?.reload() },
                    onClose = onClose,
                )
                PreviewBody(
                    target = target,
                    modifier = Modifier.fillMaxWidth().height(280.dp).clipToBounds(),
                    progress = progress,
                    unavailable = unavailable,
                    onProgress = { progress = it },
                    onUnavailable = { unavailable = true },
                    onWebViewCreated = onCreated,
                )
            }
        }
    }
}

@Composable
private fun PreviewToolbar(
    target: PreviewTarget,
    onMinimize: (() -> Unit)?,
    onExpand: (() -> Unit)?,
    onReload: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            Icons.Default.Language,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = target.label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onMinimize != null) {
            IconButton(onClick = onMinimize) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.chat_preview_minimize))
            }
        }
        if (onExpand != null) {
            IconButton(onClick = onExpand) {
                Icon(Icons.Default.OpenInFull, contentDescription = stringResource(R.string.chat_preview_expand))
            }
        }
        IconButton(onClick = onReload) {
            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.guest_browser_reload))
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.chat_preview_close))
        }
    }
}

@Composable
private fun PreviewBody(
    target: PreviewTarget,
    modifier: Modifier,
    progress: Int,
    unavailable: Boolean,
    onProgress: (Int) -> Unit,
    onUnavailable: () -> Unit,
    onWebViewCreated: (WebView?) -> Unit,
) {
    if (unavailable) {
        Text(
            text = stringResource(R.string.chat_preview_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        return
    }
    if (progress in 1..99) {
        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
    }
    when (target) {
        is PreviewTarget.Url ->
            GuestWebContent(
                url = target.url,
                modifier = modifier,
                onWebViewCreated = onWebViewCreated,
                onProgress = onProgress,
                onUnavailable = { onUnavailable() },
            )
        is PreviewTarget.Static -> {
            val interceptor = remember(target) { StaticAssetInterceptor(File(target.rootDir)) }
            GuestWebContent(
                url = staticPreviewUrl(target.entry),
                modifier = modifier,
                onWebViewCreated = onWebViewCreated,
                interceptRequest = interceptor::intercept,
                onProgress = onProgress,
                onUnavailable = { onUnavailable() },
            )
        }
    }
}

/** Opens a loopback link found in an assistant message in the pinned preview. */
@Composable
fun PreviewLinkChip(
    url: String,
    onClick: (String) -> Unit,
) {
    AssistChip(
        onClick = { onClick(url) },
        label = { Text(stringResource(R.string.chat_preview_link)) },
        leadingIcon = {
            Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp))
        },
    )
}

/** Compact floating button shown after Minimize; tapping it restores the live preview. */
@Composable
fun FloatingPreviewButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.testTag("chat-preview-floating-button"),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                text = stringResource(R.string.chat_preview_link),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Reopen chip shown after Cancel; picks among the session's known loopback/static targets. */
@Composable
fun PreviewReopenBar(
    targets: List<PreviewTarget>,
    onSelect: (PreviewTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        AssistChip(
            onClick = {
                if (targets.size == 1) onSelect(targets.first()) else menu = true
            },
            label = { Text(stringResource(R.string.chat_preview_link)) },
            leadingIcon = {
                Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp))
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            targets.forEach { target ->
                DropdownMenuItem(
                    text = { Text(target.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        menu = false
                        onSelect(target)
                    },
                )
            }
        }
    }
}
