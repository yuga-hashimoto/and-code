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
import android.os.SystemClock
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Executes a scheduled prompt in the foreground.
 *
 * Woken by an exact alarm (or by "Run now"), it boots the local runtime if needed, creates a
 * session, sends the prompt and watches both the event stream and the transcript until the run
 * finishes - then records the outcome, re-arms the next alarm and stops itself.
 *
 * Every way this can end without a run is reported through [reportScheduleStartFailure], which
 * retries the slot and, once the retries are gone, notifies the user. A schedule that quietly does
 * nothing is the one outcome they cannot act on.
 */
class ScheduleExecutionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: AndCodeApplication
    private var inForeground = false
    private var executionJob: Job? = null

    /** Why the event stream stopped, when it stopped before the run settled. */
    @Volatile
    private var streamFailure: String? = null

    /**
     * Elapsed-realtime stamp of the last sign of life from the run, on which the idle timeout is
     * measured. Written by the event stream and the transcript poll, read by [awaitSettlement].
     */
    @Volatile
    private var lastProgressAt: Long = 0L

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
        if (scheduleId == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Counted from the alarm that woke us: the original alarm is attempt 0, so the run about to
        // be started is attempt + 1 and that is what a failure has to report.
        val failedAttempts = intent.getIntExtra(EXTRA_ATTEMPT, 0) + 1
        val automatic = intent.getBooleanExtra(EXTRA_AUTOMATIC, true)
        if (!inForeground) {
            // The start was accepted but the promotion was not, which used to end here in silence
            // - no run, no history, no notice. Report it like any other refused start.
            app.scheduleRepository.schedule(scheduleId)?.let { schedule ->
                app.reportScheduleStartFailure(
                    schedule = schedule,
                    reason = getString(R.string.schedule_foreground_start_blocked),
                    failedAttempts = failedAttempts,
                    retryable = automatic,
                )
            }
            stopSelf()
            return START_NOT_STICKY
        }
        if (executionJob?.isActive == true) {
            // A run of this schedule is still going in this very service - a long one, or an
            // earlier attempt still inside its connect window. Starting a second would stack two
            // sessions on the same schedule, but dropping it in silence is what made a schedule
            // whose previous run never ended look like it had simply stopped firing.
            app.scheduleRepository.schedule(scheduleId)?.let { schedule ->
                app.reportScheduleStartFailure(
                    schedule = schedule,
                    reason = getString(R.string.schedule_run_overlapping),
                    failedAttempts = failedAttempts,
                    retryable = automatic,
                )
            }
            return START_NOT_STICKY
        }
        executionJob =
            scope.launch {
                try {
                    execute(scheduleId, failedAttempts, automatic)
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

    /**
     * Holds a [com.yugahashimoto.andcode.core.runtime.RuntimeWorkTracker] lease for the whole run.
     *
     * This path never touches [com.yugahashimoto.andcode.data.repository.RuntimeActivityRepository]
     * - it drives the runtime directly - so without a lease of its own the wake lock would see no
     * work in flight and let the device suspend mid-run, freezing the proot agent process.
     */
    private suspend fun execute(
        scheduleId: String,
        failedAttempts: Int,
        automatic: Boolean,
    ) {
        app.runtimeWork.withLease("schedule:$scheduleId") {
            executeWithRuntimeAwake(scheduleId, failedAttempts, automatic)
        }
    }

    private suspend fun executeWithRuntimeAwake(
        scheduleId: String,
        failedAttempts: Int,
        automatic: Boolean,
    ) {
        val schedule = app.scheduleRepository.schedule(scheduleId) ?: return
        if (!schedule.enabled) return

        fun cannotStart(
            reason: String,
            retryable: Boolean = true,
        ) = app.reportScheduleStartFailure(schedule, reason, failedAttempts, retryable && automatic)

        // A previous run of the same schedule may still be in flight (e.g. a run-now while a
        // recurring alarm is pending). Do not stack sessions on top of each other - but do say so,
        // because from the outside a slot that produced nothing looks like a broken schedule.
        if (app.scheduleRepository.hasActiveRun(scheduleId)) {
            cannotStart(getString(R.string.schedule_run_overlapping))
            return
        }

        val target = app.runtimeRegistry.target(schedule.runtimeId)
        if (target == null) {
            cannotStart(getString(R.string.schedule_runtime_unavailable))
            return
        }
        var activeRun: ScheduleRun? = null

        try {
            // Remote runtimes live on the user's PC and need no boot; local agents share the
            // PRoot Linux environment, which has to be running before the agent can start.
            if (target.type == RuntimeType.LOCAL && !ensureLocalRuntimeReady()) {
                cannotStart(getString(R.string.schedule_runtime_not_ready))
                return
            }
            if (!connectWithRetry(target)) {
                cannotStart(getString(R.string.schedule_runtime_connection_failed))
                return
            }
            val session =
                target.createSession(
                    title = schedule.displayName.ifBlank { null },
                    directory = schedule.workspacePath.takeIf(String::isNotBlank),
                )
            val run = app.scheduleRepository.recordRunStarted(schedule, session.id, target.id)
            activeRun = run
            // The slot is covered; a retry armed by an earlier attempt would now start a second run.
            app.scheduleManager.cancelRetry(schedule.id)
            updateForegroundNotification(app.getString(R.string.schedule_notification_running))
            runPrompt(target, schedule, run)
        } catch (error: Exception) {
            val message = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
            val run = activeRun
            if (run == null) {
                // Nothing was recorded yet - createSession threw, say - so there is no run to fail.
                // Reporting it as a failed start is what keeps this from vanishing entirely.
                Log.w(TAG, "Schedule $scheduleId could not open a session", error)
                cannotStart(getString(R.string.schedule_session_not_created) + ": " + message)
            } else {
                recordFailed(run, message)
                app.notifications.notifySessionError(run.sessionId, message, target.id)
            }
        } finally {
            // A cron schedule arms its next alarm when this run settles, whatever the outcome.
            app.scheduleManager.rescheduleAll()
        }
    }

    /**
     * Connects the runtime, giving a refused first attempt the whole [CONNECT_DEADLINE_MS] window.
     *
     * An alarm fires the moment the device wakes, which is when the agent is least likely to
     * answer: the local runtime reports Ready as soon as its process is up - it reports it for a
     * process that merely missed its port probe, which is exactly what a doze window leaves behind
     * - and a remote one is reached over a network the device has only just re-joined. Three tries
     * inside nine seconds used to skip the whole run over a thaw that takes minutes.
     */
    private suspend fun connectWithRetry(target: RuntimeTarget): Boolean {
        val deadline = SystemClock.elapsedRealtime() + CONNECT_DEADLINE_MS
        var backoffMs = CONNECT_RETRY_INITIAL_DELAY_MS
        while (true) {
            if (target.connect().isSuccess) return true
            if (SystemClock.elapsedRealtime() + backoffMs >= deadline) return false
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(CONNECT_RETRY_MAX_DELAY_MS)
        }
    }

    /**
     * Sends the prompt and records what the run did.
     *
     * The run settles on whichever watcher sees it first: the event stream, which is immediate, or
     * the transcript poll, which keeps working after the stream drops.
     */
    private suspend fun runPrompt(
        target: RuntimeTarget,
        schedule: Schedule,
        run: ScheduleRun,
    ) {
        val autoAccept = schedule.autoAcceptPermissions ?: app.settings.autoAcceptPermissions
        streamFailure = null
        markProgress()
        // Subscribe before sending: fast runtimes can emit idle during sendMessage itself.
        val watcher: Deferred<ScheduleCompletion> =
            scope.async(start = CoroutineStart.UNDISPATCHED) {
                awaitStreamCompletion(target, schedule, run, autoAccept)
            }
        // The poll sleeps before its first read, so it only ever sees this prompt's own turn.
        val transcriptPoll: Deferred<ScheduleCompletion> = scope.async { pollForCompletion(target, run) }
        val settled: Deferred<ScheduleCompletion> =
            scope.async {
                select<ScheduleCompletion> {
                    watcher.onAwait { it }
                    transcriptPoll.onAwait { it }
                }
            }
        try {
            target.sendMessage(
                run.sessionId,
                PromptRequest(
                    text = schedule.prompt,
                    providerId = schedule.providerId,
                    modelId = schedule.modelId,
                    agent = schedule.agentId,
                ),
            )
            when (val settlement = awaitSettlement(settled)) {
                is ScheduleSettlement.Settled -> settle(target, schedule, run, settlement.completion)
                is ScheduleSettlement.GaveUp -> {
                    val message =
                        when (settlement.timeout) {
                            // A stream that stopped early explains the silence better than the
                            // timeout does.
                            ScheduleRunTimeout.IDLE -> streamFailure ?: getString(R.string.schedule_completion_timeout)
                            ScheduleRunTimeout.MAX_DURATION -> getString(R.string.schedule_max_duration)
                        }
                    recordFailed(run, message)
                    // Unlike a settled failure this is never something the user is watching happen,
                    // so it is announced whatever runtime is on screen.
                    app.notifications.notifySessionError(run.sessionId, message, target.id)
                }
            }
        } finally {
            settled.cancel()
            watcher.cancel()
            transcriptPoll.cancel()
        }
    }

    /**
     * Waits for the run to settle, giving up only once it has gone quiet for [IDLE_TIMEOUT_MS] or
     * outlived [MAX_RUN_DURATION_MS] altogether.
     *
     * The old flat cap on the whole run failed every schedule whose work honestly takes longer than
     * the cap, however healthy it was - a prompt that writes twenty articles never stood a chance.
     */
    private suspend fun awaitSettlement(settled: Deferred<ScheduleCompletion>): ScheduleSettlement {
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            withTimeoutOrNull(WATCHDOG_TICK_MS) { settled.await() }
                ?.let { return ScheduleSettlement.Settled(it) }
            val now = SystemClock.elapsedRealtime()
            scheduleRunTimeout(
                elapsedMs = now - startedAt,
                sinceProgressMs = now - lastProgressAt,
                idleTimeoutMs = IDLE_TIMEOUT_MS,
                maxDurationMs = MAX_RUN_DURATION_MS,
            )?.let { return ScheduleSettlement.GaveUp(it) }
        }
    }

    /** Records the outcome, announcing it unless the user is already looking at this runtime. */
    private suspend fun settle(
        target: RuntimeTarget,
        schedule: Schedule,
        run: ScheduleRun,
        completion: ScheduleCompletion,
    ) {
        val notifyUser = target.id != app.runtimeRegistry.selected.value?.id
        when (completion) {
            ScheduleCompletion.Completed -> {
                if (notifyUser) {
                    val title =
                        runCatching { target.session(run.sessionId).title }
                            .getOrDefault(schedule.displayName)
                    app.notifications.notifySessionComplete(run.sessionId, title, target.id)
                }
                recordCompleted(run)
            }
            is ScheduleCompletion.Failed -> {
                // Stopping the run by hand settles it as MessageAbortedError; the user made that
                // decision, so it is not announced as a failure.
                if (notifyUser && !completion.silent) {
                    app.notifications.notifySessionError(run.sessionId, completion.message, target.id)
                }
                recordFailed(run, completion.message)
            }
        }
    }

    /**
     * Waits for the shared Linux runtime when the schedule targets a local agent. Returns false
     * when the runtime is missing, broken or fails to come up in time.
     */
    private suspend fun ensureLocalRuntimeReady(): Boolean {
        val status = app.localRuntimeManager.status()
        if (status is LocalRuntimeStatus.Ready) return true
        // Nothing the schedule can do revives these, so waiting out the start timeout only delays
        // the retry that might find the runtime repaired.
        if (status is LocalRuntimeStatus.NotInstalled ||
            status is LocalRuntimeStatus.Broken ||
            status is LocalRuntimeStatus.UnsupportedAbi
        ) {
            return false
        }

        app.localRuntimeController.start()
        val ready =
            withTimeoutOrNull(LOCAL_RUNTIME_START_TIMEOUT_MS) {
                app.localRuntimeManager.state.first { it is LocalRuntimeStatus.Ready }
            }
        return ready != null
    }

    /**
     * Waits for the event stream to settle the run.
     *
     * A stream that stops before the run does is not itself a failed run - the agent keeps working
     * on the runtime - so this hands the outcome to [pollForCompletion] and suspends, leaving the
     * reason behind for the timeout message.
     */
    private suspend fun awaitStreamCompletion(
        target: RuntimeTarget,
        schedule: Schedule,
        run: ScheduleRun,
        autoAccept: Boolean,
    ): ScheduleCompletion {
        val reason =
            try {
                watchForCompletion(target, schedule, run, autoAccept)?.let { return it }
                getString(R.string.schedule_event_stream_ended)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
            }
        Log.w(TAG, "Event stream stopped before the run settled: $reason")
        streamFailure = reason
        awaitCancellation()
    }

    /**
     * Settles the run from its transcript, which survives what the event stream cannot: a doze
     * window, a runtime restart, a dropped socket. Without it a run whose `session.idle` was lost
     * failed on the completion timeout even though the agent had answered long before.
     */
    private suspend fun pollForCompletion(
        target: RuntimeTarget,
        run: ScheduleRun,
    ): ScheduleCompletion {
        var settling: String? = null
        var progressMark: String? = null
        while (true) {
            delay(TRANSCRIPT_POLL_INTERVAL_MS)
            val messages =
                runCatching { target.listMessages(run.sessionId) }
                    .onFailure { error -> Log.w(TAG, "Could not read the run transcript", error) }
                    .getOrNull()
                    ?: continue
            // Once the event stream is gone this is the only sign of life left, so a transcript
            // that grew at all - a new message, a new part, more text in the part being streamed -
            // holds the idle timeout off.
            val mark = transcriptProgressMarkOf(messages)
            if (mark != progressMark) {
                if (progressMark != null) markProgress()
                progressMark = mark
            }
            val newest = messages.lastOrNull()?.info
            val outcome = scheduleCompletionOf(messages)
            if (newest == null || outcome == null) {
                settling = null
                continue
            }
            // A turn can finish one message and open another (a tool step, a subagent hop). The
            // event stream knows the difference; the transcript only shows it a read later, so a
            // run counts as settled once the same finished message is still the newest one.
            val fingerprint = "${newest.id}:${newest.time.completed}:${newest.error?.name}"
            if (settling == fingerprint) return outcome
            settling = fingerprint
        }
    }

    /**
     * Streams events for the run's session until it settles, or returns null when the stream ends
     * without settling it.
     */
    private suspend fun watchForCompletion(
        target: RuntimeTarget,
        schedule: Schedule,
        run: ScheduleRun,
        autoAccept: Boolean,
    ): ScheduleCompletion? {
        val targetSessionId = run.sessionId
        val notifyUser = target.id != app.runtimeRegistry.selected.value?.id

        try {
            target.events().collect { event ->
                if (progressSessionIdOf(event) == targetSessionId) markProgress()
                when (event) {
                    is OpenCodeEvent.SessionIdle -> {
                        if (event.sessionId == targetSessionId) {
                            throw CompletionSignal(ScheduleCompletion.Completed)
                        }
                    }
                    is OpenCodeEvent.SessionError -> {
                        if (event.sessionId == null || event.sessionId == targetSessionId) {
                            throw CompletionSignal(ScheduleCompletion.Failed(event.message, silent = event.isAbort))
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
        return null
    }

    private fun markProgress() {
        lastProgressAt = SystemClock.elapsedRealtime()
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
    private class CompletionSignal(val result: ScheduleCompletion) : Exception()

    /** How the wait for a run to settle ended. */
    private sealed interface ScheduleSettlement {
        data class Settled(val completion: ScheduleCompletion) : ScheduleSettlement

        data class GaveUp(val timeout: ScheduleRunTimeout) : ScheduleSettlement
    }

    companion object {
        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val EXTRA_ATTEMPT = "schedule_attempt"
        const val EXTRA_AUTOMATIC = "schedule_automatic"
        private const val TAG = "ScheduleExecution"
        private const val CHANNEL_ID = "andcode_schedule_runs"
        private const val NOTIFICATION_ID = 4201
        private const val LOCAL_RUNTIME_START_TIMEOUT_MS = 5 * 60_000L

        /** How long the run may go without a sign of life before it counts as dead. */
        private const val IDLE_TIMEOUT_MS = 60 * 60_000L

        /** Backstop for a run that never ends; a schedule is not a daemon. */
        private const val MAX_RUN_DURATION_MS = 12 * 60 * 60_000L
        private const val WATCHDOG_TICK_MS = 60_000L
        private const val CONNECT_DEADLINE_MS = 3 * 60_000L
        private const val CONNECT_RETRY_INITIAL_DELAY_MS = 2_000L
        private const val CONNECT_RETRY_MAX_DELAY_MS = 15_000L
        private const val TRANSCRIPT_POLL_INTERVAL_MS = 30_000L

        /**
         * Starts a run, returning false when the platform refused the foreground start.
         *
         * Android 12+ only lets a background app start a foreground service when it is exempt,
         * and an alarm grants that exemption only when it was scheduled exactly. Devices that
         * withhold the exact-alarm permission therefore wake us through an inexact alarm with no
         * exemption, and the start throws ForegroundServiceStartNotAllowedException - callers
         * have to handle the refusal instead of letting it crash the app.
         *
         * @param attempt how many attempts have already failed for this slot; 0 for the first.
         * @param automatic false when the user asked for the run and is watching it, which is when
         * a silent retry minutes later would be a surprise rather than a rescue.
         */
        fun start(
            context: Context,
            scheduleId: String,
            attempt: Int = 0,
            automatic: Boolean = true,
        ): Boolean {
            val intent =
                Intent(context, ScheduleExecutionService::class.java).apply {
                    putExtra(EXTRA_SCHEDULE_ID, scheduleId)
                    putExtra(EXTRA_ATTEMPT, attempt)
                    putExtra(EXTRA_AUTOMATIC, automatic)
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
