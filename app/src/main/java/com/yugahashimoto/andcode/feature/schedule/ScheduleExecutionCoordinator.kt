package com.yugahashimoto.andcode.feature.schedule

import android.os.SystemClock
import java.util.Collections

/** Tracks schedule jobs independently while the shared foreground service is alive. */
internal class ScheduleExecutionCoordinator {
    private val activeSchedules = Collections.synchronizedSet(mutableSetOf<String>())

    fun tryStart(scheduleId: String): Boolean = activeSchedules.add(scheduleId)

    /** Returns true when this completion leaves no schedule job running. */
    fun finish(scheduleId: String): Boolean = activeSchedules.remove(scheduleId) && activeSchedules.isEmpty()
}

/** Progress and stream state owned by one scheduled session. */
internal class ScheduleExecutionState {
    @Volatile var streamFailure: String? = null

    @Volatile var lastProgressAt: Long = 0L

    fun markProgress() {
        lastProgressAt = SystemClock.elapsedRealtime()
    }
}
