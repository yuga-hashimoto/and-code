package com.yugahashimoto.andcode.feature.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yugahashimoto.andcode.AndCodeApplication
import com.yugahashimoto.andcode.R

/**
 * Wakes the app when a schedule's alarm fires, and re-arms all alarms after boot
 * or an app update.
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val app = context.applicationContext as AndCodeApplication
        when (intent.action) {
            ACTION_RUN_SCHEDULE -> {
                val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID) ?: return
                // Counted from the alarm that armed this one, so the original alarm is attempt 0
                // and the run being started now is attempt + 1.
                val attempt = intent.getIntExtra(EXTRA_ATTEMPT, 0)
                if (!ScheduleExecutionService.start(context, scheduleId, attempt = attempt)) {
                    // The system refused the background foreground-service start. Retry it rather
                    // than lose the slot, and leave a trace once the retries run out so the
                    // schedule never looks silently stuck.
                    app.scheduleRepository.schedule(scheduleId)?.let { schedule ->
                        app.reportScheduleStartFailure(
                            schedule = schedule,
                            reason = app.getString(R.string.schedule_foreground_start_blocked),
                            failedAttempts = attempt + 1,
                            retryable = true,
                        )
                    }
                }
                // A cron alarm is one-shot: the next one is normally armed once the run settles,
                // so re-arm here too or a run that never started would end the whole series.
                app.scheduleManager.rescheduleAll()
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> app.scheduleManager.rescheduleAll()
        }
    }

    companion object {
        const val ACTION_RUN_SCHEDULE = "com.yugahashimoto.andcode.RUN_SCHEDULE"
        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val EXTRA_ATTEMPT = "schedule_attempt"
    }
}
