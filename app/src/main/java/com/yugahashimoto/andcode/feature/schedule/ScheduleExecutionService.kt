package com.yugahashimoto.andcode.feature.schedule

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yugahashimoto.andcode.AndCodeApplication
import com.yugahashimoto.andcode.MainActivity
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.data.schedule.Schedule
import com.yugahashimoto.andcode.data.schedule.ScheduleRun
import com.yugahashimoto.andcode.data.schedule.ScheduleRunStatus
import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import com.yugahashimoto.andcode.runtime.PermissionResponse
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.runtime.RuntimeType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Executes a scheduled prompt in the foreground.
 *
 * Woken by an exact alarm (or by "Run now"), it boots the local runtime if needed, creates a
 * session, sends the prompt and watches the event stream until the run finishes - then records
 * the outcome, re-arms the next alarm and stops itself.
 */
class ScheduleExecutionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: AndCodeApplication
    private var inForeground = false
    private var executionJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        app = application as AndCodeApplication
        createChannel()
        // Android 14+ can still reject the foreground promotion here even though the start itself
        // was accepted. Bailing out is the only safe answer: a service that cannot enter the
        // foreground is killed with a crash by the system anyway.
        inForeground =
            runCatching {
                startForeground(NOTIFICATION_ID, notification(app.getString(R.string.schedule_notification_starting)))
            }.onFailure { error -> Log.w(TAG, "Could not enter the foreground", error) }
                .isSuccess
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val scheduleId = intent?.getStringExtra(EXTRA_SCHEDULE_ID)
        if (scheduleId == null || !inForeground) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (executionJob?.isActive == true) return START_NOT_STICKY
        executionJob =
            scope.launch {
                try {
                    execute(scheduleId)
                } finally {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun execute(scheduleId: String) {
        val schedule = app.scheduleRepository.schedule(scheduleId) ?: return
        if (!schedule.enabled) return
        // A previous run of the same schedule may still be in flight (e.g. a run-now while a
        // recurring alarm is pending). Do not stack sessions on top of each other.
        if (app.scheduleRepository.hasActiveRun(scheduleId)) return

        val target = app.runtimeRegistry.target(schedule.runtimeId)
        if (target == null) {
            recordSkipped(schedule, getString(R.string.schedule_runtime_unavailable))
            return
        }
        var activeRun: ScheduleRun? = null

        try {
            // Remote runtimes live on the user's PC and need no boot; local agents share the
            // PRoot Linux environment, which has to be running before the agent can start.
            if (target.type == RuntimeType.LOCAL && !ensureLocalRuntimeReady()) {
                recordSkipped(schedule, getString(R.string.schedule_runtime_not_ready))
                return
            }
            if (target.connect().isFailure) {
                recordSkipped(schedule, getString(R.string.schedule_runtime_connection_failed))
                return
            }
            val session =
                target.createSession(
                    title = schedule.displayName.ifBlank { null },
                    directory = schedule.workspacePath.takeIf(String::isNotBlank),
                )
            val run = app.scheduleRepository.recordRunStarted(schedule, session.id, target.id)
            activeRun = run
            updateForegroundNotification(app.getString(R.string.schedule_notification_running))

            val autoAccept = schedule.autoAcceptPermissions ?: app.settings.autoAcceptPermissions
            // Subscribe before sending: fast runtimes can emit idle during sendMessage itself.
            val watcher: Deferred<ScheduleCompletion> =
                scope.async(start = CoroutineStart.UNDISPATCHED) {
                    watchForCompletion(target, schedule, run, autoAccept)
                }
            try {
                target.sendMessage(
                    session.id,
                    PromptRequest(
                        text = schedule.prompt,
                        providerId = schedule.providerId,
                        modelId = schedule.modelId,
                        agent = schedule.agentId,
                    ),
                )
                when (val completion = withTimeoutOrNull(COMPLETION_TIMEOUT_MS) { watcher.await() }) {
                    ScheduleCompletion.Completed -> recordCompleted(run)
                    is ScheduleCompletion.Failed -> recordFailed(run, completion.message)
                    null -> recordFailed(run, getString(R.string.schedule_completion_timeout))
                }
            } finally {
                watcher.cancel()
            }
        } catch (error: Exception) {
            activeRun?.let { recordFailed(it, error.message ?: error.javaClass.simpleName) }
        } finally {
            // A cron schedule arms its next alarm when this run settles, whatever the outcome.
            app.scheduleManager.rescheduleAll()
        }
    }

    /**
     * Waits for the shared Linux runtime when the schedule targets a local agent. Returns false
     * when the runtime is missing, broken or fails to come up in time.
     */
    private suspend fun ensureLocalRuntimeReady(): Boolean {
        val status = app.localRuntimeManager.status()
        if (status is LocalRuntimeStatus.Ready) return true
        if (status is LocalRuntimeStatus.NotInstalled || status is LocalRuntimeStatus.Broken) return false

        app.localRuntimeController.start()
        val ready =
            withTimeoutOrNull(LOCAL_RUNTIME_START_TIMEOUT_MS) {
                app.localRuntimeManager.state.first { it is LocalRuntimeStatus.Ready }
            }
        return ready != null
    }

    /**
     * Streams events for the run's session until it settles. Returns normally on completion or
     * failure, so [execute] never mistakes a settled run for a crashed one.
     */
    private suspend fun watchForCompletion(
        target: RuntimeTarget,
        schedule: Schedule,
        run: ScheduleRun,
        autoAccept: Boolean,
    ): ScheduleCompletion {
        val targetSessionId = run.sessionId
        val notifyUser = target.id != app.runtimeRegistry.selected.value?.id

        try {
            target.events().collect { event ->
                when (event) {
                    is OpenCodeEvent.SessionIdle -> {
                        if (event.sessionId == targetSessionId) {
                            if (notifyUser) {
                                val title =
                                    runCatching { target.session(targetSessionId).title }
                                        .getOrDefault(schedule.displayName)
                                app.notifications.notifySessionComplete(targetSessionId, title, target.id)
                            }
                            throw CompletionSignal(ScheduleCompletion.Completed)
                        }
                    }
                    is OpenCodeEvent.SessionError -> {
                        if (event.sessionId == null || event.sessionId == targetSessionId) {
                            if (notifyUser) app.notifications.notifySessionError(targetSessionId, event.message, target.id)
                            throw CompletionSignal(ScheduleCompletion.Failed(event.message))
                        }
                    }
                    is OpenCodeEvent.PermissionAsked -> {
                        if (event.request.sessionId != targetSessionId) return@collect
                        if (autoAccept) {
                            runCatching {
                                target.respondToPermission(
                                    event.request.sessionId,
                                    event.request.id,
                                    PermissionResponse.ONCE,
                                    remember = false,
                                )
                            }
                        } else if (notifyUser) {
                            app.notifications.notifyPermission(event.request, schedule.displayName, target.id)
                        }
                    }
                    is OpenCodeEvent.QuestionAsked -> {
                        if (event.request.sessionId == targetSessionId && notifyUser) {
                            app.notifications.notifyQuestion(event.request, schedule.displayName, target.id)
                        }
                    }
                    else -> Unit
                }
            }
        } catch (signal: CompletionSignal) {
            // The run settled; the event stream is drained.
            return signal.result
        }
        return ScheduleCompletion.Failed(getString(R.string.schedule_event_stream_ended))
    }

    private fun recordCompleted(run: ScheduleRun) {
        app.scheduleRepository.finishRun(run.id, ScheduleRunStatus.COMPLETED)
    }

    private fun recordFailed(
        run: ScheduleRun,
        error: String?,
    ) {
        app.scheduleRepository.finishRun(run.id, ScheduleRunStatus.FAILED, error = error)
    }

    private fun recordSkipped(
        schedule: Schedule,
        reason: String,
    ) {
        app.scheduleRepository.recordRunSkipped(schedule, reason)
    }

    private fun updateForegroundNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, notification(text))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_schedules),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun notification(text: String): Notification {
        val openIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.schedule_notification_title))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    /** Thrown to unwind the event collector once the run has settled. */
    private sealed interface ScheduleCompletion {
        data object Completed : ScheduleCompletion

        data class Failed(val message: String?) : ScheduleCompletion
    }

    private class CompletionSignal(val result: ScheduleCompletion) : Exception()

    companion object {
        const val EXTRA_SCHEDULE_ID = "schedule_id"
        private const val TAG = "ScheduleExecution"
        private const val CHANNEL_ID = "andcode_schedule_runs"
        private const val NOTIFICATION_ID = 4201
        private const val LOCAL_RUNTIME_START_TIMEOUT_MS = 5 * 60_000L
        private const val COMPLETION_TIMEOUT_MS = 30 * 60_000L

        /**
         * Starts a run, returning false when the platform refused the foreground start.
         *
         * Android 12+ only lets a background app start a foreground service when it is exempt,
         * and an alarm grants that exemption only when it was scheduled exactly. Devices that
         * withhold the exact-alarm permission therefore wake us through an inexact alarm with no
         * exemption, and the start throws ForegroundServiceStartNotAllowedException - callers
         * have to handle the refusal instead of letting it crash the app.
         */
        fun start(
            context: Context,
            scheduleId: String,
        ): Boolean {
            val intent =
                Intent(context, ScheduleExecutionService::class.java).apply {
                    putExtra(EXTRA_SCHEDULE_ID, scheduleId)
                }
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (error: Exception) {
                Log.w(TAG, "Foreground start refused for schedule $scheduleId", error)
                false
            }
        }
    }
}
