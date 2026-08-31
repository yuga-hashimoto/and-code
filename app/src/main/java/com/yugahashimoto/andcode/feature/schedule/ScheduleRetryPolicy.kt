package com.yugahashimoto.andcode.feature.schedule

/**
 * How long to wait before trying a scheduled run again after it failed to start.
 *
 * An alarm fires at the worst possible moment for the runtime: the device has only just woken, the
 * proot tree is still thawing and a remote runtime's network has not come back yet. Giving up on
 * the first refusal loses the whole slot - a daily schedule then misses the entire day - so a run
 * that never started is retried a few times with widening gaps before the schedule falls back to
 * waiting for its next cron slot.
 */
object ScheduleRetryPolicy {
    /**
     * Gap before the next attempt, in the order the retries are used.
     *
     * The first gap has to outlast a whole attempt - waiting out the local runtime's boot and then
     * the connect deadline takes minutes - or the retry lands while the attempt it is covering for
     * is still going, and is dropped as an overlap.
     */
    private val delaysMs = longArrayOf(10 * 60_000L, 20 * 60_000L, 30 * 60_000L)

    /** How many retries follow the original alarm before the run is given up on. */
    val maxRetries: Int = delaysMs.size

    /**
     * Delay before the retry that follows [failedAttempts] failed attempts - so 1 after the alarm
     * itself failed - or null once they are all used up and the run should wait for its next
     * scheduled time instead.
     */
    fun delayAfter(failedAttempts: Int): Long? = delaysMs.getOrNull(failedAttempts - 1)
}
