package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalRuntimeDiagnosticsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `collects storage process logs and required tool checks`() {
        val runtime = temporaryFolder.newFolder("runtime")
        runtime.resolve("environment/rootfs/usr/local/bin").mkdirs()
        runtime.resolve("environment/rootfs/usr/local/bin/opencode").writeBytes(ByteArray(10))
        runtime.resolve("cache/archive.tar.gz").apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(20))
        }
        runtime.resolve("logs/opencode-local.log").apply {
            parentFile.mkdirs()
            writeText("first\nsecond\nsecret-free-last\n")
        }
        val outputs =
            mapOf(
                "opencode" to LocalRuntimeCommandResult(0, "1.18.3"),
                "git" to LocalRuntimeCommandResult(0, "git version 2.54.0"),
                "bash" to LocalRuntimeCommandResult(0, "GNU bash, version 5.3.9"),
                "curl" to LocalRuntimeCommandResult(0, "curl 8.17.0"),
                "ssh" to LocalRuntimeCommandResult(127, "ssh: not found"),
                "rg" to LocalRuntimeCommandResult(0, "ripgrep 15.1.0"),
                "ca-certificates" to LocalRuntimeCommandResult(0, "installed"),
            )
        val collector =
            LocalRuntimeDiagnosticsCollector(
                runtimeDirectory = runtime,
                abi = "arm64-v8a",
                statusProvider = { LocalRuntimeStatus.Ready("1.18.3", 4097) },
                freeBytesProvider = { 500L },
                processMetricsProvider = {
                    LocalRuntimeProcessMetrics(pid = 42L, rssBytes = 123L, uptimeMillis = 4_000L)
                },
                commandExecutor = { check -> outputs.getValue(check.id) },
                nowMillis = { 10_000L },
                maxLogCharacters = 18,
            )

        val result = collector.collect()

        assertEquals("1.18.3", result.version)
        assertEquals("arm64-v8a", result.abi)
        assertEquals(4097, result.port)
        assertEquals(30L + "first\nsecond\nsecret-free-last\n".toByteArray().size, result.runtimeBytes)
        assertEquals(500L, result.freeBytes)
        assertEquals(42L, result.process?.pid)
        assertEquals(123L, result.process?.rssBytes)
        assertEquals(4_000L, result.process?.uptimeMillis)
        assertTrue(result.logTail.endsWith("secret-free-last\n"))
        assertTrue(result.logTail.length <= 18)
        assertTrue(result.tools.first { it.id == "git" }.available)
        assertFalse(result.tools.first { it.id == "ssh" }.available)
        assertEquals("ssh: not found", result.tools.first { it.id == "ssh" }.detail)
        assertEquals(10_000L, result.collectedAtMillis)
    }

    @Test
    fun `not installed skips guest commands and returns empty runtime metrics`() {
        val runtime = temporaryFolder.newFolder("not-installed")
        var commandCalls = 0
        val collector =
            LocalRuntimeDiagnosticsCollector(
                runtimeDirectory = runtime,
                abi = "x86_64",
                statusProvider = { LocalRuntimeStatus.NotInstalled },
                freeBytesProvider = { 900L },
                processMetricsProvider = { null },
                commandExecutor = {
                    commandCalls++
                    LocalRuntimeCommandResult(0, "unexpected")
                },
            )

        val result = collector.collect()

        assertEquals(0, commandCalls)
        assertEquals(0L, result.runtimeBytes)
        assertEquals(900L, result.freeBytes)
        assertTrue(result.tools.all { !it.available && it.detail == "Not installed" })
        assertTrue(result.logTail.isEmpty())
    }

    @Test
    fun `sums resident memory for the full runtime process tree`() {
        val statuses =
            mapOf(
                10L to "VmRSS:\t100 kB\n",
                11L to "VmRSS:\t400000 kB\n",
                12L to "VmRSS:\t5000 kB\n",
            )
        val children =
            mapOf(
                10L to listOf(11L),
                11L to listOf(12L),
                12L to emptyList(),
            )

        val result =
            totalResidentSetBytes(
                rootPid = 10L,
                statusReader = { statuses[it] },
                childrenReader = { children[it].orEmpty() },
            )

        assertEquals((100L + 400_000L + 5_000L) * 1024L, result)
    }

    @Test
    fun `discovers direct children by parent id from android proc status files`() {
        val proc = temporaryFolder.newFolder("proc")
        proc.resolve("10/status").apply {
            parentFile.mkdirs()
            writeText("Name:\tparent\nPPid:\t1\n")
        }
        proc.resolve("11/status").apply {
            parentFile.mkdirs()
            writeText("Name:\tchild\nPPid:\t10\n")
        }
        proc.resolve("12/status").apply {
            parentFile.mkdirs()
            writeText("Name:\tother\nPPid:\t2\n")
        }

        assertEquals(listOf(11L), readDirectChildPids(10L, proc))
    }

    @Test
    fun `parses resident memory from proc status`() {
        assertEquals(
            12_345L * 1024L,
            parseResidentSetBytes("Name:\topencode\nVmRSS:\t   12345 kB\nThreads:\t10\n"),
        )
        assertEquals(null, parseResidentSetBytes("Name:\topencode\n"))
    }
}
