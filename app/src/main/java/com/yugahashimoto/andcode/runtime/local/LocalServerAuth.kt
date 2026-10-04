package com.yugahashimoto.andcode.runtime.local

import java.io.File
import java.security.SecureRandom

/**
 * The HTTP basic-auth secret shared between AndCode and its own on-device OpenCode server.
 *
 * `opencode serve` leaves its API unauthenticated unless `OPENCODE_SERVER_PASSWORD` is set, and it
 * listens on loopback - reachable by any other installed app that holds the INTERNET permission.
 * That would let another app drive sessions, run shell commands inside the sandbox, and read the
 * credentials exported into the runtime's environment.
 *
 * The secret is persisted in the app-private runtime directory rather than generated per launch, so
 * the server (which can outlive our process and is restarted on boot) and this client keep agreeing
 * on it. App-private storage is unreadable by other apps, so the value never leaves the device.
 */
object LocalServerAuth {
    /** Matches OpenCode's default basic-auth username. */
    const val USERNAME: String = "opencode"

    const val PASSWORD_ENV: String = "OPENCODE_SERVER_PASSWORD"

    private const val FILE_NAME = "server-password"
    private const val SECRET_BYTES = 32

    /**
     * Returns the runtime's auth secret, generating and persisting one on first use.
     *
     * Called both when launching the server and when building the local client profile, so the same
     * value reaches both sides even across app restarts.
     */
    fun password(runtimeDirectory: File): String {
        val file = File(runtimeDirectory, FILE_NAME)
        read(file)?.let { return it }
        return synchronized(this) {
            read(file) ?: generate().also { secret ->
                file.parentFile?.mkdirs()
                file.writeText(secret)
            }
        }
    }

    private fun read(file: File): String? =
        runCatching { file.takeIf(File::isFile)?.readText()?.trim() }
            .getOrNull()
            ?.takeIf(String::isNotEmpty)

    private fun generate(): String =
        ByteArray(SECRET_BYTES)
            .also(SecureRandom()::nextBytes)
            .joinToString("") { byte -> "%02x".format(byte) }
}
