package com.yugahashimoto.andcode.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.ui.components.SectionCard
import com.yugahashimoto.andcode.ui.components.StatusChip

/** A label/value pair shown under an [AgentStatusCard]'s status row. */
data class AgentMetric(
    val label: String,
    val value: String,
)

/**
 * The card every agent's settings screen opens with.
 *
 * OpenCode, Claude Code and Antigravity each grew their own layout, so the one fact a user opens
 * these screens for — is this agent usable right now — was presented three different ways, and
 * OpenCode did not present it at all. Keeping the status chip, the version and the agent's own
 * controls in the same place makes the three screens read as one setting area.
 */
@Composable
fun AgentStatusCard(
    status: String,
    active: Boolean,
    metrics: List<AgentMetric> = emptyList(),
    content: @Composable ColumnScope.() -> Unit = {},
) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.runtime_status_label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            StatusChip(text = status, active = active)
        }
        if (metrics.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            metrics.forEach { metric -> AgentMetricRow(metric.label, metric.value) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
fun AgentMetricRow(
    label: String,
    value: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
        )
    }
}
