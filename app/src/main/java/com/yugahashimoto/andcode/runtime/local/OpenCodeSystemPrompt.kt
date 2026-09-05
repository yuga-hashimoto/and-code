package com.yugahashimoto.andcode.runtime.local

import java.io.File
import java.io.IOException

/**
 * Where the selected preset is written inside the guest filesystem.
 *
 * Listed in the `instructions` OpenCode is launched with (see
 * `AND_CODE_OPENCODE_CONFIG_CONTENT`), and deliberately separate from the AndCode environment
 * blurb at `and-code-context.md`: that one is a file the user may take over and edit, which
 * [installAndCodeAgentContext] then stops managing, while this one is rewritten on every switch.
 */
internal const val OPENCODE_SYSTEM_PROMPT_PATH = "root/.config/opencode/and-code-system-prompt.md"

/**
 * Puts the selected system-prompt preset where OpenCode will read it.
 *
 * OpenCode has no per-message system prompt to pass - the `system` field its message endpoint
 * accepts is recorded on the message and never reaches the model - so the preset travels as an
 * instructions file instead. `Instruction.system()` re-reads the file's *content* on every turn and
 * appends it alongside the environment and skill prompts, so switching presets takes effect on the
 * next message. Only the file's *path* is fixed at launch, by the config the server starts with.
 *
 * Two consequences the UI has to own, which is why this is offered as an OpenCode-wide default
 * rather than a per-chat chip like Claude Code's:
 * - every session on this runtime shares it, including ones already open;
 * - a server already running from before the path was added to its config ignores the file until
 *   the runtime restarts.
 *
 * Best-effort like the rest of the guest-filesystem writes: a preset that cannot be written simply
 * is not applied, rather than failing a runtime start or a settings tap. Every path goes through
 * [manageablePathOrNull], so a guest that replaces the file (or a parent) with a symlink pointing
 * out of the sandbox cannot make this write land outside `rootfs`.
 */
internal fun applyOpenCodeSystemPrompt(
    rootfs: File,
    prompt: String?,
) {
    val rootfsCanonical =
        try {
            rootfs.canonicalFile
        } catch (e: IOException) {
            rootfs.absoluteFile
        }
    val target = manageablePathOrNull(rootfsCanonical, File(rootfs, OPENCODE_SYSTEM_PROMPT_PATH)) ?: return
    try {
        if (prompt.isNullOrBlank()) {
            // Removed rather than blanked: an empty file would still be read and announced to the
            // model as "Instructions from: ...", which is worse than no instruction at all.
            target.delete()
            return
        }
        target.parentFile?.mkdirs()
        target.writeText(prompt)
    } catch (e: IOException) {
        // Skipped, as above.
    }
}
