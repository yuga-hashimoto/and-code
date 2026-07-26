package com.opencode.android.runtime.local

import android.content.Context
import com.opencode.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File

class LocalRuntimeInstaller(
    private val context: Context,
    private val runtimeDirectory: File,
    private val abi: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val manifestReader: LocalRuntimeManifestReader = LocalRuntimeManifestReader(context),
    private val downloader: VerifiedRuntimeDownloader = VerifiedRuntimeDownloader(httpClient),
    private val accessCoordinator: LocalRuntimeAccessCoordinator = LocalRuntimeAccessCoordinator(),
) {
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    data class InstalledRuntime(
        val metadata: LocalRuntimeMetadata,
        val commandSuite: EmbeddedCommandSuite.Paths,
        val rootfs: File,
        val openCode: File,
    )

    suspend fun install(onProgress: (Float?, String) -> Unit = { _, _ -> }): InstalledRuntime =
        withContext(Dispatchers.IO) {
            runtimeDirectory.mkdirs()
            onProgress(0.02f, context.getString(R.string.install_step_preparing_command_env))
            val commandSuite = EmbeddedCommandSuite(context, runtimeDirectory, abi).ensureInstalled()
            val manifest = manifestReader.read()
            val architecture = manifest.architecture(abi)
            val cache = File(runtimeDirectory, "cache").apply { mkdirs() }
            val staging = File(runtimeDirectory, "environment.staging")
            val active = File(runtimeDirectory, "environment")
            val rollback = File(runtimeDirectory, "environment.rollback")
            accessCoordinator.write {
                recoverInterruptedRuntimeEnvironment(
                    active = active,
                    rollback = rollback,
                    topLevelMetadata = File(runtimeDirectory, METADATA_FILE),
                )
            }
            staging.deleteRecursively()
            staging.mkdirs()

            try {
                val alpineArchive = File(cache, "alpine-${manifest.alpineVersion}-$abi.tar.gz")
                download(
                    architecture.alpineUrl,
                    alpineArchive,
                    architecture.alpineSha256,
                    0.05f,
                    0.22f,
                    context.getString(R.string.install_step_downloading_alpine),
                    onProgress,
                )
                val openCodeArchive = File(cache, "opencode-${manifest.openCodeVersion}-$abi.tar.gz")
                download(
                    architecture.openCodeUrl,
                    openCodeArchive,
                    architecture.openCodeSha256,
                    0.24f,
                    0.72f,
                    context.getString(R.string.install_step_downloading_opencode),
                    onProgress,
                )

                val rootfs = File(staging, "rootfs").apply { mkdirs() }
                onProgress(0.75f, context.getString(R.string.install_step_extracting_linux_env))
                alpineArchive.inputStream().use { RuntimeArchive.extractTarGz(it, rootfs) }

                val openCodeExtract = File(staging, "opencode-extract").apply { mkdirs() }
                onProgress(0.85f, context.getString(R.string.install_step_extracting_opencode))
                openCodeArchive.inputStream().use { RuntimeArchive.extractTarGz(it, openCodeExtract) }
                val sourceBinary =
                    openCodeExtract.walkTopDown()
                        .firstOrNull { it.isFile && it.name == "opencode" }
                        ?: error("OpenCode archive did not contain the opencode binary")
                val openCodeBinary = File(rootfs, "usr/local/bin/opencode")
                openCodeBinary.parentFile?.mkdirs()
                sourceBinary.copyTo(openCodeBinary, overwrite = true)
                require(openCodeBinary.setExecutable(true, false) || openCodeBinary.canExecute()) {
                    "Unable to mark OpenCode executable"
                }
                openCodeExtract.deleteRecursively()
                configureRootfs(rootfs, commandSuite)
                onProgress(0.91f, context.getString(R.string.install_step_installing_dev_tools))
                installDevelopmentTools(rootfs, commandSuite)

                val metadata =
                    LocalRuntimeMetadata(
                        version = manifest.openCodeVersion,
                        port = manifest.port,
                        installedAt = System.currentTimeMillis(),
                        runtimeVersion = manifest.runtimeVersion,
                        abi = abi,
                    )
                File(staging, METADATA_FILE).writeText(json.encodeToString(metadata))
                onProgress(0.96f, context.getString(R.string.install_step_activating_runtime))
                accessCoordinator.write {
                    activateRuntimeEnvironment(
                        active = active,
                        staging = staging,
                        rollback = rollback,
                        finalizeActivation = { activated ->
                            replaceFileAtomically(
                                source = File(activated, METADATA_FILE),
                                destination = File(runtimeDirectory, METADATA_FILE),
                            )
                        },
                    )
                }
                onProgress(1f, context.getString(R.string.install_step_done))
                InstalledRuntime(metadata, commandSuite, File(active, "rootfs"), File(active, "rootfs/usr/local/bin/opencode"))
            } catch (error: Throwable) {
                staging.deleteRecursively()
                throw error
            }
        }

    fun recoverInterruptedActivation(): Boolean =
        accessCoordinator.write {
            recoverInterruptedRuntimeEnvironment(
                active = File(runtimeDirectory, "environment"),
                rollback = File(runtimeDirectory, "environment.rollback"),
                topLevelMetadata = File(runtimeDirectory, METADATA_FILE),
            )
        }

    fun installedRuntime(): InstalledRuntime? =
        accessCoordinator.read {
            val active = File(runtimeDirectory, "environment")
            val metadataFile = File(runtimeDirectory, METADATA_FILE)
            val rootfs = File(active, "rootfs")
            val openCode = File(rootfs, "usr/local/bin/opencode")
            if (!metadataFile.isFile || !openCode.isFile) return@read null
            val metadata =
                runCatching {
                    json.decodeFromString<LocalRuntimeMetadata>(metadataFile.readText())
                }.getOrNull() ?: return@read null
            val commandSuite =
                runCatching {
                    EmbeddedCommandSuite(context, runtimeDirectory, abi).ensureInstalled()
                }.getOrNull() ?: return@read null
            InstalledRuntime(metadata, commandSuite, rootfs, openCode)
        }

    fun bundledOpenCodeVersion(): String = manifestReader.read().openCodeVersion

    private suspend fun download(
        url: String,
        destination: File,
        expectedSha256: String,
        startProgress: Float,
        endProgress: Float,
        label: String,
        onProgress: (Float?, String) -> Unit,
    ) {
        if (destination.isFile) {
            runCatching { RuntimeArchive.verifySha256(destination, expectedSha256) }
                .onSuccess {
                    onProgress(endProgress, label)
                    return
                }
            destination.delete()
        }
        downloader.download(
            url = url,
            destination = destination,
            expectedSha256 = expectedSha256,
            onProgress = { fraction ->
                onProgress(
                    fraction?.let {
                        startProgress + (endProgress - startProgress) * it.coerceIn(0f, 1f)
                    },
                    label,
                )
            },
        )
        onProgress(endProgress, label)
    }

    private fun installDevelopmentTools(
        rootfs: File,
        suite: EmbeddedCommandSuite.Paths,
    ) {
        val prootTmp = File(runtimeDirectory, "proot-tmp").apply { mkdirs() }
        val command =
            listOf(
                suite.proot.absolutePath,
                "--kill-on-exit",
                "--link2symlink",
                "-0",
                "-r",
                rootfs.absolutePath,
                "-b",
                "/dev",
                "-b",
                "/proc",
                "-b",
                "/sys",
                "-b",
                "/system",
                "-w",
                "/root",
                "/bin/sh",
                "-lc",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /sbin/apk add --no-cache bash git curl openssh-client ripgrep ca-certificates libstdc++ github-cli && /usr/sbin/update-ca-certificates",
            )
        val installLog =
            File(runtimeDirectory, "logs/tool-install.log").apply {
                parentFile?.mkdirs()
                delete()
            }
        val process =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(installLog))
                .apply {
                    environment().putAll(suite.environment())
                    environment()["PROOT_TMP_DIR"] = prootTmp.absolutePath
                }
                .start()
        val completed = process.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)
        if (!completed) {
            process.destroyForcibly()
            error("Development tool installation timed out")
        }
        require(process.exitValue() == 0) {
            "Unable to install Git and development tools: ${installLog.readText().takeLast(4000)}"
        }
    }

    private fun configureRootfs(
        rootfs: File,
        suite: EmbeddedCommandSuite.Paths,
    ) {
        File(rootfs, "root").mkdirs()
        File(rootfs, "tmp").apply {
            mkdirs()
            setWritable(true, false)
            setExecutable(true, false)
        }
        File(rootfs, "workspace").mkdirs()
        File(rootfs, "etc/resolv.conf").apply {
            parentFile?.mkdirs()
            writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }
        File(rootfs, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(rootfs, "etc/profile.d/opencode-android.sh").apply {
            parentFile?.mkdirs()
            writeText(
                "export HOME=/root\n" +
                    "export TMPDIR=/tmp\n" +
                    "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin\n" +
                    "export OPENCODE_CONFIG_DIR=/root/.config/opencode\n",
            )
        }
        File(rootfs, "root/.config/opencode").mkdirs()
        File(rootfs, "root/.local/share/opencode").mkdirs()
        require(suite.proot.isFile) { "PRoot launcher is unavailable" }
    }

    companion object {
        private const val METADATA_FILE = "metadata.json"
    }
}
