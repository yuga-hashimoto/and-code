package com.yugahashimoto.andcode.runtime.local

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

class PlayApkCacheTest {
    @Test
    fun `verified bundle replaces only the dedicated cache`() {
        val parent = Files.createTempDirectory("play-apk-cache").toFile()
        try {
            val target = parent.resolve("play-apk")
            target.mkdirs()
            target.resolve("old.apk").writeText("old")
            val shared = parent.resolve("apk").apply { mkdirs() }
            shared.resolve("user.apk").writeText("user data")
            val bytes = archive("package.apk", "verified package")
            PlayApkCache.prepare({ bytes.inputStream() }, digest(bytes), target)
            assertEquals("verified package", target.resolve("package.apk").readText())
            assertFalse(target.resolve("old.apk").exists())
            assertEquals("user data", shared.resolve("user.apk").readText())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `corrupt bundle preserves prior cache`() {
        val parent = Files.createTempDirectory("play-apk-cache").toFile()
        try {
            val target = parent.resolve("play-apk").apply { mkdirs() }
            target.resolve("old.apk").writeText("old")
            val bytes = archive("package.apk", "corrupt")
            assertThrows(Exception::class.java) {
                PlayApkCache.prepare({ bytes.inputStream() }, digest("other".toByteArray()), target)
            }
            assertEquals("old", target.resolve("old.apk").readText())
        } finally {
            parent.deleteRecursively()
        }
    }

    private fun archive(
        name: String,
        content: String,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                val bytes = content.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        return output.toByteArray()
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
