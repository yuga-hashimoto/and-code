package com.yugahashimoto.andcode.feature.wakeword

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VoskModelInstallerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var installer: VoskModelInstaller
    private lateinit var root: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        root = temporaryFolder.newFolder("models")
        installer = VoskModelInstaller(OkHttpClient(), root)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun spec(directoryName: String = "vosk-model-small-en-us-0.15") =
        VoskModelSpec(
            language = VoskModelLanguage.ENGLISH,
            directoryName = directoryName,
            downloadUrl = server.url("/model.zip").toString(),
            approximateBytes = 1024,
        )

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                if (!name.endsWith("/")) zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    /** The shape Vosk actually needs: an `am` and a `conf` directory under the model root. */
    private fun modelZip(root: String = "vosk-model-small-en-us-0.15") =
        zipOf(
            "$root/" to "",
            "$root/am/final.mdl" to "model",
            "$root/conf/mfcc.conf" to "conf",
        )

    private fun respondWith(body: ByteArray) {
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
    }

    private fun limitedInstaller(
        archiveBytes: Long = 100L * 1024 * 1024,
        expandedBytes: Long = 250L * 1024 * 1024,
        entries: Int = 20_000,
    ) = VoskModelInstaller(
        OkHttpClient(),
        root,
        maxArchiveBytes = archiveBytes,
        maxExpandedBytes = expandedBytes,
        maxEntryCount = entries,
    )

    @Test
    fun `a downloaded archive lands as a usable model directory`() =
        runTest {
            respondWith(modelZip())

            val result = installer.install(spec())

            assertTrue(result.isSuccess)
            val installed = result.getOrThrow()
            assertTrue(File(installed, "am/final.mdl").isFile)
            assertEquals("model", File(installed, "am/final.mdl").readText())
            assertTrue(installer.isInstalled(spec()))
        }

    @Test
    fun `an entry pointing outside the target is refused`() =
        runTest {
            // A zip is an untrusted archive from the network; "../" entries are the standard way
            // one gets to write wherever it likes in the app's storage.
            respondWith(
                zipOf(
                    "vosk-model-small-en-us-0.15/am/final.mdl" to "model",
                    "vosk-model-small-en-us-0.15/conf/mfcc.conf" to "conf",
                    "../escaped.txt" to "owned",
                ),
            )

            val result = installer.install(spec())

            assertTrue(result.isFailure)
            assertFalse(File(root.parentFile, "escaped.txt").exists())
            assertFalse(installer.isInstalled(spec()))
        }

    @Test
    fun `an archive without the expected directory is refused`() =
        runTest {
            respondWith(modelZip(root = "some-other-model"))

            val result = installer.install(spec())

            assertTrue(result.isFailure)
            assertFalse(installer.isInstalled(spec()))
        }

    @Test
    fun `a rejected download leaves nothing half-installed behind`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404))

            val result = installer.install(spec())

            assertTrue(result.isFailure)
            assertFalse(installer.isInstalled(spec()))
            assertEquals(emptyList<String>(), root.list()?.toList().orEmpty())
        }

    @Test
    fun `chunked archive exceeding the download limit is refused`() =
        runTest {
            val body = modelZip()
            server.enqueue(MockResponse().setChunkedBody(Buffer().write(body), 1))

            val result = limitedInstaller(archiveBytes = 1).install(spec())

            assertTrue(result.isFailure)
            assertFalse(limitedInstaller().isInstalled(spec()))
        }

    @Test
    fun `archive exceeding expanded limit is refused`() =
        runTest {
            respondWith(modelZip())

            val result = limitedInstaller(expandedBytes = 1).install(spec())

            assertTrue(result.isFailure)
            assertFalse(limitedInstaller().isInstalled(spec()))
        }

    @Test
    fun `archive exceeding entry limit is refused`() =
        runTest {
            respondWith(modelZip())

            val result = limitedInstaller(entries = 1).install(spec())

            assertTrue(result.isFailure)
            assertFalse(limitedInstaller().isInstalled(spec()))
        }

    @Test
    fun `progress is reported and ends at complete`() =
        runTest {
            respondWith(modelZip())
            val seen = mutableListOf<VoskInstallProgress>()

            installer.install(spec()) { seen += it }

            assertTrue(seen.toString(), seen.any { it is VoskInstallProgress.Downloading })
            assertTrue(seen.toString(), seen.any { it is VoskInstallProgress.Extracting })
        }

    @Test
    fun `an already installed model is not downloaded again`() =
        runTest {
            respondWith(modelZip())
            installer.install(spec())

            val second = installer.install(spec())

            assertTrue(second.isSuccess)
            // One request in total: the second call answered from what is already on disk.
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `removing a model takes its directory with it`() =
        runTest {
            respondWith(modelZip())
            installer.install(spec())

            installer.remove(spec())

            assertFalse(installer.isInstalled(spec()))
        }
}
