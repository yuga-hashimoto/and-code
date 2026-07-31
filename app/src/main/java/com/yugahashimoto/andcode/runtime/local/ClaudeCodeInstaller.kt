package com.yugahashimoto.andcode.runtime.local

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Provisions the official Claude Code package into the shared Alpine sandbox.
 *
 * Anthropic publishes an Alpine repository, so this is a plain `apk add` rather than a bundled
 * binary. Two details are easy to get wrong and both are fatal:
 *
 * - `apk` lives in `/sbin`, which the sandbox's `/etc/profile.d` PATH deliberately omits, so every
 *   invocation uses the absolute path instead of relying on the login shell's PATH.
 * - the package installs `claude` to `/usr/bin`, not `/usr/local/bin` where the OpenCode binary is
 *   copied. [CLAUDE_BINARY] is the single source of truth for that path.
 */
object ClaudeCodeInstaller {
    const val CLAUDE_BINARY = "/usr/bin/claude"

    /**
     * Preloaded into Claude Code so its DNS resolver has usable servers.
     *
     * The native build is a Bun binary, and Bun's resolver intermittently times out against
     * api.anthropic.com on Android even when the system resolver is fine. Pointing it at explicit
     * servers avoids a hang that otherwise looks like the agent simply never answering.
     */
    const val DNS_PRELOAD = "/usr/local/share/claude-setdns.js"

    private const val REPOSITORY = "https://downloads.claude.ai/claude-code/apk/stable"
    private const val SIGNING_KEY_URL = "https://downloads.claude.ai/keys/claude-code.rsa.pub"
    private const val SIGNING_KEY_PATH = "/etc/apk/keys/claude-code.rsa.pub"

    /**
     * Shell that adds the signing key and repository, then installs the package.
     *
     * Written so that a failure at any stage aborts instead of leaving a repository line the sandbox
     * cannot verify, and so that re-running it on an already-configured rootfs is a no-op.
     */
    private val INSTALL_SCRIPT =
        """
        set -e
        /usr/bin/wget -qO $SIGNING_KEY_PATH $SIGNING_KEY_URL
        if ! grep -qxF '$REPOSITORY' /etc/apk/repositories; then
          printf '%s\n' '$REPOSITORY' >> /etc/apk/repositories
        fi
        /sbin/apk update
        /sbin/apk add --no-cache claude-code util-linux
        $CLAUDE_BINARY --version
        """.trimIndent()

    private val UPDATE_SCRIPT =
        """
        set -e
        /sbin/apk update
        /sbin/apk add --no-cache --upgrade claude-code
        $CLAUDE_BINARY --version
        """.trimIndent()

    private val INSTALL_DIAGNOSTICS_SCRIPT =
        """
        echo '--- and-code apk diagnostics ---'
        echo '-- df -h / --'
        df -h / 2>&1 || true
        echo '-- apk add -s claude-code util-linux --'
        /sbin/apk add -s claude-code util-linux 2>&1 || true
        echo '-- apk policy claude-code --'
        /sbin/apk policy claude-code 2>&1 || true
        """.trimIndent()

    private val UPDATE_DIAGNOSTICS_SCRIPT =
        """
        echo '--- and-code apk diagnostics ---'
        echo '-- df -h / --'
        df -h / 2>&1 || true
        echo '-- apk add -s --upgrade claude-code --'
        /sbin/apk add -s --upgrade claude-code 2>&1 || true
        echo '-- apk policy claude-code --'
        /sbin/apk policy claude-code 2>&1 || true
        """.trimIndent()

    /** Steps reported while [installInto] runs, so the UI can show more than a spinner. */
    enum class Step {
        ADDING_REPOSITORY,
        DOWNLOADING_PACKAGE,
        VERIFYING,
    }

    /**
     * Installs Claude Code into [rootfs] using [suite]'s PRoot.
     *
     * Used both during a fresh sandbox install (where [rootfs] is still the staging directory) and
     * when adding Claude Code to a sandbox that is already active.
     */
    fun installInto(
        rootfs: File,
        suite: EmbeddedCommandSuite.Paths,
        runtimeDirectory: File,
        onStep: (Step) -> Unit = {},
        timeoutMinutes: Long = 15,
    ) {
        onStep(Step.ADDING_REPOSITORY)
        val log =
            File(runtimeDirectory, "logs/claude-install.log").apply {
                parentFile?.mkdirs()
                delete()
            }
        onStep(Step.DOWNLOADING_PACKAGE)
        val exitCode = runInRootfs(INSTALL_SCRIPT, rootfs, suite, runtimeDirectory, log, timeoutMinutes)
        onStep(Step.VERIFYING)
        if (exitCode != 0) {
            collectDiagnostics(INSTALL_DIAGNOSTICS_SCRIPT, rootfs, suite, runtimeDirectory, log, timeoutMinutes)
        }
        check(exitCode == 0) { failureMessage("installation", exitCode, log) }
        check(File(rootfs, CLAUDE_BINARY.removePrefix("/")).isFile) {
            "Claude Code reported success but $CLAUDE_BINARY is missing"
        }
        ensureDnsPreload(rootfs)
    }

