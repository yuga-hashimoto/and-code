package com.yugahashimoto.andcode.runtime.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yugahashimoto.andcode.AndCodeApplication
import com.yugahashimoto.andcode.MainActivity
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.lifecycle.AppForeground
import com.yugahashimoto.andcode.core.runtime.RuntimeWorkTracker
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal enum class LocalRuntimeServiceCommand {
    InstallAndStart,
    InstallFullDevelopmentTools,
    Start,
    Reinstall,
    Update,
    Rollback,
    Delete,
    Stop,
    Restart,
    Restore,
    Ignore,
}

internal fun localRuntimeServiceCommand(action: String?): LocalRuntimeServiceCommand =
    when (action) {
        LocalRuntimeService.ACTION_INSTALL_AND_START -> LocalRuntimeServiceCommand.InstallAndStart
        LocalRuntimeService.ACTION_INSTALL_FULL_DEVELOPMENT_TOOLS -> LocalRuntimeServiceCommand.InstallFullDevelopmentTools
        LocalRuntimeService.ACTION_START -> LocalRuntimeServiceCommand.Start
        LocalRuntimeService.ACTION_REINSTALL -> LocalRuntimeServiceCommand.Reinstall
        LocalRuntimeService.ACTION_UPDATE -> LocalRuntimeServiceCommand.Update
        LocalRuntimeService.ACTION_ROLLBACK -> LocalRuntimeServiceCommand.Rollback
        LocalRuntimeService.ACTION_DELETE -> LocalRuntimeServiceCommand.Delete
        LocalRuntimeService.ACTION_STOP -> LocalRuntimeServiceCommand.Stop
        LocalRuntimeService.ACTION_RESTART -> LocalRuntimeServiceCommand.Restart
        null -> LocalRuntimeServiceCommand.Restore
        else -> LocalRuntimeServiceCommand.Ignore
    }

/**
 * Whether [command] is an explicit request to have the runtime running, and should therefore clear
 * [com.yugahashimoto.andcode.data.connection.SecureSettingsRepository.localRuntimeStoppedByUser].
 *
 * [LocalRuntimeServiceCommand.Restore] is deliberately excluded: it is the system re-delivering a
 * null-action intent to a service the OS restarted on its own, not something a user or the schedule
 * path asked for, so it must not override a deliberate stop the way the commands below do.
 *
 * Reinstall, update and rollback count too. Each is reached only from the workspace screen and each
 * leaves the runtime running, so a stale stopped-by-user flag surviving one of them would suppress
 * the foreground restore for a runtime the user had just asked to be rebuilt.
 */
internal fun clearsUserStoppedFlag(command: LocalRuntimeServiceCommand): Boolean =
    when (command) {
        LocalRuntimeServiceCommand.Start,
        LocalRuntimeServiceCommand.InstallAndStart,
        LocalRuntimeServiceCommand.Restart,
        LocalRuntimeServiceCommand.Reinstall,
        LocalRuntimeServiceCommand.Update,
        LocalRuntimeServiceCommand.Rollback,
        -> true
        LocalRuntimeServiceCommand.Stop,
        LocalRuntimeServiceCommand.InstallFullDevelopmentTools,
        LocalRuntimeServiceCommand.Delete,
        LocalRuntimeServiceCommand.Restore,
        LocalRuntimeServiceCommand.Ignore,
        -> false
    }

/**
 * The agents an [LocalRuntimeService.ACTION_INSTALL_AND_START] intent asks for, from its
 * [LocalRuntimeService.EXTRA_AGENTS] extra.
 *
 * Falls back to OpenCode alone, which is what every caller that carries no selection means - the
 * runtime notification's restart, the watchdog, and the workspace picker's own button. Takes the
 * raw array rather than the Intent so the mapping is testable without an Android runtime.
 */
internal fun localRuntimeInstallAgents(ids: Array<String>?): Set<LocalAgent> =
    ids?.mapNotNull { id -> LocalAgent.entries.firstOrNull { it.id == id } }
        ?.toSet()
        ?.takeIf(Set<LocalAgent>::isNotEmpty)
        ?: setOf(LocalAgent.OPEN_CODE)

