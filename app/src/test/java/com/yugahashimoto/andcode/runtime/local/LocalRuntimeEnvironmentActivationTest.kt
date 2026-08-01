package com.yugahashimoto.andcode.runtime.local

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class LocalRuntimeEnvironmentActivationTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `failed staging activation restores previous environment`() {
        val root = temporaryFolder.newFolder("runtime")
        val active =
            root.resolve("environment").apply {
                mkdirs()
                resolve("marker.txt").writeText("old")
            }
        val staging =
            root.resolve("environment.staging").apply {
                mkdirs()
                resolve("marker.txt").writeText("new")
            }
        val rollback = root.resolve("environment.rollback")
        var failed = false

        val error =
            runCatching {
                activateRuntimeEnvironment(
                    active = active,
                    staging = staging,
                    rollback = rollback,
                    moveDirectory = { source, destination ->
                        if (!failed && source.name == "environment.staging") {
                            failed = true
                            error("simulated activation failure")
                        }
                        require(source.renameTo(destination)) {
                            "move failed: $source -> $destination"
                        }
                    },
                    finalizeActivation = { error("must not finalize") },
                )
            }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("simulated"))
        assertEquals("old", active.resolve("marker.txt").readText())
        assertFalse(rollback.exists())
        assertTrue(staging.isDirectory)
        assertEquals("new", staging.resolve("marker.txt").readText())
    }

    @Test
    fun `finalization failure restores previous environment and removes failed active`() {
        val root = temporaryFolder.newFolder("runtime-finalizer")
        val active =
            root.resolve("environment").apply {
                mkdirs()
                resolve("marker.txt").writeText("old")
            }
        val staging =
            root.resolve("environment.staging").apply {
                mkdirs()
                resolve("marker.txt").writeText("new")
            }
        val rollback = root.resolve("environment.rollback")

        val error =
            runCatching {
                activateRuntimeEnvironment(
                    active = active,
                    staging = staging,
                    rollback = rollback,
                    finalizeActivation = { error("metadata finalization failed") },
                )
            }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("metadata"))
        assertEquals("old", active.resolve("marker.txt").readText())
        assertFalse(rollback.exists())
        assertFalse(staging.exists())
    }

    @Test
    fun `interrupted activation restores rollback environment and top metadata`() {
        val root = temporaryFolder.newFolder("runtime-interrupted")
        val active =
            root.resolve("environment").apply {
                mkdirs()
                resolve("marker.txt").writeText("new")
                resolve("metadata.json").writeText(json.encodeToString(metadata("1.19.0")))
            }
        val rollback =
            root.resolve("environment.rollback").apply {
                mkdirs()
                resolve("marker.txt").writeText("old")
                resolve("metadata.json").writeText(json.encodeToString(metadata("1.18.3")))
            }
        val topMetadata =
            root.resolve("metadata.json").apply {
                writeText(json.encodeToString(metadata("1.19.0")))
            }

        val recovered =
            recoverInterruptedRuntimeEnvironment(
                active = active,
                rollback = rollback,
                topLevelMetadata = topMetadata,
            )

        assertTrue(recovered)
        assertEquals("old", active.resolve("marker.txt").readText())
        assertEquals(
            "1.18.3",
            json.decodeFromString<LocalRuntimeMetadata>(topMetadata.readText()).version,
        )
        assertFalse(rollback.exists())
        assertFalse(root.resolve("environment.failed").exists())
    }

    @Test
    fun `successful activation removes rollback only after finalization`() {
        val root = temporaryFolder.newFolder("runtime-success")
        val active =
            root.resolve("environment").apply {
                mkdirs()
                resolve("marker.txt").writeText("old")
            }
        val staging =
            root.resolve("environment.staging").apply {
                mkdirs()
                resolve("marker.txt").writeText("new")
            }
        val rollback = root.resolve("environment.rollback")
        var observedRollback = false

        activateRuntimeEnvironment(
            active = active,
            staging = staging,
            rollback = rollback,
            finalizeActivation = {
                observedRollback = rollback.resolve("marker.txt").readText() == "old"
                assertEquals("new", it.resolve("marker.txt").readText())
            },
        )

        assertTrue(observedRollback)
        assertEquals("new", active.resolve("marker.txt").readText())
        assertFalse(rollback.exists())
        assertFalse(staging.exists())
    }

    @Test
    fun `activation converts proot hard link symlinks to relative paths`() {
        val root = temporaryFolder.newFolder("runtime-links")
        val active = root.resolve("environment")
        val staging = root.resolve("environment.staging").apply { mkdirs() }
        val bin = staging.resolve("rootfs/usr/bin").apply { mkdirs() }
        val backing = bin.resolve(".l2s.unzip.0002").apply { writeText("binary") }
        val intermediary = bin.resolve(".l2s.unzip")
        val executable = bin.resolve("unzip")
        Files.createSymbolicLink(intermediary.toPath(), backing.toPath().toAbsolutePath())
        Files.createSymbolicLink(executable.toPath(), intermediary.toPath().toAbsolutePath())

        activateRuntimeEnvironment(
            active = active,
            staging = staging,
            rollback = root.resolve("environment.rollback"),
            finalizeActivation = {},
        )

        val activatedExecutable = active.resolve("rootfs/usr/bin/unzip")
        assertFalse(Files.readSymbolicLink(activatedExecutable.toPath()).isAbsolute)
        assertEquals("binary", activatedExecutable.readText())
    }

    @Test
    fun `normalization repairs links left behind by an older staging activation`() {
        val root = temporaryFolder.newFolder("runtime-stale-links")
        val active = root.resolve("environment").apply { mkdirs() }
        val bin = active.resolve("rootfs/usr/bin").apply { mkdirs() }
        val backing = bin.resolve(".l2s.unzip.0002").apply { writeText("binary") }
        val staleRoot = root.resolve("environment.staging").toPath().toAbsolutePath()
        val executable = bin.resolve("unzip")
        Files.createSymbolicLink(
            executable.toPath(),
            staleRoot.resolve("rootfs/usr/bin/${backing.name}"),
        )

        normalizeRuntimeSymlinks(active)

        assertFalse(Files.readSymbolicLink(executable.toPath()).isAbsolute)
        assertEquals("binary", executable.readText())
        assertTrue(active.resolve(".internal-symlinks-relative").isFile)
    }

    @Test
    fun `normalization skips directories it cannot open`() {
        val root = temporaryFolder.newFolder("runtime-unreadable")
        val active = root.resolve("environment").apply { mkdirs() }
        val rootfs = active.resolve("rootfs").apply { mkdirs() }
        // PRoot's bind-mount points come out of the guest tarball with no permissions at all, and
        // the app's own uid cannot open them either. Walking must skip them, not give up.
        val mountPoint = rootfs.resolve("system").apply { mkdirs() }
        assumeTrue(mountPoint.setReadable(false, false) && !mountPoint.canRead())
        val bin = rootfs.resolve("usr/bin").apply { mkdirs() }
        val backing = bin.resolve(".l2s.unzip.0002").apply { writeText("binary") }
        val executable = bin.resolve("unzip")
        Files.createSymbolicLink(
            executable.toPath(),
            root.resolve("environment.staging").toPath().toAbsolutePath().resolve("rootfs/usr/bin/${backing.name}"),
        )

        try {
            normalizeRuntimeSymlinks(active)
        } finally {
            mountPoint.setReadable(true, false)
        }

        assertFalse(Files.readSymbolicLink(executable.toPath()).isAbsolute)
        assertEquals("binary", executable.readText())
        assertTrue(active.resolve(".internal-symlinks-relative").isFile)
    }

    private fun metadata(version: String) =
        LocalRuntimeMetadata(
            version = version,
            port = 4097,
            installedAt = 123,
            runtimeVersion = "2026.07.18.1",
            abi = "arm64-v8a",
        )
}