    /**
     * Writes the DNS preload if it is missing.
     *
     * Called before every launch, not just on install: the launcher always passes --preload, and a
     * sandbox provisioned by an older build would otherwise point Bun at a file that is not there.
     */
    fun ensureDnsPreload(rootfs: File) {
        runCatching {
            File(rootfs, DNS_PRELOAD.removePrefix("/")).apply {
                if (isFile) return@runCatching
                parentFile?.mkdirs()
                writeText(
                    """
                    try { require("dns").setServers(["1.1.1.1", "8.8.8.8"]); } catch (e) {}
                    """.trimIndent()
                        +
                        "\n",
                )
            }
        }
    }

    /** Upgrades an already-installed Claude Code package in place. */
    fun updateIn(
        rootfs: File,
        suite: EmbeddedCommandSuite.Paths,
        runtimeDirectory: File,
        timeoutMinutes: Long = 15,
    ) {
        val log =
            File(runtimeDirectory, "logs/claude-update.log").apply {
                parentFile?.mkdirs()
                delete()
            }
        val exitCode = runInRootfs(UPDATE_SCRIPT, rootfs, suite, runtimeDirectory, log, timeoutMinutes)
        if (exitCode != 0) {
            collectDiagnostics(UPDATE_DIAGNOSTICS_SCRIPT, rootfs, suite, runtimeDirectory, log, timeoutMinutes)
        }
        check(exitCode == 0) { failureMessage("update", exitCode, log) }
    }

    fun isInstalledIn(rootfs: File): Boolean = File(rootfs, CLAUDE_BINARY.removePrefix("/")).isFile

    private fun runInRootfs(
        script: String,
        rootfs: File,
        suite: EmbeddedCommandSuite.Paths,
        runtimeDirectory: File,
        log: File,
        timeoutMinutes: Long,
        append: Boolean = false,
    ): Int {
        val prootTmp = File(runtimeDirectory, "proot-tmp").apply { mkdirs() }
        val process =
            ProcessBuilder(
                listOf(
                    suite.proot.absolutePath,
                    "--kill-on-exit",
                    "--link2symlink",
                    "-0",
                    "-r",
                    rootfs.absolutePath,
                    "-b",
                    "/dev",
                    "-b",
                    "/proc",
                    "-b",
                    "/sys",
                    "-b",
                    "/system",
                    "-w",
                    "/root",
                    // Deliberately not a login shell: /etc/profile.d narrows PATH to the OpenCode
                    // set, and apk would then be unreachable.
                    "/bin/sh",
                    "-c",
                    script,
                ),
            ).redirectErrorStream(true)
                .redirectOutput(
                    if (append) ProcessBuilder.Redirect.appendTo(log) else ProcessBuilder.Redirect.to(log),
                )
                .apply {
                    environment().clear()
                    environment().putAll(localRuntimeEnvironment(suite.environment(), prootTmp))
                }
                .start()
        if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            error("Claude Code package operation timed out after $timeoutMinutes minutes")
        }
        return process.exitValue()
    }

    private fun collectDiagnostics(
        script: String,
        rootfs: File,
        suite: EmbeddedCommandSuite.Paths,
        runtimeDirectory: File,
        log: File,
        timeoutMinutes: Long,
    ) {
        runCatching {
            runInRootfs(script, rootfs, suite, runtimeDirectory, log, timeoutMinutes, append = true)
        }
    }

    private val APK_ERROR_PATTERN =
        Regex("(?i)\\b(error|warning|fatal|conflict|overwrite|unsatisfiable|masked|untrusted|denied|no space left|not found|failed to)\\b")

    internal fun failureMessage(operation: String, exitCode: Int, log: File): String {
        val text = log.takeIf(File::isFile)?.readText().orEmpty()
        val errors = extractApkErrors(text)
        val tail = text.takeLast(2_000)
        val primary =
            errors.lineSequence().firstOrNull()
                ?: tail.lineSequence().map(String::trim).firstOrNull { it.isNotBlank() }.orEmpty()
        return buildString {
            append("Claude Code ")
            append(operation)
            append(" failed (exit ")
            append(exitCode)
            append("): ")
            append(primary)
            if (errors.isNotBlank()) {
                append('\n')
                append(errors)
            }
            append("\n--- log tail ---\n")
            append(tail)
        }
    }

    internal fun extractApkErrors(text: String): String =
        text.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() }
            .filter { line ->
                !line.startsWith("fetch ") &&
                    !line.startsWith("(") &&
                    APK_ERROR_PATTERN.containsMatchIn(line)
            }
            .take(40)
            .joinToString("\n")
}
