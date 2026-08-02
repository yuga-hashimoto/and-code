package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

data class PreparedRuntimeUpdate(
    val release: LocalRuntimeRelease,
    val candidateBinary: File,
    val candidateMetadata: LocalRuntimeMetadata,
    val baseVersion: String,
)

@Serializable
private data class LocalRuntimeRollbackJournal(
    @SerialName("currentVersion") val currentVersion: String,
    @SerialName("targetVersion") val targetVersion: String,
)

@Serializable
private data class LocalRuntimeUpdateJournal(
    @SerialName("oldVersion") val oldVersion: String,
    @SerialName("newVersion") val newVersion: String,
    @SerialName("candidateFileName") val candidateFileName: String,
    @SerialName("candidateMetadataFileName") val candidateMetadataFileName: String,
    @SerialName("hadRollback") val hadRollback: Boolean,
)

class LocalRuntimeUpdater(
    private val runtimeDirectory: File,
    private val abi: String,
    private val freeBytesProvider: () -> Long = {
        runtimeDirectory.parentFile?.usableSpace ?: runtimeDirectory.usableSpace
    },
    private val downloadAsset: suspend (
        asset: LocalRuntimeReleaseAsset,
        destination: File,
        progress: (Float?) -> Unit,
    ) -> Unit,
    private val extractArchive: (archive: File, destination: File) -> Unit = { archive, destination ->
        archive.inputStream().use { RuntimeArchive.extractTarGz(it, destination) }
    },
    private val candidateVersionProvider: (binary: File) -> String,
    private val moveFile: (source: File, destination: File) -> Unit = { source, destination ->
        destination.parentFile?.mkdirs()
        require(source.renameTo(destination)) {
            "Unable to move ${source.name} to ${destination.name}"
        }
    },
    private val accessCoordinator: LocalRuntimeAccessCoordinator,
    private val messages: LocalRuntimeMessages = LocalRuntimeMessages,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        },
) {
    private val operationMutex = Mutex()

    suspend fun prepare(
        release: LocalRuntimeRelease,
        onProgress: (Float?, String) -> Unit = { _, _ -> },
    ): PreparedRuntimeUpdate =
        operationMutex.withLock {
            withContext(Dispatchers.IO) {
                prepareLocked(release, onProgress)
            }
        }

    suspend fun activate(prepared: PreparedRuntimeUpdate): LocalRuntimeMetadata =
        operationMutex.withLock {
            withContext(Dispatchers.IO) {
                accessCoordinator.write { activateLocked(prepared) }
            }
        }

    suspend fun commitActivation() =
        operationMutex.withLock {
            withContext(Dispatchers.IO) {
                accessCoordinator.write { commitActivationLocked() }
            }
        }

    suspend fun recoverInterruptedActivation(): LocalRuntimeMetadata? =
        operationMutex.withLock {
            withContext(Dispatchers.IO) {
                accessCoordinator.write { recoverInterruptedActivationLocked() }
            }
        }

    suspend fun rollback(): LocalRuntimeMetadata =
        operationMutex.withLock {
            withContext(Dispatchers.IO) {
                accessCoordinator.write { rollbackLocked() }
            }
        }

    suspend fun rollbackVersion(): String? =
        operationMutex.withLock {
            withContext(Dispatchers.IO) {
                accessCoordinator.read { readMetadata(ROLLBACK_METADATA_FILE)?.version }
            }
        }

    private suspend fun prepareLocked(
        release: LocalRuntimeRelease,
        onProgress: (Float?, String) -> Unit,
    ): PreparedRuntimeUpdate {
        accessCoordinator.write { recoverInterruptedActivationLocked() }
        require(release.asset.name == expectedAssetName(abi)) {
            "Unexpected OpenCode update asset for ABI $abi: ${release.asset.name}"
        }
        val snapshot =
            accessCoordinator.read {
                val metadata =
                    readMetadata(METADATA_FILE)
                        ?: error("Local runtime metadata is missing")
                require(activeBinary().isFile) { "Active OpenCode binary is missing" }
                RuntimePreparationSnapshot(metadata, freeBytesProvider())
            }
        val currentMetadata = snapshot.metadata
        val freeBytes = snapshot.freeBytes
        require(freeBytes >= release.asset.requiredFreeBytes) {
            messages.insufficientFreeSpace(release.asset.requiredFreeBytes, freeBytes)
        }

        val normalizedVersion = normalizeOpenCodeVersion(release.version)
        val cacheArchive = File(runtimeDirectory, "cache/opencode-update-$normalizedVersion-$abi.tar.gz")
        val extractionDirectory = File(runtimeDirectory, "update-staging-$normalizedVersion")
        val candidate = candidateBinary(normalizedVersion)
        extractionDirectory.deleteRecursively()
        candidate.delete()

        try {
            onProgress(0.05f, messages.downloadingOpenCode(normalizedVersion))
            downloadAsset(release.asset, cacheArchive) { fraction ->
                onProgress(
                    fraction?.let { 0.05f + it.coerceIn(0f, 1f) * 0.65f },
                    messages.downloadingOpenCode(normalizedVersion),
                )
            }
            extractionDirectory.mkdirs()
            onProgress(0.75f, messages.extractingUpdate())
            extractArchive(cacheArchive, extractionDirectory)
            val extractedBinary =
                extractionDirectory.walkTopDown()
                    .firstOrNull { it.isFile && it.name == "opencode" }
                    ?: error("OpenCode update archive did not contain the opencode binary")
            candidate.parentFile?.mkdirs()
            extractedBinary.copyTo(candidate, overwrite = true)
            require(candidate.setExecutable(true, false) || candidate.canExecute()) {
                "Unable to mark update candidate executable"
            }
            onProgress(0.9f, messages.verifyingUpdate())
            val reportedVersion = binaryVersion(candidate)
            require(reportedVersion == normalizedVersion) {
                "OpenCode candidate version mismatch: expected $normalizedVersion, got $reportedVersion"
            }
            val candidateMetadata =
                currentMetadata.copy(
                    version = normalizedVersion,
                    installedAt = nowMillis(),
                    abi = abi,
                )
            onProgress(1f, messages.updateCandidateReady())
            return PreparedRuntimeUpdate(
                release = release.copy(version = normalizedVersion),
                candidateBinary = candidate,
                candidateMetadata = candidateMetadata,
                baseVersion = normalizeOpenCodeVersion(currentMetadata.version),
            )
        } catch (error: Throwable) {
            candidate.delete()
            throw error
        } finally {
            extractionDirectory.deleteRecursively()
            cacheArchive.delete()
        }
    }

    private fun activateLocked(prepared: PreparedRuntimeUpdate): LocalRuntimeMetadata {
        recoverInterruptedActivationLocked()
        val active = activeBinary()
        val candidate = prepared.candidateBinary
        val rollback = rollbackBinary()
        val rollbackPrevious = binaryFile(ROLLBACK_PREVIOUS_BINARY)
        val binarySwap = binaryFile(SWAP_BINARY)

        val metadata = metadataFile(METADATA_FILE)
        val candidateMetadata = metadataFile(candidateMetadataName(prepared.candidateMetadata.version))
        val rollbackMetadata = metadataFile(ROLLBACK_METADATA_FILE)
        val rollbackMetadataPrevious = metadataFile(ROLLBACK_PREVIOUS_METADATA_FILE)
        val metadataSwap = metadataFile(SWAP_METADATA_FILE)

        require(candidate.parentFile?.canonicalFile == active.parentFile?.canonicalFile) {
            "Prepared update candidate is outside the managed binary directory"
        }
        require(active.isFile && candidate.isFile && metadata.isFile) {
            "Local runtime update files are incomplete"
        }
        val previousMetadata =
            readMetadata(METADATA_FILE)
                ?: error("Local runtime metadata is invalid")
        require(normalizeOpenCodeVersion(previousMetadata.version) == prepared.baseVersion) {
            "Local runtime changed after the update candidate was prepared"
        }
        require(binaryVersion(candidate) == normalizeOpenCodeVersion(prepared.candidateMetadata.version)) {
            "Prepared OpenCode candidate no longer matches its metadata"
        }

        cleanup(binarySwap, metadataSwap, rollbackPrevious, rollbackMetadataPrevious, candidateMetadata)
        val hadRollback = rollback.isFile && rollbackMetadata.isFile
        if (!hadRollback) cleanup(rollback, rollbackMetadata)
        val journal =
            LocalRuntimeUpdateJournal(
                oldVersion = normalizeOpenCodeVersion(previousMetadata.version),
                newVersion = normalizeOpenCodeVersion(prepared.candidateMetadata.version),
                candidateFileName = candidate.name,
                candidateMetadataFileName = candidateMetadata.name,
                hadRollback = hadRollback,
            )
        writeJsonAtomically(journalFile(), journal)
        writeJsonAtomically(candidateMetadata, prepared.candidateMetadata)

        try {
            if (hadRollback) {
                move(rollback, rollbackPrevious)
                move(rollbackMetadata, rollbackMetadataPrevious)
            }
            swapPair(active, candidate, binarySwap)
            swapPair(metadata, candidateMetadata, metadataSwap)
            move(candidate, rollback)
            move(candidateMetadata, rollbackMetadata)
            require(active.setExecutable(true, false) || active.canExecute()) {
                "Updated OpenCode binary is not executable"
            }
            return previousMetadata
        } catch (error: Throwable) {
            val recovery = runCatching { recoverInterruptedActivationLocked() }
            recovery.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    private fun commitActivationLocked() {
        val rollbackJournal = readRollbackJournal()
        if (rollbackJournal != null) {
            require(rollbackJournalFile().delete()) {
                "Unable to commit the runtime rollback transaction"
            }
            cleanupRollbackRecoveryFiles()
            return
        }
        val journal = readJournal() ?: return
        require(journalFile().delete()) { "Unable to commit the runtime update transaction" }
        cleanup(
            binaryFile(SWAP_BINARY),
            metadataFile(SWAP_METADATA_FILE),
            binaryFile(ROLLBACK_PREVIOUS_BINARY),
            metadataFile(ROLLBACK_PREVIOUS_METADATA_FILE),
            binaryFile(journal.candidateFileName),
            metadataFile(journal.candidateMetadataFileName),
        )
    }

    private fun recoverInterruptedActivationLocked(): LocalRuntimeMetadata? {
        recoverInterruptedRollbackLocked()?.let { return it }
        val journal = readJournal() ?: return null
        val active = activeBinary()
        val rollback = rollbackBinary()
        val candidate = binaryFile(journal.candidateFileName)
        val binarySwap = binaryFile(SWAP_BINARY)
        val rollbackPrevious = binaryFile(ROLLBACK_PREVIOUS_BINARY)

        val metadata = metadataFile(METADATA_FILE)
        val candidateMetadata = metadataFile(journal.candidateMetadataFileName)
        val metadataSwap = metadataFile(SWAP_METADATA_FILE)
        val rollbackMetadata = metadataFile(ROLLBACK_METADATA_FILE)
        val rollbackMetadataPrevious = metadataFile(ROLLBACK_PREVIOUS_METADATA_FILE)

        val oldBinary =
            listOf(active, binarySwap, candidate, rollback, rollbackPrevious)
                .distinctBy { it.absolutePath }
                .firstOrNull { file -> file.isFile && runCatching { binaryVersion(file) }.getOrNull() == journal.oldVersion }
                ?: error("Unable to locate previous OpenCode ${journal.oldVersion} binary")
        val oldMetadataFile =
            listOf(
                metadata,
                metadataSwap,
                candidateMetadata,
                rollbackMetadata,
                rollbackMetadataPrevious,
            ).distinctBy { it.absolutePath }
                .firstOrNull { file -> readMetadataFile(file)?.version?.let(::normalizeOpenCodeVersion) == journal.oldVersion }
                ?: error("Unable to locate previous OpenCode ${journal.oldVersion} metadata")
        val oldMetadata = requireNotNull(readMetadataFile(oldMetadataFile))

        val failedBinary = binaryFile("opencode.failed.${journal.newVersion}")
        val failedMetadata = metadataFile("metadata.failed.${journal.newVersion}.json")
        cleanup(failedBinary, failedMetadata)
        if (oldBinary.canonicalFile != active.canonicalFile) {
            if (active.exists()) move(active, failedBinary)
            move(oldBinary, active)
        }
        if (oldMetadataFile.canonicalFile != metadata.canonicalFile) {
            if (metadata.exists()) move(metadata, failedMetadata)
            move(oldMetadataFile, metadata)
        }

        if (journal.hadRollback) {
            if (rollbackPrevious.isFile) {
                cleanup(rollback)
                move(rollbackPrevious, rollback)
            }
            if (rollbackMetadataPrevious.isFile) {
                cleanup(rollbackMetadata)
                move(rollbackMetadataPrevious, rollbackMetadata)
            }
            require(rollback.isFile && rollbackMetadata.isFile) {
                "Unable to restore the previous rollback version"
            }
        } else {
            cleanup(rollback, rollbackMetadata, rollbackPrevious, rollbackMetadataPrevious)
        }

        cleanup(
            candidate,
            candidateMetadata,
            binarySwap,
            metadataSwap,
            failedBinary,
            failedMetadata,
        )
        require(active.setExecutable(true, false) || active.canExecute()) {
            "Recovered OpenCode binary is not executable"
        }
        journalFile().delete()
        return oldMetadata
    }

    private fun recoverInterruptedRollbackLocked(): LocalRuntimeMetadata? {
        val journal = readRollbackJournal() ?: return null
        val recoveryCurrentBinary = binaryFile(ROLLBACK_RECOVERY_CURRENT_BINARY)
        val recoveryTargetBinary = binaryFile(ROLLBACK_RECOVERY_TARGET_BINARY)
        val recoveryCurrentMetadata = metadataFile(ROLLBACK_RECOVERY_CURRENT_METADATA)
        val recoveryTargetMetadata = metadataFile(ROLLBACK_RECOVERY_TARGET_METADATA)

        val binaryCandidates =
            listOf(
                recoveryCurrentBinary,
                recoveryTargetBinary,
                activeBinary(),
                rollbackBinary(),
                binaryFile(SWAP_BINARY),
            ).distinctBy { it.absolutePath }
        val metadataCandidates =
            listOf(
                recoveryCurrentMetadata,
                recoveryTargetMetadata,
                metadataFile(METADATA_FILE),
                metadataFile(ROLLBACK_METADATA_FILE),
                metadataFile(SWAP_METADATA_FILE),
            ).distinctBy { it.absolutePath }

        val currentBinary = findBinaryVersion(binaryCandidates, journal.currentVersion)
        val targetBinary = findBinaryVersion(binaryCandidates, journal.targetVersion)
        val currentMetadataFile = findMetadataVersion(metadataCandidates, journal.currentVersion)
        val targetMetadataFile = findMetadataVersion(metadataCandidates, journal.targetVersion)
        val currentMetadata = requireNotNull(readMetadataFile(currentMetadataFile))

        stageRecoveryFile(currentBinary, recoveryCurrentBinary)
        stageRecoveryFile(targetBinary, recoveryTargetBinary)
        stageRecoveryFile(currentMetadataFile, recoveryCurrentMetadata)
        stageRecoveryFile(targetMetadataFile, recoveryTargetMetadata)

        cleanup(
            activeBinary(),
            rollbackBinary(),
            binaryFile(SWAP_BINARY),
            metadataFile(METADATA_FILE),
            metadataFile(ROLLBACK_METADATA_FILE),
            metadataFile(SWAP_METADATA_FILE),
        )
        move(recoveryCurrentBinary, activeBinary())
        move(recoveryTargetBinary, rollbackBinary())
        move(recoveryCurrentMetadata, metadataFile(METADATA_FILE))
        move(recoveryTargetMetadata, metadataFile(ROLLBACK_METADATA_FILE))
        require(activeBinary().setExecutable(true, false) || activeBinary().canExecute()) {
            "Recovered current OpenCode binary is not executable"
        }
        require(rollbackBinary().setExecutable(true, false) || rollbackBinary().canExecute()) {
            "Recovered rollback OpenCode binary is not executable"
        }
        require(rollbackJournalFile().delete()) {
            "Unable to finalize rollback transaction recovery"
        }
        cleanupRollbackRecoveryFiles()
        return currentMetadata
    }

    private fun rollbackLocked(): LocalRuntimeMetadata {
        recoverInterruptedActivationLocked()
        val active = activeBinary()
        val rollback = rollbackBinary()
        val binarySwap = binaryFile(SWAP_BINARY)
        val metadata = metadataFile(METADATA_FILE)
        val rollbackMetadata = metadataFile(ROLLBACK_METADATA_FILE)
        val metadataSwap = metadataFile(SWAP_METADATA_FILE)
        require(active.isFile && rollback.isFile && metadata.isFile && rollbackMetadata.isFile) {
            "Rollback version is unavailable"
        }
        require(rollback.canExecute()) { "Rollback OpenCode binary is not executable" }
        val currentMetadata =
            readMetadata(METADATA_FILE)
                ?: error("Current runtime metadata is invalid")
        val targetMetadata =
            readMetadata(ROLLBACK_METADATA_FILE)
                ?: error("Rollback runtime metadata is invalid")
        require(normalizeOpenCodeVersion(currentMetadata.version) != normalizeOpenCodeVersion(targetMetadata.version)) {
            "Current and rollback OpenCode versions are identical"
        }
        writeJsonAtomically(
            rollbackJournalFile(),
            LocalRuntimeRollbackJournal(
                currentVersion = normalizeOpenCodeVersion(currentMetadata.version),
                targetVersion = normalizeOpenCodeVersion(targetMetadata.version),
            ),
        )

        swapPair(active, rollback, binarySwap)
        try {
            swapPair(metadata, rollbackMetadata, metadataSwap)
        } catch (error: Throwable) {
            runCatching { recoverInterruptedRollbackLocked() }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
        require(active.setExecutable(true, false) || active.canExecute()) {
            "Restored OpenCode binary is not executable"
        }
        return readMetadata(METADATA_FILE) ?: error("Restored runtime metadata is invalid")
    }

    private fun swapPair(
        first: File,
        second: File,
        temporary: File,
    ) {
        require(first.exists() && second.exists()) {
            "Unable to swap missing runtime files: ${first.name}, ${second.name}"
        }
        temporary.delete()
        move(first, temporary)
        var secondMoved = false
        try {
            move(second, first)
            secondMoved = true
            move(temporary, second)
        } catch (error: Throwable) {
            if (secondMoved && first.exists() && !second.exists()) {
                runCatching { move(first, second) }
            }
            if (temporary.exists() && !first.exists()) {
                runCatching { move(temporary, first) }
            }
            throw error
        } finally {
            if (first.exists() && second.exists()) temporary.delete()
        }
    }

    private fun findBinaryVersion(
        candidates: List<File>,
        version: String,
    ): File =
        candidates.firstOrNull { file ->
            file.isFile && runCatching { binaryVersion(file) }.getOrNull() == version
        } ?: error("Unable to locate OpenCode $version binary during rollback recovery")

    private fun findMetadataVersion(
        candidates: List<File>,
        version: String,
    ): File =
        candidates.firstOrNull { file ->
            readMetadataFile(file)?.version?.let(::normalizeOpenCodeVersion) == version
        } ?: error("Unable to locate OpenCode $version metadata during rollback recovery")

    private fun stageRecoveryFile(
        source: File,
        destination: File,
    ) {
        if (source.canonicalFile == destination.canonicalFile) return
        destination.delete()
        move(source, destination)
    }

    private fun cleanupRollbackRecoveryFiles() {
        cleanup(
            binaryFile(SWAP_BINARY),
            metadataFile(SWAP_METADATA_FILE),
            binaryFile(ROLLBACK_RECOVERY_CURRENT_BINARY),
            binaryFile(ROLLBACK_RECOVERY_TARGET_BINARY),
            metadataFile(ROLLBACK_RECOVERY_CURRENT_METADATA),
            metadataFile(ROLLBACK_RECOVERY_TARGET_METADATA),
        )
    }

    private fun move(
        source: File,
        destination: File,
    ) {
        require(source.parentFile?.canonicalFile == destination.parentFile?.canonicalFile) {
            "Atomic runtime move requires the same parent directory"
        }
        destination.parentFile?.mkdirs()
        moveFile(source, destination)
    }

    private fun binaryVersion(file: File): String = normalizeOpenCodeVersion(candidateVersionProvider(file))

    private fun readJournal(): LocalRuntimeUpdateJournal? = readJson<LocalRuntimeUpdateJournal>(journalFile())

    private fun readRollbackJournal(): LocalRuntimeRollbackJournal? = readJson<LocalRuntimeRollbackJournal>(rollbackJournalFile())

    private fun readMetadata(name: String): LocalRuntimeMetadata? = readMetadataFile(metadataFile(name))

    private fun readMetadataFile(file: File): LocalRuntimeMetadata? = readJson<LocalRuntimeMetadata>(file)

    private inline fun <reified T> readJson(file: File): T? {
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<T>(file.readText()) }.getOrNull()
    }

    private inline fun <reified T> writeJsonAtomically(
        destination: File,
        value: T,
    ) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".write")
        temporary.delete()
        FileOutputStream(temporary).use { output ->
            output.write(json.encodeToString(value).toByteArray())
            output.fd.sync()
        }
        Files.move(
            temporary.toPath(),
            destination.toPath(),
            ATOMIC_MOVE,
            REPLACE_EXISTING,
        )
    }

    private fun activeBinary() = binaryFile("opencode")

    private fun candidateBinary(version: String) = binaryFile("opencode.candidate.$version")

    private fun rollbackBinary() = binaryFile("opencode.rollback")

    private fun binaryFile(name: String) = File(runtimeDirectory, "environment/rootfs/usr/local/bin/$name")

    private fun metadataFile(name: String) = File(runtimeDirectory, name)

    private fun journalFile() = metadataFile(TRANSACTION_FILE)

    private fun rollbackJournalFile() = metadataFile(ROLLBACK_TRANSACTION_FILE)

    private fun cleanup(vararg files: File) = files.forEach(File::delete)

    private fun candidateMetadataName(version: String) = "metadata.candidate.$version.json"

    private data class RuntimePreparationSnapshot(
        val metadata: LocalRuntimeMetadata,
        val freeBytes: Long,
    )

    companion object {
        private const val METADATA_FILE = "metadata.json"
        private const val ROLLBACK_METADATA_FILE = "metadata.rollback.json"
        private const val ROLLBACK_PREVIOUS_METADATA_FILE = "metadata.rollback.previous.json"
        private const val SWAP_METADATA_FILE = "metadata.swap.json"
        private const val ROLLBACK_PREVIOUS_BINARY = "opencode.rollback.previous"
        private const val SWAP_BINARY = "opencode.swap"
        private const val TRANSACTION_FILE = "update-transaction.json"
        private const val ROLLBACK_TRANSACTION_FILE = "rollback-transaction.json"
        private const val ROLLBACK_RECOVERY_CURRENT_BINARY = "opencode.rollback-recovery.current"
        private const val ROLLBACK_RECOVERY_TARGET_BINARY = "opencode.rollback-recovery.target"
        private const val ROLLBACK_RECOVERY_CURRENT_METADATA = "metadata.rollback-recovery.current.json"
        private const val ROLLBACK_RECOVERY_TARGET_METADATA = "metadata.rollback-recovery.target.json"

        private fun expectedAssetName(abi: String): String =
            when (abi) {
                "arm64-v8a" -> "opencode-linux-arm64-musl.tar.gz"
                "x86_64" -> "opencode-linux-x64-musl.tar.gz"
                else -> error("Unsupported Android ABI for OpenCode updates: $abi")
            }
    }
}
