package com.yugahashimoto.andcode.data.schedule

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Persists schedules and their run history.
 *
 * The prompt can contain code or configuration, so it lives in the same encrypted
 * preferences family as the other app secrets.
 */
class ScheduleRepository(context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val mutableSchedules = MutableStateFlow(loadSchedules())
    val schedules: StateFlow<List<Schedule>> = mutableSchedules.asStateFlow()

    private val mutableRuns = MutableStateFlow(loadRuns())
    val runs: StateFlow<List<ScheduleRun>> = mutableRuns.asStateFlow()

    @Synchronized
    fun schedule(id: String): Schedule? = mutableSchedules.value.firstOrNull { it.id == id }

    @Synchronized
    fun upsert(schedule: Schedule) {
        val updatedAt = System.currentTimeMillis()
        val stored = schedule.copy(updatedAt = updatedAt)
        mutableSchedules.update { current ->
            (current.filterNot { it.id == stored.id } + stored)
                .sortedByDescending { it.updatedAt }
        }
        persistSchedules()
    }

    @Synchronized
    fun delete(id: String) {
        mutableSchedules.update { current -> current.filterNot { it.id == id } }
        mutableRuns.update { current -> current.filterNot { it.scheduleId == id } }
        persistSchedules()
        persistRuns()
    }

    @Synchronized
    fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {
        mutableSchedules.update { current ->
            current.map { schedule ->
                if (schedule.id == id) schedule.copy(enabled = enabled, updatedAt = System.currentTimeMillis()) else schedule
            }
        }
        persistSchedules()
    }

    /** Records that a run started and returns the persisted run for later updates. */
    @Synchronized
    fun recordRunStarted(
        schedule: Schedule,
        sessionId: String,
        runtimeId: String,
    ): ScheduleRun {
        val run = ScheduleRun(scheduleId = schedule.id, sessionId = sessionId, runtimeId = runtimeId)
        mutableRuns.update { current -> (listOf(run) + current).take(MAX_RUNS) }
        persistRuns()
        return run
    }

    /** Records a run that was skipped before a session could be created. */
    @Synchronized
    fun recordRunSkipped(
        schedule: Schedule,
        reason: String,
    ): ScheduleRun {
        val run =
            ScheduleRun(
                scheduleId = schedule.id,
                sessionId = "",
                runtimeId = schedule.runtimeId,
                status = ScheduleRunStatus.SKIPPED,
                finishedAt = System.currentTimeMillis(),
                error = reason,
            )
        mutableRuns.update { current -> (listOf(run) + current).take(MAX_RUNS) }
        persistRuns()
        return run
    }

    @Synchronized
    fun updateRun(run: ScheduleRun) {
        mutableRuns.update { current ->
            val existing = current.firstOrNull { it.id == run.id }
            val updated = existing?.copy(
                sessionId = run.sessionId.takeIf(String::isNotBlank) ?: existing.sessionId,
                finishedAt = run.finishedAt ?: existing.finishedAt,
                status = run.status,
                error = run.error,
            )
            if (updated == null) {
                (listOf(run) + current).take(MAX_RUNS)
            } else {
                current.map { if (it.id == run.id) updated else it }
            }
        }
        persistRuns()
    }

    /** True while a run for [scheduleId] is still in flight; used to avoid overlapping runs. */
    @Synchronized
    fun hasActiveRun(scheduleId: String): Boolean =
        mutableRuns.value.any { it.scheduleId == scheduleId && it.isActive }

    private fun loadSchedules(): List<Schedule> =
        ScheduleCodec.decodeSchedules(preferences.getString(KEY_SCHEDULES, null).orEmpty())

    private fun loadRuns(): List<ScheduleRun> =
        ScheduleCodec.decodeRuns(preferences.getString(KEY_RUNS, null).orEmpty())

    private fun persistSchedules() {
        preferences.edit().putString(KEY_SCHEDULES, ScheduleCodec.encodeSchedules(mutableSchedules.value)).apply()
    }

    private fun persistRuns() {
        preferences.edit().putString(KEY_RUNS, ScheduleCodec.encodeRuns(mutableRuns.value)).apply()
    }

    private companion object {
        const val PREFS_NAME = "andcode_schedules"
        const val KEY_SCHEDULES = "schedules"
        const val KEY_RUNS = "runs"
        const val MAX_RUNS = 200
    }
}
