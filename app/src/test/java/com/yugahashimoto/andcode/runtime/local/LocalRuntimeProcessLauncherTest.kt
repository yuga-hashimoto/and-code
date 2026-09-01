package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalRuntimeProcessLauncherTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `guest environment exposes Alpine tools and root home`() {
        val environment =
            localRuntimeEnvironment(
                suiteEnvironment =
                    mapOf(
                        "LD_LIBRARY_PATH" to "/native/lib",
                        "PATH" to "/system/bin",
                        "HOME" to "/android/home",
                    ),
                prootTmp = File("/android/proot-tmp"),
            )

        assertEquals("/root", environment["HOME"])
        assertEquals(
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin",
            environment["PATH"],
        )
        assertEquals("/usr/lib/jvm/java-17-openjdk", environment["JAVA_HOME"])
        assertEquals("/tmp", environment["TMPDIR"])
        assertEquals("/root/.config", environment["XDG_CONFIG_HOME"])
        assertEquals("/android/proot-tmp", environment["PROOT_TMP_DIR"])
        assertEquals("/native/lib", environment["LD_LIBRARY_PATH"])
        assertTrue(environment["OPENCODE_DISABLE_AUTOUPDATE"] == "true")
        assertEquals(
            "{\"instructions\":[\"/root/.config/opencode/and-code-context.md\"]}",
            environment["OPENCODE_CONFIG_CONTENT"],
        )
    }

    @Test
    fun `guest agent context is copied to each agent's instruction path`() {
        val rootfs = temporaryFolder.newFolder("rootfs")

        installAndCodeAgentContext(rootfs, AGENT_CONTEXT_FIXTURE.toByteArray())

        listOf(
            "root/.config/opencode/and-code-context.md",
            "root/.claude/CLAUDE.md",
            "root/.gemini/GEMINI.md",
        ).forEach { relativePath ->
            val context = File(rootfs, relativePath).readText()
            assertEquals(AGENT_CONTEXT_FIXTURE, context)
        }
    }

    @Test
    fun `user edits to an instructions file survive a later context refresh`() {
        val rootfs = temporaryFolder.newFolder("rootfs-user-edit")

        installAndCodeAgentContext(rootfs, AGENT_CONTEXT_FIXTURE.toByteArray())

        val claudeMd = File(rootfs, "root/.claude/CLAUDE.md")
        val customInstructions = "Always use 4-space indentation and write tests first."
        claudeMd.writeText(customInstructions)

        // A later runtime start (e.g. bundled context text changes, or the same context is
        // simply re-ensured) must not stomp the user's edit.
        installAndCodeAgentContext(rootfs, (AGENT_CONTEXT_FIXTURE + "\nExtra default line.").toByteArray())

        assertEquals(customInstructions, claudeMd.readText())
    }

    @Test
    fun `untouched instructions files still pick up bundled context updates`() {
        val rootfs = temporaryFolder.newFolder("rootfs-untouched")

        installAndCodeAgentContext(rootfs, AGENT_CONTEXT_FIXTURE.toByteArray())

        val updatedFixture = "$AGENT_CONTEXT_FIXTURE\nExtra default line."
        installAndCodeAgentContext(rootfs, updatedFixture.toByteArray())

        listOf(
            "root/.config/opencode/and-code-context.md",
            "root/.claude/CLAUDE.md",
            "root/.gemini/GEMINI.md",
        ).forEach { relativePath ->
            assertEquals(updatedFixture, File(rootfs, relativePath).readText())
        }
    }

    @Test
    fun `symlinked instructions path outside rootfs is left alone`() {
        val rootfs = temporaryFolder.newFolder("rootfs-symlink")
        val outsideTarget = temporaryFolder.newFile("outside-secret.txt")
        val originalContent = "do not touch"
        outsideTarget.writeText(originalContent)

        // Simulate an agent (or malicious content it wrote) replacing the CLAUDE.md path with a
        // symlink pointing outside the rootfs, before AndCode's own copy logic runs.
        val claudeMd = File(rootfs, "root/.claude/CLAUDE.md")
        claudeMd.parentFile.mkdirs()
        java.nio.file.Files.createSymbolicLink(claudeMd.toPath(), outsideTarget.toPath())

        installAndCodeAgentContext(rootfs, AGENT_CONTEXT_FIXTURE.toByteArray())

        assertEquals(originalContent, outsideTarget.readText())
    }

    @Test
    fun `dangling symlinked instructions path outside rootfs is left alone`() {
        val rootfs = temporaryFolder.newFolder("rootfs-dangling-symlink")
        val outsideDir = temporaryFolder.newFolder("outside-dangling")
        val outsideTarget = File(outsideDir, "missing.txt")

        // Simulate an agent replacing the GEMINI.md path with a symlink pointing outside the
        // rootfs at a target that does not exist yet. File.canonicalFile can't realpath a
        // nonexistent final component, so it falls back to the link's own (in-rootfs) path,
        // which must not be mistaken for "nothing there yet".
        val geminiMd = File(rootfs, "root/.gemini/GEMINI.md")
        geminiMd.parentFile.mkdirs()
        java.nio.file.Files.createSymbolicLink(geminiMd.toPath(), outsideTarget.toPath())

        installAndCodeAgentContext(rootfs, AGENT_CONTEXT_FIXTURE.toByteArray())

        assertFalse(outsideTarget.exists())
    }

    @Test
    fun `instructions path replaced with a directory does not crash and is left alone`() {
        val rootfs = temporaryFolder.newFolder("rootfs-directory")

        // Simulate an agent replacing CLAUDE.md with a directory before AndCode's copy logic
        // runs. isFile is false for a directory, so the old code treated it as "nothing there
        // yet" and crashed trying to copyTo() over it.
        val claudeMd = File(rootfs, "root/.claude/CLAUDE.md")
        claudeMd.mkdirs()

        installAndCodeAgentContext(rootfs, AGENT_CONTEXT_FIXTURE.toByteArray())

        assertTrue(claudeMd.isDirectory)
        assertEquals(
            AGENT_CONTEXT_FIXTURE,
            File(rootfs, "root/.gemini/GEMINI.md").readText(),
        )
    }

    @Test
    fun `written-hashes sidecar replaced with a directory does not stop instructions from installing`() {
        val rootfs = temporaryFolder.newFolder("rootfs-sidecar-directory")

        // Simulate an agent replacing the written-hashes sidecar path with a directory before
        // AndCode's install logic runs. The sidecar being unmanageable must degrade to "no
        // recorded hashes" rather than aborting the whole install.
        val sidecar = File(rootfs, "root/.config/and-code/agent-context-written.tsv")
        sidecar.mkdirs()

        installAndCodeAgentContext(rootfs, AGENT_CONTEXT_FIXTURE.toByteArray())

        listOf(
            "root/.config/opencode/and-code-context.md",
            "root/.claude/CLAUDE.md",
            "root/.gemini/GEMINI.md",
        ).forEach { relativePath ->
            assertEquals(AGENT_CONTEXT_FIXTURE, File(rootfs, relativePath).readText())
        }
        assertTrue(sidecar.isDirectory)
    }

    @Test
    fun `written-hashes sidecar replaced with a symlink does not stop instructions from installing`() {
        val rootfs = temporaryFolder.newFolder("rootfs-sidecar-symlink")
        val outsideTarget = temporaryFolder.newFile("outside-sidecar.tsv")
        val originalContent = "do not touch"
        outsideTarget.writeText(originalContent)

        val sidecar = File(rootfs, "root/.config/and-code/agent-context-written.tsv")
        sidecar.parentFile.mkdirs()
        java.nio.file.Files.createSymbolicLink(sidecar.toPath(), outsideTarget.toPath())

        installAndCodeAgentContext(rootfs, AGENT_CONTEXT_FIXTURE.toByteArray())

        listOf(
            "root/.config/opencode/and-code-context.md",
            "root/.claude/CLAUDE.md",
            "root/.gemini/GEMINI.md",
        ).forEach { relativePath ->
            assertEquals(AGENT_CONTEXT_FIXTURE, File(rootfs, relativePath).readText())
        }
        assertEquals(originalContent, outsideTarget.readText())
    }

    @Test
    fun `staged source path replaced with a directory does not stop instructions from installing`() {
        val rootfs = temporaryFolder.newFolder("rootfs-source-directory")

        // Simulate an agent replacing the staged source path with a directory. The blurb hash is
        // computed from the in-memory bytes, and targets are written from those bytes directly,
        // so installing the instruction files must not depend on the staging copy at all.
        val source = File(rootfs, "root/.config/and-code/agent-context.md")
        source.mkdirs()

        installAndCodeAgentContext(rootfs, AGENT_CONTEXT_FIXTURE.toByteArray())

        listOf(
            "root/.config/opencode/and-code-context.md",
            "root/.claude/CLAUDE.md",
            "root/.gemini/GEMINI.md",
        ).forEach { relativePath ->
            assertEquals(AGENT_CONTEXT_FIXTURE, File(rootfs, relativePath).readText())
        }
        assertTrue(source.isDirectory)
    }

    @Test
    fun `staged source path replaced with a symlink does not stop instructions from installing`() {
        val rootfs = temporaryFolder.newFolder("rootfs-source-symlink")
        val outsideTarget = temporaryFolder.newFile("outside-source.md")
        val originalContent = "do not touch"
        outsideTarget.writeText(originalContent)

        val source = File(rootfs, "root/.config/and-code/agent-context.md")
        source.parentFile.mkdirs()
        java.nio.file.Files.createSymbolicLink(source.toPath(), outsideTarget.toPath())

        installAndCodeAgentContext(rootfs, AGENT_CONTEXT_FIXTURE.toByteArray())

        listOf(
            "root/.config/opencode/and-code-context.md",
            "root/.claude/CLAUDE.md",
            "root/.gemini/GEMINI.md",
        ).forEach { relativePath ->
            assertEquals(AGENT_CONTEXT_FIXTURE, File(rootfs, relativePath).readText())
        }
        assertEquals(originalContent, outsideTarget.readText())
    }

    private companion object {
        const val AGENT_CONTEXT_FIXTURE =
            "You are running inside and-code (AndCode), a native Android application.\n" +
                "This is an Android/PRoot environment with a /workspace mount."
    }

    @Test
    fun `guest PATH includes Android system directories for am pm input access`() {
        val environment =
            localRuntimeEnvironment(
                suiteEnvironment = emptyMap(),
                prootTmp = File("/android/proot-tmp"),
            )

        val path = environment["PATH"]!!
        val entries = path.split(":")
        assertTrue("PATH must contain /system/bin", "/system/bin" in entries)
        assertTrue("PATH must contain /system/xbin", "/system/xbin" in entries)
        assertTrue(
            "Alpine paths must precede Android system paths",
            entries.indexOf("/usr/local/bin") < entries.indexOf("/system/bin"),
        )
    }

    @Test
    fun `guest environment passes GitHub token only to the running local process`() {
        val environment =
            localRuntimeEnvironment(
                suiteEnvironment = emptyMap(),
                prootTmp = File("/android/proot-tmp"),
                githubToken = "token-from-encrypted-store",
            )

        assertEquals("token-from-encrypted-store", environment["OPENCODE_GITHUB_TOKEN"])
    }

    @Test
    fun `process tree termination order is children before parent`() {
        val children =
            mapOf(
                10L to listOf(11L, 12L),
                11L to listOf(13L),
                12L to emptyList(),
                13L to emptyList(),
            )

        assertEquals(
            listOf(13L, 11L, 12L, 10L),
            processTreePostOrder(10L) { children[it].orEmpty() },
        )
    }

    @Test
    fun `finds managed proot roots by runtime directory marker`() {
        val procRoot = temporaryFolder.newFolder("proc")
        val runtimeDirectory = File("/data/user/0/com.yugahashimoto.andcode/files/runtime")
        procRoot.resolve("100/cmdline").apply {
            parentFile.mkdirs()
            writeText(
                "${EmbeddedCommandSuite.PROOT_LIBRARY_NAME}\u0000-r\u0000" +
                    runtimeDirectory.resolve("environment/rootfs").path,
            )
        }
        procRoot.resolve("101/cmdline").apply {
            parentFile.mkdirs()
            writeText("opencode\u0000serve\u0000--port\u00004097")
        }
        procRoot.resolve("200/cmdline").apply {
            parentFile.mkdirs()
            writeText("unrelated")
        }

        assertEquals(
            listOf(100L),
            findManagedRuntimeRootPids(runtimeDirectory, procRoot),
        )
    }

    @Test
    fun `exit record starts null and restart count starts at zero`() {
        val launcher =
            LocalRuntimeProcessLauncher(
                runtimeDirectory = temporaryFolder.root,
                portProbe = { false },
            )

        assertEquals(null to null, launcher.exitRecord())
        assertEquals(0, launcher.restartCount())
    }

    @Test
    fun `exit callback is fired when process exits`() {
        var capturedExitCode: Int? = null
        var capturedPid: Long? = null
        var capturedUptime: Long = -1L

        val launcher =
            LocalRuntimeProcessLauncher(
                runtimeDirectory = temporaryFolder.root,
                portProbe = { false },
            )
        launcher.setOnExit { exitCode, pid, uptime ->
            capturedExitCode = exitCode
            capturedPid = pid
            capturedUptime = uptime
        }

        launcher.setOnExit(null)

        assertNull(capturedExitCode)
        assertEquals(-1L, capturedUptime)
    }

    @Test
    fun `log truncation keeps recent content when file exceeds max bytes`() {
        val logFile = temporaryFolder.newFile("opencode-local.log")
        val maxBytes = 100L
        val line = "abcdefghijklmnopqrstuvwxyz\n" // 27 bytes per line
        val lines = 20 // 540 bytes total
        val content = List(lines) { "$it$line" }.joinToString("")
        logFile.writeText(content)

        truncateLogFile(logFile, maxBytes)

        val remaining = logFile.readText()
        val maxRemaining = (maxBytes / 2).toInt()
        assertTrue(
            "Truncated log must be at most $maxRemaining bytes but was ${remaining.length}",
            remaining.length <= maxRemaining + 30,
        )
        assertTrue(
            "Truncated log retains recent lines: '$remaining'",
            remaining.contains("19$line") || remaining.contains("18$line"),
        )
    }
}
