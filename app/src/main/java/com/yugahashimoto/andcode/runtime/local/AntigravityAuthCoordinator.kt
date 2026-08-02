package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

data class AntigravityAuthStart(val process: Process, val url: String? = null)

/**
 * Owns the official agy first-launch OAuth process. Authentication is not a subcommand: Google
 * documents launching `agy` with no arguments, choosing "Google OAuth" in the sign-in chooser, then
 * opening the printed URL and pasting the code back. The process and its token store remain inside
 * the Debian rootfs; only the URL and the user-entered code cross the Android boundary.
 */
class AntigravityAuthCoordinator(
    private val runtimeDirectory: File,
    private val installedRuntimeProvider: () -> LocalRuntimeInstaller.InstalledRuntime?,
    private val githubToken: () -> String? = { null },
) {
    sealed interface State {
        data object Idle : State

        data object Starting : State

        data class AwaitingBrowser(val url: String, val transcript: String) : State

        data object Verifying : State

        data class SignedIn(val detail: String = "Google") : State

        data class Failed(val message: String, val transcript: String) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The attempt whose output and death still speak for this coordinator; null when none does.
     *
     * Volatile so [markStarting] can read it from the UI thread. Writes and compare-and-clear go
     * through this class's monitor, which [start] holds for as long as its pre-flight takes - a
     * minute and a half in the worst case - so nothing on the UI thread may wait for it.
     */
    @Volatile private var current: Attempt? = null

    /**
     * One sign-in attempt: the guest process and everything read out of it.
     *
     * Attempts overlap by construction. Killing the guest process tree is asynchronous, so an
     * attempt the user cancelled - or one a second press replaced - keeps draining its buffer, and
     * still reports its own exit, while the next attempt is already running. Per-attempt state is
     * what keeps the two apart: the coordinator acts only on the attempt it still holds, so a dead
     * one can neither publish a stale URL nor stamp its own kill onto its successor.
     */
    internal class Attempt(val process: Process) {
        private val transcript = StringBuilder()

        /**
         * Frozen once the code field is live. Everything the TUI paints from that point on can
         * contain the authorization code the user typed, so it must never reach the UI or a log.
         */
        @Volatile var diagnostics: String = ""

        @Volatile var codeFieldLive = false

        @Volatile var menuAnswered = false

        fun append(chunk: String) {
            synchronized(this) {
                transcript.append(chunk)
                if (transcript.length > MAX_TRANSCRIPT) transcript.delete(0, transcript.length - MAX_TRANSCRIPT)
            }
        }

        fun clean(): String = AntigravityAuthParser.stripAnsi(synchronized(this) { transcript.toString() })
    }

    /**
     * Starts the no-argument agy TUI and returns once it is past its own vulnerable startup window.
     *
     * Blocks for up to [STARTUP_GRACE_MS] - callers must run this from a background thread, never
     * from the UI thread. See [AntigravityProcessGate] for why the wait exists.
     *
     * The already-signed-in check happens *before* the TUI is launched, never alongside it: two agy
     * processes running at once deadlock (see [AntigravityProcessGate]), so a poll that raced the
     * live TUI made the sign-in button hang itself with no visible progress at all.
     */
    @Synchronized
    fun start(): AntigravityAuthStart {
        // A press while an attempt is already running is a second press, not a request to start
        // over: restarting would kill the process that is about to print the URL, and that kill
        // would then be reported as a failure of the attempt the press had just started.
        current?.takeIf { it.process.isAlive }?.let { return AntigravityAuthStart(it.process) }
        // Before anything blocking. Everything below - repairing the guest settings, and above all
        // the already-signed-in check, which is a whole `agy models` run behind a gate that waits up
        // to a minute - happens before the TUI exists, and the state used to be published only after
        // all of it. Worse, the cleanup this used to do here went through `cancel`, which publishes
        // Idle: pressing "Sign in" put the plain sign-in button straight back on screen and left it
        // there for as long as the check took, so the press looked like it had done nothing at all.
        mutableState.value = State.Starting
        abandonCurrent()
        val runtime = installedRuntimeProvider() ?: error("Linux environment is not installed")
        AntigravityGuestSettings.repair(runtime)
        if (verifyModels()) {
            mutableState.value = State.SignedIn()
            return AntigravityAuthStart(NoOpProcess)
        }
        val started =
            AntigravityProcessGate.acquireThenRelease(STARTUP_GRACE_MS) { launchTui(runtime) }
                ?: error("Antigravity is busy with another operation; try again in a moment")
        val attempt = adopt(Attempt(started))
        mutableState.value = State.Starting
        scope.launch { driveLoginChooser(attempt) }
        scope.launch {
            runCatching { started.inputStream.bufferedReader().forEachChunk { chunk -> onOutput(attempt, chunk) } }
            onProcessExit(attempt, runCatching { started.waitFor() }.getOrDefault(-1))
        }
        scope.launch { watchForDiscoveryTimeout(attempt) }
        return AntigravityAuthStart(started)
    }

    /**
     * Takes ownership of [attempt], so that from here on it is the one allowed to publish state.
     *
     * Internal rather than private because the abandonment rules are the whole point of [Attempt]
     * and testing them needs an attempt, not a guest process.
     */
    @Synchronized
    internal fun adopt(attempt: Attempt): Attempt {
        current = attempt
        return attempt
    }

    /**
     * Drops the current attempt and kills its process, publishing nothing.
     *
     * The kill is asynchronous, so the abandoned attempt keeps running for a moment and then reports
     * its own exit. Dropping it *before* that happens is what stops the report: [onProcessExit] only
     * speaks for the attempt this coordinator still holds.
     */
    @Synchronized
    private fun abandonCurrent() {
        current?.let { terminateAsync(it.process) }
        current = null
    }

    @Synchronized
    private fun isCurrent(attempt: Attempt): Boolean = current === attempt

    @Synchronized
    private fun releaseIfCurrent(attempt: Attempt): Boolean {
        if (current !== attempt) return false
        current = null
        return true
    }

    /**
     * "1. Google OAuth" is preselected, so a single Enter starts the flow. The chooser only appears
     * once the bundled language server is up, which is why this waits for the chooser to be painted
     * instead of pressing Enter on a fixed delay.
     */
    private suspend fun driveLoginChooser(attempt: Attempt) {
        val started = attempt.process
        val deadline = System.currentTimeMillis() + MENU_TIMEOUT_MS
        while (started.isAlive && System.currentTimeMillis() < deadline) {
            if (AntigravityAuthParser.isLoginMenuVisible(attempt.clean())) {
                attempt.menuAnswered = true
                // Bubble Tea reads Enter as CR because it puts the PTY in raw mode.
                runCatching {
                    started.outputStream.write('\r'.code)
                    started.outputStream.flush()
                }
                return
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun watchForDiscoveryTimeout(attempt: Attempt) {
        delay(AUTH_DISCOVERY_TIMEOUT_MS)
        if (!attempt.process.isAlive || !isCurrent(attempt) || mutableState.value !is State.Starting) return
        val clean = attempt.clean()
        mutableState.value =
            State.Failed(
                when {
                    AntigravityAuthParser.isLocalBrowserMode(clean) ->
                        "Antigravity selected local browser mode and did not print a sign-in URL"
                    !attempt.menuAnswered -> "Antigravity did not show its sign-in chooser"
                    else -> "Antigravity did not print a Google sign-in URL"
                },
                visibleDiagnostics(clean),
            )
        terminate(attempt.process)
    }

    @Synchronized
    fun submitCode(code: String) {
        val attempt = current ?: return
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return
        mutableState.value = State.Verifying
        runCatching {
            attempt.process.outputStream.write((trimmed + "\r").toByteArray())
            attempt.process.outputStream.flush()
        }.onFailure {
            mutableState.value = State.Failed(it.message ?: "Could not submit the Antigravity code", attempt.diagnostics)
            return
        }
        scope.launch { awaitVerification(attempt) }
    }

    /**
     * The official CLI stays running after a successful exchange, so completion is confirmed out of
     * band with `agy models` rather than by waiting for the process to exit.
     */
    private suspend fun awaitVerification(attempt: Attempt) {
        val deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (mutableState.value !is State.Verifying) return
            val clean = attempt.clean()
            if (AntigravityAuthParser.isFailure(clean)) {
                mutableState.value = State.Failed("Antigravity rejected the authorization code", attempt.diagnostics)
                return
            }
            if (AntigravityAuthParser.isSignedIn(clean) || verifyModels()) {
                releaseIfCurrent(attempt)
                terminate(attempt.process)
                mutableState.value = State.SignedIn()
                return
            }
            delay(VERIFY_POLL_MS)
        }
        if (mutableState.value is State.Verifying) {
            mutableState.value = State.Failed("Antigravity sign-in did not complete in time", attempt.diagnostics)
        }
    }

    @Synchronized
    fun cancel() {
        abandonCurrent()
        if (mutableState.value !is State.SignedIn) mutableState.value = State.Idle
    }

    /**
     * Signs out by clearing the guest token store, then verifying the CLI agrees.
     *
     * The documented `/logout` is a slash command typed into the interactive TUI, and driving it
     * through a PTY proved unreliable here for the same reasons the sign-in TUI did. Deleting the
     * token file the CLI itself writes under the guest `$HOME` is deterministic and stays inside the
     * app's own sandbox - it removes local credentials rather than reading or exporting them, and
     * nothing is copied to the Android side. Without a working sign-out there is no way to
     * re-authenticate at all once a token exists, which is exactly the state this got stuck in.
     */
    fun logout() {
        val runtime = installedRuntimeProvider() ?: return
        cancel()
        val rootfs = runtime.antigravityRootfs ?: runtime.rootfs
        runCatching { File(rootfs, GUEST_TOKEN_PATH).delete() }
        runtime.let { AntigravityGuestSettings.repair(it) }
        mutableState.value = State.Idle
    }

    /** True when the guest token store already satisfies the official CLI. */
    fun isSignedIn(): Boolean = verifyModels()

    /** Restores the signed-in state discovered from the guest token store after an app restart. */
    fun markSignedIn() {
        if (mutableState.value is State.Idle) mutableState.value = State.SignedIn()
    }

    /**
     * Publishes [State.Starting] without taking [start]'s lock, so the button reacts on the tap.
     *
     * [start] is `@Synchronized` and everything it does before publishing a state is slow - an
     * already-signed-in check that is a whole `agy models` run behind a gate that waits up to a
     * minute. Its own first line cannot report anything until it holds the lock, so the press has to
     * be acknowledged from outside it.
     */
    fun markStarting() {
        // Not over a live attempt: a second press while the sign-in URL is on screen would replace
        // it with a spinner for a process nothing is going to restart. [start] ignores that press.
        // Read without the lock - this runs on the UI thread. See [current].
        if (current?.process?.isAlive == true) return
        mutableState.value = State.Starting
    }

    /**
     * Publishes a failure that happened instead of a sign-in rather than during one.
     *
     * [AntigravityController.beginAuth] used to record this on its own state, which is combined with
     * - and therefore immediately overwritten by - this flow. A sign-in that could not even start,
     * because no Linux environment is installed or the process gate is still busy, left the UI on a
     * spinner with nothing to say and no way back.
     */
    fun markFailed(message: String) {
        mutableState.value = State.Failed(message, "")
    }

    private fun launchTui(runtime: LocalRuntimeInstaller.InstalledRuntime): Process {
        val workspace = File(runtimeDirectory, "workspace").apply { mkdirs() }
        val command = AntigravitySandboxLauncher.command(runtime, workspace.absolutePath, emptyList(), pty = true)
        return ProcessBuilder(command)
            .directory(runtimeDirectory)
            .redirectErrorStream(true)
            .apply {
                environment().putAll(
                    AntigravitySandboxLauncher.environment(
                        runtime,
                        File(runtimeDirectory, "proot-tmp").apply { mkdirs() },
                        githubToken(),
                    ),
                )
                // agy treats CI as a headless test mode and skips the browser handoff entirely.
                environment().remove("CI")
            }
            .start()
    }

    private fun visibleDiagnostics(clean: String): String = AntigravityAuthParser.redact(clean).takeLast(VISIBLE_TRANSCRIPT)

    private fun onOutput(
        attempt: Attempt,
        chunk: String,
    ) {
        attempt.append(chunk)
        // An abandoned attempt drains whatever is left in its buffer before the kill lands. None of
        // it can be published: its URL is dead, and its transcript is not the live attempt's.
        if (!isCurrent(attempt)) return
        val clean = attempt.clean()
        if (!attempt.codeFieldLive) {
            attempt.diagnostics = visibleDiagnostics(clean)
            attempt.codeFieldLive = AntigravityAuthParser.isAwaitingCode(clean)
        }
        if (mutableState.value is State.Verifying || mutableState.value is State.SignedIn) return
        val url = AntigravityAuthParser.findOAuthUrl(clean)
        if (url != null) mutableState.value = State.AwaitingBrowser(url, attempt.diagnostics)
    }

    /** Internal for the same reason as [adopt]: the rule below is what the tests are about. */
    internal fun onProcessExit(
        attempt: Attempt,
        exitCode: Int,
    ) {
        // Only the attempt this coordinator still holds may report anything. A cancelled attempt, or
        // one a second press replaced, is being killed on purpose, and announcing that kill as
        // "stopped (exit code 137)" described the coordinator's own SIGKILL as a sign-in failure -
        // on top of whatever state had already taken its place.
        if (!releaseIfCurrent(attempt)) return
        when (mutableState.value) {
            is State.SignedIn -> return
            // Verification owns the terminal state once a code has been submitted.
            is State.Verifying -> return
            // A reason is already known - the discovery watchdog sets one and *then* kills the
            // process, so overwriting it here replaced "did not print a Google sign-in URL" with
            // the bare "stopped (exit code 137)" of the kill this coordinator had just issued,
            // which is the least informative description of its own action.
            is State.Failed -> return
            else -> Unit
        }
        mutableState.value =
            if (verifyModels()) {
                State.SignedIn()
            } else {
                State.Failed("Antigravity sign-in stopped (exit code $exitCode)", attempt.diagnostics)
            }
    }

    private fun verifyModels(): Boolean =
        runCatching {
            val runtime = installedRuntimeProvider() ?: return false
            val workspace = File(runtimeDirectory, "workspace").apply { mkdirs() }
            AntigravityProcessGate.exclusive {
                val command = AntigravitySandboxLauncher.command(runtime, workspace.absolutePath, listOf("models"), pty = false)
                val target =
                    with(AntigravityProcessGate) {
                        ProcessBuilder(command)
                            .directory(runtimeDirectory)
                            .redirectErrorStream(true)
                            .withoutStdin()
                            .apply {
                                environment().putAll(
                                    AntigravitySandboxLauncher.environment(
                                        runtime,
                                        File(runtimeDirectory, "proot-tmp").apply { mkdirs() },
                                        githubToken(),
                                    ),
                                )
                            }
                            .start()
                    }
                val output = AntigravityProcessGate.readWithTimeout(target, MODELS_TIMEOUT_SECONDS * 1000)
                if (output == null || !target.waitFor(5, TimeUnit.SECONDS)) {
                    terminate(target)
                    false
                } else {
                    target.exitValue() == 0 && output.isNotBlank() && !output.contains(NOT_LOGGED_IN, ignoreCase = true)
                }
            } ?: false
        }.getOrDefault(false)

    /**
     * Kills the whole guest process tree, blocking - only call this from a background coroutine.
     *
     * See [killAntigravityProcessTree] for why a plain `destroy()`/`destroyForcibly()` is not enough.
     */
    private fun terminate(target: Process) {
        killAntigravityProcessTree(target)
        if (target.isAlive) target.waitFor(GRACEFUL_KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /** Same as [terminate], but never blocks the caller - for call sites that may run on the UI thread. */
    private fun terminateAsync(target: Process) {
        if (!target.isAlive) return
        scope.launch { terminate(target) }
    }

    private fun java.io.BufferedReader.forEachChunk(onChunk: (String) -> Unit) {
        val buffer = CharArray(1024)
        while (true) {
            val read = read(buffer)
            if (read < 0) return
            if (read > 0) onChunk(String(buffer, 0, read))
        }
    }

    internal companion object {
        /**
         * How many screens of TUI output the transcript keeps.
         *
         * agy is a full-screen Bubble Tea program: every repaint rewrites the whole screen, so one
         * repaint alone is [AntigravitySandboxLauncher.PTY_ROWS] x
         * [AntigravitySandboxLauncher.PTY_COLUMNS] characters before escape sequences. The sign-in
         * URL has to still be in the buffer when [AntigravityAuthParser.findOAuthUrl] looks for it,
         * and that lookup is anchored on the URL's `https://accounts.google.com/...` beginning.
         *
         * This buffer used to be a flat 16,000 characters while the PTY is 24x1000 - so a *single*
         * repaint was half again larger than the whole buffer, and the line carrying the start of
         * the URL was dropped the moment the TUI redrew. What survived was the wrapped tail, which
         * matches nothing, so sign-in sat in Starting until the discovery watchdog killed it 120
         * seconds later. Whether it worked at all was a race against the next repaint.
         */
        const val TRANSCRIPT_SCREENS = 4

        const val MAX_TRANSCRIPT = TRANSCRIPT_SCREENS * AntigravitySandboxLauncher.PTY_ROWS * AntigravitySandboxLauncher.PTY_COLUMNS
        const val VISIBLE_TRANSCRIPT = 1_200
        const val POLL_INTERVAL_MS = 400L
        const val MENU_TIMEOUT_MS = 90_000L
        const val AUTH_DISCOVERY_TIMEOUT_MS = 120_000L
        const val VERIFY_POLL_MS = 2_000L
        const val VERIFY_TIMEOUT_MS = 120_000L
        const val MODELS_TIMEOUT_SECONDS = 90L
        const val GRACEFUL_KILL_TIMEOUT_MS = 3_000L

        /** How long [start] holds [AntigravityProcessGate] before letting the TUI run unattended. */
        const val STARTUP_GRACE_MS = 2_500L
        const val NOT_LOGGED_IN = "not logged into Antigravity"

        /** Where the official CLI keeps its token inside the guest `$HOME`. */
        const val GUEST_TOKEN_PATH = "root/.gemini/antigravity-cli/antigravity-oauth-token"
    }
}

/**
 * Stand-in for the sign-in TUI that [AntigravityAuthCoordinator.start] never had to launch because
 * the guest was already signed in. Callers only ever hold this to submit a code or cancel, and there
 * is nothing to do in either case.
 */
private object NoOpProcess : Process() {
    // `OutputStream.nullOutputStream()`/`InputStream.nullInputStream()` are API 33; minSdk is 26.
    override fun getOutputStream(): java.io.OutputStream =
        object : java.io.OutputStream() {
            override fun write(b: Int) = Unit
        }

    override fun getInputStream(): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))

    override fun getErrorStream(): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int = 0

    override fun exitValue(): Int = 0

    override fun destroy() = Unit

    override fun isAlive(): Boolean = false
}