/**
 * Whether the runtime is live enough to be worth keeping the CPU out of suspend for.
 *
 * A foreground service only stops the app from being killed; it does nothing to stop the device
 * from suspending once the screen goes off, and the agent runs as a proot child of this process, so
 * suspending mid-run freezes it - the symptom being work that "stopped by itself" in the
 * background. [Ready][LocalRuntimeStatus.Ready] on its own used to be enough to hold the lock,
 * which meant the device could never sleep for as long as the runtime was simply up - the common
 * case, since nothing stops it once started. It now only qualifies while [hasActiveWork] says real
 * work (a chat run, a scheduled run, a runtime operation, a live ADB link - see
 * [com.yugahashimoto.andcode.core.runtime.RuntimeWorkTracker]) is actually in flight. The
 * bounded, self-limiting states - installing, starting, updating - stay unconditional: a boot or
 * install that gets frozen mid-way is broken, not idle. States with no running process - stopped,
 * broken, never installed, unsupported - hold nothing, since keeping a dead runtime's device awake
 * only costs battery.
 */
internal fun localRuntimeNeedsWakeLock(
    status: LocalRuntimeStatus,
    hasActiveWork: Boolean,
): Boolean =
    when (status) {
        is LocalRuntimeStatus.Installing,
        is LocalRuntimeStatus.Starting,
        is LocalRuntimeStatus.Updating,
        -> true
        is LocalRuntimeStatus.Ready -> hasActiveWork
        LocalRuntimeStatus.NotInstalled,
        is LocalRuntimeStatus.Stopped,
        is LocalRuntimeStatus.Broken,
        is LocalRuntimeStatus.UnsupportedAbi,
        -> false
    }

private const val WATCHDOG_ACTIVE_INTERVAL_MILLIS = 5_000L
private const val WATCHDOG_IDLE_INTERVAL_MILLIS = 60_000L

/** How long a [LocalRuntimeStatus.Ready], work-free, backgrounded runtime may sit before it stops. */
internal const val IDLE_STOP_TIMEOUT_MILLIS = 15 * 60 * 1000L

/**
 * How long the watchdog should sleep before its next tick.
 *
 * An idle, work-free [Ready][LocalRuntimeStatus.Ready] runtime backs off to a much slower poll: with
 * no wake lock held (see [localRuntimeNeedsWakeLock]) the device is free to suspend, so there is
 * nothing productive a 5-second tick buys that a 60-second one does not - the runtime is not going
 * anywhere on its own while genuinely idle.
 * [Broken][LocalRuntimeStatus.Broken], [UnsupportedAbi][LocalRuntimeStatus.UnsupportedAbi] and
 * [NotInstalled][LocalRuntimeStatus.NotInstalled] back off for a different reason: they are terminal
 * states this watchdog's auto-restart check cannot do anything about, so a tight poll only burns
 * battery for no chance of recovery. [Stopped][LocalRuntimeStatus.Stopped] is the one state that
 * keeps the tight interval despite having no active work - it is exactly what the auto-restart
 * check exists to notice and recover from quickly - and every bounded, in-progress state
 * ([Installing][LocalRuntimeStatus.Installing], [Starting][LocalRuntimeStatus.Starting],
 * [Updating][LocalRuntimeStatus.Updating]) needs its wake lock re-armed well before the timeout.
 */
internal fun watchdogIntervalMillis(
    status: LocalRuntimeStatus,
    hasActiveWork: Boolean,
): Long =
    when {
        status is LocalRuntimeStatus.Ready && !hasActiveWork -> WATCHDOG_IDLE_INTERVAL_MILLIS
        status is LocalRuntimeStatus.Broken -> WATCHDOG_IDLE_INTERVAL_MILLIS
        status is LocalRuntimeStatus.UnsupportedAbi -> WATCHDOG_IDLE_INTERVAL_MILLIS
        status is LocalRuntimeStatus.NotInstalled -> WATCHDOG_IDLE_INTERVAL_MILLIS
        else -> WATCHDOG_ACTIVE_INTERVAL_MILLIS
    }

