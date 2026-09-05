package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalRuntimeManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `arm64 without metadata is not installed`() {
        val manager =
            LocalRuntimeManager(
                runtimeDirectory = temporaryFolder.root,
                abi = "arm64-v8a",
                portProbe = { false },
            )

        assertEquals(LocalRuntimeStatus.NotInstalled, manager.status())
    }

    @Test
    fun `unsupported abi is reported`() {
        val manager =
            LocalRuntimeManager(
                runtimeDirectory = temporaryFolder.root,
                abi = "armeabi-v7a",
                portProbe = { false },
            )

        assertEquals(LocalRuntimeStatus.UnsupportedAbi("armeabi-v7a"), manager.status())
    }

    @Test
    fun `corrupt metadata is broken`() {
        temporaryFolder.newFile("metadata.json").writeText("not-json")
        val manager =
            LocalRuntimeManager(
                runtimeDirectory = temporaryFolder.root,
                abi = "arm64-v8a",
                portProbe = { false },
            )

        assertTrue(manager.status() is LocalRuntimeStatus.Broken)
    }

    @Test
    fun `healthy metadata and port probe are ready`() {
        createRuntimeFiles()
        temporaryFolder.newFile("metadata.json").writeText(
            """{"version":"1.17.20","port":4096,"installedAt":123}""",
        )
        val manager =
            LocalRuntimeManager(
                runtimeDirectory = temporaryFolder.root,
                abi = "arm64-v8a",
                portProbe = { port -> port == 4096 },
            )

        assertEquals(LocalRuntimeStatus.Ready("1.17.20", 4096), manager.status())
    }

    @Test
    fun `a running process that missed the probe stays ready`() {
        createRuntimeFiles()
        temporaryFolder.newFile("metadata.json").writeText(
            """{"version":"1.17.20","port":4096,"installedAt":123}""",
        )
        val manager =
            LocalRuntimeManager(
                runtimeDirectory = temporaryFolder.root,
                abi = "arm64-v8a",
                portProbe = { false },
                processAlive = { true },
            )

        // The probe misses when the device is loaded — a Claude Code turn is enough. Calling that a
        // dead server had the watchdog restart a healthy OpenCode, which is what looked like a crash.
        assertEquals(LocalRuntimeStatus.Ready("1.17.20", 4096), manager.status())
    }

    @Test
    fun `metadata without a listening server is stopped`() {
        createRuntimeFiles()
        temporaryFolder.newFile("metadata.json").writeText(
            """{"version":"1.17.20","port":4096,"installedAt":123}""",
        )
        val manager =
            LocalRuntimeManager(
                runtimeDirectory = temporaryFolder.root,
                abi = "arm64-v8a",
                portProbe = { false },
            )

        assertEquals(LocalRuntimeStatus.Stopped("1.17.20", 4096), manager.status())
    }

    @Test
    fun `legacy metadata defaults to the full development toolchain`() {
        val metadata =
            Json.decodeFromString<LocalRuntimeMetadata>(
                """{"version":"1.17.20","port":4096,"installedAt":123}""",
            )

        assertTrue(metadata.fullDevelopmentToolsInstalled)
        assertTrue(metadata.hasFullDevelopmentTools())
    }

    @Test
    fun `legacy Antigravity runtime still offers the Debian toolchain`() {
        val metadata =
            Json.decodeFromString<LocalRuntimeMetadata>(
                """{"version":"1.17.20","port":4096,"installedAt":123,"components":["opencode","antigravity"]}""",
            )

        assertTrue(metadata.fullDevelopmentToolsInstalled)
        assertFalse(metadata.hasFullDevelopmentTools())
        assertTrue(metadata.copy(fullDebianDevelopmentToolsInstalled = true).hasFullDevelopmentTools())
        assertTrue(metadata.without(LocalAgent.ANTIGRAVITY).hasFullDevelopmentTools())
    }

    @Test
    fun `delete runtime removes every managed file and returns not installed`() =
        runBlocking {
            val runtime = temporaryFolder.newFolder("managed-runtime")
            runtime.resolve("metadata.json").writeText(
                """{"version":"1.18.3","port":4097,"installedAt":123}""",
            )
            runtime.resolve("environment/rootfs/usr/local/bin/opencode").apply {
                parentFile.mkdirs()
                writeText("binary")
            }
            runtime.resolve("cache/archive.tar.gz").apply {
                parentFile.mkdirs()
                writeText("cache")
            }
            runtime.resolve("logs/opencode-local.log").apply {
                parentFile.mkdirs()
                writeText("log")
            }
            runtime.resolve("workspace/project/file.txt").apply {
                parentFile.mkdirs()
                writeText("workspace")
            }
            val manager =
                LocalRuntimeManager(
                    runtimeDirectory = runtime,
                    abi = "arm64-v8a",
                    portProbe = { false },
                )

            val result = manager.deleteRuntime()

            assertTrue(result.isSuccess)
            assertEquals(LocalRuntimeStatus.NotInstalled, result.getOrNull())
            assertTrue(!runtime.exists())
            assertEquals(LocalRuntimeStatus.NotInstalled, manager.status())
        }

    private fun createRuntimeFiles() {
        val binary = temporaryFolder.root.resolve("environment/rootfs/usr/local/bin/opencode")
        binary.parentFile.mkdirs()
        binary.writeText("binary")
    }
}
