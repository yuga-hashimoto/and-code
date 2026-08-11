package com.yugahashimoto.andcode.data.schedule

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.yugahashimoto.andcode.R
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
    private val interruptedRunMessage = context.getString(R.string.schedule_run_interrupted)
    private val legacyPreferences = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
    private val preferences: SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

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
            val updated =
                existing?.copy(
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

    /** Marks interrupted runs as failed so they cannot block all future executions. */
    @Synchronized
    fun reconcileStaleRuns(
        now: Long = System.currentTimeMillis(),
        staleAfterMs: Long = STALE_RUN_TIMEOUT_MS,
    ): Int {
        val cutoff = now - staleAfterMs
        var reconciled = 0
        mutableRuns.update { current ->
            current.map { run ->
                if (run.isActive && run.startedAt <= cutoff) {
                    reconciled++
                    run.copy(
                        status = ScheduleRunStatus.FAILED,
                        finishedAt = now,
                        error = interruptedRunMessage,
                    )
                } else {
                    run
                }
            }
        }
        if (reconciled > 0) persistRuns()
        return reconciled
    }

    /** Performs a terminal transition only while the run is still active. */
    @Synchronized
    fun finishRun(
        id: String,
        status: ScheduleRunStatus,
        finishedAt: Long = System.currentTimeMillis(),
        error: String? = null,
    ): Boolean {
        if (status == ScheduleRunStatus.PENDING || status == ScheduleRunStatus.RUNNING) return false
        val current = mutableRuns.value.firstOrNull { it.id == id } ?: return false
        if (!current.isActive) return false
        mutableRuns.update { runs ->
            runs.map {
                if (it.id == id) it.copy(status = status, finishedAt = finishedAt, error = error) else it
            }
        }
        persistRuns()
        return true
    }

    /** True while a run for [scheduleId] is still in flight; used to avoid overlapping runs. */
    @Synchronized
    fun hasActiveRun(scheduleId: String): Boolean = mutableRuns.value.any { it.scheduleId == scheduleId && it.isActive }

    private fun loadSchedules(): List<Schedule> {
        val stored = preferences.getString(KEY_SCHEDULES, null)
        if (stored != null) return ScheduleCodec.decodeSchedules(stored)
        return legacyPreferences.getString(KEY_SCHEDULES, null)?.let { encoded ->
            val decoded = ScheduleCodec.decodeSchedules(encoded)
            preferences.edit().putString(KEY_SCHEDULES, encoded).apply()
            legacyPreferences.edit().remove(KEY_SCHEDULES).apply()
            decoded
        }.orEmpty()
    }

    private fun loadRuns(): List<ScheduleRun> {
        val stored = preferences.getString(KEY_RUNS, null)
        if (stored != null) return ScheduleCodec.decodeRuns(stored)
        return legacyPreferences.getString(KEY_RUNS, null)?.let { encoded ->
            val decoded = ScheduleCodec.decodeRuns(encoded)
            preferences.edit().putString(KEY_RUNS, encoded).apply()
            legacyPreferences.edit().remove(KEY_RUNS).apply()
            decoded
        }.orEmpty()
    }

    private fun persistSchedules() {
        preferences.edit().putString(KEY_SCHEDULES, ScheduleCodec.encodeSchedules(mutableSchedules.value)).apply()
    }

    private fun persistRuns() {
        preferences.edit().putString(KEY_RUNS, ScheduleCodec.encodeRuns(mutableRuns.value)).apply()
    }

    private companion object {
        const val PREFS_NAME = "andcode_schedules_secure"
        const val LEGACY_PREFS_NAME = "andcode_schedules"
        const val KEY_SCHEDULES = "schedules"
        const val KEY_RUNS = "runs"
        const val MAX_RUNS = 200
        const val STALE_RUN_TIMEOUT_MS = 30 * 60_000L
    }
}
