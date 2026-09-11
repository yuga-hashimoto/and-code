package com.yugahashimoto.andcode.runtime.local

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
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
 * The new prompt is staged beside the target and renamed onto it, never written into it in place:
 * OpenCode re-reads this file every turn, so truncating the live file would let a turn that starts
 * mid-write see an empty or half-written prompt. A rename swaps the name in one step, so every read
 * sees either the old prompt or the new one.
 *
 * The path goes through [manageablePathOrNull], which refuses anything resolving outside `rootfs`,
 * anything that is already a symlink, and anything that is not a plain file; the staging file is
 * then opened `NOFOLLOW_LINKS`, and the rename and the removal both act on the name rather than
 * following it. What is left uncovered is a *parent* directory swapped for a symlink between the
 * check and the write, which Java cannot close without an `openat` walk - and it buys nothing,
 * because the guest runs under PRoot as the app's own uid and can write any of these files
 * directly. These checks exist to keep AndCode from clobbering a path the user has taken over, not
 * as a privilege boundary.
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
        // Same directory, so the rename below stays within one filesystem and can be atomic.
        val staging = File(target.parentFile, "${target.name}.staged")
        try {
            Files
                .newOutputStream(
                    staging.toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS,
                ).use { out -> out.write(prompt.toByteArray()) }
            // REPLACE_EXISTING alongside ATOMIC_MOVE, as LocalProviderCredentialStore does:
            // ATOMIC_MOVE leaves replacement of an existing target provider-defined, and a
            // provider that refuses one would fail every switch after the first.
            Files.move(
                staging.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            // A no-op once the move succeeded; on any failure it clears the half-written staging
            // file so the next switch does not inherit it.
            staging.delete()
        }
    } catch (e: IOException) {
        // Skipped, as above - including the ELOOP a swapped-in symlink turns the open into.
    } catch (e: UnsupportedOperationException) {
        // A filesystem provider that will not take NOFOLLOW_LINKS as an open option. Skipping the
        // preset is the same best-effort outcome as any other failed write; it must not crash a
        // settings tap.
    }
}
