package com.yugahashimoto.andcode.runtime.local

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Serializes launches against the shared Antigravity guest rootfs.
 *
 * Confirmed on a real device: a single `agy models` invocation completes in about 5 seconds, but two
 * `agy` processes started within the same second or so of each other - two one-shot checks, or a
 * one-shot check racing the interactive sign-in TUI's own startup - do not return, apparently stuck
 * indefinitely. The official CLI does its own state initialization on every launch (an SQLite
 * trajectory store, a keyring session), and that appears not to be safe for two guest processes to
 * race through at once. Every caller in this file blocks only from a background dispatcher thread;
 * nothing here is safe to call from the UI thread.
 *
 * The hang is specific to the PRoot/Android deployment, not the binary itself: measured on a native
 * host, three simultaneous one-shot `agy` runs all completed in about ten seconds with no
 * contention, and `lsof` showed the CLI holding only read-only config files and network sockets, no
 * exclusive lock. Because the deadlock cannot be reproduced or fixed from outside PRoot, the app
 * gates only the short startup window; once a chat process is started, its stream is independent and
 * may run alongside another session. [exclusive] keeps short probes like `models` fail-fast.
 */
object AntigravityProcessGate {
    private val lock = ReentrantLock()
    private const val MAX_WAIT_MS = 60_000L

    /**
     * Runs [block] with the gate held, or returns null without running it if the gate is still busy
     * after [MAX_WAIT_MS].
     *
     * Failing is the only safe outcome: an earlier version proceeded anyway rather than block
     * forever, which turned a wait into exactly the concurrent-agy condition this gate exists to
     * prevent. On device that made a `models` call that normally finishes in 5 seconds overlap a
     * still-running one and hang until its own 45s read timeout, so the catalogue came back empty and
     * the picker showed a placeholder. Callers must handle null as "could not run".
     */
    fun <T> exclusive(block: () -> T): T? {
        if (!lock.tryLock(MAX_WAIT_MS, TimeUnit.MILLISECONDS)) return null
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Holds the gate through [block] and for [holdMillis] afterward, then releases it - for a
     * long-lived process (a chat turn or the interactive sign-in TUI) that keeps running once
     * started. The hold covers just the CLI's own vulnerable startup window rather than the
     * process's whole lifetime, so unrelated processes can start once that window has passed.
     */
    fun <T> acquireThenRelease(
        holdMillis: Long,
        block: () -> T,
    ): T? {
        if (!lock.tryLock(MAX_WAIT_MS, TimeUnit.MILLISECONDS)) return null
        try {
            val result = block()
            Thread.sleep(holdMillis)
            return result
        } finally {
            lock.unlock()
        }
    }

    /**
     * Points a one-shot launch's stdin at `/dev/null`.
     *
     * `ProcessBuilder` gives the child a pipe whose write end the JVM holds open, so a child that
     * reads stdin waits on input that never arrives and never gets EOF either. Measured on device:
     * `agy models` finishes in 6 seconds with stdin at `/dev/null` and was still running after 40
     * seconds with an open pipe - identical in every other respect. That hang is what made the model
     * catalogue come back empty and the picker show a placeholder.
     *
     * Only for non-interactive launches. The sign-in TUI needs its pipe: the Enter that picks Google
     * OAuth and the authorization code are both written into it.
     */
    fun ProcessBuilder.withoutStdin(): ProcessBuilder = redirectInput(java.io.File("/dev/null"))

    /**
     * Reads all of [process]'s stdout, but never blocks past [timeoutMillis] even if the process
     * neither produces output nor exits.
     *
     * `Process.waitFor(timeout, unit)` looks like it bounds a one-shot invocation, but a plain
     * `inputStream.bufferedReader().readText()` immediately before it does not: that call has no
     * timeout of its own, so a hung `agy` that never writes anything and never exits blocks there
     * forever - the `waitFor` timeout after it is never reached at all. Confirmed on a real device: a
     * `models` call that should time out in 45 seconds was still running 2+ minutes later. Reading on
     * a separate thread and joining it with a real deadline is what actually enforces one.
     */
    fun readWithTimeout(
        process: Process,
        timeoutMillis: Long,
    ): String? {
        var output: String? = null
        val reader =
            Thread {
                runCatching { output = process.inputStream.bufferedReader().readText() }
            }
        reader.isDaemon = true
        reader.start()
        reader.join(timeoutMillis)
        return if (reader.isAlive) null else output
    }
}
