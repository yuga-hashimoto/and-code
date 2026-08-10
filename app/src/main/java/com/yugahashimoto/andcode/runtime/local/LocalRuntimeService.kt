package com.yugahashimoto.andcode.runtime.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yugahashimoto.andcode.AndCodeApplication
import com.yugahashimoto.andcode.MainActivity
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal enum class LocalRuntimeServiceCommand {
    InstallAndStart,
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
 * suspending freezes it mid-run - the symptom being work that "stopped by itself" in the background.
 * The states that carry no running process - stopped, broken, never installed, unsupported - hold
 * nothing, since keeping a dead runtime's device awake only costs battery.
 */
internal fun localRuntimeNeedsWakeLock(status: LocalRuntimeStatus): Boolean =
    when (status) {
        is LocalRuntimeStatus.Installing,
        is LocalRuntimeStatus.Starting,
        is LocalRuntimeStatus.Updating,
        is LocalRuntimeStatus.Ready,
        -> true
        LocalRuntimeStatus.NotInstalled,
        is LocalRuntimeStatus.Stopped,
        is LocalRuntimeStatus.Broken,
        is LocalRuntimeStatus.UnsupportedAbi,
        -> false
    }

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

class LocalRuntimeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var operation: Job? = null
    private var watchdogJob: Job? = null
    private var exitMonitorJob: Job? = null

    @Volatile private var autoRestartEnabled = false
    private lateinit var manager: LocalRuntimeManager
    private val backoff = RestartBackoff()
    private var inForeground = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var shuttingDown = false

    override fun onCreate() {
        super.onCreate()
        manager = (application as AndCodeApplication).localRuntimeManager
        createChannel()
        // The platform can refuse the foreground promotion for a service the app started while it
        // was in the background. Giving up beats being killed for never calling startForeground.
        inForeground =
            runCatching { startForeground(NOTIFICATION_ID, notification(manager.status())) }
                .onFailure { error -> Log.w(TAG, "Could not enter the foreground", error) }
                .isSuccess
        if (!inForeground) return
        syncWakeLock(manager.status())
        scope.launch {
            manager.state.collectLatest { status ->
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(status))
                syncWakeLock(status)
                if (status is LocalRuntimeStatus.Ready) {
                    backoff.reset()
                }
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
        if (!inForeground) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (localRuntimeServiceCommand(intent?.action)) {
            LocalRuntimeServiceCommand.InstallAndStart -> {
                autoRestartEnabled = true
                val agents = localRuntimeInstallAgents(intent?.getStringArrayExtra(EXTRA_AGENTS))
                launchOperation { manager.installAndStart(agents) }
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun launchOperation(block: suspend () -> Unit) {
        operation?.cancel()
        operation = scope.launch(Dispatchers.IO) { block() }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob =
            scope.launch(Dispatchers.IO) {
                val watchdog = LocalRuntimeWatchdog()
                while (isActive) {
                    delay(WATCHDOG_INTERVAL_MILLIS)
                    val status = manager.status()
                    // Re-arms the wake lock's timeout well inside it, so a run lasting hours keeps
                    // the CPU up while a service that died without onDestroy still lets it lapse.
                    // Ahead of the auto-restart check so that a runtime left running by a path that
                    // never enables it - an unknown action, a stop that threw - still stays awake.
                    syncWakeLock(status)
                    if (!autoRestartEnabled) continue
                    if (watchdog.observe(status)) {
                        manager.ensureRunning()
                    }
                }
            }
    }

    /**
     * Holds or drops the CPU wake lock to match [status]; see [localRuntimeNeedsWakeLock].
     *
     * Synchronized because both the status collector on the main thread and the watchdog on an IO
     * one call it. The lock is not reference counted, so an acquire on a status that already holds
     * it only pushes the timeout back rather than stacking a release debt.
     */
    @Synchronized
    private fun syncWakeLock(status: LocalRuntimeStatus) {
        if (shuttingDown || !localRuntimeNeedsWakeLock(status)) {
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
        private const val WATCHDOG_INTERVAL_MILLIS = 5_000L
        private const val WAKELOCK_TAG = "opencode:runtime"
        private const val WAKELOCK_TIMEOUT_MILLIS = 10 * 60 * 1000L
        const val ACTION_INSTALL_AND_START = "com.yugahashimoto.andcode.local.INSTALL_AND_START"
        const val ACTION_START = "com.yugahashimoto.andcode.local.START"
        const val ACTION_STOP = "com.yugahashimoto.andcode.local.STOP"
        const val ACTION_RESTART = "com.yugahashimoto.andcode.local.RESTART"
        const val ACTION_REINSTALL = "com.yugahashimoto.andcode.local.REINSTALL"
        const val ACTION_UPDATE = "com.yugahashimoto.andcode.local.UPDATE"
        const val ACTION_ROLLBACK = "com.yugahashimoto.andcode.local.ROLLBACK"
        const val ACTION_DELETE = "com.yugahashimoto.andcode.local.DELETE"

        /** Ids of the agents an install should provision; see [LocalRuntimeInstaller.install]. */
        const val EXTRA_AGENTS = "com.yugahashimoto.andcode.local.AGENTS"

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
        ) {
            val intent = Intent(context, LocalRuntimeService::class.java).setAction(action)
            if (agents.isNotEmpty()) intent.putExtra(EXTRA_AGENTS, agents.map(LocalAgent::id).toTypedArray())
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
    fun installAndStart(agents: Set<LocalAgent> = setOf(LocalAgent.OPEN_CODE)) =
        LocalRuntimeService.send(context, LocalRuntimeService.ACTION_INSTALL_AND_START, agents)

    fun start() = LocalRuntimeService.send(context, LocalRuntimeService.ACTION_START)

    fun stop() = LocalRuntimeService.send(context, LocalRuntimeService.ACTION_STOP)

    fun restart() = LocalRuntimeService.send(context, LocalRuntimeService.ACTION_RESTART)

    fun reinstall() = LocalRuntimeService.send(context, LocalRuntimeService.ACTION_REINSTALL)

    fun update() = LocalRuntimeService.send(context, LocalRuntimeService.ACTION_UPDATE)

    fun rollback() = LocalRuntimeService.send(context, LocalRuntimeService.ACTION_ROLLBACK)

    fun delete() = LocalRuntimeService.send(context, LocalRuntimeService.ACTION_DELETE)
}