/**
 * Whether the local runtime should be shut down after sitting idle for too long.
 *
 * Scheduled runs restart the runtime on demand
 * ([com.yugahashimoto.andcode.feature.schedule.ScheduleExecutionService.ensureLocalRuntimeReady])
 * and opening the app restarts it via `RuntimeAutoStartInitializer`, so nothing the user asked for
 * is lost by stopping here - it only costs a start-up delay the next time work actually arrives.
 * Restricted to [Ready][LocalRuntimeStatus.Ready] so an install, update, rollback or restore -
 * every one of which holds its own lease through
 * [com.yugahashimoto.andcode.core.runtime.RuntimeWorkTracker] and therefore already fails
 * [hasActiveWork] - is never caught mid-operation even if this were checked at the wrong moment.
 *
 * [adbConnected] is deliberately not folded into [hasActiveWork]: a live wireless-debugging link is
 * only leased for the duration of an actual shell command (see [AdbConnectionManager]), since that
 * state is re-established every 30 seconds by its auto-reconnect loop and a lease held for as long
 * as it reads `Connected` would keep the device awake forever for anyone who has ever paired. A
 * connected link still has to block this shutdown though - killing the runtime out from under an
 * active debugging session would be as disruptive as freezing a chat run - so it is threaded through
 * as its own condition instead.
 */
internal fun shouldStopIdleRuntime(
    status: LocalRuntimeStatus,
    hasActiveWork: Boolean,
    appInForeground: Boolean,
    adbConnected: Boolean,
    idleForMillis: Long,
    timeoutMillis: Long,
): Boolean =
    status is LocalRuntimeStatus.Ready &&
        !hasActiveWork &&
        !appInForeground &&
        !adbConnected &&
        idleForMillis >= timeoutMillis

internal class RestartBackoff(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val baseDelayMs: Long = 1_000L,
    private val maxDelayMs: Long = 60_000L,
    private val resetWindowMs: Long = 30_000L,
) {
    private var consecutiveCrashes = 0
    private var lastCrashAtMillis = 0L

    fun recordCrash(): Long {
        val now = nowMillis()
        if (now - lastCrashAtMillis > resetWindowMs) {
            consecutiveCrashes = 1
        } else {
            consecutiveCrashes++
        }
        lastCrashAtMillis = now
        return delayMillis()
    }

    fun reset() {
        consecutiveCrashes = 0
        lastCrashAtMillis = 0L
    }

    fun recordUptime(uptimeMillis: Long) {
        if (uptimeMillis >= resetWindowMs) {
            reset()
        }
    }

    private fun delayMillis(): Long {
        if (consecutiveCrashes <= 1) return 0L
        val delay = baseDelayMs * (1L shl (consecutiveCrashes - 2).coerceAtMost(6))
        return delay.coerceAtMost(maxDelayMs)
    }
}

/**
 * Tracks how long [shouldStopIdleRuntime]'s idle condition has continuously held, using
 * [SystemClock.elapsedRealtime] rather than [System.currentTimeMillis] by default - the latter jumps
 * with an NTP correction or a manual clock change, which would make "idle for 15 minutes" fire early
 * or never depending on which way the clock moved, while elapsed-realtime only ever moves forward
 * with actual device uptime. The clock is injected, and kept in its own class rather than a bare
 * field on [LocalRuntimeService], so this bookkeeping is testable without a real Android service.
 */
internal class IdleStopTracker(private val nowMillis: () -> Long = SystemClock::elapsedRealtime) {
    /**
     * When the idle condition first became true; `null` while it does not currently hold. Callers
     * must serialize their own calls to [update] - this class does no synchronization of its own.
     */
    private var idleSinceMillis: Long? = null

