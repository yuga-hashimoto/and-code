package com.yugahashimoto.andcode.feature.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
        // Exact alarms are exempt from Doze and battery optimizations. An inexact alarm is not
        // merely late: see [exactAlarmsAllowed] for why the run cannot start from one at all.
        if (exactAlarmsAllowed()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    fun cancel(scheduleId: String) {
        alarmManager.cancel(pendingIntent(scheduleId))
    }

    /** Runs [scheduleId] immediately, outside of its cron timing. */
    fun runNow(scheduleId: String): Boolean = ScheduleExecutionService.start(context, scheduleId)

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

    /**
     * True when the app may arm exact alarms; always true below Android 12, which does not gate
     * them.
     *
     * An inexact alarm is not just a late alarm. The wake-up it delivers carries no
     * foreground-service start exemption, so the run cannot be launched at all - and Android 14
     * denies this permission by default to apps targeting SDK 33 and up. The schedules screen
     * therefore asks the user for it.
     */
    fun exactAlarmsAllowed(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

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

/**
 * Screens that let the user grant exact alarms, best first.
 *
 * The dedicated screen only exists on Android 12+ and some builds do not ship it at all, so the
 * app's own settings page follows as a fallback the caller can try next.
 */
fun exactAlarmSettingsIntents(context: Context): List<Intent> {
    val appSettings =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return listOf(appSettings)
    return listOf(
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
        appSettings,
    )
}
