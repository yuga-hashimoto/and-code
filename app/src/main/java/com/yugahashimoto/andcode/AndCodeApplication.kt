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
import com.yugahashimoto.andcode.core.locale.AppLanguage
import com.yugahashimoto.andcode.core.notification.RuntimeNotificationHelper
import com.yugahashimoto.andcode.core.security.SecretRedaction
import com.yugahashimoto.andcode.core.storage.DeviceStorage
import com.yugahashimoto.andcode.core.storage.DeviceStorageAccess
import com.yugahashimoto.andcode.data.connection.SecureSettingsRepository
import com.yugahashimoto.andcode.data.repository.AndroidRuntimeActivityMessages
import com.yugahashimoto.andcode.data.repository.AndroidRuntimeCatalogMessages
import com.yugahashimoto.andcode.data.repository.ProviderCatalogCache
import com.yugahashimoto.andcode.data.repository.PullRequestStatusRepository
import com.yugahashimoto.andcode.data.repository.RuntimeActivityRepository
import com.yugahashimoto.andcode.data.repository.RuntimeCatalogRepository
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
import com.yugahashimoto.andcode.runtime.local.VerifiedRuntimeDownloader
import com.yugahashimoto.andcode.startup.CatalogReconcileInitializer
import com.yugahashimoto.andcode.startup.RuntimeAutoStartInitializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        claudeCodeTarget = ClaudeCodeTarget(claudeCodeRuntime, claudeMessages)
        antigravityRuntime = AntigravityRuntime(runtimeDirectory, installer::installedRuntime, githubToken = { settings.githubToken })
        antigravityTarget = AntigravityTarget(antigravityRuntime)
        antigravityController = AntigravityController(installer, antigravityTarget, applicationScope)
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
                messages = runtimeMessages,
            )
        // Keep the persisted wireless-debugging link alive for the whole process lifetime. The
        // loop is a cheap no-op until the user has connected once, and it self-heals the link
        // whenever the adb server inside the Linux runtime is restarted.
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
        githubStarCoordinator.refresh()
        scheduleManager = ScheduleManager(this, scheduleRepository)
        // Re-arm alarms for schedules that were saved in a previous process lifetime.
        scheduleManager.rescheduleAll()
        // Let guest agents read and manage schedules through the and-code-schedule MCP server.
        // The poll loop is a cheap no-op (one directory stat) while no guest has touched the bridge.
        val scheduleBridge = ScheduleBridge(File(runtimeDirectory, "workspace"), AppScheduleStore(scheduleRepository, scheduleManager))
        applicationScope.launch { scheduleBridge.run() }
        scheduleDeferredInitialization()
    }

    private fun scheduleDeferredInitialization() {
        Handler(Looper.getMainLooper()).post {
            AppInitializer.getInstance(this)
                .initializeComponent(CatalogReconcileInitializer::class.java)
            AppInitializer.getInstance(this)
                .initializeComponent(RuntimeAutoStartInitializer::class.java)
        }
    }
}
