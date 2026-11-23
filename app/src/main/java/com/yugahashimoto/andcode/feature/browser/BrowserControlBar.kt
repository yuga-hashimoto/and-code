package com.yugahashimoto.andcode.feature.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R

/**
 * Shared human/agent control bar used by both the inline chat preview and the full-screen Guest
 * Browser so control status and handoff behave identically in both places.
 */
@Composable
internal fun BrowserControlBar(
    agentControlEnabled: Boolean,
    onEnableAgentControl: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    when (BrowserHandoff.controlOwner) {
        BrowserControlOwner.AGENT_HANDOFF -> {
            GuestBrowserHandoffBanner(
                onHandBack = BrowserHandoff::handBack,
                onDismiss = BrowserHandoff::dismiss,
                modifier = modifier,
            )
        }
        BrowserControlOwner.USER_TAKEOVER -> {
            ControlStatusStrip(
                label = stringResource(R.string.guest_browser_control_user),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = modifier,
            ) {
                Button(onClick = BrowserHandoff::handBack) {
                    Text(stringResource(R.string.guest_browser_handoff_done))
                }
            }
        }
        BrowserControlOwner.AGENT -> {
            if (agentControlEnabled) {
                val active = BrowserHandoff.agentWorking
                ControlStatusStrip(
                    label =
                        stringResource(
                            if (active) {
                                R.string.guest_browser_control_agent_active
                            } else {
                                R.string.guest_browser_control_shared_idle
                            },
                        ),
                    containerColor =
                        if (active) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    contentColor =
                        if (active) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    modifier = modifier,
                ) {
                    TextButton(onClick = { BrowserHandoff.takeOver() }) {
                        Text(stringResource(R.string.guest_browser_control_take_over))
                    }
                }
            } else if (onEnableAgentControl != null) {
                ControlStatusStrip(
                    label = stringResource(R.string.guest_browser_control_manual_only),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = modifier,
                ) {
                    TextButton(onClick = onEnableAgentControl) {
                        Text(stringResource(R.string.guest_browser_control_enable_agent))
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlStatusStrip(
    label: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit,
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            action()
        }
    }
}
