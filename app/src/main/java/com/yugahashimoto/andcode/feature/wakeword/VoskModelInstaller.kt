package com.yugahashimoto.andcode.feature.wakeword

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/** How far along an install is, for the settings screen to show. */
sealed interface VoskInstallProgress {
    /** [total] is null when the server does not say how big the download is. */
    data class Downloading(val bytes: Long, val total: Long?) : VoskInstallProgress {
        val fraction: Float? get() = total?.takeIf { it > 0 }?.let { (bytes.toFloat() / it).coerceIn(0f, 1f) }
    }

    data object Extracting : VoskInstallProgress
}

/**
 * Fetches a speech model and unpacks it into its own directory.
 *
 * Everything lands in a temporary directory first and is moved into place only once the whole
 * archive has been read: a download interrupted halfway - by cancelling, by losing signal, by the
 * process being killed - must not leave something that looks installed but cannot be loaded.
 */
class VoskModelInstaller(
    private val client: OkHttpClient,
    private val root: File,
    private val maxArchiveBytes: Long = 100L * 1024 * 1024,
    private val maxExpandedBytes: Long = 250L * 1024 * 1024,
    private val maxEntryCount: Int = 20_000,
) {
    fun directoryFor(spec: VoskModelSpec): File = File(root, spec.directoryName)

    /**
     * Installed means the directory exists and holds the two things Vosk needs to load. A
     * directory that exists but is empty is what a kill mid-extract used to leave behind.
     */
    fun isInstalled(spec: VoskModelSpec): Boolean {
        val directory = directoryFor(spec)
        return directory.isDirectory && File(directory, "am").isDirectory && File(directory, "conf").isDirectory
    }

    fun remove(spec: VoskModelSpec) {
        directoryFor(spec).deleteRecursively()
    }

    suspend fun install(
        spec: VoskModelSpec,
        onProgress: (VoskInstallProgress) -> Unit = {},
    ): Result<File> =
        withContext(Dispatchers.IO) {
            if (isInstalled(spec)) return@withContext Result.success(directoryFor(spec))
            val staging = File(root, "${spec.directoryName}.partial")
            staging.deleteRecursively()
            runCatching {
                root.mkdirs()
                check(staging.mkdirs()) { "Could not create ${staging.absolutePath}" }
                download(spec, staging, onProgress)
                val unpacked = File(staging, spec.directoryName)
                check(unpacked.isDirectory) {
                    "Archive did not contain ${spec.directoryName}"
                }
                val target = directoryFor(spec)
                target.deleteRecursively()
                check(unpacked.renameTo(target)) { "Could not move the model into place" }
                target
            }.onFailure {
                // Nothing partially written survives a failure, so the next attempt starts clean
                // rather than reporting a model that cannot be loaded.
                directoryFor(spec).deleteRecursively()
            }.also {
                staging.deleteRecursively()
            }
        }

    private suspend fun download(
        spec: VoskModelSpec,
        staging: File,
        onProgress: (VoskInstallProgress) -> Unit,
    ) {
        val request = Request.Builder().url(spec.downloadUrl).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Model download failed with HTTP ${response.code}" }
            val body = checkNotNull(response.body) { "Model download returned no body" }
            val total = body.contentLength().takeIf { it > 0 }
            check(total == null || total <= maxArchiveBytes) {
                "Model archive exceeds the ${maxArchiveBytes / (1024 * 1024)} MiB limit"
            }
            onProgress(VoskInstallProgress.Downloading(0, total))
            extract(CountingStream(body.byteStream(), total, maxArchiveBytes, onProgress), staging)
            onProgress(VoskInstallProgress.Extracting)
        }
    }

    private suspend fun extract(
        source: InputStream,
        staging: File,
    ) {
        ZipInputStream(source).use { zip ->
            var entryCount = 0
            var expandedBytes = 0L
            var entry = zip.nextEntry
            while (entry != null) {
                coroutineContext.ensureActive()
                check(++entryCount <= maxEntryCount) { "Model archive contains too many entries" }
                val target = File(staging, entry.name)
                // The archive comes off the network, so its entry names are untrusted: "../" in a
                // name is the standard way one writes outside the directory it was given.
                check(target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                    "Archive entry escapes the target directory: ${entry?.name}"
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            expandedBytes += count
                            check(expandedBytes <= maxExpandedBytes) {
                                "Model archive expands beyond the ${maxExpandedBytes / (1024 * 1024)} MiB limit"
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    /** Reports how much of the body has been read as the zip is streamed through it. */
    private class CountingStream(
        private val delegate: InputStream,
        private val total: Long?,
        private val maxBytes: Long,
        private val onProgress: (VoskInstallProgress) -> Unit,
    ) : InputStream() {
        private var read = 0L
        private var lastReported = 0L

        override fun read(): Int = delegate.read().also { if (it >= 0) advance(1) }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = delegate.read(buffer, offset, length).also { if (it > 0) advance(it.toLong()) }

        override fun close() = delegate.close()

        private fun advance(count: Long) {
            read += count
            check(read <= maxBytes) {
                "Model archive exceeds the ${maxBytes / (1024 * 1024)} MiB limit"
            }
            // Throttled: the archive is tens of megabytes and a callback per buffer would spend
            // more time recomposing the progress bar than reading.
            if (read - lastReported < PROGRESS_STEP_BYTES) return
            lastReported = read
            onProgress(VoskInstallProgress.Downloading(read, total))
        }

        private companion object {
            const val PROGRESS_STEP_BYTES = 256L * 1024
        }
    }

    private companion object {
        const val BUFFER_SIZE = 32 * 1024
    }
}
