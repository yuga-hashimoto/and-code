package com.yugahashimoto.andcode.di

import android.os.Build
import com.yugahashimoto.andcode.core.notification.RuntimeNotificationHelper
import com.yugahashimoto.andcode.data.connection.SecureSettingsRepository
import com.yugahashimoto.andcode.data.repository.RuntimeActivityRepository
import com.yugahashimoto.andcode.data.repository.RuntimeCatalogRepository
import com.yugahashimoto.andcode.data.settings.AppPreferencesRepository
import com.yugahashimoto.andcode.data.settings.DraftRepository
import com.yugahashimoto.andcode.runtime.RuntimeRegistry
import com.yugahashimoto.andcode.runtime.local.AntigravityRuntime
import com.yugahashimoto.andcode.runtime.local.AntigravityTarget
import com.yugahashimoto.andcode.runtime.local.DefaultLocalRuntimeUpdateEngine
import com.yugahashimoto.andcode.runtime.local.GitCredentialHelper
import com.yugahashimoto.andcode.runtime.local.LocalProviderCredentialStore
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeAccessCoordinator
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeCommandRunner
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeInstaller
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeManager
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeProcessLauncher
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeReleaseClient
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeServiceController
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeTarget
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeUpdater
import com.yugahashimoto.andcode.runtime.local.VerifiedRuntimeDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.io.File

val appModule =
    module {

        single<File> { File(androidContext().filesDir, "runtime") }

        single { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

        single { SecureSettingsRepository(androidContext()) }

        single { AppPreferencesRepository(get()) }

        single { DraftRepository(androidContext()) }

        single { RuntimeNotificationHelper(androidContext()) }

        single { LocalProviderCredentialStore(get()) }

        single { OkHttpClient() }

        single { LocalRuntimeAccessCoordinator() }

        single {
            val runtimeDirectory: File = get()
            val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            LocalRuntimeInstaller(
                context = androidContext(),
                runtimeDirectory = runtimeDirectory,
                abi = abi,
                accessCoordinator = get(),
            )
        }

        single {
            val settings: SecureSettingsRepository = get()
            val providerCredentials: LocalProviderCredentialStore = get()
            val runtimeDirectory: File = get()
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
        }

        single {
            val runtimeDirectory: File = get()
            val installer: LocalRuntimeInstaller = get()
            LocalRuntimeCommandRunner(
                runtimeDirectory = runtimeDirectory,
                installedRuntimeProvider = installer::installedRuntime,
                accessCoordinator = get(),
            )
        }

        single {
            val runtimeDirectory: File = get()
            val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            val httpClient: OkHttpClient = get()
            val commandRunner: LocalRuntimeCommandRunner = get()
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
                    accessCoordinator = get(),
                )
            val updateEngine =
                DefaultLocalRuntimeUpdateEngine(
                    releaseClient = LocalRuntimeReleaseClient(httpClient),
                    updater = updater,
                )
            LocalRuntimeManager(
                runtimeDirectory = runtimeDirectory,
                abi = abi,
                installer = get(),
                processLauncher = get(),
                updateEngine = updateEngine,
            )
        }

        single { LocalRuntimeServiceController(androidContext()) }

        single {
            RuntimeRegistry(
                store = get(),
                localTarget = LocalRuntimeTarget(get()),
                additionalTargets =
                    listOf(
                        AntigravityTarget(
                            AntigravityRuntime(get(), (get<LocalRuntimeInstaller>())::installedRuntime),
                        ),
                    ),
            )
        }

        single {
            RuntimeCatalogRepository(get(), get())
        }

        single {
            val notifications: RuntimeNotificationHelper = get()
            RuntimeActivityRepository(
                registry = get(),
                scope = get(),
                onPermissionAsked = { request, title, runtimeId ->
                    notifications.notifyPermission(request, title, runtimeId)
                },
                onSessionIdle = notifications::notifySessionComplete,
                onSessionError = notifications::notifySessionError,
                onQuestionAsked = notifications::notifyQuestion,
            )
        }
    }
