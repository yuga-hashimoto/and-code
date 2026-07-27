package com.opencode.android

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.startup.AppInitializer
import com.opencode.android.accessibility.AccessibilityHttpServer
import com.opencode.android.core.notification.RuntimeNotificationHelper
import com.opencode.android.data.connection.SecureSettingsRepository
import com.opencode.android.data.repository.ProviderCatalogCache
import com.opencode.android.data.repository.RuntimeActivityRepository
import com.opencode.android.data.repository.RuntimeCatalogRepository
import com.opencode.android.data.settings.AppPreferencesRepository
import com.opencode.android.di.appModule
import com.opencode.android.di.viewModelModule
import com.opencode.android.feature.support.GitHubStarCoordinator
import com.opencode.android.feature.support.GitHubStarService
import com.opencode.android.runtime.RuntimeRegistry
import com.opencode.android.runtime.local.AdbConnectionManager
import com.opencode.android.runtime.local.AndroidClaudeMessages
import com.opencode.android.runtime.local.AndroidLocalRuntimeMessages
import com.opencode.android.runtime.local.ClaudeCodeController
import com.opencode.android.runtime.local.ClaudeCodeRuntime
import com.opencode.android.runtime.local.ClaudeCodeTarget
import com.opencode.android.runtime.local.DefaultLocalRuntimeUpdateEngine
import com.opencode.android.runtime.local.GitCloneRepository
import com.opencode.android.runtime.local.GitCredentialHelper
import com.opencode.android.runtime.local.LocalProviderCredentialStore
import com.opencode.android.runtime.local.LocalRuntimeAccessCoordinator
import com.opencode.android.runtime.local.LocalRuntimeCommandRunner
import com.opencode.android.runtime.local.LocalRuntimeDiagnosticsCollector
import com.opencode.android.runtime.local.LocalRuntimeInstaller
import com.opencode.android.runtime.local.LocalRuntimeManager
import com.opencode.android.runtime.local.LocalRuntimeMessages
import com.opencode.android.runtime.local.LocalRuntimeProcessLauncher
import com.opencode.android.runtime.local.LocalRuntimeReleaseClient
import com.opencode.android.runtime.local.LocalRuntimeServiceController
import com.opencode.android.runtime.local.LocalRuntimeTarget
import com.opencode.android.runtime.local.LocalRuntimeUpdater
import com.opencode.android.runtime.local.VerifiedRuntimeDownloader
import com.opencode.android.startup.CatalogReconcileInitializer
import com.opencode.android.startup.RuntimeAutoStartInitializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import java.io.File

class OpenCodeApplication : Application() {
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

    lateinit var runtimeMessages: LocalRuntimeMessages
        private set

    val accessibilityHttpServer = AccessibilityHttpServer()

    lateinit var githubStarCoordinator: GitHubStarCoordinator
        private set

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@OpenCodeApplication)
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
        adbConnectionManager = AdbConnectionManager(this, commandRunner)
        accessibilityHttpServer.start()
        runtimeRegistry =
            RuntimeRegistry(
                store = settings,
                localTarget = LocalRuntimeTarget(localRuntimeManager, messages = runtimeMessages),
                additionalTargets = listOf(claudeCodeTarget),
            )
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
                unreadStore = settings,
            )
        githubStarCoordinator.refresh()
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
