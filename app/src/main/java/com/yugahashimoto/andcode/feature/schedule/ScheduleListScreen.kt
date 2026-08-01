package com.yugahashimoto.andcode.feature.schedule

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.data.schedule.Schedule
import com.yugahashimoto.andcode.data.schedule.ScheduleRun
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.ui.runtimeAgentIcon
import com.yugahashimoto.andcode.ui.runtimeTargetLabel
import java.time.Instant

@Composable
fun ScheduleListScreen(
    schedules: List<Schedule>,
    runs: List<ScheduleRun>,
    runtimeTargets: List<RuntimeTarget>,
    nextFireAt: (Schedule) -> Instant?,
    onOpenDrawer: () -> Unit,
    onNewSchedule: () -> Unit,
    onOpenSchedule: (String) -> Unit,
    onEdit: (String) -> Unit,
    onRunNow: (String) -> Unit,
    onDelete: (String) -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    exactAlarmsAllowed: () -> Boolean,
    onExactAlarmsGranted: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<Schedule?>(null) }
    val context = LocalContext.current
    // Re-read on the way back from settings: granting is what makes scheduled runs able to start.
    var alarmsAreExact by remember { mutableStateOf(exactAlarmsAllowed()) }
    val exactAlarmSettings =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            alarmsAreExact = exactAlarmsAllowed()
            if (alarmsAreExact) onExactAlarmsGranted()
        }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.nav_schedules),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.menu_description))
                    }
                },
                colors =
                    TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewSchedule) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.schedule_new))
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!alarmsAreExact) {
                ExactAlarmBanner(
                    onAllow = {
                        // The dedicated screen is missing on some builds, so fall through the
                        // candidates until one of them opens.
                        exactAlarmSettingsIntents(context).any { intent ->
                            runCatching { exactAlarmSettings.launch(intent) }.isSuccess
                        }
                    },
                )
            }
            if (schedules.isEmpty()) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp),
                    )
                    Text(
                        text = stringResource(R.string.schedule_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(schedules, key = { it.id }) { schedule ->
                        ScheduleRow(
                            schedule = schedule,
                            lastRun = runs.firstOrNull { it.scheduleId == schedule.id },
                            nextFireAt = nextFireAt(schedule),
                            runtimeTargets = runtimeTargets,
                            onOpen = { onOpenSchedule(schedule.id) },
                            onEdit = { onEdit(schedule.id) },
                            onRunNow = { onRunNow(schedule.id) },
                            onDelete = { pendingDelete = schedule },
                            onToggleEnabled = { enabled -> onToggleEnabled(schedule.id, enabled) },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { schedule ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.schedule_delete)) },
            text = { Text(stringResource(R.string.schedule_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(schedule.id)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * Asks for the alarms & reminders permission, without which Android refuses to let a scheduled
 * run start at all.
 */
@Composable
private fun ExactAlarmBanner(onAllow: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.schedule_exact_alarm_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = stringResource(R.string.schedule_exact_alarm_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            TextButton(onClick = onAllow) {
                Text(
                    text = stringResource(R.string.schedule_exact_alarm_action),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun ScheduleRow(
    schedule: Schedule,
    lastRun: ScheduleRun?,
    nextFireAt: Instant?,
    runtimeTargets: List<RuntimeTarget>,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onRunNow: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val target = runtimeTargets.firstOrNull { it.id == schedule.runtimeId }
    val context = androidx.compose.ui.platform.LocalContext.current

    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(runtimeAgentIcon(target?.agent)),
                contentDescription = target?.let { runtimeTargetLabel(it) },
                tint = if (schedule.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = schedule.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color =
                        if (schedule.enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                Text(
                    text = scheduleTimingLabel(context, schedule),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val statusLine =
                    when {
                        lastRun?.isActive == true -> stringResource(R.string.schedule_status_running)
                        nextFireAt != null && schedule.enabled ->
                            stringResource(R.string.schedule_next_run, dateTimeLabel(context, nextFireAt.toEpochMilli()))
                        lastRun != null ->
                            stringResource(R.string.schedule_last_run, scheduleRunStatusLabel(context, lastRun.status))
                        else -> stringResource(R.string.schedule_never_ran)
                    }
                Text(
                    text = statusLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = schedule.enabled,
                onCheckedChange = onToggleEnabled,
            )
            ScheduleMoreMenu(
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = it },
                onEdit = onEdit,
                onRunNow = onRunNow,
                onDelete = onDelete,
            )
        }
    }
}

@Composable
private fun ScheduleMoreMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRunNow: () -> Unit,
    onDelete: () -> Unit,
) {
    androidx.compose.foundation.layout.Box {
        IconButton(onClick = { onExpandedChange(true) }) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.schedule_edit_menu),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.schedule_edit_menu)) },
                leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
                onClick = {
                    onExpandedChange(false)
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.schedule_run_now)) },
                leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                onClick = {
                    onExpandedChange(false)
                    onRunNow()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.schedule_delete), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    onExpandedChange(false)
                    onDelete()
                },
            )
        }
    }
}
