package com.yugahashimoto.andcode.feature.chat

import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.feature.browser.GuestWebContent

/**
 * A live, interactive preview of a loopback dev server, pinned above the composer.
 *
 * Only one instance exists at a time: the WebView inside is heavy, so the card is shown for the
 * single page the agent (or the user, from a loopback link) asked to preview, and torn down when
 * dismissed. Expanding hands the URL to the full-screen guest browser.
 */
@Composable
fun ChatPreviewCard(
    url: String,
    onExpand: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var progress by remember(url) { mutableIntStateOf(0) }
    var webView by remember(url) { mutableStateOf<WebView?>(null) }
    var unavailable by remember(url) { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth().testTag("chat-preview-card"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column {
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
                    text = url,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { webView?.reload() }) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.guest_browser_reload))
                }
                IconButton(onClick = { onExpand(url) }) {
                    Icon(Icons.Default.OpenInFull, contentDescription = stringResource(R.string.chat_preview_expand))
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.chat_preview_close))
                }
            }
            if (unavailable) {
                Text(
                    text = stringResource(R.string.chat_preview_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                if (progress in 1..99) {
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
                GuestWebContent(
                    url = url,
                    modifier = Modifier.fillMaxWidth().height(280.dp).clipToBounds(),
                    onWebViewCreated = { webView = it },
                    onProgress = { progress = it },
                    onUnavailable = { unavailable = true },
                )
            }
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
            Icon(
                Icons.Default.Language,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}
