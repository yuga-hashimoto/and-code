package com.yugahashimoto.andcode.feature.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.yugahashimoto.andcode.data.schedule.CronExpression
import com.yugahashimoto.andcode.data.schedule.Schedule
import com.yugahashimoto.andcode.data.schedule.ScheduleRepository
import java.time.Instant

/**
 * Owns the AlarmManager alarms that wake the process when a schedule is due.
 *
 * Every mutation of a schedule (create, edit, delete, toggle) must call [rescheduleAll]
 * afterwards so the pending alarms always match the stored schedules.
 */
class ScheduleManager(
    private val context: Context,
    private val repository: ScheduleRepository,
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun rescheduleAll() {
        repository.schedules.value.forEach(::reschedule)
    }

    fun reschedule(schedule: Schedule) {
        val pending = pendingIntent(schedule.id)
        alarmManager.cancel(pending)
        if (!schedule.enabled) return
        val nextFireAt = nextFireAt(schedule) ?: return
        val triggerAt = nextFireAt.toEpochMilli()
        // Exact alarms are exempt from Doze and battery optimizations; without the exact-alarm
        // permission (Android 12+) the alarm still fires, just possibly a few minutes late.
        if (canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    fun cancel(scheduleId: String) {
        alarmManager.cancel(pendingIntent(scheduleId))
    }

    /** Runs [scheduleId] immediately, outside of its cron timing. */
    fun runNow(scheduleId: String) {
        ScheduleExecutionService.start(context, scheduleId)
    }

    /** Next moment this schedule fires, or null when it has no upcoming trigger. */
    fun nextFireAt(schedule: Schedule): Instant? =
        when {
            schedule.oneTimeAt != null ->
                Instant.ofEpochMilli(schedule.oneTimeAt).takeIf { it.isAfter(Instant.now()) }
            schedule.cron != null ->
                CronExpression.parse(schedule.cron)
                    ?.nextAfter(System.currentTimeMillis())
                    ?.let(Instant::ofEpochMilli)
            else -> null
        }

    private fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun pendingIntent(scheduleId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            scheduleId.hashCode(),
            Intent(context, ScheduleAlarmReceiver::class.java).apply {
                action = ScheduleAlarmReceiver.ACTION_RUN_SCHEDULE
                putExtra(ScheduleAlarmReceiver.EXTRA_SCHEDULE_ID, scheduleId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
