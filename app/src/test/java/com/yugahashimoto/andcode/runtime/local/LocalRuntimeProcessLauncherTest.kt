package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
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
