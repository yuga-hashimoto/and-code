package com.yugahashimoto.andcode.feature.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.api.OpenCodeProvider
import com.yugahashimoto.andcode.data.schedule.CronExpression
import com.yugahashimoto.andcode.data.schedule.Schedule
import com.yugahashimoto.andcode.feature.chat.ModelAndRuntimePickerSheet
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import com.yugahashimoto.andcode.ui.runtimeAgentIcon
import com.yugahashimoto.andcode.ui.runtimeTargetLabel
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

private enum class TimingMode { ONCE, DAILY, WEEKLY, MONTHLY, CUSTOM }

private enum class AutoAcceptMode { DEFAULT, ALWAYS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditorScreen(
    existing: Schedule?,
    runtimeTargets: List<RuntimeTarget>,
    providers: List<OpenCodeProvider>,
    workspaces: List<WorkspaceRef>,
    onSave: (Schedule) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var prompt by remember { mutableStateOf(existing?.prompt.orEmpty()) }
    var runtimeId by remember { mutableStateOf(existing?.runtimeId.orEmpty()) }
    var providerId by remember { mutableStateOf(existing?.providerId) }
    var modelId by remember { mutableStateOf(existing?.modelId) }
    var workspacePath by remember { mutableStateOf(existing?.workspacePath.orEmpty()) }
    var autoAcceptMode by remember {
        mutableStateOf(
            if (existing?.autoAcceptPermissions == true) AutoAcceptMode.ALWAYS else AutoAcceptMode.DEFAULT,
        )
    }

    val timingState = remember(existing) { TimingState.from(existing) }

    var showModelPicker by remember { mutableStateOf(false) }
    var workspaceMenuExpanded by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var invalidCron by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState()

    val selectedTarget = runtimeTargets.firstOrNull { it.id == runtimeId }
    val selectedProvider = providers.firstOrNull { it.id == providerId }
    val selectedModelName = selectedProvider?.models?.get(modelId)?.name

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text =
                            stringResource(
                                if (existing == null) R.string.schedule_new else R.string.schedule_edit,
                            ),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val built =
                                buildSchedule(
                                    existing,
                                    timingState,
                                    name,
                                    prompt,
                                    runtimeId,
                                    providerId,
                                    modelId,
                                    workspacePath,
                                    autoAcceptMode,
                                )
                            if (built != null) onSave(built)
                        },
                    ) {
                        Text(stringResource(R.string.schedule_save), fontWeight = FontWeight.SemiBold)
                    }
                },
                colors =
                    TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                        actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.schedule_name)) },
                placeholder = { Text(stringResource(R.string.schedule_name_hint)) },
            )

            EditorSectionHeader(stringResource(R.string.schedule_agent_model))
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { showModelPicker = true },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(runtimeAgentIcon(selectedTarget?.agent)),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = selectedTarget?.let { runtimeTargetLabel(it) } ?: stringResource(R.string.schedule_agent_unset),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = selectedModelName ?: stringResource(R.string.schedule_model_unset),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Default.ModelTraining,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            EditorSectionHeader(stringResource(R.string.schedule_workspace))
            val workspaceSummary =
                workspaces
                    .firstOrNull { it.path == workspacePath }
                    ?.let { "${it.name} · ${it.path}" }
                    ?: workspacePath
            OutlinedButton(
                onClick = { workspaceMenuExpanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = workspaceSummary.ifBlank { stringResource(R.string.schedule_workspace_unset) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            EditorSectionHeader(stringResource(R.string.schedule_prompt))
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                label = { Text(stringResource(R.string.schedule_prompt)) },
                placeholder = { Text(stringResource(R.string.schedule_prompt_hint)) },
            )

            EditorSectionHeader(stringResource(R.string.schedule_timing))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = timingState.mode == TimingMode.ONCE,
                    onClick = { timingState.mode = TimingMode.ONCE },
                    label = { Text(stringResource(R.string.schedule_timing_once)) },
                )
                FilterChip(
                    selected = timingState.mode == TimingMode.DAILY,
                    onClick = { timingState.mode = TimingMode.DAILY },
                    label = { Text(stringResource(R.string.schedule_timing_daily)) },
                )
                FilterChip(
                    selected = timingState.mode == TimingMode.WEEKLY,
                    onClick = { timingState.mode = TimingMode.WEEKLY },
                    label = { Text(stringResource(R.string.schedule_timing_weekly)) },
                )
                FilterChip(
                    selected = timingState.mode == TimingMode.MONTHLY,
                    onClick = { timingState.mode = TimingMode.MONTHLY },
                    label = { Text(stringResource(R.string.schedule_timing_monthly)) },
                )
                FilterChip(
                    selected = timingState.mode == TimingMode.CUSTOM,
                    onClick = { timingState.mode = TimingMode.CUSTOM },
                    label = { Text(stringResource(R.string.schedule_timing_custom)) },
                )
            }

            when (timingState.mode) {
                TimingMode.ONCE -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { showDatePicker = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                timingState.onceDate?.toString()
                                    ?: stringResource(R.string.schedule_date),
                            )
                        }
                        OutlinedButton(
                            onClick = { showTimePicker = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                timingState.onceTime?.format(TIME_FORMAT)
                                    ?: stringResource(R.string.schedule_time),
                            )
                        }
                    }
                }
                TimingMode.DAILY ->
                    TimePickerButton(
                        label = timingState.dailyTime?.format(TIME_FORMAT) ?: stringResource(R.string.schedule_time),
                        onClick = { showTimePicker = true },
                    )
                TimingMode.WEEKLY -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DayOfWeek.entries.forEach { day ->
                            FilterChip(
                                selected = timingState.weeklyDay == day,
                                onClick = { timingState.weeklyDay = day },
                                label = {
                                    Text(
                                        day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                                    )
                                },
                            )
                        }
                    }
                    TimePickerButton(
                        label = timingState.weeklyTime?.format(TIME_FORMAT) ?: stringResource(R.string.schedule_time),
                        onClick = { showTimePicker = true },
                    )
                }
                TimingMode.MONTHLY -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = {
                                timingState.monthlyDay = (timingState.monthlyDay ?: 1) % 31 + 1
                            },
                        ) {
                            Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.schedule_day_of_month) + ": " + (timingState.monthlyDay ?: 1))
                        }
                        Spacer(Modifier.width(12.dp))
                        TimePickerButton(
                            label = timingState.monthlyTime?.format(TIME_FORMAT) ?: stringResource(R.string.schedule_time),
                            onClick = { showTimePicker = true },
                        )
                    }
                }
                TimingMode.CUSTOM -> {
                    OutlinedTextField(
                        value = timingState.cronText,
                        onValueChange = {
                            timingState.cronText = it
                            invalidCron = it.isNotBlank() && CronExpression.parse(it) == null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.schedule_cron)) },
                        placeholder = { Text(stringResource(R.string.schedule_cron_example)) },
                        supportingText = {
                            Text(
                                if (invalidCron) {
                                    stringResource(R.string.schedule_cron_invalid)
                                } else {
                                    stringResource(R.string.schedule_cron_hint)
                                },
                                color =
                                    if (invalidCron) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                        },
                        isError = invalidCron,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

            EditorSectionHeader(stringResource(R.string.schedule_auto_accept))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = autoAcceptMode == AutoAcceptMode.DEFAULT,
                    onClick = { autoAcceptMode = AutoAcceptMode.DEFAULT },
                    label = { Text(stringResource(R.string.schedule_auto_accept_default)) },
                )
                FilterChip(
                    selected = autoAcceptMode == AutoAcceptMode.ALWAYS,
                    onClick = { autoAcceptMode = AutoAcceptMode.ALWAYS },
                    label = { Text(stringResource(R.string.always_allow)) },
                )
            }
            Text(
                text = stringResource(R.string.schedule_auto_accept_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showModelPicker) {
        ModelAndRuntimePickerSheet(
            sheetState = sheetState,
            runtimeTargets = runtimeTargets,
            selectedRuntimeId = runtimeId,
            onSelectRuntime = { runtimeId = it },
            providers = providers,
            selectedProviderId = providerId,
            selectedModelId = modelId,
            onSelectModel = { newProviderId, newModelId ->
                providerId = newProviderId
                modelId = newModelId
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false },
        )
    }

    if (showDatePicker) {
        val datePickerState =
            rememberDatePickerState(initialSelectedDateMillis = timingState.onceDate?.toEpochDay()?.let { it * 86_400_000L })
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        timingState.onceDate = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
                    }
                    showDatePicker = false
                }) {
                    Text(stringResource(R.string.schedule_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.schedule_cancel))
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val initial = timingState.pendingTime()
        val timePickerState = rememberTimePickerState(initialHour = initial?.hour ?: 9, initialMinute = initial?.minute ?: 0)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val time = LocalTime.of(timePickerState.hour, timePickerState.minute)
                    timingState.setPendingTime(time)
                    showTimePicker = false
                }) {
                    Text(stringResource(R.string.schedule_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(stringResource(R.string.schedule_cancel))
                }
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TimePicker(state = timePickerState)
                }
            },
        )
    }
}

