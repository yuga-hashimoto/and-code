package com.yugahashimoto.andcode.feature.schedule

import android.util.Log
import com.yugahashimoto.andcode.AndCodeApplication
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.data.schedule.Schedule

private const val TAG = "ScheduleStart"

/**
 * Handles a run that never got off the ground: retries it while retries are left, and otherwise
 * records the miss and tells the user.
 *
 * Every way a run can fail to start goes through here, because a schedule that silently does
 * nothing is the one outcome the user cannot act on. [retryable] is false for causes a retry
 * cannot fix - a run the user started by hand and is watching, a schedule whose own settings are
 * wrong - so those are reported straight away.
 *
 * @param failedAttempts how many attempts have already failed, the one being reported included.
 */
fun AndCodeApplication.reportScheduleStartFailure(
    schedule: Schedule,
    reason: String,
    failedAttempts: Int,
    retryable: Boolean,
) {
    val retryDelayMs = if (retryable) ScheduleRetryPolicy.delayAfter(failedAttempts) else null
    if (retryDelayMs != null) {
        Log.w(TAG, "Schedule ${schedule.id} did not start ($reason); retrying in ${retryDelayMs}ms")
        scheduleManager.scheduleRetry(schedule.id, attempt = failedAttempts, delayMs = retryDelayMs)
        return
    }
    Log.w(TAG, "Schedule ${schedule.id} did not start after $failedAttempts attempts: $reason")
    // Only the attempt that runs out of retries is written to the history: a row per retry would
    // bury the day's real outcome under three near-identical skips.
    val recorded =
        if (failedAttempts > 1) {
            getString(R.string.schedule_run_not_started_attempts, reason, failedAttempts)
        } else {
            reason
        }
    scheduleRepository.recordRunSkipped(schedule, recorded)
    notifications.notifyScheduleNotStarted(schedule.id, schedule.displayName, recorded)
}
