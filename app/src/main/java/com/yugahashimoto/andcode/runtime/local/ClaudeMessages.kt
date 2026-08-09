package com.yugahashimoto.andcode.runtime.local

import android.content.Context
import com.yugahashimoto.andcode.R

/**
 * User-visible text produced by the Claude Code runtime.
 *
 * Mirrors [LocalRuntimeMessages]: the runtime layer stays free of a [Context] while its failure
 * messages are still translated like the rest of the UI.
 */
interface ClaudeMessages {
    val runtimeMissing: String
    val signInStartFailed: String
    val signInIncomplete: String
    val submitCodeFailed: String
    val installFailed: String
    val updateFailed: String

    fun signInExited(exitCode: Int): String

    fun processExited(
        exitCode: Int?,
        detail: String?,
    ): String

    /** English fallbacks for unit tests and any construction path without a [Context]. */
    companion object Default : ClaudeMessages {
        override val runtimeMissing = "The Linux environment is not installed yet"
        override val signInStartFailed = "Could not start Claude Code sign-in"
        override val signInIncomplete = "Sign-in did not complete"
        override val submitCodeFailed = "Could not submit the code"
        override val installFailed = "Claude Code installation failed"
        override val updateFailed = "Claude Code update failed"

        override fun signInExited(exitCode: Int) = "Claude Code sign-in stopped (exit code $exitCode)"

        override fun processExited(
            exitCode: Int?,
            detail: String?,
        ): String {
            val cause = detail ?: exitCode?.let { "exit code $it" } ?: "process exited"
            return "Claude Code stopped before finishing the turn ($cause)"
        }
    }
}

class AndroidClaudeMessages(private val context: Context) : ClaudeMessages {
    override val runtimeMissing get() = context.getString(R.string.claude_error_runtime_missing)
    override val signInStartFailed get() = context.getString(R.string.claude_error_sign_in_start)
    override val signInIncomplete get() = context.getString(R.string.claude_error_sign_in_incomplete)
    override val submitCodeFailed get() = context.getString(R.string.claude_error_submit_code)
    override val installFailed get() = context.getString(R.string.claude_error_install_failed)
    override val updateFailed get() = context.getString(R.string.claude_error_update_failed)

    override fun signInExited(exitCode: Int): String = context.getString(R.string.claude_error_sign_in_exit, exitCode)

    override fun processExited(
        exitCode: Int?,
        detail: String?,
    ): String {
        val cause = detail ?: exitCode?.let { "exit code $it" } ?: "process exited"
        return context.getString(R.string.claude_error_process_exited, cause)
    }
}
