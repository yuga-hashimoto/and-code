package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * The installer is release-agnostic: whatever [AntigravityRelease] the caller resolved — GitHub's
 * latest, or this build's pin — is what gets downloaded, verified and recorded.
 */
class AntigravityInstallerTest {
    @get:Rule val folder = TemporaryFolder()

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
    fun `installs the resolved release and records its version`() =
        runTest {
            val archive = tarGzWithBinary()
            server.enqueue(MockResponse().setBody(Buffer().write(archive)))
            val rootfs = folder.newFolder("rootfs")
            val installer = AntigravityInstaller(folder.root, VerifiedRuntimeDownloader(OkHttpClient()))

            val installed =
                installer.installInto(
                    rootfs,
                    release =
                        AntigravityRelease(
                            version = "1.1.17",
                            asset = asset(url = server.url("/agy_cli_linux_arm64.tar.gz").toString(), bytes = archive),
                        ),
                )

            assertEquals("1.1.17", installed)
            val binary = rootfs.resolve("usr/local/bin/agy")
            assertTrue(binary.isFile)
            assertTrue(binary.canExecute())
            assertEquals("1.1.17", AntigravityInstaller.installedVersion(rootfs))
            // The verified archive is consumed once installed, so nothing stale is left behind.
            assertTrue(java.io.File(folder.root, "cache").list()!!.isEmpty())
        }

    @Test
    fun `an install failure leaves the previous binary in place`() =
        runTest {
            val first = tarGzWithBinary()
            server.enqueue(MockResponse().setBody(Buffer().write(first)))
            val rootfs = folder.newFolder("rootfs")
            val installer = AntigravityInstaller(folder.root, VerifiedRuntimeDownloader(OkHttpClient()))
            installer.installInto(
                rootfs,
                release = AntigravityRelease("1.0.0", asset(url = server.url("/a").toString(), bytes = first)),
            )

            server.enqueue(MockResponse().setResponseCode(500))

            val error =
                runCatching {
                    installer.installInto(rootfs, release = AntigravityRelease("1.1.0", asset(url = server.url("/b").toString())))
                }.exceptionOrNull()

            assertTrue(error != null)
            assertEquals("1.0.0", AntigravityInstaller.installedVersion(rootfs))
            assertTrue(rootfs.resolve("usr/local/bin/agy").isFile)
        }

    @Test
    fun `the insufficient space message reports the 800 MB gate and available bytes`() {
        val message = AntigravityInstaller.insufficientSpaceMessage(123_456_789L)

        assertTrue(message.contains("800 MB"))
        assertTrue(message.contains("123456789"))
    }

    private fun asset(
        url: String,
        bytes: ByteArray? = null,
    ): AntigravityAsset {
        val body = bytes ?: ByteArray(64)
        return AntigravityAsset(
            name = "agy_cli_linux_arm64.tar.gz",
            url = url,
            sha256 = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) },
            sizeBytes = body.size.toLong(),
        )
    }

    /** A minimal stand-in for Google's release archive: one executable file named `antigravity`. */
    private fun tarGzWithBinary(): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(GzipCompressorOutputStream(bytes)).use { tar ->
            val payload = "#!/bin/sh\necho agy\n".toByteArray()
            tar.putArchiveEntry(
                TarArchiveEntry("antigravity").apply {
                    size = payload.size.toLong()
                    mode = 0b111_101_101
                },
            )
            tar.write(payload)
            tar.closeArchiveEntry()
        }
        return bytes.toByteArray()
    }
}
