package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

class BundledRuntimeArchiveTest {
    @Test
    fun `copies and verifies bundled archive`() {
        val dir = Files.createTempDirectory("bundled-runtime").toFile()
        try {
            val data = "verified payload".toByteArray()
            val target = dir.resolve("alpine.tar.gz")
            BundledRuntimeArchive.copyVerified({ data.inputStream() }, target, digest(data))
            assertArrayEquals(data, target.readBytes())
            assertFalse(dir.resolve("alpine.tar.gz.bundled-partial").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `checksum mismatch preserves old destination and deletes partial file`() {
        val dir = Files.createTempDirectory("bundled-runtime").toFile()
        try {
            val target = dir.resolve("alpine.tar.gz")
            target.writeText("old")
            assertThrows(Exception::class.java) {
                BundledRuntimeArchive.copyVerified({ "corrupt".byteInputStream() }, target, digest("expected".toByteArray()))
            }
            assertArrayEquals("old".toByteArray(), target.readBytes())
            assertFalse(dir.resolve("alpine.tar.gz.bundled-partial").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
