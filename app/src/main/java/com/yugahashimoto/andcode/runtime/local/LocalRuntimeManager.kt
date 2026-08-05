package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

@Serializable
data class LocalRuntimeMetadata(
    @SerialName("version") val version: String,
    @SerialName("port") val port: Int,
    @SerialName("installedAt") val installedAt: Long,
    @SerialName("runtimeVersion") val runtimeVersion: String = "legacy",
    @SerialName("abi") val abi: String = "unknown",
    /**
     * Agents provisioned into the sandbox, by [LocalAgent.id].
     *
     * Defaulted to OpenCode so runtimes installed before agents became selectable keep reporting
     * the agent they actually contain.
     */
    @SerialName("components") val components: Set<String> = setOf(LocalAgent.OPEN_CODE.id),
) {
    fun has(agent: LocalAgent): Boolean = agent.id in components

    fun with(agent: LocalAgent): LocalRuntimeMetadata = copy(components = components + agent.id)

    fun without(agent: LocalAgent): LocalRuntimeMetadata = copy(components = components - agent.id)
}

class LocalRuntimeManager(
    private val runtimeDirectory: File,
    private val abi: String,
    private val portProbe: (Int) -> Boolean = ::defaultPortProbe,
    private val installer: LocalRuntimeInstaller? = null,
    private val processLauncher: LocalRuntimeProcessLauncher? = null,
    /** Whether the server process we started is still alive, independent of whether it answers. */
    private val processAlive: () -> Boolean = { processLauncher?.isRunning() == true },
    private val updateEngine: LocalRuntimeUpdateEngine? = null,
    private val runtimeOperations: LocalRuntimeOperations? = null,
    private val messages: LocalRuntimeMessages = LocalRuntimeMessages,
) {
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow(computeStatus())
    val state: StateFlow<LocalRuntimeStatus> = mutableState.asStateFlow()

    private val mutableLastOperation = MutableStateFlow<LocalRuntimeOperationResult?>(null)
    val lastOperation: StateFlow<LocalRuntimeOperationResult?> = mutableLastOperation.asStateFlow()

    fun status(): LocalRuntimeStatus {
        val operation = mutableState.value
        if (
            operation is LocalRuntimeStatus.Installing ||
            operation is LocalRuntimeStatus.Starting ||
            operation is LocalRuntimeStatus.Updating
        ) {
            return operation
        }
        return computeStatus().also { mutableState.value = it }
    }

    fun installedPort(): Int? = readMetadata()?.port

    fun isHealthy(): Boolean = installedPort()?.let(portProbe) == true

    fun setOnExit(callback: ((Int?, Long?, Long) -> Unit)?) {
        processLauncher?.setOnExit(callback)
    }

    fun lastExitCode(): Int? = processLauncher?.exitRecord()?.first

    fun lastExitAtMillis(): Long? = processLauncher?.exitRecord()?.second

    fun restartCount(): Int = processLauncher?.restartCount() ?: 0

    suspend fun installAndStart(agents: Set<LocalAgent> = setOf(LocalAgent.OPEN_CODE)): Result<LocalRuntimeStatus.Ready> =
        operationMutex.withLock {
            val configuredInstaller =
                installer
                    ?: return@withLock Result.failure(IllegalStateException("Local runtime installer is not configured"))
            runCatching {
                val installed =
                    configuredInstaller.install(agents) { progress, step, agent ->
                        mutableState.value = LocalRuntimeStatus.Installing(progress, step, agent)
                    }
                mutableState.value = LocalRuntimeStatus.Stopped(installed.metadata.version, installed.metadata.port)
                startInstalled(installed)
            }.onFailure { error ->
                mutableState.value =
                    LocalRuntimeStatus.Broken(
                        error.message ?: messages.installFailed,
                    )
            }
        }

    suspend fun start(): Result<LocalRuntimeStatus.Ready> =
        operationMutex.withLock {
            startLocked()
        }

    suspend fun ensureRunning(): Result<LocalRuntimeStatus.Ready> =
        operationMutex.withLock {
            updateEngine?.recover()
            val metadata =
                readMetadata()
                    ?: return@withLock Result.failure(IllegalStateException("Local runtime is not installed"))
            val bundledVersion = installer?.bundledOpenCodeVersion()
            if (updateEngine != null &&
                bundledVersion != null &&
                compareOpenCodeVersions(metadata.version, bundledVersion) < 0
            ) {
                return@withLock updateToLatestLocked()
            }
            // Same reasoning as computeStatus(): only restart a server that is genuinely gone. A
            // live process that missed a probe is busy, and stopping it here would end whatever it
            // was busy with.
            if (portProbe(metadata.port) || processAlive()) {
                return@withLock Result.success(
                    LocalRuntimeStatus.Ready(metadata.version, metadata.port).also { mutableState.value = it },
                )
            }
            withContext(Dispatchers.IO) { processLauncher?.stop() }
            startLocked()
        }

    suspend fun stop(): Result<LocalRuntimeStatus.Stopped> =
        operationMutex.withLock {
            runCatching {
                withContext(Dispatchers.IO) { processLauncher?.stop() }
                val metadata = readMetadata() ?: error("Local runtime metadata is missing")
                LocalRuntimeStatus.Stopped(metadata.version, metadata.port).also { mutableState.value = it }
            }.onFailure { error ->
                mutableState.value =
                    LocalRuntimeStatus.Broken(
                        error.message ?: messages.stopFailed,
                    )
            }
        }

    suspend fun deleteRuntime(): Result<LocalRuntimeStatus.NotInstalled> =
        operationMutex.withLock {
            runCatching {
                withContext(Dispatchers.IO) {
                    processLauncher?.stop()
                    if (runtimeDirectory.exists()) {
                        require(runtimeDirectory.deleteRecursively()) {
                            messages.deleteIncomplete
                        }
                    }
                }
                mutableLastOperation.value = null
                LocalRuntimeStatus.NotInstalled.also { mutableState.value = it }
            }.onFailure { error ->
                mutableState.value =
                    LocalRuntimeStatus.Broken(
                        error.message ?: messages.deleteFailed,
                    )
            }
        }

    suspend fun reinstall(): Result<LocalRuntimeStatus.Ready> =
        operationMutex.withLock {
            withContext(Dispatchers.IO) { processLauncher?.stop() }
            File(runtimeDirectory, METADATA_FILE).delete()
            val configuredInstaller =
                installer
                    ?: return@withLock Result.failure(IllegalStateException("Local runtime installer is not configured"))
            runCatching {
                val installed =
                    configuredInstaller.install { progress, step, agent ->
                        mutableState.value = LocalRuntimeStatus.Installing(progress, step, agent)
                    }
                startInstalled(installed)
            }.onFailure { error ->
                mutableState.value =
                    LocalRuntimeStatus.Broken(
                        error.message ?: messages.reinstallFailed,
                    )
            }
        }

    suspend fun checkForUpdate(): Result<LocalRuntimeUpdateCheck> =
        operationMutex.withLock {
            val engine =
                updateEngine
                    ?: return@withLock Result.failure(IllegalStateException("Local runtime updater is not configured"))
            val metadata =
                currentMetadataForOperation()
                    ?: return@withLock Result.failure(IllegalStateException("Local runtime is not installed"))
            runCatching { engine.check(metadata.version, abi) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    mutableLastOperation.value =
                        LocalRuntimeOperationResult.Failed(
                            operation = "update-check",
                            message = error.message ?: messages.updateCheckFailed,
                        )
                }
        }

    suspend fun rollbackVersion(): String? =
        operationMutex.withLock {
            updateEngine?.rollbackVersion()
        }

    suspend fun updateToLatest(): Result<LocalRuntimeStatus.Ready> =
        operationMutex.withLock {
            updateToLatestLocked()
        }

    suspend fun rollback(): Result<LocalRuntimeStatus.Ready> =
        operationMutex.withLock {
            rollbackLocked()
        }

    private suspend fun updateToLatestLocked(): Result<LocalRuntimeStatus.Ready> {
        val engine =
            updateEngine
                ?: return Result.failure(IllegalStateException("Local runtime updater is not configured"))
        val current =
            currentMetadataForOperation()
                ?: return Result.failure(IllegalStateException("Local runtime is not installed"))
        val check =
            runCatching { engine.check(current.version, abi) }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    mutableLastOperation.value =
                        LocalRuntimeOperationResult.Failed(
                            "update-check",
                            error.message ?: messages.updateCheckFailed,
                        )
                    return Result.failure(error)
                }

        if (check is LocalRuntimeUpdateCheck.UpToDate) {
            val ready =
                if (portProbe(current.port)) {
                    LocalRuntimeStatus.Ready(current.version, current.port)
                        .also { mutableState.value = it }
                } else {
                    startForOperation()
                }
            mutableLastOperation.value = LocalRuntimeOperationResult.UpdateSkipped(current.version)
            return Result.success(ready)
        }

        val available = check as LocalRuntimeUpdateCheck.Available
        val targetVersion = available.release.version
        mutableState.value =
            LocalRuntimeStatus.Updating(
                currentVersion = current.version,
                targetVersion = targetVersion,
                progress = null,
                step = messages.preparingUpdate(),
            )
        val prepared =
            runCatching {
                engine.prepare(available.release) { progress, step ->
                    mutableState.value =
                        LocalRuntimeStatus.Updating(
                            currentVersion = current.version,
                            targetVersion = targetVersion,
                            progress = progress,
                            step = step,
                        )
                }
            }.getOrElse { error ->
                restoreStateBeforeMutation(current)
                if (error is CancellationException) throw error
                mutableLastOperation.value =
                    LocalRuntimeOperationResult.Failed(
                        "update-prepare",
                        error.message ?: messages.updatePrepareFailed,
                    )
                return Result.failure(error)
            }

        return try {
            stopForOperation()
            engine.activate(prepared)
            val ready = startForOperation()
            engine.commit()
            mutableState.value = ready
            mutableLastOperation.value =
                LocalRuntimeOperationResult.Updated(
                    fromVersion = current.version,
                    toVersion = ready.version,
                )
            Result.success(ready)
        } catch (error: Throwable) {
            val recovery =
                withContext(NonCancellable) {
                    restoreAfterFailedMutation(
                        engine = engine,
                        fallbackMetadata = current,
                        attemptedVersion = targetVersion,
                        originalError = error,
                        rollbackOperation = false,
                    )
                }
            if (error is CancellationException) throw error
            recovery
        }
    }

    private suspend fun rollbackLocked(): Result<LocalRuntimeStatus.Ready> {
        val engine =
            updateEngine
                ?: return Result.failure(IllegalStateException("Local runtime updater is not configured"))
        val current =
            currentMetadataForOperation()
                ?: return Result.failure(IllegalStateException("Local runtime is not installed"))
        val targetVersion =
            runCatching { engine.rollbackVersion() }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    mutableLastOperation.value =
                        LocalRuntimeOperationResult.Failed(
                            "rollback-check",
                            error.message ?: messages.rollbackCheckFailed,
                        )
                    return Result.failure(error)
                }
                ?: return Result.failure(IllegalStateException("Rollback version is unavailable"))

        mutableState.value =
            LocalRuntimeStatus.Updating(
                currentVersion = current.version,
                targetVersion = targetVersion,
                progress = null,
                step = messages.rollingBackTo(targetVersion),
            )
        return try {
            stopForOperation()
            engine.rollback()
            val ready = startForOperation()
            engine.commit()
            mutableState.value = ready
            mutableLastOperation.value =
                LocalRuntimeOperationResult.RolledBack(
                    fromVersion = current.version,
                    toVersion = ready.version,
                )
            Result.success(ready)
        } catch (error: Throwable) {
            val recovery =
                withContext(NonCancellable) {
                    restoreAfterFailedMutation(
                        engine = engine,
                        fallbackMetadata = current,
                        attemptedVersion = targetVersion,
                        originalError = error,
                        rollbackOperation = true,
                    )
                }
            if (error is CancellationException) throw error
            recovery
        }
    }

    private suspend fun restoreAfterFailedMutation(
        engine: LocalRuntimeUpdateEngine,
        fallbackMetadata: LocalRuntimeMetadata,
        attemptedVersion: String,
        originalError: Throwable,
        rollbackOperation: Boolean,
    ): Result<LocalRuntimeStatus.Ready> {
        runCatching { stopForOperation() }
            .exceptionOrNull()
            ?.let(originalError::addSuppressed)
        val recoveryError = runCatching { engine.recover() }.exceptionOrNull()
        recoveryError?.let(originalError::addSuppressed)
        val restoredMetadata = currentMetadataForOperation() ?: fallbackMetadata
        val restart = runCatching { startForOperation() }
        return restart.fold(
            onSuccess = { ready ->
                mutableState.value = ready
                mutableLastOperation.value =
                    if (rollbackOperation) {
                        LocalRuntimeOperationResult.RollbackFailedRestored(
                            attemptedVersion = attemptedVersion,
                            restoredVersion = ready.version,
                            reason = originalError.message ?: messages.startAfterRollbackFailed,
                        )
                    } else {
                        LocalRuntimeOperationResult.AutomaticRollback(
                            failedVersion = attemptedVersion,
                            restoredVersion = ready.version,
                            reason = originalError.message ?: messages.startAfterUpdateFailed,
                        )
                    }
                Result.failure(originalError)
            },
            onFailure = { restartError ->
                originalError.addSuppressed(restartError)
                mutableState.value =
                    LocalRuntimeStatus.Broken(
                        messages.restoredButCannotStart(restoredMetadata.version, restartError.message.orEmpty()),
                    )
                mutableLastOperation.value =
                    LocalRuntimeOperationResult.Failed(
                        operation = if (rollbackOperation) "rollback-recovery" else "update-recovery",
                        message = originalError.message ?: messages.restoreFailed,
                    )
                Result.failure(originalError)
            },
        )
    }

    private fun restoreStateBeforeMutation(metadata: LocalRuntimeMetadata) {
        mutableState.value =
            if (portProbe(metadata.port)) {
                LocalRuntimeStatus.Ready(metadata.version, metadata.port)
            } else {
                LocalRuntimeStatus.Stopped(metadata.version, metadata.port)
            }
    }

    private fun currentMetadataForOperation(): LocalRuntimeMetadata? = runtimeOperations?.currentMetadata() ?: readMetadata()

    private suspend fun stopForOperation() {
        val operations = runtimeOperations
        if (operations != null) {
            operations.stop()
        } else {
            withContext(Dispatchers.IO) { processLauncher?.stop() }
        }
    }

    private suspend fun startForOperation(): LocalRuntimeStatus.Ready {
        runtimeOperations?.let { return it.start() }
        val configuredInstaller =
            installer
                ?: error("Local runtime installer is not configured")
        val installed =
            configuredInstaller.installedRuntime()
                ?: error("Local runtime is not installed")
        return startInstalled(installed)
    }

    private suspend fun startLocked(): Result<LocalRuntimeStatus.Ready> =
        runCatching {
            updateEngine?.recover()
            runtimeOperations?.let { operations ->
                val ready = operations.start()
                mutableState.value = ready
                return@runCatching ready
            }
            val configuredInstaller =
                installer
                    ?: error("Local runtime installer is not configured")
            withContext(Dispatchers.IO) {
                configuredInstaller.recoverInterruptedActivation()
            }
            val installed =
                configuredInstaller.installedRuntime()
                    ?: error("Local runtime is not installed")
            startInstalled(installed)
        }.onFailure { error ->
            mutableState.value =
                LocalRuntimeStatus.Broken(
                    error.message ?: messages.startFailed,
                )
        }

    private suspend fun startInstalled(installed: LocalRuntimeInstaller.InstalledRuntime): LocalRuntimeStatus.Ready =
        withContext(Dispatchers.IO) {
            val launcher =
                processLauncher
                    ?: error("Local runtime process launcher is not configured")
            mutableState.value =
                LocalRuntimeStatus.Starting(
                    installed.metadata.version,
                    installed.metadata.port,
                )
            // Runtimes installed before the guest-browser MCP provisioning existed pick it up
            // here; the call is idempotent and a failure must never block the runtime start.
            runCatching { installer?.provisionBrowserMcpForExistingInstall() }
            if (!portProbe(installed.metadata.port)) launcher.start(installed)
            val ready =
                LocalRuntimeStatus.Ready(
                    installed.metadata.version,
                    installed.metadata.port,
                )
            mutableState.value = ready
            ready
        }

    private fun computeStatus(): LocalRuntimeStatus {
        if (abi !in SUPPORTED_ABIS) return LocalRuntimeStatus.UnsupportedAbi(abi)
        val metadataFile = File(runtimeDirectory, METADATA_FILE)
        if (!metadataFile.isFile) return LocalRuntimeStatus.NotInstalled
        val metadata =
            runCatching {
                json.decodeFromString<LocalRuntimeMetadata>(metadataFile.readText())
            }.getOrElse { error ->
                return LocalRuntimeStatus.Broken("Runtime metadata is invalid: ${error.message}")
            }
        // A sandbox provisioned for Claude Code only is not a broken OpenCode install: OpenCode was
        // never asked for, so it is simply not installed and the UI should offer to add it.
        if (!metadata.has(LocalAgent.OPEN_CODE)) return LocalRuntimeStatus.NotInstalled
        val rootfs = File(runtimeDirectory, "environment/rootfs")
        val openCode = File(rootfs, "usr/local/bin/opencode")
        if (!rootfs.isDirectory || !openCode.isFile) {
            return LocalRuntimeStatus.Broken(messages.missingFiles)
        }
        if (metadata.version.isBlank() || metadata.port !in 1..65535) {
            return LocalRuntimeStatus.Broken("Runtime metadata contains invalid values")
        }
        if (portProbe(metadata.port)) return LocalRuntimeStatus.Ready(metadata.version, metadata.port)
        // A missed probe is not proof of death. Our own child process is: if it is still running,
        // the server exists and is merely too busy to answer, and restarting it would destroy work
        // in progress rather than recover anything.
        if (processAlive()) {
            return LocalRuntimeStatus.Ready(metadata.version, metadata.port)
        }
        return LocalRuntimeStatus.Stopped(metadata.version, metadata.port)
    }

    private fun readMetadata(): LocalRuntimeMetadata? {
        val metadataFile = File(runtimeDirectory, METADATA_FILE)
        if (!metadataFile.isFile) return null
        return runCatching {
            json.decodeFromString<LocalRuntimeMetadata>(metadataFile.readText())
        }.getOrNull()
    }

    companion object {
        private const val METADATA_FILE = "metadata.json"
        private val SUPPORTED_ABIS = setOf("arm64-v8a", "x86_64")

        /**
         * Timeout for the liveness probe.
         *
         * A loopback connect to a listening socket returns in well under a millisecond, so a
         * generous limit costs nothing while the server is healthy. It matters only when the device
         * is loaded — and the heaviest load this app produces is a Claude Code turn, a 250 MB
         * binary running under PRoot. At the previous 300 ms the probe missed under that load, the
         * watchdog read it as a dead server, and a perfectly healthy OpenCode was killed and
         * restarted. That is the "OpenCode keeps crashing" the logs never showed a crash for.
         */
        private const val PROBE_TIMEOUT_MILLIS = 2_500

        fun defaultPortProbe(port: Int): Boolean =
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), PROBE_TIMEOUT_MILLIS)
                }
                true
            }.getOrDefault(false)
    }
}
