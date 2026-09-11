package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class LocalRuntimeUpdaterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
    private lateinit var runtime: File

    @Before
    fun setUp() {
        runtime = temporaryFolder.newFolder("runtime")
        writeActive(version = "1.18.3", binary = "old-binary")
    }

    @Test
    fun `insufficient space rejects before download`() =
        runTest {
            var downloadCalls = 0
            val updater =
                updater(
                    freeBytes = release().asset.requiredFreeBytes - 1,
                    download = { _, _, _ -> downloadCalls++ },
                )

            val error = runCatching { updater.prepare(release()) }.exceptionOrNull()

            assertTrue(error?.message.orEmpty().contains("free space"))
            assertEquals(0, downloadCalls)
            assertEquals("old-binary", activeBinary().readText())
        }

    @Test
    fun `candidate version mismatch leaves active runtime unchanged`() =
        runTest {
            val updater = updater(candidateVersion = "1.18.9")

            val error = runCatching { updater.prepare(release()) }.exceptionOrNull()

            assertTrue(error?.message.orEmpty().contains("version", ignoreCase = true))
            assertEquals("old-binary", activeBinary().readText())
            assertFalse(runtime.resolve("environment/rootfs/usr/local/bin/opencode.candidate.1.19.0").exists())
        }

    @Test
    fun `successful activation rotates binary and metadata`() =
        runTest {
            val updater = updater()
            val prepared = updater.prepare(release())

            val previous = updater.activate(prepared)

            assertEquals("1.18.3", previous.version)
            assertEquals("new-binary", activeBinary().readText())
            assertEquals("old-binary", rollbackBinary().readText())
            assertEquals("1.19.0", metadata().version)
            assertEquals("1.18.3", rollbackMetadata().version)
            assertEquals("1.18.3", updater.rollbackVersion())
            updater.commitActivation()
            assertFalse(runtime.resolve("update-transaction.json").exists())
        }

    @Test
    fun `update progress uses the default English runtime language`() =
        runTest {
            val labels = mutableListOf<String>()

            updater().prepare(release()) { _, label -> labels += label }

            assertEquals(
                listOf(
                    "Downloading OpenCode 1.19.0",
                    "Downloading OpenCode 1.19.0",
                    "Extracting the update",
                    "Verifying the update candidate",
                    "Update candidate is ready",
                ),
                labels,
            )
        }

    @Test
    fun `interrupted activated update recovers previous version on next startup`() =
        runTest {
            val updater = updater()
            updater.activate(updater.prepare(release()))
            assertEquals("new-binary", activeBinary().readText())
            assertTrue(runtime.resolve("update-transaction.json").isFile)

            val restored = updater.recoverInterruptedActivation()

            assertEquals("1.18.3", restored?.version)
            assertEquals("old-binary", activeBinary().readText())
            assertEquals("1.18.3", metadata().version)
            assertFalse(runtime.resolve("update-transaction.json").exists())
        }

    @Test
    fun `activation failure restores current binary metadata and prior rollback`() =
        runTest {
            rollbackBinary().apply {
                parentFile.mkdirs()
                writeText("older-binary")
            }
            rollbackMetadataFile().writeText(json.encodeToString(metadata("1.17.9")))
            var failed = false
            val updater =
                updater(
                    moveFile = { source, destination ->
                        if (!failed && source.name.startsWith("opencode.candidate.") && destination.name == "opencode") {
                            failed = true
                            error("simulated activation failure")
                        }
                        require(source.renameTo(destination)) { "move failed: $source -> $destination" }
                    },
                )
            val prepared = updater.prepare(release())

            val error = runCatching { updater.activate(prepared) }.exceptionOrNull()

            assertTrue(error?.message.orEmpty().contains("simulated"))
            assertEquals("old-binary", activeBinary().readText())
            assertEquals("1.18.3", metadata().version)
            assertEquals("older-binary", rollbackBinary().readText())
            assertEquals("1.17.9", rollbackMetadata().version)
        }

    @Test
    fun `activation failure after metadata swap restores current runtime`() =
        runTest {
            var failed = false
            val updater =
                updater(
                    moveFile = { source, destination ->
                        if (!failed && source.name.startsWith("metadata.candidate.") && destination.name == "metadata.json") {
                            failed = true
                            error("simulated metadata activation failure")
                        }
                        require(source.renameTo(destination)) { "move failed: $source -> $destination" }
                    },
                )

            val error = runCatching { updater.activate(updater.prepare(release())) }.exceptionOrNull()

            assertTrue(error?.message.orEmpty().contains("metadata"))
            assertEquals("old-binary", activeBinary().readText())
            assertEquals("1.18.3", metadata().version)
            assertFalse(runtime.resolve("update-transaction.json").exists())
        }

    @Test
    fun `interrupted manual rollback recovers pre rollback version pair`() =
        runTest {
            val updater = updater()
            updater.activate(updater.prepare(release()))
            updater.commitActivation()
            runtime.resolve("rollback-transaction.json").writeText(
                """{"currentVersion":"1.19.0","targetVersion":"1.18.3"}""",
            )
            val swap = runtime.resolve("environment/rootfs/usr/local/bin/opencode.swap")
            require(activeBinary().renameTo(swap))
            require(rollbackBinary().renameTo(activeBinary()))
            require(swap.renameTo(rollbackBinary()))

            val restored = updater.recoverInterruptedActivation()

            assertEquals("1.19.0", restored?.version)
            assertEquals("new-binary", activeBinary().readText())
            assertEquals("old-binary", rollbackBinary().readText())
            assertEquals("1.19.0", metadata().version)
            assertEquals("1.18.3", rollbackMetadata().version)
            assertFalse(runtime.resolve("rollback-transaction.json").exists())
        }

    @Test
    fun `manual rollback swaps current and previous versions`() =
        runTest {
            val updater = updater()
            updater.activate(updater.prepare(release()))
            updater.commitActivation()

            val restored = updater.rollback()

            assertEquals("1.18.3", restored.version)
            assertEquals("old-binary", activeBinary().readText())
            assertEquals("new-binary", rollbackBinary().readText())
            assertEquals("1.18.3", metadata().version)
            assertEquals("1.19.0", rollbackMetadata().version)
            assertEquals("1.19.0", updater.rollbackVersion())
            assertTrue(runtime.resolve("rollback-transaction.json").isFile)
            updater.commitActivation()
            assertFalse(runtime.resolve("rollback-transaction.json").exists())
        }

    @Test
    fun `rollback rejects missing rollback version`() =
        runTest {
            val error = runCatching { updater().rollback() }.exceptionOrNull()

            assertTrue(error?.message.orEmpty().contains("unavailable", ignoreCase = true))
            assertEquals("old-binary", activeBinary().readText())
            assertEquals("1.18.3", metadata().version)
        }

    @Test
    fun `rollback rejects identical current and rollback versions`() =
        runTest {
            rollbackBinary().apply {
                parentFile.mkdirs()
                writeText("old-binary")
                setExecutable(true, false)
            }
            rollbackMetadataFile().writeText(json.encodeToString(metadata("1.18.3")))

            val error = runCatching { updater().rollback() }.exceptionOrNull()

            assertTrue(error?.message.orEmpty().contains("identical", ignoreCase = true))
            assertEquals("old-binary", activeBinary().readText())
            assertEquals("1.18.3", metadata().version)
        }

    private fun updater(
        freeBytes: Long = Long.MAX_VALUE,
        candidateVersion: String = "1.19.0",
        download: suspend (LocalRuntimeReleaseAsset, File, (Float?) -> Unit) -> Unit = { _, destination, progress ->
            destination.parentFile?.mkdirs()
            destination.writeText("archive")
            progress(1f)
        },
        moveFile: (File, File) -> Unit = { source, destination ->
            destination.parentFile?.mkdirs()
            require(source.renameTo(destination)) { "move failed: $source -> $destination" }
        },
    ) = LocalRuntimeUpdater(
        runtimeDirectory = runtime,
        abi = "arm64-v8a",
        accessCoordinator = LocalRuntimeAccessCoordinator(),
        freeBytesProvider = { freeBytes },
        downloadAsset = download,
        extractArchive = { _, destination ->
            destination.resolve("nested/opencode").apply {
                parentFile.mkdirs()
                writeText("new-binary")
            }
        },
        candidateVersionProvider = { file ->
            when (file.readText()) {
                "new-binary" -> candidateVersion
                "old-binary" -> "1.18.3"
                "older-binary" -> "1.17.9"
                else -> error("unknown test binary: ${file.name}")
            }
        },
        moveFile = moveFile,
        nowMillis = { 999L },
    )

    private fun release() =
        LocalRuntimeRelease(
            version = "1.19.0",
            releaseNotes = "notes",
            asset =
                LocalRuntimeReleaseAsset(
                    name = "opencode-linux-arm64-musl.tar.gz",
                    url = "https://github.com/anomalyco/opencode/releases/download/v1.19.0/opencode-linux-arm64-musl.tar.gz",
                    sha256 = "a".repeat(64),
                    sizeBytes = 100,
                ),
        )

    private fun writeActive(
        version: String,
        binary: String,
    ) {
        activeBinary().apply {
            parentFile.mkdirs()
            writeText(binary)
            setExecutable(true, false)
        }
        metadataFile().writeText(json.encodeToString(metadata(version)))
    }

    private fun metadata(version: String) =
        LocalRuntimeMetadata(
            version = version,
            port = 4097,
            installedAt = 123,
            runtimeVersion = "2026.07.18.1",
            abi = "arm64-v8a",
        )

    private fun metadata(): LocalRuntimeMetadata = json.decodeFromString<LocalRuntimeMetadata>(metadataFile().readText())

    private fun rollbackMetadata(): LocalRuntimeMetadata = json.decodeFromString<LocalRuntimeMetadata>(rollbackMetadataFile().readText())

    private fun activeBinary() = runtime.resolve("environment/rootfs/usr/local/bin/opencode")

    private fun candidateBinary() = runtime.resolve("environment/rootfs/usr/local/bin/opencode.candidate.1.19.0")

    private fun rollbackBinary() = runtime.resolve("environment/rootfs/usr/local/bin/opencode.rollback")

    private fun metadataFile() = runtime.resolve("metadata.json")

    private fun rollbackMetadataFile() = runtime.resolve("metadata.rollback.json")
}

class VerifiedRuntimeDownloaderTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `http failure preserves existing destination`() =
        runTest {
            val root = createTempDir(prefix = "verified-downloader-http-")
            try {
                val destination = root.resolve("asset.tar.gz").apply { writeText("existing") }
                server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
                val downloader = VerifiedRuntimeDownloader(OkHttpClient())

                val error =
                    runCatching {
                        downloader.download(
                            url = server.url("/asset").toString(),
                            destination = destination,
                            expectedSha256 = "0".repeat(64),
                        )
                    }.exceptionOrNull()

                assertTrue(error?.message.orEmpty().contains("503"))
                assertEquals("existing", destination.readText())
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `sha mismatch deletes partial and does not replace destination`() =
        runTest {
            val root = createTempDir(prefix = "verified-downloader-")
            try {
                val destination = root.resolve("asset.tar.gz")
                destination.writeText("existing")
                server.enqueue(MockResponse().setResponseCode(200).setBody("downloaded"))
                val downloader = VerifiedRuntimeDownloader(OkHttpClient())
                val wrongSha = "0".repeat(64)

                val error =
                    runCatching {
                        downloader.download(
                            url = server.url("/asset").toString(),
                            destination = destination,
                            expectedSha256 = wrongSha,
                            expectedSizeBytes = "downloaded".toByteArray().size.toLong(),
                        )
                    }.exceptionOrNull()

                assertTrue(error?.message.orEmpty().contains("SHA-256"))
                assertEquals("existing", destination.readText())
                assertFalse(root.resolve("asset.tar.gz.partial").exists())
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `partial download resumes with an HTTP range request`() =
        runTest {
            val root = createTempDir(prefix = "verified-downloader-resume-")
            try {
                val destination = root.resolve("asset.tar.gz")
                root.resolve("asset.tar.gz.partial").writeText("hello ")
                val payload = "hello world"
                server.enqueue(
                    MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes 6-10/11")
                        .setBody("world"),
                )

                VerifiedRuntimeDownloader(OkHttpClient()).download(
                    url = server.url("/asset").toString(),
                    destination = destination,
                    expectedSha256 = sha256(payload),
                    expectedSizeBytes = payload.toByteArray().size.toLong(),
                )

                assertEquals("bytes=6-", server.takeRequest().getHeader("Range"))
                assertEquals(payload, destination.readText())
                assertFalse(root.resolve("asset.tar.gz.partial").exists())
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `server ignoring range restarts the partial download`() =
        runTest {
            val root = createTempDir(prefix = "verified-downloader-restart-")
            try {
                val destination = root.resolve("asset.tar.gz")
                root.resolve("asset.tar.gz.partial").writeText("stale prefix")
                val payload = "complete payload"
                server.enqueue(MockResponse().setResponseCode(200).setBody(payload))

                VerifiedRuntimeDownloader(OkHttpClient()).download(
                    url = server.url("/asset").toString(),
                    destination = destination,
                    expectedSha256 = sha256(payload),
                    expectedSizeBytes = payload.toByteArray().size.toLong(),
                )

                assertEquals("bytes=12-", server.takeRequest().getHeader("Range"))
                assertEquals(payload, destination.readText())
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `interrupted response retains bytes and the next attempt resumes`() =
        runTest {
            val root = kotlin.io.path.createTempDirectory("verified-downloader-interrupted-").toFile()
            try {
                val destination = root.resolve("asset.tar.gz").apply { writeText("existing") }
                val partial = root.resolve("asset.tar.gz.partial")
                val payload = "0123456789abcdef".repeat(8192)
                val downloader = VerifiedRuntimeDownloader(OkHttpClient())
                server.enqueue(MockResponse().setBody(payload).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))

                val failure =
                    runCatching {
                        downloader.download(server.url("/asset").toString(), destination, sha256(payload), payload.length.toLong())
                    }.exceptionOrNull()

                assertTrue(failure is IOException)
                assertEquals("existing", destination.readText())
                val offset = partial.length()
                assertTrue(offset in 1 until payload.length.toLong())
                assertEquals(payload.take(offset.toInt()), partial.readText())
                server.takeRequest()
                server.enqueue(
                    MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes $offset-${payload.lastIndex}/${payload.length}")
                        .setBody(payload.drop(offset.toInt())),
                )

                downloader.download(server.url("/asset").toString(), destination, sha256(payload), payload.length.toLong())

                assertEquals("bytes=$offset-", server.takeRequest().getHeader("Range"))
                assertEquals(payload, destination.readText())
                assertFalse(partial.exists())
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `invalid range discards partial without replacing the active file`() =
        runTest {
            val root = kotlin.io.path.createTempDirectory("verified-downloader-bad-range-").toFile()
            try {
                val destination = root.resolve("asset.tar.gz").apply { writeText("existing") }
                val partial = root.resolve("asset.tar.gz.partial").apply { writeText("hello ") }
                server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-4/11").setBody("world"))

                val failure =
                    runCatching {
                        VerifiedRuntimeDownloader(OkHttpClient()).download(
                            server.url("/asset").toString(),
                            destination,
                            sha256("hello world"),
                            11L,
                        )
                    }.exceptionOrNull()

                assertTrue(failure?.message.orEmpty().contains("Content-Range"))
                assertEquals("existing", destination.readText())
                assertFalse(partial.exists())
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `complete partial download is reused without another request`() =
        runTest {
            val root = createTempDir(prefix = "verified-downloader-complete-partial-")
            try {
                val destination = root.resolve("asset.tar.gz")
                val payload = "complete payload"
                root.resolve("asset.tar.gz.partial").writeText(payload)

                VerifiedRuntimeDownloader(OkHttpClient()).download(
                    url = server.url("/asset").toString(),
                    destination = destination,
                    expectedSha256 = sha256(payload),
                )

                assertEquals(payload, destination.readText())
                assertEquals(0, server.requestCount)
                assertFalse(root.resolve("asset.tar.gz.partial").exists())
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `range not satisfiable retries once from byte zero`() =
        runTest {
            val root = createTempDir(prefix = "verified-downloader-range-retry-")
            try {
                val destination = root.resolve("asset.tar.gz")
                root.resolve("asset.tar.gz.partial").writeText("stale")
                val payload = "complete payload"
                server.enqueue(MockResponse().setResponseCode(416))
                server.enqueue(MockResponse().setResponseCode(200).setBody(payload))

                VerifiedRuntimeDownloader(OkHttpClient()).download(
                    url = server.url("/asset").toString(),
                    destination = destination,
                    expectedSha256 = sha256(payload),
                )

                assertEquals("bytes=5-", server.takeRequest().getHeader("Range"))
                assertEquals(null, server.takeRequest().getHeader("Range"))
                assertEquals(payload, destination.readText())
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `failed retry preserves a preexisting backup`() =
        runTest {
            val root = createTempDir(prefix = "verified-downloader-backup-")
            try {
                val destination = root.resolve("asset.tar.gz").apply { writeText("unverified-current") }
                val backup = root.resolve("asset.tar.gz.backup").apply { writeText("last-known-good") }
                server.enqueue(MockResponse().setResponseCode(200).setBody("bad-new-download"))
                val downloader = VerifiedRuntimeDownloader(OkHttpClient())

                runCatching {
                    downloader.download(
                        url = server.url("/asset").toString(),
                        destination = destination,
                        expectedSha256 = "0".repeat(64),
                        expectedSizeBytes = "bad-new-download".toByteArray().size.toLong(),
                    )
                }

                assertEquals("unverified-current", destination.readText())
                assertEquals("last-known-good", backup.readText())
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `successful retry removes stale backup`() =
        runTest {
            val root = createTempDir(prefix = "verified-downloader-stale-")
            try {
                val destination = root.resolve("asset.tar.gz").apply { writeText("unverified-current") }
                val backup = root.resolve("asset.tar.gz.backup").apply { writeText("last-known-good") }
                val payload = "verified-new"
                server.enqueue(MockResponse().setResponseCode(200).setBody(payload))
                val downloader = VerifiedRuntimeDownloader(OkHttpClient())

                downloader.download(
                    url = server.url("/asset").toString(),
                    destination = destination,
                    expectedSha256 = sha256(payload),
                    expectedSizeBytes = payload.toByteArray().size.toLong(),
                )

                assertEquals(payload, destination.readText())
                assertFalse(backup.exists())
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `verified download atomically replaces destination`() =
        runTest {
            val root = createTempDir(prefix = "verified-downloader-")
            try {
                val destination = root.resolve("asset.tar.gz")
                destination.writeText("old")
                val payload = "verified-payload"
                server.enqueue(MockResponse().setResponseCode(200).setBody(payload))
                val downloader = VerifiedRuntimeDownloader(OkHttpClient())

                downloader.download(
                    url = server.url("/asset").toString(),
                    destination = destination,
                    expectedSha256 = sha256(payload),
                    expectedSizeBytes = payload.toByteArray().size.toLong(),
                )

                assertEquals(payload, destination.readText())
                assertFalse(root.resolve("asset.tar.gz.partial").exists())
            } finally {
                root.deleteRecursively()
            }
        }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
