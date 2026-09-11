package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class VerifiedRuntimeDownloader(
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val operationMutex = Mutex()

    suspend fun download(
        url: String,
        destination: File,
        expectedSha256: String,
        expectedSizeBytes: Long? = null,
        headers: Map<String, String> = emptyMap(),
        onProgress: (Float?) -> Unit = {},
    ) = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            downloadLocked(
                url = url,
                destination = destination,
                expectedSha256 = expectedSha256,
                expectedSizeBytes = expectedSizeBytes,
                headers = headers,
                onProgress = onProgress,
            )
        }
    }

    private fun downloadLocked(
        url: String,
        destination: File,
        expectedSha256: String,
        expectedSizeBytes: Long?,
        headers: Map<String, String>,
        onProgress: (Float?) -> Unit,
    ) {
        val parsedUrl = url.toHttpUrl()
        require(parsedUrl.isHttps || parsedUrl.host in LOOPBACK_HOSTS) {
            "Runtime download URL must use HTTPS"
        }
        require(SHA256.matches(expectedSha256)) { "Invalid expected SHA-256" }
        require(expectedSizeBytes == null || expectedSizeBytes > 0L) {
            "Expected download size must be positive"
        }
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, destination.name + ".partial")
        val backup = File(destination.parentFile, destination.name + ".backup")
        recoverBackupIfDestinationMissing(destination, backup)

        var completed = false
        var validationFailed = false
        try {
            var downloaded = partial.length().coerceAtLeast(0L)
            if (expectedSizeBytes != null && downloaded > expectedSizeBytes) {
                partial.delete()
                downloaded = 0L
            }
            // The process may have died after writing the final byte but before verification. In
            // that case a range request starts at EOF and commonly receives HTTP 416 forever.
            // Verify the preserved body first so a complete partial can be activated offline.
            val partialAlreadyComplete =
                downloaded > 0L &&
                    (expectedSizeBytes == null || downloaded == expectedSizeBytes) &&
                    runCatching {
                        RuntimeArchive.verifySha256(partial, expectedSha256)
                    }.isSuccess
            if (!partialAlreadyComplete && expectedSizeBytes != null && downloaded == expectedSizeBytes) {
                partial.delete()
                downloaded = 0L
            }
            if (!partialAlreadyComplete) {
                val resume = downloaded > 0L
                val requestBuilder = Request.Builder().url(parsedUrl).get()
                headers.forEach { (name, value) -> requestBuilder.header(name, value) }
                if (resume) requestBuilder.header("Range", "bytes=$downloaded-")
                val request = requestBuilder.build()
                httpClient.newCall(request).execute().use { response ->
                    if (resume && response.code == 416) {
                        require(partial.delete()) { "Unable to discard an invalid partial download" }
                        response.close()
                        return downloadLocked(
                            url = url,
                            destination = destination,
                            expectedSha256 = expectedSha256,
                            expectedSizeBytes = expectedSizeBytes,
                            headers = headers,
                            onProgress = onProgress,
                        )
                    }
                    require(response.isSuccessful) {
                        "Runtime download failed with HTTP ${response.code}"
                    }
                    val body = requireNotNull(response.body) { "Runtime download response had no body" }
                    // A compliant server returns 206 for a range request. If it ignores Range and
                    // returns 200, restart from byte zero rather than duplicating the prefix.
                    val append = resume && response.code == 206
                    if (append) {
                        validationFailed = true
                        require(response.header("Content-Range")?.startsWith("bytes $downloaded-") == true) {
                            "Runtime download returned an invalid Content-Range"
                        }
                        validationFailed = false
                    }
                    if (!append) downloaded = 0L
                    FileOutputStream(partial, append).buffered().use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                downloaded += count
                                onProgress(expectedSizeBytes?.let { downloaded.toFloat() / it })
                            }
                        }
                    }
                }
                expectedSizeBytes?.let { expected ->
                    validationFailed = true
                    require(downloaded == expected) {
                        "Runtime download size mismatch: expected $expected, got $downloaded"
                    }
                }
                validationFailed = true
                RuntimeArchive.verifySha256(partial, expectedSha256)
                validationFailed = false
            }

            if (destination.isFile &&
                runCatching {
                    RuntimeArchive.verifySha256(destination, expectedSha256)
                }.isSuccess
            ) {
                partial.delete()
                backup.delete()
                onProgress(1f)
                return
            }

            if (!backup.exists() && destination.exists()) {
                move(destination, backup)
            } else if (backup.exists() && destination.exists()) {
                require(destination.delete()) {
                    "Unable to remove the unverified runtime download"
                }
            }
            try {
                move(partial, destination)
                backup.delete()
                completed = true
            } catch (error: Throwable) {
                destination.delete()
                if (backup.exists()) {
                    runCatching { move(backup, destination) }
                        .exceptionOrNull()
                        ?.let(error::addSuppressed)
                }
                throw error
            }
            onProgress(1f)
        } catch (error: Throwable) {
            // Keep an incomplete body for transient network/process failures. Invalid complete
            // bodies are never useful for a retry and are removed just as before.
            if (validationFailed) partial.delete()
            throw error
        } finally {
            if (completed) partial.delete()
            recoverBackupIfDestinationMissing(destination, backup)
        }
    }

    private fun recoverBackupIfDestinationMissing(
        destination: File,
        backup: File,
    ) {
        if (!destination.exists() && backup.exists()) {
            move(backup, destination)
        }
    }

    private fun move(
        source: File,
        destination: File,
    ) {
        require(source.parentFile?.canonicalFile == destination.parentFile?.canonicalFile) {
            "Verified runtime download moves must stay on one filesystem"
        }
        destination.parentFile?.mkdirs()
        require(source.renameTo(destination)) {
            "Unable to move ${source.name} to ${destination.name}"
        }
    }

    companion object {
        private val SHA256 = Regex("^[a-f0-9]{64}$")
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")
    }
}
