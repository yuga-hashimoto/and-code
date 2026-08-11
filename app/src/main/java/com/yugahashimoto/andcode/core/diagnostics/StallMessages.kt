package com.yugahashimoto.andcode.core.diagnostics

import android.content.Context
import com.yugahashimoto.andcode.R

/**
 * Turns a diagnosis into the sentence shown to the user. The chat card and the notification say the
 * same thing about the same run, so the wording lives here rather than in either of them.
 */
fun StallDiagnosis.explain(context: Context): String =
    when (reason) {
        StallReason.RUNTIME_UNREACHABLE -> context.getString(R.string.chat_stall_reason_runtime_unreachable)
        StallReason.PROVIDER_ERROR -> context.getString(R.string.chat_stall_reason_provider_error)
        StallReason.COMPLETION_MISSED -> context.getString(R.string.chat_stall_reason_completion_missed)
        StallReason.AWAITING_PERMISSION -> context.getString(R.string.chat_stall_reason_awaiting_permission)
        StallReason.AWAITING_QUESTION -> context.getString(R.string.chat_stall_reason_awaiting_question)
        StallReason.STREAM_DISCONNECTED -> context.getString(R.string.chat_stall_reason_stream_disconnected)
        // A sentence of its own rather than a noun dropped into the one above: "The tool a tool is
        // still running" is not a sentence in any of the languages this ships in.
        StallReason.TOOL_RUNNING ->
            detail?.takeIf(String::isNotBlank)
                ?.let { context.getString(R.string.chat_stall_reason_tool_running, it) }
                ?: context.getString(R.string.chat_stall_reason_tool_running_unnamed)
        StallReason.NO_OUTPUT -> context.getString(R.string.chat_stall_reason_no_output)
    }

/**
 * The runtime's own words for the stall, when they add something the sentence above does not
 * already say. A tool name is already in that sentence, and the terminal reasons are reported
 * through the normal error surfaces, so only a failed probe leaves anything extra to show.
 */
fun StallDiagnosis.supportingDetail(): String? =
    detail?.takeIf { it.isNotBlank() && (reason == StallReason.RUNTIME_UNREACHABLE || reason == StallReason.STREAM_DISCONNECTED) }
