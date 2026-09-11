package com.yugahashimoto.andcode.runtime.local

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

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
 * is not applied, rather than failing a runtime start or a settings tap.
 *
 * The path goes through [manageablePathOrNull], which refuses anything resolving outside `rootfs`,
 * anything that is already a symlink, and anything that is not a plain file; the write then opens
 * the file `NOFOLLOW_LINKS`, so a symlink swapped in after that check fails the open instead of
 * being followed. Removal unlinks the name, which never follows either. What is left uncovered is a
 * *parent* directory swapped for a symlink inside that same window, which Java cannot close without
 * an `openat` walk - and it buys nothing, because the guest runs under PRoot as the app's own uid
 * and can write any of these files directly. These checks exist to keep AndCode from clobbering a
 * path the user has taken over, not as a privilege boundary.
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
        Files
            .newOutputStream(
                target.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS,
            ).use { out -> out.write(prompt.toByteArray()) }
    } catch (e: IOException) {
        // Skipped, as above - including the ELOOP a swapped-in symlink turns the open into.
    } catch (e: UnsupportedOperationException) {
        // A filesystem provider that will not take NOFOLLOW_LINKS as an open option. Skipping the
        // preset is the same best-effort outcome as any other failed write; it must not crash a
        // settings tap.
    }
}
