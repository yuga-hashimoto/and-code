package com.yugahashimoto.andcode

import android.app.Application
import android.content.Context
import android.net.nsd.NsdManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.startup.AppInitializer
import com.yugahashimoto.andcode.core.api.GitHubApiClient
import com.yugahashimoto.andcode.core.diagnostics.AnalyticsReporter
import com.yugahashimoto.andcode.core.diagnostics.CrashLog
import com.yugahashimoto.andcode.core.diagnostics.CrashReporter
import com.yugahashimoto.andcode.core.lifecycle.AppForeground
import com.yugahashimoto.andcode.core.lifecycle.ForegroundReturnDetector
import com.yugahashimoto.andcode.core.lifecycle.ProcessLifecycleAppForeground
import com.yugahashimoto.andcode.core.locale.AppLanguage
import com.yugahashimoto.andcode.core.notification.RuntimeNotificationHelper
import com.yugahashimoto.andcode.core.runtime.RuntimeWorkTracker
import com.yugahashimoto.andcode.core.security.SecretRedaction
import com.yugahashimoto.andcode.core.storage.DeviceStorage
import com.yugahashimoto.andcode.core.storage.DeviceStorageAccess
import com.yugahashimoto.andcode.core.util.debounceFalseEdge
import com.yugahashimoto.andcode.data.connection.SecureSettingsRepository
import com.yugahashimoto.andcode.data.repository.AndroidRuntimeActivityMessages
import com.yugahashimoto.andcode.data.repository.AndroidRuntimeCatalogMessages
import com.yugahashimoto.andcode.data.repository.ProviderCatalogCache
import com.yugahashimoto.andcode.data.repository.PullRequestStatusRepository
import com.yugahashimoto.andcode.data.repository.RuntimeActivityRepository
import com.yugahashimoto.andcode.data.repository.RuntimeCatalogRepository
import com.yugahashimoto.andcode.data.repository.SessionAutoArchiver
import com.yugahashimoto.andcode.data.schedule.ScheduleRepository
import com.yugahashimoto.andcode.data.settings.AppPreferencesRepository
import com.yugahashimoto.andcode.di.appModule
import com.yugahashimoto.andcode.di.viewModelModule
import com.yugahashimoto.andcode.feature.schedule.AppScheduleStore
import com.yugahashimoto.andcode.feature.schedule.ScheduleBridge
import com.yugahashimoto.andcode.feature.schedule.ScheduleManager
import com.yugahashimoto.andcode.feature.support.GitHubStarCoordinator
import com.yugahashimoto.andcode.feature.support.GitHubStarService
import com.yugahashimoto.andcode.feature.wakeword.VoskModelStore
import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import com.yugahashimoto.andcode.runtime.RuntimeRegistry
import com.yugahashimoto.andcode.runtime.local.AdbConnectionManager
import com.yugahashimoto.andcode.runtime.local.AdbShellRunner
import com.yugahashimoto.andcode.runtime.local.AndroidClaudeMessages
import com.yugahashimoto.andcode.runtime.local.AndroidLocalRuntimeMessages
import com.yugahashimoto.andcode.runtime.local.AntigravityController
import com.yugahashimoto.andcode.runtime.local.AntigravityRuntime
import com.yugahashimoto.andcode.runtime.local.AntigravityTarget
import com.yugahashimoto.andcode.runtime.local.ClaudeCodeController
import com.yugahashimoto.andcode.runtime.local.ClaudeCodeRuntime
import com.yugahashimoto.andcode.runtime.local.ClaudeCodeTarget
import com.yugahashimoto.andcode.runtime.local.DefaultLocalRuntimeUpdateEngine
import com.yugahashimoto.andcode.runtime.local.GitCloneRepository
import com.yugahashimoto.andcode.runtime.local.GitCredentialHelper
import com.yugahashimoto.andcode.runtime.local.LocalProviderCredentialStore
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeAccessCoordinator
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeCommandRunner
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeDiagnosticsCollector
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeInstaller
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeManager
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeMessages
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeProcessLauncher
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeReleaseClient
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeServiceController
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeTarget
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeUpdater
import com.yugahashimoto.andcode.runtime.local.SystemPromptStore
import com.yugahashimoto.andcode.runtime.local.VerifiedRuntimeDownloader
import com.yugahashimoto.andcode.runtime.local.applyOpenCodeSystemPrompt
import com.yugahashimoto.andcode.startup.CatalogReconcileInitializer
import com.yugahashimoto.andcode.startup.RuntimeAutoStartInitializer
import com.yugahashimoto.andcode.startup.RuntimeAutoStartTrigger
import com.yugahashimoto.andcode.startup.shouldRestoreOnForegroundReturn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import java.io.File

class AndCodeApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var settings: SecureSettingsRepository
        private set

    lateinit var preferences: AppPreferencesRepository
        private set

    /**
     * The single source of truth for whether the device may suspend. Created at the very start of
     * [onCreate] so every collaborator built afterwards - the adb manager, the agent controllers,
     * the activity repository's bridge - can take it as a plain constructor dependency.
     */
    lateinit var runtimeWork: RuntimeWorkTracker
        private set

    /** Whether an activity of the app is on screen; see [AppForeground]. */
    lateinit var appForeground: AppForeground
        private set

    private val mutableIdleStopInProgress = MutableStateFlow(false)

    /**
     * True for the brief window between [LocalRuntimeService.checkIdleStop] deciding to shut the
     * idle runtime down and [LocalRuntimeManager.stop] actually finishing. The status is still read
     * as [LocalRuntimeStatus.Ready] for that whole window - there is no `Stopping` state - so
     * without this a foreground return landing inside it would fail
     * [shouldRestoreOnForegroundReturn]'s `Stopped` check and leave the runtime down until the
     * *next* return instead of this one. Set by [LocalRuntimeService] itself via
     * [setIdleStopInProgress].
     */
    val idleStopInProgress: StateFlow<Boolean> = mutableIdleStopInProgress.asStateFlow()

    fun setIdleStopInProgress(inProgress: Boolean) {
        mutableIdleStopInProgress.value = inProgress
    }

    lateinit var localRuntimeManager: LocalRuntimeManager
        private set

    lateinit var localRuntimeController: LocalRuntimeServiceController
        private set

    lateinit var localRuntimeDiagnosticsCollector: LocalRuntimeDiagnosticsCollector
        private set

    lateinit var runtimeRegistry: RuntimeRegistry
        private set

    lateinit var catalogRepository: RuntimeCatalogRepository
        private set

    lateinit var activityRepository: RuntimeActivityRepository
        private set

    lateinit var notifications: RuntimeNotificationHelper
        private set

    lateinit var scheduleRepository: ScheduleRepository
        private set

    lateinit var scheduleManager: ScheduleManager
        private set

    lateinit var providerCredentials: LocalProviderCredentialStore
        private set

    lateinit var voskModels: VoskModelStore
        private set

    lateinit var gitCloneRepository: GitCloneRepository
        private set

    lateinit var commandRunner: LocalRuntimeCommandRunner
        private set

    lateinit var claudeCodeRuntime: ClaudeCodeRuntime
        private set

    lateinit var claudeCodeTarget: ClaudeCodeTarget
        private set

    /** Shared by every agent that can carry a system-prompt preset. */
    lateinit var systemPromptStore: SystemPromptStore
        private set

    lateinit var claudeCodeController: ClaudeCodeController
        private set

    lateinit var adbConnectionManager: AdbConnectionManager
        private set

    lateinit var antigravityRuntime: AntigravityRuntime
        private set

    lateinit var antigravityTarget: AntigravityTarget
        private set

    lateinit var antigravityController: AntigravityController
        private set

    lateinit var runtimeMessages: LocalRuntimeMessages
        private set

    lateinit var githubStarCoordinator: GitHubStarCoordinator
        private set

    lateinit var pullRequestStatusRepository: PullRequestStatusRepository
        private set

    lateinit var deviceStorageAccess: DeviceStorageAccess
        private set

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.applyTo(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        // First, so a crash in the rest of this method is recorded too.
        CrashLog.install(this)
        // CrashLog handles the local file; Crashlytics adds remote fatal and non-fatal reporting.
        CrashReporter.install()
        startKoin {
            androidContext(this@AndCodeApplication)
            modules(appModule, viewModelModule)
        }
        appForeground = ProcessLifecycleAppForeground.install()
        // Created early so every collaborator constructed below - the adb manager, the agent
        // controllers, the activity repository's bridge - can take it as a plain constructor
        // dependency instead of a nullable one wired in after the fact.
        runtimeWork = RuntimeWorkTracker()
        settings = SecureSettingsRepository(this)
        // Analytics is explicitly opt-in; source-code tooling should not silently collect usage data.
        AnalyticsReporter.install(this, settings.analyticsEnabled)
        preferences = AppPreferencesRepository(settings)
        scheduleRepository = ScheduleRepository(this).also { it.reconcileStaleRuns() }
        deviceStorageAccess = DeviceStorageAccess(this)
        // Asked on every sandbox launch rather than captured once: the user can grant all-files
        // access from system settings and come straight back without the process restarting.
        DeviceStorage.install { deviceStorageAccess.mounts() }
        notifications = RuntimeNotificationHelper(this)
        providerCredentials = LocalProviderCredentialStore(settings)
        val httpClient = OkHttpClient()
        // Application-scoped so that navigating away from voice settings does not abandon a model
        // download half-written.
        voskModels = VoskModelStore(this, applicationScope, httpClient)
        githubStarCoordinator =
            GitHubStarCoordinator(
                settings = settings,
                service = GitHubStarService(client = httpClient, tokenProvider = { settings.githubToken }),
                scope = applicationScope,
            )
        pullRequestStatusRepository =
            PullRequestStatusRepository(
                api = GitHubApiClient(token = { settings.githubToken }, client = httpClient),
                scope = applicationScope,
            )
        val runtimeDirectory = File(filesDir, "runtime")
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val accessCoordinator = LocalRuntimeAccessCoordinator()
        val installer =
            LocalRuntimeInstaller(
                context = this,
                runtimeDirectory = runtimeDirectory,
                abi = abi,
                accessCoordinator = accessCoordinator,
            )
        val launcher =
            LocalRuntimeProcessLauncher(
                runtimeDirectory = runtimeDirectory,
                portProbe = LocalRuntimeManager::defaultPortProbe,
                githubToken = { settings.githubToken },
                beforeStart = { installed ->
                    runCatching { providerCredentials.syncToRuntime(installed.rootfs) }
                    runCatching {
                        GitCredentialHelper(installed.rootfs) { settings.githubToken }.let { helper ->
                            if (settings.githubToken.isNullOrBlank()) helper.remove() else helper.install()
                        }
                    }
                },
            )
        commandRunner =
            LocalRuntimeCommandRunner(
                runtimeDirectory = runtimeDirectory,
                installedRuntimeProvider = installer::installedRuntime,
                accessCoordinator = accessCoordinator,
                messages = AndroidLocalRuntimeMessages(this),
            )
        val claudeMessages = AndroidClaudeMessages(this)
        claudeCodeRuntime =
            ClaudeCodeRuntime(
                runtimeDirectory,
                installer::installedRuntime,
                accessCoordinator,
                claudeMessages,
                githubToken = { settings.githubToken },
            )
        // One store for every agent that can carry a preset, so a prompt the user wrote for Claude
        // Code is the same one OpenCode offers. Claude Code passes it per session on the command
        // line; OpenCode reads it from an instructions file, which is kept in step below.
        systemPromptStore = SystemPromptStore(File(runtimeDirectory, "claude-system-prompts.json"))
        claudeCodeTarget = ClaudeCodeTarget(claudeCodeRuntime, claudeMessages, systemPromptStore)
        antigravityRuntime = AntigravityRuntime(runtimeDirectory, installer::installedRuntime, githubToken = { settings.githubToken })
        antigravityTarget = AntigravityTarget(antigravityRuntime)
        antigravityController = AntigravityController(installer, antigravityTarget, runtimeWork, applicationScope)
        runtimeMessages = AndroidLocalRuntimeMessages(this)
        gitCloneRepository =
            GitCloneRepository(
                runtimeDirectory = runtimeDirectory,
                installedRuntimeProvider = installer::installedRuntime,
                accessCoordinator = accessCoordinator,
                githubToken = { settings.githubToken },
            )
        val verifiedDownloader = VerifiedRuntimeDownloader(httpClient)
        val updater =
            LocalRuntimeUpdater(
                runtimeDirectory = runtimeDirectory,
                abi = abi,
                downloadAsset = { asset, destination, progress ->
                    verifiedDownloader.download(
                        url = asset.url,
                        destination = destination,
                        expectedSha256 = asset.sha256,
                        expectedSizeBytes = asset.sizeBytes,
                        onProgress = progress,
                    )
                },
                candidateVersionProvider = { candidate ->
                    val result =
                        commandRunner.runShell(
                            commandText = "/usr/local/bin/${candidate.name} --version",
                            timeoutSeconds = 30L,
                        )
                    require(result.exitCode == 0) {
                        "OpenCode update candidate validation failed: ${result.output}"
                    }
                    result.output.lineSequence().firstOrNull(String::isNotBlank)
                        ?: error("OpenCode update candidate returned no version")
                },
                accessCoordinator = accessCoordinator,
                messages = runtimeMessages,
            )
        val updateEngine =
            DefaultLocalRuntimeUpdateEngine(
                releaseClient = LocalRuntimeReleaseClient(httpClient),
                updater = updater,
            )
        localRuntimeManager =
            LocalRuntimeManager(
                runtimeDirectory = runtimeDirectory,
                abi = abi,
                installer = installer,
                processLauncher = launcher,
                updateEngine = updateEngine,
                messages = runtimeMessages,
            )
        // Keeps OpenCode's instructions file in step with the selected preset: on every switch, and
        // on every runtime start, since a reinstall replaces the guest filesystem the file lives in.
        // Claude Code needs none of this - it takes the prompt on the command line per session.
        applicationScope.launch {
            // Watches the presets as well as the selection: editing the text of the preset already
            // selected leaves selectedId untouched, and OpenCode would have gone on reading the old
            // wording until the next switch or restart. Claude Code needs no equivalent - it reads
            // the prompt out of the store at send time.
            combine(
                localRuntimeManager.state,
                systemPromptStore.selectedId,
                systemPromptStore.presets,
            ) { status, _, _ -> status }
                .collect { status ->
                    if (status !is LocalRuntimeStatus.Ready) return@collect
                    val rootfs = installer.installedRuntime()?.rootfs ?: return@collect
                    applyOpenCodeSystemPrompt(rootfs, systemPromptStore.selectedPrompt())
                }
        }

        localRuntimeDiagnosticsCollector =
            LocalRuntimeDiagnosticsCollector(
                runtimeDirectory = runtimeDirectory,
                abi = abi,
                statusProvider = localRuntimeManager::status,
                processMetricsProvider = launcher::metrics,
                commandExecutor = commandRunner::run,
                messages = runtimeMessages,
            )
        localRuntimeController = LocalRuntimeServiceController(this)
        adbConnectionManager =
            AdbConnectionManager(
                shellRunner = AdbShellRunner { command, timeoutSeconds -> commandRunner.runShell(command, timeoutSeconds) },
                connectionStore = settings,
                nsdManagerProvider = { getSystemService(Context.NSD_SERVICE) as? NsdManager },
                runtimeWork = runtimeWork,
                messages = runtimeMessages,
            )
        // Keep the persisted wireless-debugging link alive for the whole process lifetime. The
        // loop is a cheap no-op until the user has connected once, and it self-heals the link
        // whenever the adb server inside the Linux runtime is restarted. The wake-lock lease for
        // this work is taken inside AdbConnectionManager itself, around each shell invocation - a
        // live `Connected` state is not leased here, since for anyone who has ever paired wireless
        // debugging that state is re-established every 30 seconds and would hold the lock forever;
        // it instead blocks the idle auto-stop directly, in LocalRuntimeService.checkIdleStop.
        adbConnectionManager.startAutoReconnect(applicationScope)
        runtimeRegistry =
            RuntimeRegistry(
                store = settings,
                localTarget = LocalRuntimeTarget(localRuntimeManager, messages = runtimeMessages),
                additionalTargets = listOf(claudeCodeTarget, antigravityTarget),
            )
        // Surface the installed/version state to the workspace picker without waiting for the
        // first chat to touch Antigravity.
        applicationScope.launch { antigravityTarget.connect() }
        claudeCodeController =
            ClaudeCodeController(
                target = claudeCodeTarget,
                runtime = claudeCodeRuntime,
                installer = installer,
                scope = applicationScope,
                runtimeWork = runtimeWork,
                messages = claudeMessages,
            )
        catalogRepository =
            RuntimeCatalogRepository(
                registry = runtimeRegistry,
                scope = applicationScope,
                providerCache =
                    ProviderCatalogCache(
                        directory = File(filesDir, "catalog-cache"),
                        json =
                            Json {
                                ignoreUnknownKeys = true
                                isLenient = true
                                encodeDefaults = true
                            },
                    ),
                messages = AndroidRuntimeCatalogMessages(this),
            )
        activityRepository =
            RuntimeActivityRepository(
                registry = runtimeRegistry,
                scope = applicationScope,
                onPermissionAsked = { request, title, runtimeId ->
                    notifications.notifyPermission(request, title, runtimeId)
                },
                onPermissionResolved = notifications::cancelPermission,
                onSessionIdle = { sessionId, title, runtimeId ->
                    AnalyticsReporter.recordRuntimeSessionCompleted()
                    notifications.notifySessionComplete(sessionId, title, runtimeId)
                    githubStarCoordinator.onSessionCompleted()
                },
                onSessionError = { sessionId, message, runtimeId ->
                    AnalyticsReporter.recordRuntimeSessionError()
                    CrashReporter.recordException(
                        error = IllegalStateException(SecretRedaction.redact(message ?: "Runtime session failed")),
                        message = "Runtime session error",
                        customKeys = mapOf("session_id" to (sessionId ?: "unknown")),
                    )
                    notifications.notifySessionError(sessionId, message, runtimeId)
                },
                onQuestionAsked = { request, title, runtimeId ->
                    notifications.notifyQuestion(request, title, runtimeId)
                },
                // A run that dies in the background used to be silent by design: no event, no
                // notification, and a drawer spinner that never stopped.
                onSessionStalled = { sessionId, title, diagnosis, runtimeId ->
                    AnalyticsReporter.recordRuntimeSessionStalled(diagnosis.reason)
                    notifications.notifySessionStalled(sessionId, title, diagnosis, runtimeId)
                },
                unreadStore = settings,
                messages = AndroidRuntimeActivityMessages(this),
            )
        SessionAutoArchiver(
            registry = runtimeRegistry,
            catalog = catalogRepository,
            activeSessionIds = { activityRepository.state.value.activeSessionIds },
            preferences = preferences.state,
            scope = applicationScope,
        ).start()
        // A chat or agent run in flight is real work happening on the runtime's proot process,
        // so it holds the device awake until the session settles - wired here rather than inside
        // the repository's own constructor, which has no reason to know about wake locks.
        //
        // debounceFalseEdge rides out a momentary SSE/HTTP blip: activeSessionIds is cleared the
        // instant the runtime target leaves Connected/Connecting (see RuntimeActivityRepository),
        // and releasing this lease on that same instant would let the device suspend mid-run and
        // freeze the proot child - precisely the failure the wake-lock rework exists to prevent.
        // Only a drop that holds for the whole grace window releases the lease.
        bridgeLeaseToWork(
            tag = "sessions",
            hasWork =
                activityRepository.state.map { it.activeSessionIds.isNotEmpty() }
                    .distinctUntilChanged()
                    .debounceFalseEdge(SESSIONS_LEASE_GRACE_MILLIS),
            scope = applicationScope,
        )
        githubStarCoordinator.refresh()
        scheduleManager = ScheduleManager(this, scheduleRepository)
        // Re-arm alarms for schedules that were saved in a previous process lifetime.
        scheduleManager.rescheduleAll()
        // Let guest agents read and manage schedules through the and-code-schedule MCP server.
        // The poll loop is a cheap no-op (one directory stat) while no guest has touched the bridge.
        val scheduleBridge = ScheduleBridge(File(runtimeDirectory, "workspace"), AppScheduleStore(scheduleRepository, scheduleManager))
        applicationScope.launch { scheduleBridge.run() }
        observeForegroundForRuntimeRestart()
        scheduleDeferredInitialization()
    }

    /**
     * Restarts the local runtime on every actual entry into the foreground - the app's own cold
     * start included, not just a later return after the 15-minute idle auto-stop shut it down while
     * backgrounded (see [LocalRuntimeService.checkIdleStop]).
     *
     * This is now the *only* place [RuntimeAutoStartTrigger.AppLaunch] fires from.
     * [RuntimeAutoStartInitializer.create] used to restore the runtime itself at cold start, but
     * that runs once per process via [scheduleDeferredInitialization] - including the UI-less
     * process `BOOT_COMPLETED` creates, where it silently undid
     * [RuntimeAutoStartReceiver] refusing that same restore under
     * [RuntimeAutoStartTrigger.BootOrPackageReplaced]. [appForeground] is backed by
     * [ProcessLifecycleOwner][androidx.lifecycle.ProcessLifecycleOwner], which only ever reports
     * foreground for an actual activity on screen, so a broadcast-only process never reaches this
     * collector at all - the runtime now correctly stays down after a reboot when the setting says
     * so.
     *
     * [ForegroundReturnDetector] turns the raw flow into "just entered the foreground" events,
     * firing on the very first one too now that nothing else covers cold start.
     * [shouldRestoreOnForegroundReturn] then decides whether to act: a runtime that is
     * [Stopped][LocalRuntimeStatus.Stopped], or mid-way through the idle auto-stop's own `stop()`
     * call (see [idleStopInProgress]), is restored; a [Ready][LocalRuntimeStatus.Ready] one or one
     * mid-install/mid-update is left alone, since [LocalRuntimeService.ACTION_START] cancels
     * whatever operation is currently in flight and firing it at a healthy runtime would interrupt
     * real work rather than being the no-op it deserves; and a runtime the user deliberately stopped
     * - the runtime notification's Stop action,
     * [com.yugahashimoto.andcode.feature.workspace.WorkspaceViewModel.stopLocalRuntime] - stays down
     * until an explicit start clears that flag.
     *
     * [RuntimeAutoStartInitializer.syncOnboardingCompleted] runs on every entry regardless of that
     * decision - it used to run unconditionally at cold start too, and a reinstalled APK landing on
     * a device with the runtime already set up should not be sent back through onboarding just
     * because [restoreIfConfigured][RuntimeAutoStartInitializer.restoreIfConfigured] itself is
     * skipped this time.
     */
    private fun observeForegroundForRuntimeRestart() {
        val detector = ForegroundReturnDetector()
        applicationScope.launch {
            appForeground.foreground.collect { inForeground ->
                if (!detector.onForegroundChanged(inForeground)) return@collect
                RuntimeAutoStartInitializer.syncOnboardingCompleted(this@AndCodeApplication)
                val shouldRestore =
                    shouldRestoreOnForegroundReturn(
                        status = localRuntimeManager.status(),
                        idleStopInProgress = idleStopInProgress.value,
                        userStoppedRuntime = settings.localRuntimeStoppedByUser,
                    )
                if (shouldRestore) {
                    RuntimeAutoStartInitializer.restoreIfConfigured(this@AndCodeApplication, RuntimeAutoStartTrigger.AppLaunch)
                }
            }
        }
    }

    /**
     * Keeps exactly one [RuntimeWorkTracker] lease under [tag] alive for as long as [hasWork]
     * reports true, releasing it the instant that flips - the lease's own idempotent release means
     * a lease this function no longer references is never touched twice.
     */
    private fun bridgeLeaseToWork(
        tag: String,
        hasWork: Flow<Boolean>,
        scope: CoroutineScope,
    ) {
        scope.launch {
            var lease: RuntimeWorkTracker.Lease? = null
            try {
                hasWork.collect { active ->
                    if (active) {
                        if (lease == null) lease = runtimeWork.acquire(tag)
                    } else {
                        lease?.release()
                        lease = null
                    }
                }
            } finally {
                // A held lease outliving its flow would pin runtimeWork.active to true for the rest
                // of the process - the wake lock held forever, which is the exact symptom this
                // whole rework exists to remove. Both flows passed in today are StateFlows that
                // never end, so this is insurance rather than a live path.
                lease?.release()
            }
        }
    }

    private fun scheduleDeferredInitialization() {
        Handler(Looper.getMainLooper()).post {
            AppInitializer.getInstance(this)
                .initializeComponent(CatalogReconcileInitializer::class.java)
            AppInitializer.getInstance(this)
                .initializeComponent(RuntimeAutoStartInitializer::class.java)
        }
    }

    private companion object {
        /**
         * How long [debounceFalseEdge] rides out a drop to "no active sessions" before releasing
         * the `"sessions"` wake-lock lease. Long enough to survive a transient SSE/HTTP blip during
         * a long agent run, short enough that a genuine end still releases the lock promptly.
         */
        private const val SESSIONS_LEASE_GRACE_MILLIS = 60_000L
    }
}