    /** Call on every watchdog tick with the current idle condition; returns how long it has held. */
    fun update(idleNow: Boolean): Long {
        val now = nowMillis()
        idleSinceMillis = if (idleNow) idleSinceMillis ?: now else null
        return idleSinceMillis?.let { now - it } ?: 0L
    }
}

/**
 * The idle auto-stop's set-stop-clear sequence, pulled out of [LocalRuntimeService.checkIdleStop]
 * so [markInProgress]'s set/clear pairing can be verified without a real Android [Service]: the
 * only way [markInProgress](false) can fail to run after [markInProgress](true) succeeded is
 * [stop] throwing, and the `finally` here covers that the same way the inline version did. What the
 * extraction actually buys is atomicity - the set and the eventual clear are one suspend-function
 * call, so scheduling this via `scope.launch` means a scope cancelled before that coroutine starts
 * runs neither, instead of the set running standalone with nothing left to run the clear.
 */
internal suspend fun runIdleStopSequence(
    markInProgress: (Boolean) -> Unit,
    stop: suspend () -> Unit,
    onStopped: () -> Unit,
) {
    markInProgress(true)
    try {
        stop()
    } finally {
        markInProgress(false)
        onStopped()
    }
}

class LocalRuntimeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var operation: Job? = null
    private var watchdogJob: Job? = null
    private var exitMonitorJob: Job? = null

    @Volatile private var autoRestartEnabled = false
    private lateinit var app: AndCodeApplication
    private lateinit var manager: LocalRuntimeManager
    private lateinit var runtimeWork: RuntimeWorkTracker
    private lateinit var appForeground: AppForeground
    private val backoff = RestartBackoff()
    private var inForeground = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var shuttingDown = false

    /**
     * Read and written only from [startWatchdog]'s own coroutine, so it needs no synchronization of
     * its own despite [IdleStopTracker] not providing any.
     */
    private val idleTracker = IdleStopTracker()

    override fun onCreate() {
        super.onCreate()
        app = application as AndCodeApplication
        manager = app.localRuntimeManager
        runtimeWork = app.runtimeWork
        appForeground = app.appForeground
        createChannel()
        // The platform can refuse the foreground promotion for a service the app started while it
        // was in the background. Giving up beats being killed for never calling startForeground.
        inForeground =
            runCatching { startForeground(NOTIFICATION_ID, notification(manager.status())) }
                .onFailure { error -> Log.w(TAG, "Could not enter the foreground", error) }
                .isSuccess
        if (!inForeground) return
        syncWakeLock(manager.status(), runtimeWork.active.value)
        scope.launch {
            manager.state.collectLatest { status ->
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(status))
                if (status is LocalRuntimeStatus.Ready) {
                    backoff.reset()
                }
            }
        }
        // A separate collector from the one above: a chat starting or ending while the status
        // itself does not change (Ready the whole time) must still re-arm or drop the wake lock
        // immediately, not wait for the next incidental status update.
        scope.launch {
            combine(manager.state, runtimeWork.active, ::Pair).collectLatest { (status, hasActiveWork) ->
                syncWakeLock(status, hasActiveWork)
            }
        }
        manager.setOnExit { exitCode, pid, uptime ->
            if (!autoRestartEnabled) return@setOnExit
            backoff.recordUptime(uptime)
            val delayMs = backoff.recordCrash()
            exitMonitorJob?.cancel()
            exitMonitorJob =
                scope.launch(Dispatchers.IO) {
                    if (delayMs > 0) delay(delayMs)
                    if (!isActive || !autoRestartEnabled) return@launch
                    manager.ensureRunning()
                }
        }
        startWatchdog()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val command = localRuntimeServiceCommand(intent?.action)
        // An explicit start always means "the user (or something acting for them) wants this
        // running" - without clearing the flag here, a deliberate restart from the workspace screen
        // would still read as "leave it down" the very next time the app comes to the foreground.
        // Cleared before the startForeground-failure early return below too: a background
        // ACTION_START the platform refused to promote is still an explicit ask, and leaving the
        // flag set would strand the very next foreground return's restore attempt on it.
        if (clearsUserStoppedFlag(command)) app.settings.localRuntimeStoppedByUser = false
        if (!inForeground) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (command) {
            LocalRuntimeServiceCommand.InstallAndStart -> {
                autoRestartEnabled = true
                val agents = localRuntimeInstallAgents(intent?.getStringArrayExtra(EXTRA_AGENTS))
                val installFullDevelopmentTools = intent?.getBooleanExtra(EXTRA_FULL_DEVELOPMENT_TOOLS, false) == true
                launchOperation { manager.installAndStart(agents, installFullDevelopmentTools) }
            }
            LocalRuntimeServiceCommand.InstallFullDevelopmentTools -> {
                val runtimeWasRunning = manager.status() is LocalRuntimeStatus.Ready
                launchOperation {
                    manager.installFullDevelopmentTools()
                    if (!runtimeWasRunning) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
            LocalRuntimeServiceCommand.Start -> {
                autoRestartEnabled = true
                launchOperation { manager.start() }
            }
            LocalRuntimeServiceCommand.Reinstall -> {
                autoRestartEnabled = true
                launchOperation { manager.reinstall() }
            }
            LocalRuntimeServiceCommand.Update -> {
                autoRestartEnabled = true
                launchOperation { manager.updateToLatest() }
            }
            LocalRuntimeServiceCommand.Rollback -> {
                autoRestartEnabled = true
                launchOperation { manager.rollback() }
            }
            LocalRuntimeServiceCommand.Delete -> {
                autoRestartEnabled = false
                launchOperation {
                    manager.deleteRuntime()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            LocalRuntimeServiceCommand.Stop -> {
                autoRestartEnabled = false
                // A deliberate stop - the notification's action or WorkspaceViewModel.stopLocalRuntime
                // - must not be silently undone the next time the app is opened, unlike the idle
                // auto-stop's own shutdown in checkIdleStop, which never sets this.
                app.settings.localRuntimeStoppedByUser = true
                launchOperation {
                    manager.stop()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            LocalRuntimeServiceCommand.Restart -> {
                autoRestartEnabled = true
                launchOperation {
                    manager.stop()
                    manager.start()
                }
            }
            LocalRuntimeServiceCommand.Restore -> {
                autoRestartEnabled = true
                if (manager.status() is LocalRuntimeStatus.Stopped) {
                    launchOperation { manager.ensureRunning() }
                }
            }
            LocalRuntimeServiceCommand.Ignore -> Unit
        }
        return START_STICKY
    }

    override fun onDestroy() {
        autoRestartEnabled = false
        manager.setOnExit(null)
        operation?.cancel()
        exitMonitorJob?.cancel()
        watchdogJob?.cancel()
        scope.cancel()
        shutDownWakeLock()
        // Belt-and-braces: scope.cancel() above can land between checkIdleStop scheduling its
        // coroutine and that coroutine actually starting, in which case the set/clear pair inside it
        // never runs at all. Clearing here as well means the flag can never outlive this service
        // instance regardless of exactly where a cancellation lands.
        app.setIdleStopInProgress(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Runs [block] under a `"runtime-op"` [RuntimeWorkTracker] lease.
     *
     * Every command this service itself executes - installing, starting, updating, rolling back,
     * restoring or deleting the OpenCode runtime - passes through here, and each is real work the
     * device must not suspend through; the difference from a chat session is only that nothing else
     * already tracks it, since none of them touch
     * [com.yugahashimoto.andcode.data.repository.RuntimeActivityRepository]. Claude Code and
     * Antigravity's own install and update flows are separate controllers
     * ([ClaudeCodeController], [AntigravityController]) that never call this service, so they hold
     * their own leases instead of passing through here.
     */
    private fun launchOperation(block: suspend () -> Unit) {
        operation?.cancel()
        operation = scope.launch(Dispatchers.IO) { runtimeWork.withLease(RUNTIME_OPERATION_LEASE_TAG) { block() } }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob =
            scope.launch(Dispatchers.IO) {
                val watchdog = LocalRuntimeWatchdog()
                while (isActive) {
                    delay(watchdogIntervalMillis(manager.status(), runtimeWork.active.value))
                    val status = manager.status()
                    val hasActiveWork = runtimeWork.active.value
                    // Re-arms the wake lock's timeout well inside it, so a run lasting hours keeps
                    // the CPU up while a service that died without onDestroy still lets it lapse -
                    // still comfortably inside the 10-minute timeout even at the 60-second idle
                    // interval. Ahead of the auto-restart check so that a runtime left running by a
                    // path that never enables it - an unknown action, a stop that threw - still
                    // stays awake.
                    syncWakeLock(status, hasActiveWork)

                    if (checkIdleStop(status, hasActiveWork)) return@launch

                    if (!autoRestartEnabled) continue
                    if (watchdog.observe(status)) {
                        manager.ensureRunning()
                    }
                }
            }
    }

    /**
     * Tracks how long the idle-stop condition has held and, once [shouldStopIdleRuntime] agrees it
     * has held long enough, stops the runtime and this service the same way
     * [LocalRuntimeServiceCommand.Stop] does. Returns true when it stopped the runtime, so the
     * watchdog loop can end its own tick loop instead of running once more against a service
     * already on its way down.
     *
     * The setting is read fresh on every tick rather than cached, so flipping it in Settings takes
     * effect on the very next check instead of needing the service to restart.
     */
    private fun checkIdleStop(
        status: LocalRuntimeStatus,
        hasActiveWork: Boolean,
    ): Boolean {
        val appInForeground = appForeground.foreground.value
        val adbConnected = app.adbConnectionManager.state.value is AdbConnectionState.Connected
        val idleNow = status is LocalRuntimeStatus.Ready && !hasActiveWork && !appInForeground && !adbConnected
        val idleForMillis = idleTracker.update(idleNow)

        if (!app.settings.localRuntimeIdleStopEnabled) return false
        if (!shouldStopIdleRuntime(status, hasActiveWork, appInForeground, adbConnected, idleForMillis, IDLE_STOP_TIMEOUT_MILLIS)) {
            return false
        }
        autoRestartEnabled = false
        // Deliberately not launchOperation: this is the watchdog's own decision, not a user or
        // notification action, so it must not cancel anything already tracked in `operation`. The
        // set/clear pair for setIdleStopInProgress is extracted into runIdleStopSequence precisely so
        // both live inside one coroutine body rather than straddling this launch call: if onDestroy
        // (and therefore scope.cancel()) lands between scheduling this coroutine and it actually
        // starting, the body - set included - simply never runs, rather than setting the flag and
        // then never reaching the finally that clears it. A stuck `true` would make
        // shouldRestoreOnForegroundReturn treat every later foreground entry as needing a restore,
        // sending ACTION_START at a healthy Ready runtime and cancelling whatever operation it finds
        // in flight. onDestroy also clears the flag directly as a belt-and-braces guard for any
        // window this reasoning misses.
        scope.launch(Dispatchers.IO) {
            runtimeWork.withLease(RUNTIME_OPERATION_LEASE_TAG) {
                runIdleStopSequence(
                    markInProgress = app::setIdleStopInProgress,
                    stop = manager::stop,
                    onStopped = {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    },
                )
            }
        }
        return true
    }

    /**
     * Holds or drops the CPU wake lock to match [status] and [hasActiveWork]; see
     * [localRuntimeNeedsWakeLock].
     *
     * Synchronized because both the status collector on the main thread and the watchdog on an IO
     * one call it. The lock is not reference counted, so an acquire on a status that already holds
     * it only pushes the timeout back rather than stacking a release debt.
     */
    @Synchronized
    private fun syncWakeLock(
        status: LocalRuntimeStatus,
        hasActiveWork: Boolean,
    ) {
        if (shuttingDown || !localRuntimeNeedsWakeLock(status, hasActiveWork)) {
            releaseWakeLock()
            return
        }
        val lock =
            wakeLock ?: getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
                ?.apply { setReferenceCounted(false) }
                ?.also { wakeLock = it }
        runCatching { lock?.acquire(WAKELOCK_TIMEOUT_MILLIS) }
            .onFailure { error -> Log.w(TAG, "Could not hold the CPU awake", error) }
    }

    @Synchronized
    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (!lock.isHeld) return
        runCatching { lock.release() }
            .onFailure { error -> Log.w(TAG, "Could not let the CPU sleep", error) }
    }

    /**
     * Drops the lock for good.
     *
     * [android.app.Service.onDestroy] cancelling the scope does not interrupt a watchdog tick that
     * is already inside [syncWakeLock], so a plain release can be followed by that tick's acquire
     * and leave the lock held past the service. The flag closes that window from inside the same
     * monitor: whichever of the two runs first, the tick ends up a no-op.
     */
    @Synchronized
    private fun shutDownWakeLock() {
        shuttingDown = true
        releaseWakeLock()
    }

    private fun notification(status: LocalRuntimeStatus): android.app.Notification {
        val openIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val stopIntent =
            PendingIntent.getService(
                this,
                1,
                Intent(this, LocalRuntimeService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val (title, text, indeterminate, progress) =
            when (status) {
                LocalRuntimeStatus.NotInstalled ->
                    NotificationState(
                        getString(R.string.app_name),
                        getString(R.string.notification_runtime_not_installed),
                    )
                is LocalRuntimeStatus.Installing ->
                    NotificationState(
                        getString(R.string.notification_runtime_setting_up),
                        status.step,
                        status.progress == null,
                        ((status.progress ?: 0f) * 100).toInt(),
                    )
                is LocalRuntimeStatus.Starting ->
                    NotificationState(
                        getString(R.string.notification_runtime_starting),
                        getString(R.string.capability_version, status.version),
                        true,
                    )
                is LocalRuntimeStatus.Updating ->
                    NotificationState(
                        getString(R.string.notification_runtime_updating),
                        status.step,
                        status.progress == null,
                        ((status.progress ?: 0f) * 100).toInt(),
                    )
                is LocalRuntimeStatus.Stopped ->
                    NotificationState(
                        getString(R.string.notification_runtime_stopped),
                        getString(R.string.capability_version, status.version),
                    )
                is LocalRuntimeStatus.Ready ->
                    NotificationState(
                        getString(R.string.notification_runtime_ready),
                        getString(R.string.notification_runtime_ready_detail, status.version, status.port),
                    )
                is LocalRuntimeStatus.Broken -> NotificationState(getString(R.string.notification_runtime_broken), status.reason)
                is LocalRuntimeStatus.UnsupportedAbi ->
                    NotificationState(
                        getString(R.string.notification_runtime_unsupported_device),
                        getString(R.string.unsupported_abi, status.abi),
                    )
            }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(
                status is LocalRuntimeStatus.Ready ||
                    status is LocalRuntimeStatus.Installing ||
                    status is LocalRuntimeStatus.Starting ||
                    status is LocalRuntimeStatus.Updating,
            )
            .setOnlyAlertOnce(true)
            .setProgress(if (indeterminate || progress > 0) 100 else 0, progress, indeterminate)
            .addAction(0, getString(R.string.stop_run), stopIntent)
            .build()
    }

    private fun createChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.local_runtime_screen_title),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_local_runtime_description)
            }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private data class NotificationState(
        val title: String,
        val text: String,
        val indeterminate: Boolean = false,
        val progress: Int = 0,
    )

    companion object {
        private const val TAG = "LocalRuntimeService"
        private const val CHANNEL_ID = "local_opencode_runtime"
        private const val NOTIFICATION_ID = 4107
        private const val WAKELOCK_TAG = "opencode:runtime"
        private const val WAKELOCK_TIMEOUT_MILLIS = 10 * 60 * 1000L
        private const val RUNTIME_OPERATION_LEASE_TAG = "runtime-op"
        const val ACTION_INSTALL_AND_START = "com.yugahashimoto.andcode.local.INSTALL_AND_START"
        const val ACTION_INSTALL_FULL_DEVELOPMENT_TOOLS = "com.yugahashimoto.andcode.local.INSTALL_FULL_DEVELOPMENT_TOOLS"
        const val ACTION_START = "com.yugahashimoto.andcode.local.START"
        const val ACTION_STOP = "com.yugahashimoto.andcode.local.STOP"
        const val ACTION_RESTART = "com.yugahashimoto.andcode.local.RESTART"
        const val ACTION_REINSTALL = "com.yugahashimoto.andcode.local.REINSTALL"
        const val ACTION_UPDATE = "com.yugahashimoto.andcode.local.UPDATE"
        const val ACTION_ROLLBACK = "com.yugahashimoto.andcode.local.ROLLBACK"
        const val ACTION_DELETE = "com.yugahashimoto.andcode.local.DELETE"

        /** Ids of the agents an install should provision; see [LocalRuntimeInstaller.install]. */
        const val EXTRA_AGENTS = "com.yugahashimoto.andcode.local.AGENTS"
        const val EXTRA_FULL_DEVELOPMENT_TOOLS = "com.yugahashimoto.andcode.local.FULL_DEVELOPMENT_TOOLS"

        /**
         * Sends a command to the runtime service, starting it when it is not running yet.
         *
         * Android 12+ only lets an app start a foreground service from the background when it is
         * exempt, and the app is woken in the background by things it does not drive: a schedule's
         * alarm, a widget update. The auto-start on process creation then reaches this from a
         * process that holds no exemption, so a refusal has to stay a refusal rather than take the
         * app down - the runtime comes up on the next start that is allowed.
         */
        fun send(
            context: Context,
            action: String,
            agents: Set<LocalAgent> = emptySet(),
            installFullDevelopmentTools: Boolean = false,
        ) {
            val intent = Intent(context, LocalRuntimeService::class.java).setAction(action)
            if (agents.isNotEmpty()) intent.putExtra(EXTRA_AGENTS, agents.map(LocalAgent::id).toTypedArray())
            if (installFullDevelopmentTools) intent.putExtra(EXTRA_FULL_DEVELOPMENT_TOOLS, true)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { error -> Log.w(TAG, "Foreground start refused for $action", error) }
        }
    }
}

class LocalRuntimeServiceController(private val context: Context) {
    /**
     * [agents] is the setup guide's selection, and provisioning them in this one install is what
     * makes ticking Claude Code or Antigravity alongside OpenCode actually install them: their own
     * installers would otherwise have to race this one for the same staging directory.
     */
    fun installAndStart(
        agents: Set<LocalAgent> = setOf(LocalAgent.OPEN_CODE),
        installFullDevelopmentTools: Boolean = false,
    ) = LocalRuntimeService.send(
        context,
        LocalRuntimeService.ACTION_INSTALL_AND_START,
        agents,
        installFullDevelopmentTools,
    )

    fun installFullDevelopmentTools() = LocalRuntimeService.send(context, LocalRuntimeService.ACTION_INSTALL_FULL_DEVELOPMENT_TOOLS)

    fun start() = LocalRuntimeService.send(context, LocalRuntimeService.ACTION_START)

    fun stop() = LocalRuntimeService.send(context, LocalRuntimeService.ACTION_STOP)

    fun restart() = LocalRuntimeService.send(context, LocalRuntimeService.ACTION_RESTART)

    fun reinstall() = LocalRuntimeService.send(context, LocalRuntimeService.ACTION_REINSTALL)

    fun update() = LocalRuntimeService.send(context, LocalRuntimeService.ACTION_UPDATE)

    fun rollback() = LocalRuntimeService.send(context, LocalRuntimeService.ACTION_ROLLBACK)

    fun delete() = LocalRuntimeService.send(context, LocalRuntimeService.ACTION_DELETE)
}
