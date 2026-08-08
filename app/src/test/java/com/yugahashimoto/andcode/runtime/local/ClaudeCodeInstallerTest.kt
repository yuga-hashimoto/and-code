package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class ClaudeCodeInstallerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

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

    @Test
    fun `failureMessage prefers a real error over apk's bare error count`() {
        val log =
            File.createTempFile("claude-update", ".log").apply {
                deleteOnExit()
                writeText(
                    "1 error; 2322.8 MiB in 392 packages\nERROR: unable to select packages:\n",
                )
            }
        val message = ClaudeCodeInstaller.failureMessage("update", 1, log)
        assertTrue(
            message.lineSequence().first().endsWith("ERROR: unable to select packages:"),
        )
    }

    @Test
    fun `assembled scripts abort on the first failure and end with the package work`() {
        listOf(ClaudeCodeInstaller.INSTALL_SCRIPT, ClaudeCodeInstaller.UPDATE_SCRIPT).forEach { script ->
            assertEquals("set -e", script.lineSequence().first())
            assertEquals("${ClaudeCodeInstaller.CLAUDE_BINARY} --version", script.lineSequence().last())
            assertTrue(script.contains("/sbin/apk fix ||"))
        }
    }

    @Test
    fun `both scripts point the sandbox at the latest channel before apk reads the index`() {
        listOf(ClaudeCodeInstaller.INSTALL_SCRIPT, ClaudeCodeInstaller.UPDATE_SCRIPT).forEach { script ->
            assertTrue(script.contains("https://downloads.claude.ai/claude-code/apk/latest"))
            // The repository has to be in place before the index is refreshed, or the first update
            // after a channel change still resolves against the old channel.
            assertTrue(script.indexOf("apk/latest") < script.indexOf("/sbin/apk update"))
        }
    }

    @Test
    fun `repository configuration moves an existing sandbox off the stable channel`() {
        // A sandbox provisioned by an earlier build carries the stable line, and nothing else in the
        // update rewrites it -- so without this the channel change would only reach fresh installs.
        val repositories =
            temporaryFolder.newFile("repositories").apply {
                writeText(
                    listOf(
                        "https://dl-cdn.alpinelinux.org/alpine/v3.24/main",
                        "https://downloads.claude.ai/claude-code/apk/stable",
                    ).joinToString("\n", postfix = "\n"),
                )
            }

        configureRepository(repositories)

        assertEquals(
            listOf(
                "https://dl-cdn.alpinelinux.org/alpine/v3.24/main",
                "https://downloads.claude.ai/claude-code/apk/latest",
            ),
            repositories.readLines(),
        )
    }

    @Test
    fun `repository configuration adds the channel to a sandbox that has none`() {
        val repositories =
            temporaryFolder.newFile("repositories").apply {
                writeText("https://dl-cdn.alpinelinux.org/alpine/v3.24/main\n")
            }

        configureRepository(repositories)

        assertEquals(
            listOf(
                "https://dl-cdn.alpinelinux.org/alpine/v3.24/main",
                "https://downloads.claude.ai/claude-code/apk/latest",
            ),
            repositories.readLines(),
        )
    }

    @Test
    fun `repository configuration leaves a single channel line when it runs again`() {
        val repositories =
            temporaryFolder.newFile("repositories").apply {
                writeText("https://dl-cdn.alpinelinux.org/alpine/v3.24/main\n")
            }

        configureRepository(repositories)
        configureRepository(repositories)

        assertEquals(1, repositories.readLines().count { it.contains("downloads.claude.ai") })
    }

    @Test
    fun `update reinstalls every broken package rather than naming one`() {
        val run = runPackageCommands(ClaudeCodeInstaller::updatePackageCommands)

        assertEquals(0, run.exitCode)
        // `apk fix <pkg>` reinstalls that package and still trips over every other package's broken
        // flag, so the repair has to be the unqualified form.
        assertTrue(run.apkInvocations.contains("fix"))
    }

    @Test
    fun `update survives an apk exit code caused by an unrelated broken package`() {
        // apk counts one error per package flagged broken in its database, in every transaction it
        // commits -- so `apk add` exits non-zero even when claude-code itself upgraded cleanly.
        val run =
            runPackageCommands(ClaudeCodeInstaller::updatePackageCommands, "ADD_STATUS" to "1")

        assertEquals(0, run.exitCode)
        assertTrue(run.output.contains("claude-code is up to date"))
        assertTrue(run.claudeRan)
    }

    @Test
    fun `update fails when apk fails and claude-code is still behind the repository`() {
        val run =
            runPackageCommands(
                ClaudeCodeInstaller::updatePackageCommands,
                "ADD_STATUS" to "1",
                "PENDING_UPGRADE" to "1",
            )

        assertNotEquals(0, run.exitCode)
        assertTrue(run.output.contains("still behind the repository"))
    }

    @Test
    fun `update still upgrades when the broken-package repair itself fails`() {
        val run =
            runPackageCommands(ClaudeCodeInstaller::updatePackageCommands, "FIX_STATUS" to "1")

        assertEquals(0, run.exitCode)
        assertTrue(run.apkInvocations.contains("add --no-cache --upgrade claude-code"))
    }

    @Test
    fun `update fails when the installed binary cannot run`() {
        val run =
            runPackageCommands(ClaudeCodeInstaller::updatePackageCommands, "CLAUDE_STATUS" to "1")

        assertNotEquals(0, run.exitCode)
    }

    @Test
    fun `install survives an apk exit code once both packages are installed`() {
        val run =
            runPackageCommands(
                ClaudeCodeInstaller::installPackageCommands,
                "ADD_STATUS" to "1",
                "INSTALLED" to "claude-code util-linux jq",
            )

        assertEquals(0, run.exitCode)
        assertTrue(run.claudeRan)
    }

    @Test
    fun `install fails when apk fails and a requested package is missing`() {
        val run =
            runPackageCommands(
                ClaudeCodeInstaller::installPackageCommands,
                "ADD_STATUS" to "1",
                "INSTALLED" to "claude-code",
            )

        assertNotEquals(0, run.exitCode)
        assertTrue(run.output.contains("requested packages are not installed"))
    }

    /** Runs the repository half of a script against a stand-in for `/etc/apk/repositories`. */
    private fun configureRepository(repositories: File) {
        val process =
            ProcessBuilder("sh", "-c", "set -e\n" + ClaudeCodeInstaller.configureRepositoryCommands(repositories.absolutePath))
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("stub script timed out", process.waitFor(60, TimeUnit.SECONDS))
        assertEquals(output, 0, process.exitValue())
    }

    private class ScriptRun(val exitCode: Int, val output: String, val apkInvocations: List<String>) {
        val claudeRan: Boolean get() = output.contains(CLAUDE_VERSION)
    }

    /**
     * Runs the package-manager half of a script against stub `apk` and `claude` binaries.
     *
     * The stubs report whatever [variables] ask them to, which is the only way to exercise the
     * branches that matter here: apk failing for reasons that have nothing to do with claude-code.
     */
    private fun runPackageCommands(
        commands: (String, String) -> String,
        vararg variables: Pair<String, String>,
    ): ScriptRun {
        val bin = temporaryFolder.newFolder("bin")
        val apkLog = File(bin, "apk.log")
        val apk = stub(bin, "apk", APK_STUB.replace("@LOG@", apkLog.absolutePath))
        val claude = stub(bin, "claude", CLAUDE_STUB)
        val process =
            ProcessBuilder("sh", "-c", "set -e\n" + commands(apk.absolutePath, claude.absolutePath))
                .redirectErrorStream(true)
                .apply { environment().putAll(variables.toMap()) }
                .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("stub script timed out", process.waitFor(60, TimeUnit.SECONDS))
        return ScriptRun(
            exitCode = process.exitValue(),
            output = output,
            apkInvocations = apkLog.takeIf(File::isFile)?.readLines().orEmpty(),
        )
    }

    private fun stub(
        directory: File,
        name: String,
        body: String,
    ): File =
        File(directory, name).apply {
            writeText(body)
            check(setExecutable(true))
        }

    private companion object {
        const val CLAUDE_VERSION = "2.1.212 (Claude Code)"

        val APK_STUB =
            """
            #!/bin/sh
            echo "${'$'}*" >> '@LOG@'
            case "${'$'}1" in
              fix) exit "${'$'}{FIX_STATUS:-0}" ;;
              add) exit "${'$'}{ADD_STATUS:-0}" ;;
              info)
                case " ${'$'}{INSTALLED:-} " in *" ${'$'}3 "*) echo "${'$'}3" ;; esac
                ;;
              version)
                [ -z "${'$'}{PENDING_UPGRADE:-}" ] || echo claude-code
                ;;
            esac
            exit 0
            """.trimIndent()

        val CLAUDE_STUB =
            """
            #!/bin/sh
            echo '$CLAUDE_VERSION'
            exit "${'$'}{CLAUDE_STATUS:-0}"
            """.trimIndent()
    }
}
