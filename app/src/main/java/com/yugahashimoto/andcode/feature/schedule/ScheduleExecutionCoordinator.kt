package com.yugahashimoto.andcode.feature.schedule

import android.os.SystemClock

/** Tracks schedule jobs independently while the shared foreground service is alive. */
internal class ScheduleExecutionCoordinator {
    private val activeSchedules = mutableSetOf<String>()
    private var latestStartId = 0

    @Synchronized
    fun tryStart(
        scheduleId: String,
        startId: Int,
    ): Boolean {
        latestStartId = maxOf(latestStartId, startId)
        return activeSchedules.add(scheduleId)
    }

    /** Returns the newest service start ID when this completion leaves no job running. */
    @Synchronized
    fun finish(scheduleId: String): Int? {
        if (!activeSchedules.remove(scheduleId) || activeSchedules.isNotEmpty()) return null
        return latestStartId
    }
}

/** Progress and stream state owned by one scheduled session. */
internal class ScheduleExecutionState {
    @Volatile var streamFailure: String? = null

    @Volatile var lastProgressAt: Long = 0L

    fun markProgress() {
        lastProgressAt = SystemClock.elapsedRealtime()
    }
}
