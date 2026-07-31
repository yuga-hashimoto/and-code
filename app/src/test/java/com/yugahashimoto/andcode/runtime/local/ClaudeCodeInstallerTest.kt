package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClaudeCodeInstallerTest {
    @Test
    fun `extractApkErrors keeps ERROR and WARNING and summary lines`() {
        val log =
            """
            v3.24.1-299 [https://example/main]
            OK: 28595 distinct packages available
            ERROR: unable to select packages:
              claude-code-2.1.108-r1:
            WARNING: something went wrong
            1 error; 2322.8 MiB in 392 packages
            """.trimIndent()
        val errors = ClaudeCodeInstaller.extractApkErrors(log)
        assertTrue(errors.contains("ERROR: unable to select packages:"))
        assertTrue(errors.contains("WARNING: something went wrong"))
        assertTrue(errors.contains("1 error; 2322.8 MiB in 392 packages"))
    }

    @Test
    fun `extractApkErrors drops fetch and progress lines even when they contain a keyword`() {
        val log =
            """
            fetch https://dl-cdn.alpinelinux.org/alpine/v3.24/main/aarch64/error/APKINDEX.tar.gz
            (12/392) Installing error-handler (1.0-r0)
            ERROR: boom
            """.trimIndent()
        val errors = ClaudeCodeInstaller.extractApkErrors(log)
        assertFalse(errors.contains("fetch "))
        assertFalse(errors.contains("(12/392)"))
        assertTrue(errors.contains("ERROR: boom"))
    }

    @Test
    fun `extractApkErrors returns empty when nothing matches`() {
        assertEquals("", ClaudeCodeInstaller.extractApkErrors("OK: 100 distinct packages available\n"))
    }

    @Test
    fun `failureMessage puts the primary error on the first line so compact views show it`() {
        val log =
            File.createTempFile("claude-install", ".log").apply {
                deleteOnExit()
                writeText(
                    "OK: 28595 distinct packages available\nERROR: unable to select packages:\n1 error; 2322.8 MiB in 392 packages\n",
                )
            }
        val message = ClaudeCodeInstaller.failureMessage("installation", 1, log)
        val firstLine = message.lineSequence().first()
        assertTrue(
            firstLine.startsWith("Claude Code installation failed (exit 1): ERROR: unable to select packages:"),
        )
        assertTrue(message.contains("--- log tail ---"))
    }
}
