package com.yugahashimoto.andcode.feature.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yugahashimoto.andcode.AndCodeApplication

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
                ScheduleExecutionService.start(context, scheduleId)
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
    }
}
