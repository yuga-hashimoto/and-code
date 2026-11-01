package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.storage.DeviceStorage
import java.io.File
import java.util.Locale

/**
 * A GGUF model found on the device, ready to serve through the embedded llama-server.
 *
 * [file] is the host path of the model. When the server starts, the model's containing directory
 * is bound into the sandbox at `/models` (see [LocalAiProotCommand]), so the guest path of every
 * model file is simply `/models/<name>`.
 */
data class LocalAiModel(
    val file: File,
) {
    val modelId: String get() = file.name
    val displayName: String get() = file.name
    val sizeBytes: Long get() = file.length()

    fun sizeLabel(): String {
        val kb = sizeBytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.0f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", kb)
        }
    }
}

/** Finds `.gguf` models in every device directory the sandbox can reach. */
class LocalAiModelScanner(
    private val mounts: () -> DeviceStorage.Mounts = DeviceStorage::mounts,
    private val extraDirectories: List<File> = emptyList(),
    private val fileFilter: (File) -> Boolean = { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) },
    private val maxDepth: Int = 3,
) {
    /** All models found, sorted by name. Only files that really exist are returned. */
    fun scan(): List<LocalAiModel> =
        buildList {
            mounts().sharedStorage?.let { addAll(scanDir(it)) }
            mounts().volumes?.let { addAll(scanDir(it)) }
            extraDirectories.forEach { addAll(scanDir(it)) }
        }
            .distinctBy { it.file.absolutePath }
            .sortedBy { it.displayName.lowercase(Locale.US) }

    private fun scanDir(root: File): List<LocalAiModel> {
        if (!root.isDirectory) return emptyList()
        val result = mutableListOf<LocalAiModel>()
        root.walkTopDown()
            .maxDepth(maxDepth)
            .filter(fileFilter)
            .forEach { file -> result += LocalAiModel(file) }
        return result
    }
}
