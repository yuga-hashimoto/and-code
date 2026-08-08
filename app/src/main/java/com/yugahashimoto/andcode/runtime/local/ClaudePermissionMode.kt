package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.R

/**
 * How Claude Code handles tool permissions for a session.
 *
 * When AndCode's PermissionRequest hook bridge is installed, [ASK] routes unmatched tools to the
 * Android approval UI. Without the bridge, prefer [ACCEPT_EDITS] (pre-approves Bash) so the CLI
 * does not hang waiting for a prompt nobody can answer.
 */
enum class ClaudePermissionMode(
    val cliValue: String,
    val labelRes: Int,
    val descriptionRes: Int,
    /**
     * Tools pre-approved for the session, passed as `--allowedTools`.
     *
     * Used when the mode auto-approves a subset and still needs a prompt channel for the rest.
     * [ASK] leaves this empty so every unmatched call reaches the PermissionRequest hook.
     */
    val allowedTools: List<String> = emptyList(),
    /** True when this mode expects the AndCode permission bridge to answer prompts. */
    val requiresBridge: Boolean = false,
) {
    PLAN("plan", R.string.claude_permission_plan, R.string.claude_permission_plan_desc),
    ASK(
        "default",
        R.string.claude_permission_ask,
        R.string.claude_permission_ask_desc,
        requiresBridge = true,
    ),
    ACCEPT_EDITS(
        "acceptEdits",
        R.string.claude_permission_accept_edits,
        R.string.claude_permission_accept_edits_desc,
        // Commands stay auto-approved so Accept edits remains useful without prompting every Bash.
        // File edits are covered by acceptEdits itself; other tools may still hit the bridge.
        allowedTools = listOf("Bash"),
    ),
    FULL_ACCESS("bypassPermissions", R.string.claude_permission_full_access, R.string.claude_permission_full_access_desc),
    ;

    companion object {
        /** Safe default until the user opts into [ASK] after the bridge is ready. */
        val DEFAULT = ACCEPT_EDITS

        fun fromCliValue(value: String?): ClaudePermissionMode = entries.firstOrNull { it.cliValue == value } ?: DEFAULT
    }
}
