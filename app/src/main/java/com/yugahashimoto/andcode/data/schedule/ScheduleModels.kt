package com.yugahashimoto.andcode.data.schedule

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** A user-defined prompt to run at a fixed time and/or on a cron schedule. */
@Serializable
data class Schedule(
    val id: String = UUID.randomUUID().toString(),
    /** Display name; when blank the prompt's first line is shown instead. */
    val name: String = "",
    /** The runtime target (agent) this schedule runs on. */
    val runtimeId: String = "",
    /** Working directory for the session, e.g. /workspace/android-code. */
    val workspacePath: String = "",
    val providerId: String? = null,
    val modelId: String? = null,
    /** OpenCode sub-agent name (build, plan, ...), not the runtime agent. */
    val agentId: String? = null,
    val prompt: String = "",
    /** Epoch millis of the single run. Mutually exclusive with [cron]. */
    val oneTimeAt: Long? = null,
    /** Five-field cron expression (minute hour day-of-month month day-of-week). */
    val cron: String? = null,
    val enabled: Boolean = true,
    /**
     * Per-schedule override for auto-approving tool permission requests. Null falls back to the
     * app-wide setting.
     */
    val autoAcceptPermissions: Boolean? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val displayName: String
        get() =
            name.trim().takeIf(String::isNotEmpty)
                ?: prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                    .ifBlank { id.take(8) }

    val isRecurring: Boolean get() = cron != null
}

enum class ScheduleRunStatus { PENDING, RUNNING, COMPLETED, FAILED, SKIPPED }

/** One execution of a [Schedule], tied to the session the run produced. */
@Serializable
data class ScheduleRun(
    val id: String = UUID.randomUUID().toString(),
    val scheduleId: String,
    val sessionId: String,
    /** Runtime the session was created on; survives the schedule being deleted. */
    val runtimeId: String,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val status: ScheduleRunStatus = ScheduleRunStatus.RUNNING,
    val error: String? = null,
) {
    /** The run is live and may still be producing output. */
    val isActive: Boolean get() = status == ScheduleRunStatus.PENDING || status == ScheduleRunStatus.RUNNING
}

/** 5-field cron expression with a [nextAfter] calculator. Field order: minute hour dom month dow. */
class CronExpression private constructor(
    val raw: String,
    private val minutesField: Field,
    private val hoursField: Field,
    private val daysOfMonthField: Field,
    private val monthsField: Field,
    private val daysOfWeekField: Field,
) {
    /** Sorted values for the "minute" field. */
    val minutes: List<Int> get() = minutesField.ints

    /** Sorted values for the "hour" field. */
    val hours: List<Int> get() = hoursField.ints

    /** Sorted values for the "day-of-month" field. */
    val daysOfMonth: List<Int> get() = daysOfMonthField.ints

    /** Sorted values for the "month" field. */
    val months: List<Int> get() = monthsField.ints

    /** Sorted values for the "day-of-week" field (0 = Sunday). */
    val daysOfWeek: List<Int> get() = daysOfWeekField.ints

    fun nextAfter(fromMillis: Long): Long? {
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(fromMillis).atZone(zone)
        val startDate = start.toLocalDate()
        val startTime = start.toLocalTime()

        // Scan up to five years forward so e.g. "0 0 29 2 *" still finds the next leap day.
        repeat(5 * 366) { dayOffset ->
            val date = startDate.plusDays(dayOffset.toLong())
            if (!monthsField.contains(date.monthValue)) return@repeat
            val domRestricted = daysOfMonthField.isRestricted
            val dowRestricted = daysOfWeekField.isRestricted
            val domMatches = daysOfMonthField.contains(date.dayOfMonth)
            val dowMatches = daysOfWeekField.contains(date.dayOfWeek.value % 7)
            val dayMatches =
                when {
                    domRestricted && dowRestricted -> domMatches || dowMatches
                    domRestricted -> domMatches
                    dowRestricted -> dowMatches
                    else -> true
                }
            if (!dayMatches) return@repeat

            val after = if (dayOffset == 0) startTime else null
            val time = firstTimeAfter(after) ?: return@repeat
            return LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
        }
        return null
    }

    private fun firstTimeAfter(after: LocalTime?): LocalTime? {
        for (hour in hoursField.ints) {
            if (after != null && hour < after.hour) continue
            val minuteOffset = if (hour == after?.hour) after.minute + 1 else 0
            minutesField.ints.firstOrNull { it >= minuteOffset }?.let { minute ->
                return LocalTime.of(hour, minute)
            }
        }
        return null
    }

    companion object {
        fun parse(expression: String): CronExpression? {
            val fields = expression.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
            if (fields.size != 5) return null
            val minute = Field.parse(fields[0], 0, 59) ?: return null
            val hour = Field.parse(fields[1], 0, 23) ?: return null
            val dom = Field.parse(fields[2], 1, 31) ?: return null
            val month = Field.parse(fields[3], 1, 12) ?: return null
            val dow = Field.parse(fields[4], 0, 7) ?: return null
            return CronExpression(expression.trim(), minute, hour, dom, month, dow)
        }
    }

    private class Field(
        private val values: Set<Int>,
        val isRestricted: Boolean,
    ) {
        val ints: List<Int> get() = values.sorted()

        fun contains(value: Int): Boolean = value in values

        companion object {
            fun parse(
                raw: String,
                min: Int,
                max: Int,
            ): Field? {
                var restricted = raw != "*"
                val values = mutableSetOf<Int>()
                for (part in raw.split(',')) {
                    if (part.isBlank()) return null
                    val stepPart = part.split('/')
                    if (stepPart.size > 2) return null
                    val step = stepPart.getOrNull(1)?.toIntOrNull() ?: 1
                    if (step < 1) return null
                    val rangePart = stepPart[0]
                    val (lo, hi) =
                        if (rangePart == "*") {
                            // "*/n" with n > 1 restricts to every n-th value.
                            if (step > 1) restricted = true
                            min to max
                        } else {
                            val bounds = rangePart.split('-')
                            if (bounds.size == 1) {
                                val single = bounds[0].toIntOrNull() ?: return null
                                single to single
                            } else if (bounds.size == 2) {
                                val lo = bounds[0].toIntOrNull() ?: return null
                                val hi = bounds[1].toIntOrNull() ?: return null
                                lo to hi
                            } else {
                                return null
                            }
                        }
                    if (lo < min || hi > max || lo > hi) return null
                    var value = lo
                    while (value <= hi) {
                        values += value
                        value += step
                    }
                }
                val normalized =
                    if (min == 0 && max == 7) {
                        // cron allows both 0 and 7 for Sunday.
                        values.map { if (it == 7) 0 else it }.toSet()
                    } else {
                        values
                    }
                return Field(normalized, restricted)
            }
        }
    }
}

object ScheduleCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    fun encodeSchedules(schedules: List<Schedule>): String = json.encodeToString(schedules)

    fun decodeSchedules(encoded: String): List<Schedule> = if (encoded.isBlank()) emptyList() else json.decodeFromString(encoded)

    fun encodeRuns(runs: List<ScheduleRun>): String = json.encodeToString(runs)

    fun decodeRuns(encoded: String): List<ScheduleRun> = if (encoded.isBlank()) emptyList() else json.decodeFromString(encoded)
}
