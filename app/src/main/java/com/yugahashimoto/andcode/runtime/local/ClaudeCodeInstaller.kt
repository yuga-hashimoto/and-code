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

    /**
     * The `latest` channel, not `stable`.
     *
     * Anthropic publishes both: `stable` deliberately lags — "typically about one week old" per the
     * install docs, and in practice it sat on 2.1.212 for over two weeks while `latest` moved eight
     * releases ahead. Pinned to `stable`, the update button had nothing to fetch no matter how often
     * it was pressed, and the card truthfully — but uselessly — reported the agent as up to date.
     */
    private const val REPOSITORY = "https://downloads.claude.ai/claude-code/apk/latest"

    /** Matches any channel of [REPOSITORY], so a sandbox on an older one can be rewritten. */
    private const val REPOSITORY_MARKER = "downloads.claude.ai/claude-code/apk/"
    private const val REPOSITORIES = "/etc/apk/repositories"
    private const val SIGNING_KEY_URL = "https://downloads.claude.ai/keys/claude-code.rsa.pub"
    private const val SIGNING_KEY_PATH = "/etc/apk/keys/claude-code.rsa.pub"
    private const val APK = "/sbin/apk"
    private const val INSTALLED_DB = "/lib/apk/db/installed"

    /** `$` in a shell snippet, spelled out because these scripts live in Kotlin raw strings. */
    private const val S = "$"

    /**
     * Reinstalls every package apk has flagged as broken, best effort.
     *
     * A package whose extraction failed keeps an `f:f` (broken files) or `f:s` (broken script) flag
     * in [INSTALLED_DB], and apk then counts one error per flagged package in *every* transaction it
     * commits afterwards — including ones that touch nothing else, and including `-s` simulations.
     * The transaction exits non-zero having printed nothing but `1 error; <size> in <n> packages`,
     * which is exactly the opaque failure updates were dying with in the field: the flag was left on
     * a package broken by PRoot's hard-link emulation, and every later `apk add` inherited it.
     *
     * `apk fix` without arguments is the only form that clears this: it reinstalls precisely the
     * packages carrying a flag. Naming one package instead (the previous `apk fix unzip`) reinstalls
     * that package and still trips over every other package's flag, so it cannot succeed — and under
     * `set -e` its exit code aborted the update before the upgrade was even attempted.
     *
     * Failure here is not fatal: a package that cannot be reinstalled must not block an upgrade that
     * would otherwise work, so the verification below decides the outcome instead.
     */
    private fun repairBrokenPackages(apk: String) = "$apk fix || echo 'and-code: apk fix could not clear every broken package' >&2"

    /**
     * Points [repositories] at [REPOSITORY], replacing whichever channel is configured there.
     *
     * Every sandbox provisioned before this switched to `latest` carries the `stable` line, and the
     * update path never rewrote that file — appending only when the exact line was missing — so a
     * channel change would otherwise have reached fresh installs alone. Deleting by [REPOSITORY_MARKER]
     * and re-appending covers both directions and stays idempotent when nothing changed.
     *
     * `grep -v` rather than `sed -i` because BSD sed reads the argument after `-i` as a backup
     * suffix, which would make this untestable on a macOS host. `|| true` because grep exits 1 when
     * it selects nothing, which under `set -e` would abort on a repositories file holding only the
     * Claude Code line.
     */
    internal fun configureRepositoryCommands(repositories: String = REPOSITORIES) =
        """
        if [ -f "$repositories" ]; then
          grep -v '$REPOSITORY_MARKER' "$repositories" > "$repositories.tmp" || true
          mv -f "$repositories.tmp" "$repositories"
        fi
        printf '%s\n' '$REPOSITORY' >> "$repositories"
        """.trimIndent()

    /**
     * Package-manager half of the install, split out so tests can drive it with a stub `apk`.
     *
     * Both paths deliberately outlive a non-zero `apk` status: it covers the whole transaction, and a
     * package flagged broken before this run fails that transaction even when everything asked for
     * here succeeded. The requested packages, not the exit code, decide the outcome.
     */
    internal fun installPackageCommands(
        apk: String = APK,
        claude: String = CLAUDE_BINARY,
    ) = """
        $apk update
        ${repairBrokenPackages(apk)}
        if ! $apk add --no-cache claude-code util-linux jq; then
          if [ -z "$S($apk info -e claude-code)" ] || [ -z "$S($apk info -e util-linux)" ]; then
            echo 'and-code: apk failed and the requested packages are not installed' >&2
            exit 1
          fi
          echo 'and-code: apk reported errors from unrelated packages; requested packages are installed' >&2
        fi
        $claude --version
        """.trimIndent()

    /**
     * Package-manager half of the update.
     *
     * `apk version` reads the database without committing anything, so unlike `apk add` it is
     * unaffected by broken-package flags: an empty result means the repository has nothing newer than
     * what is installed, which is all this operation was asked to achieve.
     */
    internal fun updatePackageCommands(
        apk: String = APK,
        claude: String = CLAUDE_BINARY,
    ) = """
        $apk update
        ${repairBrokenPackages(apk)}
        if ! $apk add --no-cache --upgrade claude-code; then
          if [ -n "$S($apk version -q -l '<' claude-code)" ]; then
            echo 'and-code: apk failed and claude-code is still behind the repository' >&2
            exit 1
          fi
          echo 'and-code: apk reported errors from unrelated packages; claude-code is up to date' >&2
        fi
        $claude --version
        """.trimIndent()

    /**
     * Shell that adds the signing key and repository, then installs the package.
     *
     * Written so that a failure at any stage aborts instead of leaving a repository line the sandbox
     * cannot verify, and so that re-running it on an already-configured rootfs is a no-op.
     */
    internal val INSTALL_SCRIPT =
        script(
            """
            set -e
            /usr/bin/wget -qO $SIGNING_KEY_PATH $SIGNING_KEY_URL
            """,
            configureRepositoryCommands(),
            installPackageCommands(),
        )

    internal val UPDATE_SCRIPT =
        script(
            """
            set -e
            # Refresh the signing key on every update so a rotated or expired key cannot strand an
            # upgrade behind an untrusted repository (every version then resolves as masked in:
            # latest). If the download fails, keep the existing key so a transient outage does not
            # block an otherwise-valid update; only abort when there is no key to fall back to.
            if /usr/bin/wget -qO $SIGNING_KEY_PATH.new $SIGNING_KEY_URL; then
              mv -f $SIGNING_KEY_PATH.new $SIGNING_KEY_PATH
            elif [ ! -s $SIGNING_KEY_PATH ]; then
              echo "claude-code signing key is missing and could not be downloaded" >&2
              exit 1
            fi
            """,
            // Repeated on every update, not just at install: a sandbox provisioned before the switch
            // to the `latest` channel is still on `stable`, and this is the only path that reaches it.
            configureRepositoryCommands(),
            updatePackageCommands(),
        )

    /** Joins script sections, trimming each so they can be indented to match their surroundings. */
    private fun script(vararg sections: String) = sections.joinToString("\n") { it.trimIndent().trim() }

    /**
     * Diagnostics appended to the log when [simulation] — the failed operation, re-run with `-s` —
     * is worth capturing alongside the sandbox's package state.
     *
     * The broken-package listing is here because apk names a package only when it breaks it, never
     * when it later refuses to work because of the flag it left behind; without the listing that
     * failure reads as a bare error count with nothing to act on.
     */
    private fun diagnosticsScript(simulation: String) =
        """
        echo '--- and-code apk diagnostics ---'
        echo '-- df -h / --'
        df -h / 2>&1 || true
        echo '-- packages flagged broken in apk database --'
        /usr/bin/awk '/^P:/{p=substr(${S}0,3)} /^f:/{print p, ${S}0}' $INSTALLED_DB 2>&1 || true
        echo '-- apk $simulation --'
        $APK $simulation 2>&1 || true
        echo '-- apk policy claude-code --'
        $APK policy claude-code 2>&1 || true
        """.trimIndent()

    private val INSTALL_DIAGNOSTICS_SCRIPT = diagnosticsScript("add -s claude-code util-linux jq")

    private val UPDATE_DIAGNOSTICS_SCRIPT = diagnosticsScript("add -s --upgrade claude-code")

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

    private val APK_ERROR_SUMMARY_PATTERN = Regex("^\\d+ errors?;")

    private val APK_ERROR_PATTERN =
        Regex("(?i)\\b(error|warning|fatal|conflict|overwrite|unsatisfiable|masked|untrusted|denied|no space left|not found|failed to)\\b")

    internal fun failureMessage(
        operation: String,
        exitCode: Int,
        log: File,
    ): String {
        val text = log.takeIf(File::isFile)?.readText().orEmpty()
        val errors = extractApkErrors(text)
        val head = text.take(1_500)
        val tail = text.takeLast(2_000)
        // The tail is often filled by the post-failure `apk policy` diagnostics, which pushes the
        // `apk update` WARNING/ERROR lines (emitted early) out of view. Include the head, skipping it
        // only when the log is short enough that head and tail already overlap.
        val showHead = text.length > head.length + tail.length
        // apk's `N errors; <size> in <n> packages` summary says nothing about what went wrong, so it
        // is the headline only when the log holds no message that does.
        val primary =
            errors.lineSequence().firstOrNull { !APK_ERROR_SUMMARY_PATTERN.containsMatchIn(it) }
                ?: errors.lineSequence().firstOrNull()
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
            if (showHead) {
                append("\n--- log head ---\n")
                append(head)
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
