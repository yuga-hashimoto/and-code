package com.yugahashimoto.andcode

import android.app.Application
import android.content.Context
import android.net.nsd.NsdManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.startup.AppInitializer
import com.yugahashimoto.andcode.core.notification.RuntimeNotificationHelper
import com.yugahashimoto.andcode.data.connection.SecureSettingsRepository
import com.yugahashimoto.andcode.data.repository.ProviderCatalogCache
import com.yugahashimoto.andcode.data.repository.RuntimeActivityRepository
import com.yugahashimoto.andcode.data.repository.RuntimeCatalogRepository
import com.yugahashimoto.andcode.data.schedule.ScheduleRepository
import com.yugahashimoto.andcode.data.settings.AppPreferencesRepository
import com.yugahashimoto.andcode.di.appModule
import com.yugahashimoto.andcode.di.viewModelModule
import com.yugahashimoto.andcode.feature.schedule.ScheduleManager
import com.yugahashimoto.andcode.feature.support.GitHubStarCoordinator
import com.yugahashimoto.andcode.feature.support.GitHubStarService
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

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@AndCodeApplication)
            modules(appModule, viewModelModule)
        }
        settings = SecureSettingsRepository(this)
        preferences = AppPreferencesRepository(settings)
        notifications = RuntimeNotificationHelper(this)
        providerCredentials = LocalProviderCredentialStore(settings)
        val httpClient = OkHttpClient()
        githubStarCoordinator =
            GitHubStarCoordinator(
                settings = settings,
                service = GitHubStarService(client = httpClient, tokenProvider = { settings.githubToken }),
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
            )
        localRuntimeController = LocalRuntimeServiceController(this)
        adbConnectionManager =
            AdbConnectionManager(
                shellRunner = AdbShellRunner { command, timeoutSeconds -> commandRunner.runShell(command, timeoutSeconds) },
                connectionStore = settings,
                nsdManagerProvider = { getSystemService(Context.NSD_SERVICE) as? NsdManager },
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
            )
        activityRepository =
            RuntimeActivityRepository(
                registry = runtimeRegistry,
                scope = applicationScope,
                onPermissionAsked = { request, title, runtimeId ->
                    notifications.notifyPermission(request, title, runtimeId)
                },
                onPermissionResolved = notifications::cancelPermission,
                onSessionIdle = { sessionId, title ->
                    notifications.notifySessionComplete(sessionId, title)
                    githubStarCoordinator.onSessionCompleted()
                },
                onSessionError = notifications::notifySessionError,
                onQuestionAsked = notifications::notifyQuestion,
                unreadStore = settings,
            )
        githubStarCoordinator.refresh()
        scheduleRepository = ScheduleRepository(this)
        scheduleManager = ScheduleManager(this, scheduleRepository)
        // Re-arm alarms for schedules that were saved in a previous process lifetime.
        scheduleManager.rescheduleAll()
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