@Composable
private fun EditorSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun TimePickerButton(
    label: String,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label)
    }
}

/** Mutable timing form state that turns itself back into cron/one-time fields on save. */
private class TimingState(
    var mode: TimingMode,
    var onceDate: LocalDate?,
    var onceTime: LocalTime?,
    var dailyTime: LocalTime?,
    var weeklyDay: DayOfWeek?,
    var weeklyTime: LocalTime?,
    var monthlyDay: Int?,
    var monthlyTime: LocalTime?,
    var cronText: String,
) {
    fun pendingTime(): LocalTime? =
        when (mode) {
            TimingMode.ONCE -> onceTime
            TimingMode.DAILY -> dailyTime
            TimingMode.WEEKLY -> weeklyTime
            TimingMode.MONTHLY -> monthlyTime
            TimingMode.CUSTOM -> null
        }

    fun setPendingTime(time: LocalTime) {
        when (mode) {
            TimingMode.ONCE -> onceTime = time
            TimingMode.DAILY -> dailyTime = time
            TimingMode.WEEKLY -> weeklyTime = time
            TimingMode.MONTHLY -> monthlyTime = time
            TimingMode.CUSTOM -> Unit
        }
    }

    /** Null when the current mode's inputs are incomplete or the cron is invalid. */
    fun toTrigger(): Pair<Long?, String?>? {
        return when (mode) {
            TimingMode.ONCE -> {
                val date = onceDate ?: return null
                val time = onceTime ?: return null
                val epoch: Long = LocalDateTime.of(date, time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                epoch to null as String?
            }
            TimingMode.DAILY -> {
                val time = dailyTime ?: return null
                null as Long? to "${time.minute} ${time.hour} * * *"
            }
            TimingMode.WEEKLY -> {
                val day = weeklyDay ?: return null
                val time = weeklyTime ?: return null
                null as Long? to "${time.minute} ${time.hour} * * ${day.value % 7}"
            }
            TimingMode.MONTHLY -> {
                val day = monthlyDay ?: return null
                val time = monthlyTime ?: return null
                null as Long? to "${time.minute} ${time.hour} $day * *"
            }
            TimingMode.CUSTOM -> {
                val text = cronText.trim()
                if (CronExpression.parse(text) == null) null else null as Long? to text
            }
        }
    }

    companion object {
        fun from(schedule: Schedule?): TimingState {
            if (schedule == null) {
                return TimingState(
                    mode = TimingMode.DAILY,
                    onceDate = LocalDate.now(),
                    onceTime = LocalTime.of(9, 0),
                    dailyTime = LocalTime.of(9, 0),
                    weeklyDay = DayOfWeek.MONDAY,
                    weeklyTime = LocalTime.of(9, 0),
                    monthlyDay = 1,
                    monthlyTime = LocalTime.of(9, 0),
                    cronText = "0 9 * * *",
                )
            }
            schedule.oneTimeAt?.let { epoch ->
                val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(epoch), ZoneId.systemDefault())
                return TimingState(
                    mode = TimingMode.ONCE,
                    onceDate = local.toLocalDate(),
                    onceTime = local.toLocalTime(),
                    dailyTime = null,
                    weeklyDay = null,
                    weeklyTime = null,
                    monthlyDay = null,
                    monthlyTime = null,
                    cronText = "0 9 * * *",
                )
            }
            val cron = schedule.cron?.let(CronExpression::parse)
            if (cron != null && cron.hours.size == 1 && cron.minutes.size == 1) {
                val time = LocalTime.of(cron.hours.first(), cron.minutes.first())
                val daily = cron.daysOfMonth == (1..31).toList() && cron.daysOfWeek == (0..6).toList()
                val weekly = cron.daysOfMonth == (1..31).toList() && cron.daysOfWeek.size == 1
                val monthly = cron.daysOfMonth.size == 1 && cron.daysOfWeek == (0..6).toList()
                return when {
                    daily ->
                        TimingState(
                            mode = TimingMode.DAILY,
                            onceDate = null,
                            onceTime = null,
                            dailyTime = time,
                            weeklyDay = null,
                            weeklyTime = null,
                            monthlyDay = null,
                            monthlyTime = null,
                            cronText = schedule.cron.orEmpty(),
                        )
                    weekly ->
                        TimingState(
                            mode = TimingMode.WEEKLY,
                            onceDate = null,
                            onceTime = null,
                            dailyTime = null,
                            weeklyDay = DayOfWeek.of(if (cron.daysOfWeek.first() == 0) 7 else cron.daysOfWeek.first()),
                            weeklyTime = time,
                            monthlyDay = null,
                            monthlyTime = null,
                            cronText = schedule.cron.orEmpty(),
                        )
                    monthly ->
                        TimingState(
                            mode = TimingMode.MONTHLY,
                            onceDate = null,
                            onceTime = null,
                            dailyTime = null,
                            weeklyDay = null,
                            weeklyTime = null,
                            monthlyDay = cron.daysOfMonth.first(),
                            monthlyTime = time,
                            cronText = schedule.cron.orEmpty(),
                        )
                    else ->
                        TimingState(
                            mode = TimingMode.CUSTOM,
                            onceDate = null,
                            onceTime = null,
                            dailyTime = null,
                            weeklyDay = null,
                            weeklyTime = null,
                            monthlyDay = null,
                            monthlyTime = null,
                            cronText = schedule.cron.orEmpty(),
                        )
                }
            }
            return TimingState(
                mode = TimingMode.CUSTOM,
                onceDate = null,
                onceTime = null,
                dailyTime = null,
                weeklyDay = null,
                weeklyTime = null,
                monthlyDay = null,
                monthlyTime = null,
                cronText = schedule.cron.orEmpty(),
            )
        }
    }
}

private fun buildSchedule(
    existing: Schedule?,
    timingState: TimingState,
    name: String,
    prompt: String,
    runtimeId: String,
    providerId: String?,
    modelId: String?,
    workspacePath: String,
    autoAcceptMode: AutoAcceptMode,
): Schedule? {
    if (prompt.trim().isEmpty()) return null
    if (runtimeId.isEmpty()) return null
    val (oneTimeAt, cron) = timingState.toTrigger() ?: return null
    return (existing ?: Schedule()).copy(
        name = name.trim(),
        prompt = prompt.trim(),
        runtimeId = runtimeId,
        providerId = providerId,
        modelId = modelId,
        workspacePath = workspacePath,
        oneTimeAt = oneTimeAt,
        cron = cron,
        autoAcceptPermissions = if (autoAcceptMode == AutoAcceptMode.ALWAYS) true else null,
    )
}
