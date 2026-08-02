package com.yugahashimoto.andcode.runtime.local

import android.content.Context
import android.system.Os
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.LocalAgent
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
        /** Null when the sandbox was provisioned without the OpenCode agent. */
        val openCode: File?,
        /** Debian Bookworm rootfs for the glibc-linked official Antigravity binary. */
        val antigravityRootfs: File? = null,
    )

    /**
     * Provisions the shared Alpine sandbox and the requested [agents].
     *
     * Agents already recorded in the current install are carried over, so adding one never silently
     * removes another. The OpenCode binary — by far the largest download — is fetched only when
     * OpenCode is actually among the resulting agents.
     */
    suspend fun install(
        agents: Set<LocalAgent> = setOf(LocalAgent.OPEN_CODE),
        /**
         * Progress, the step to show, and which agent that step belongs to - null for the shared
         * Alpine environment every agent runs in. One install provisions the whole selection, so
         * without the third argument the setup guide attributed every step to OpenCode and showed
         * "Installing Claude Code" underneath the OpenCode heading.
         */
        onProgress: (Float?, String, LocalAgent?) -> Unit = { _, _, _ -> },
    ): InstalledRuntime =
        withContext(Dispatchers.IO) {
            // The shared environment's own steps, which belong to no single agent.
            val onShared: (Float?, String) -> Unit = { progress, step -> onProgress(progress, step, null) }
            val onClaude: (Float?, String) -> Unit = { progress, step -> onProgress(progress, step, LocalAgent.CLAUDE_CODE) }
            val onAntigravity: (Float?, String) -> Unit = { progress, step -> onProgress(progress, step, LocalAgent.ANTIGRAVITY) }
            runtimeDirectory.mkdirs()
            onShared(0.02f, context.getString(R.string.install_step_preparing_command_env))
            val requestedAgents =
                agents + (
                    installedMetadata()?.let {
                            existing ->
                        LocalAgent.entries.filter(existing::has)
                    } ?: emptyList()
                )
            require(requestedAgents.isNotEmpty()) { "At least one agent must be selected" }
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
                    onShared,
                )
                val withOpenCode = LocalAgent.OPEN_CODE in requestedAgents
                val openCodeArchive =
                    File(cache, "opencode-${manifest.openCodeVersion}-$abi.tar.gz").takeIf { withOpenCode }?.also { archive ->
                        download(
                            architecture.openCodeUrl,
                            archive,
                            architecture.openCodeSha256,
                            0.24f,
                            0.72f,
                            context.getString(R.string.install_step_downloading_opencode),
                            onShared,
                        )
                    }

                val rootfs = File(staging, "rootfs").apply { mkdirs() }
                onShared(0.75f, context.getString(R.string.install_step_extracting_linux_env))
                alpineArchive.inputStream().use { RuntimeArchive.extractTarGz(it, rootfs) }

                val openCodeBinary =
                    openCodeArchive?.let { archive ->
                        onShared(0.85f, context.getString(R.string.install_step_extracting_opencode))
                        extractOpenCode(archive, staging, rootfs)
                    }
                configureRootfs(rootfs, commandSuite)
                val antigravityRootfs =
                    if (LocalAgent.ANTIGRAVITY in requestedAgents) {
                        onAntigravity(0.90f, context.getString(R.string.install_step_preparing_antigravity_rootfs))
                        DebianRootfsInstaller(runtimeDirectory, abi, downloader, httpClient, commandSuite).installInto(
                            File(staging, "antigravity-rootfs"),
                        ) { progress ->
                            onAntigravity(0.90f + progress * 0.03f, context.getString(R.string.install_step_preparing_antigravity_rootfs))
                        }
                    } else {
                        null
                    }
                if (antigravityRootfs != null) {
                    ensureAndCodeAgentContext(antigravityRootfs, context)
                    copyCaCertificates(rootfs, antigravityRootfs)
                    // Keep the Android-vision tool surface added for Claude/OpenCode available
                    // to agy's Debian tool runner as well. The scripts still fail closed when adb
                    // or Pillow is not installed; they are never silently replaced by a fork.
                    installAndroidHelperScripts(antigravityRootfs)
                }
                // Credentials and agent config live under /root inside the rootfs. Activation swaps
                // the whole environment directory, so without this the user is signed out of every
                // agent whenever another one is added or the runtime is reinstalled.
                carryOverHomeDirectory(File(active, "rootfs"), rootfs)
                ensureAndCodeAgentContext(rootfs, context)
                onShared(0.91f, context.getString(R.string.install_step_installing_dev_tools))
                installDevelopmentTools(rootfs, commandSuite)
                if (LocalAgent.CLAUDE_CODE in requestedAgents) {
                    onClaude(0.93f, context.getString(R.string.install_step_installing_claude_code))
                    ClaudeCodeInstaller.installInto(rootfs, commandSuite, runtimeDirectory)
                }
                if (LocalAgent.ANTIGRAVITY in requestedAgents) {
                    onAntigravity(0.94f, context.getString(R.string.install_step_downloading_antigravity))
                    AntigravityInstaller(runtimeDirectory, abi, downloader).installInto(antigravityRootfs ?: rootfs) { progress ->
                        onAntigravity(0.94f + progress * 0.04f, context.getString(R.string.install_step_installing_antigravity))
                    }
                }

                val metadata =
                    LocalRuntimeMetadata(
                        version = if (withOpenCode) manifest.openCodeVersion else "",
                        port = manifest.port,
                        installedAt = System.currentTimeMillis(),
                        runtimeVersion = manifest.runtimeVersion,
                        abi = abi,
                        components = requestedAgents.map(LocalAgent::id).toSet(),
                    )
                File(staging, METADATA_FILE).writeText(json.encodeToString(metadata))
                onShared(0.96f, context.getString(R.string.install_step_activating_runtime))
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
                onShared(1f, context.getString(R.string.install_step_done))
                InstalledRuntime(
                    metadata,
                    commandSuite,
                    File(active, "rootfs"),
                    openCodeBinary?.let { File(active, "rootfs/usr/local/bin/opencode") },
                    File(active, "antigravity-rootfs").takeIf { LocalAgent.ANTIGRAVITY in requestedAgents && it.isDirectory },
                )
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

    /**
     * The provisioned sandbox, or null when no usable Linux environment exists yet.
     *
     * The OpenCode binary is no longer part of the liveness check: a Claude Code-only sandbox is a
     * complete, usable runtime and callers that need OpenCode check [InstalledRuntime.openCode].
     */
    fun installedRuntime(): InstalledRuntime? =
        accessCoordinator.write {
            val active = File(runtimeDirectory, "environment")
            normalizeRuntimeSymlinks(active)
            val metadataFile = File(runtimeDirectory, METADATA_FILE)
            val rootfs = File(active, "rootfs")
            if (!metadataFile.isFile || !rootfs.isDirectory) return@write null
            val metadata =
                runCatching {
                    json.decodeFromString<LocalRuntimeMetadata>(metadataFile.readText())
                }.getOrNull() ?: return@write null
            val commandSuite =
                runCatching {
                    EmbeddedCommandSuite(context, runtimeDirectory, abi).ensureInstalled()
                }.getOrNull() ?: return@write null
            InstalledRuntime(
                metadata,
                commandSuite,
                rootfs,
                File(rootfs, "usr/local/bin/opencode").takeIf { it.isFile },
                File(active, "antigravity-rootfs").takeIf { metadata.has(LocalAgent.ANTIGRAVITY) && it.isDirectory },
            )
        }?.also { installed ->
            ensureAndCodeAgentContext(installed.rootfs, context)
            installed.antigravityRootfs?.let { ensureAndCodeAgentContext(it, context) }
        }

    /** Metadata of the active install, without requiring the command suite to be extractable. */
    fun installedMetadata(): LocalRuntimeMetadata? =
        accessCoordinator.read {
            val metadataFile = File(runtimeDirectory, METADATA_FILE)
            if (!metadataFile.isFile) return@read null
            runCatching { json.decodeFromString<LocalRuntimeMetadata>(metadataFile.readText()) }.getOrNull()
        }

    /** Records [agent] as provisioned, so a later reinstall keeps it. */
    fun recordAgent(agent: LocalAgent) {
        accessCoordinator.write {
            val metadataFile = File(runtimeDirectory, METADATA_FILE)
            val metadata =
                runCatching { json.decodeFromString<LocalRuntimeMetadata>(metadataFile.readText()) }.getOrNull()
                    ?: return@write
            metadataFile.writeText(json.encodeToString(metadata.with(agent)))
        }
    }

    fun bundledOpenCodeVersion(): String = manifestReader.read().openCodeVersion

    /**
     * Reinstalls the pinned Antigravity release into the sandbox that is already active.
     *
     * Antigravity ships as a binary this app pins, so an update means "install what this build
     * pins" — and going through [install] for that would provision a whole new environment
     * directory to replace one file. [AntigravityInstaller] verifies the release's SHA-256 and swaps
     * `agy` in place instead, leaving the Debian rootfs and every agent's credentials untouched.
     *
     * Not serialised against [install]: that one builds a staging directory and swaps it in, so the
     * worst a concurrent run can do is discard this binary, which the next update reinstates. The
     * controller that owns both actions runs one at a time.
     */
    suspend fun updateAntigravity(onProgress: (Float) -> Unit = {}): String {
        val runtime = installedRuntime() ?: error("The Linux environment is not installed")
        AntigravityInstaller(runtimeDirectory, abi, downloader)
            .installInto(runtime.antigravityRootfs ?: runtime.rootfs, onProgress)
        return AntigravityManifest.VERSION
    }

    private fun extractOpenCode(
        archive: File,
        staging: File,
        rootfs: File,
    ): File {
        val extractDir = File(staging, "opencode-extract").apply { mkdirs() }
        try {
            archive.inputStream().use { RuntimeArchive.extractTarGz(it, extractDir) }
            val sourceBinary =
                extractDir.walkTopDown()
                    .firstOrNull { it.isFile && it.name == "opencode" }
                    ?: error("OpenCode archive did not contain the opencode binary")
            val binary = File(rootfs, "usr/local/bin/opencode")
            binary.parentFile?.mkdirs()
            sourceBinary.copyTo(binary, overwrite = true)
            require(binary.setExecutable(true, false) || binary.canExecute()) {
                "Unable to mark OpenCode executable"
            }
            return binary
        } finally {
            extractDir.deleteRecursively()
        }
    }

    private fun carryOverHomeDirectory(
        currentRootfs: File,
        stagingRootfs: File,
    ) {
        val currentHome = File(currentRootfs, "root")
        if (!currentHome.isDirectory) return
        runCatching { currentHome.copyRecursively(File(stagingRootfs, "root"), overwrite = true) }
    }

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
                // `gcompat` and `util-linux` are this branch's additions and must survive main's
                // wider toolchain list: the official agy binary is glibc-linked, and its sign-in TUI
                // needs util-linux's `script` to be handed a real PTY.
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /sbin/apk add --no-cache bash git curl wget jq tree file less nano vim openssh-client ripgrep ca-certificates libstdc++ github-cli android-tools openjdk17 gradle python3 py3-pillow py3-pip nodejs npm make cmake gcc g++ musl-dev pkgconf patch zip unzip sqlite go gcompat util-linux && /usr/sbin/update-ca-certificates",
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
        val completed = process.waitFor(15, java.util.concurrent.TimeUnit.MINUTES)
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
        File(rootfs, "etc/profile.d/android-code.sh").apply {
            parentFile?.mkdirs()
            writeText(
                "export HOME=/root\n" +
                    "export TMPDIR=/tmp\n" +
                    "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin\n" +
                    "export JAVA_HOME=/usr/lib/jvm/java-17-openjdk\n" +
                    "export OPENCODE_CONFIG_DIR=/root/.config/opencode\n",
            )
        }
        File(rootfs, "root/.config/opencode").mkdirs()
        File(rootfs, "root/.local/share/opencode").mkdirs()
        // The compact Alpine archive intentionally omits a few SONAME symlinks. apk loads
        // libapk.so.3 by SONAME, so restore the link before installing agent dependencies.
        val libApk = File(rootfs, "usr/lib/libapk.so.3")
        if (!libApk.exists() && File(rootfs, "usr/lib/libapk.so.3.0.0").isFile) {
            Os.symlink("libapk.so.3.0.0", libApk.absolutePath)
        }
        installAndroidHelperScripts(rootfs)
        require(suite.proot.isFile) { "PRoot launcher is unavailable" }
    }

    private fun installAndroidHelperScripts(rootfs: File) {
        val binDir = File(rootfs, "usr/local/bin").apply { mkdirs() }
        File(binDir, "android-ui").delete()
        listOf(
            "android-vision.sh" to "android-vision",
            "android-screenshot.sh" to "android-screenshot",
            "android-instrument.sh" to "android-instrument",
            "android-app.sh" to "android-app",
        ).forEach {
                (assetName, scriptName) ->
            val scriptFile = File(binDir, scriptName)
            context.assets.open("scripts/$assetName").use { input ->
                scriptFile.outputStream().use { output -> input.copyTo(output) }
            }
            scriptFile.setExecutable(true, false)
        }
    }

    private fun copyCaCertificates(
        alpineRootfs: File,
        debianRootfs: File,
    ) {
        val source = File(alpineRootfs, "etc/ssl/certs/ca-certificates.crt")
        if (!source.isFile) return
        val destination = File(debianRootfs, "etc/ssl/certs/ca-certificates.crt")
        destination.parentFile?.mkdirs()
        source.copyTo(destination, overwrite = true)
    }

    companion object {
        private const val METADATA_FILE = "metadata.json"
    }
}
