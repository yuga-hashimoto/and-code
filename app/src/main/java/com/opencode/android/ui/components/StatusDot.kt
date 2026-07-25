package com.opencode.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opencode.android.ui.theme.LocalThemeColors

enum class SessionStatus {
    RUNNING,
    WAITING,
    ERROR,
    PERMISSION,
    COMPLETED_UNREAD,
    IDLE,
}

/**
 * Chat state at a glance.
 *
 * Working shows a spinner, a finished-but-unread chat shows a filled blue dot, and a chat the user
 * has already read shows a muted grey dot.
 */
@Composable
fun StatusDot(
    status: SessionStatus,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
) {
    val tc = LocalThemeColors.current

    if (status == SessionStatus.RUNNING) {
        CircularProgressIndicator(
            modifier = modifier.size(size + 4.dp),
            color = tc.statusRunning,
            strokeWidth = 1.5.dp,
        )
        return
    }

    val targetColor =
        when (status) {
            SessionStatus.RUNNING -> tc.statusRunning
            SessionStatus.WAITING -> tc.statusWaiting
            SessionStatus.ERROR -> tc.statusError
            SessionStatus.PERMISSION -> tc.statusPermission
            SessionStatus.COMPLETED_UNREAD -> tc.statusUnread
            SessionStatus.IDLE -> tc.statusIdle
        }
    val color by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(300),
        label = "statusDot",
    )
    Box(
        modifier =
            modifier
                .size(if (status == SessionStatus.COMPLETED_UNREAD) size + 2.dp else size)
                .clip(CircleShape)
                .background(color),
    )
}
