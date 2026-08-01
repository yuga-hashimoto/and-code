package com.yugahashimoto.andcode.feature.schedule

import android.content.Context
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.data.schedule.CronExpression
import com.yugahashimoto.andcode.data.schedule.Schedule
import com.yugahashimoto.andcode.data.schedule.ScheduleRunStatus
import java.text.DateFormat
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

/** Human-readable timing label for a schedule, e.g. "Daily 09:00" or "Monthly on 1st at 09:00". */
fun scheduleTimingLabel(
    context: Context,
    schedule: Schedule,
): String {
    if (schedule.oneTimeAt != null) return dateTimeLabel(context, schedule.oneTimeAt)
    val cron = schedule.cron?.let(CronExpression::parse) ?: return schedule.cron.orEmpty()
    if (cron.hours.size == 1 && cron.minutes.size == 1) {
        val time = TIME_FORMAT.format(LocalTime.of(cron.hours.first(), cron.minutes.first()))
        val daily = cron.daysOfMonth == (1..31).toList() && cron.daysOfWeek == (0..6).toList()
        val weekly = cron.daysOfMonth == (1..31).toList() && cron.daysOfWeek.size == 1
        val monthly = cron.daysOfMonth.size == 1 && cron.daysOfWeek == (0..6).toList()
        return when {
            daily -> context.getString(R.string.schedule_timing_daily_label, time)
            weekly -> {
                val day = DayOfWeek.of(if (cron.daysOfWeek.first() == 0) 7 else cron.daysOfWeek.first())
                val dayName = day.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                context.getString(R.string.schedule_timing_weekly_label, dayName, time)
            }
            monthly -> context.getString(R.string.schedule_timing_monthly_label, cron.daysOfMonth.first(), time)
            else -> schedule.cron
        }
    }
    return schedule.cron.orEmpty()
}

/** One-time or next-fire timestamp, localized. */
fun dateTimeLabel(
    context: Context,
    epochMillis: Long,
): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(epochMillis))

/** Status label for a run row. */
fun scheduleRunStatusLabel(
    context: Context,
    status: ScheduleRunStatus,
): String =
    when (status) {
        ScheduleRunStatus.PENDING -> context.getString(R.string.schedule_status_pending)
        ScheduleRunStatus.RUNNING -> context.getString(R.string.schedule_status_running)
        ScheduleRunStatus.COMPLETED -> context.getString(R.string.schedule_status_completed)
        ScheduleRunStatus.FAILED -> context.getString(R.string.schedule_status_failed)
        ScheduleRunStatus.SKIPPED -> context.getString(R.string.schedule_status_skipped)
    }
