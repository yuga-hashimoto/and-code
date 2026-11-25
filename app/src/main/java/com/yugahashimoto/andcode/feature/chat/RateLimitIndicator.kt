package com.yugahashimoto.andcode.feature.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.RateLimitGroup
import com.yugahashimoto.andcode.runtime.RateLimitWindow
import com.yugahashimoto.andcode.runtime.SubscriptionRateLimit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private const val WARNING_THRESHOLD_PERCENT = 70f
private const val CRITICAL_THRESHOLD_PERCENT = 90f

/**
 * Compact circular quota gauge placed at the bottom-right corner of the composer (under the send
 * button). Displays the higher usage percentage between the 5-hour and weekly limits.
 */
@Composable
fun SubscriptionRateLimitRing(
    rateLimit: SubscriptionRateLimit?,
    selectedModelId: String?,
    refreshing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val peakPercent = rateLimit?.peakUsedPercentFor(selectedModelId)
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    val progressColor = rateLimitColor(peakPercent ?: 0f)
    val accessibilityLabel =
        if (peakPercent != null) {
            stringResource(R.string.rate_limit_ring_cd_percent, peakPercent.roundToInt())
        } else {
            stringResource(R.string.rate_limit_ring_cd_unknown)
        }

    Box(
        modifier =
            modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = accessibilityLabel }
                .testTag("chat-rate-limit-ring"),
        contentAlignment = Alignment.Center,
    ) {
        if (refreshing && peakPercent == null) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Canvas(modifier = Modifier.size(20.dp)) {
                val strokeWidth = 2.6.dp.toPx()
                val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                drawArc(
                    color = trackColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = stroke,
                )
                if (peakPercent != null && peakPercent > 0f) {
                    drawArc(
                        color = progressColor,
                        startAngle = -90f,
                        sweepAngle = (peakPercent.coerceIn(0f, 100f) / 100f) * 360f,
                        useCenter = false,
                        style = stroke,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RateLimitDetailSheet(
    backendName: String,
    rateLimit: SubscriptionRateLimit?,
    selectedModelId: String?,
    canRefresh: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.rate_limit_sheet_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (backendName.isNotBlank()) {
                        Text(
                            text = backendName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (canRefresh) {
                    IconButton(onClick = onRefresh, enabled = !refreshing) {
                        if (refreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.rate_limit_refresh_cd),
                            )
                        }
                    }
                }
            }

            val groups = rateLimit?.groups.orEmpty()
            if (groups.isEmpty()) {
                Text(
                    text =
                        stringResource(
                            if (canRefresh) {
                                R.string.rate_limit_empty_refreshable
                            } else {
                                R.string.rate_limit_empty_passive
                            },
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val primaryId = rateLimit?.primaryGroup(selectedModelId)?.id
                groups.forEach { group ->
                    RateLimitGroupCard(
                        group = group,
                        showHeader = groups.size > 1 || !group.label.isNullOrBlank(),
                        highlighted = groups.size > 1 && group.id == primaryId,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun RateLimitGroupCard(
    group: RateLimitGroup,
    showHeader: Boolean,
    highlighted: Boolean,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color =
            if (highlighted) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (showHeader) {
                Text(
                    text = group.label ?: group.id,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            group.fiveHour?.let { window ->
                RateLimitWindowRow(
                    label = stringResource(R.string.rate_limit_window_five_hour),
                    window = window,
                )
            }
            group.weekly?.let { window ->
                RateLimitWindowRow(
                    label = stringResource(R.string.rate_limit_window_weekly),
                    window = window,
                )
            }
        }
    }
}

@Composable
private fun RateLimitWindowRow(
    label: String,
    window: RateLimitWindow,
) {
    val percent = window.usedPercent.coerceIn(0f, 100f)
    val barColor = rateLimitColor(percent)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${percent.roundToInt()}%",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = barColor,
            )
        }
        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
            color = barColor,
            trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f),
            strokeCap = StrokeCap.Butt,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        window.resetsAtEpochSeconds?.let { epochSeconds ->
            Text(
                text = stringResource(R.string.rate_limit_resets_at, formatResetTime(epochSeconds)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun rateLimitColor(usedPercent: Float): Color =
    when {
        usedPercent >= CRITICAL_THRESHOLD_PERCENT -> MaterialTheme.colorScheme.error
        usedPercent >= WARNING_THRESHOLD_PERCENT -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

private fun formatResetTime(epochSeconds: Long): String {
    val zoned = Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault())
    val formatter = DateTimeFormatter.ofPattern("M/d HH:mm", Locale.getDefault())
    return formatter.format(zoned)
}
