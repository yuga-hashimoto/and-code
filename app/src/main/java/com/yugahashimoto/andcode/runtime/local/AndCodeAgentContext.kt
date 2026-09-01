package com.yugahashimoto.andcode.runtime.local

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

internal const val AND_CODE_AGENT_CONTEXT_ASSET = "and-code-agent-context.md"

private const val RUNTIME_CONTEXT_PATH = "root/.config/and-code/agent-context.md"

/**
 * Records the hash of what AndCode itself last wrote to each path in [AGENT_CONTEXT_PATHS], so a
 * later run can tell "still what we wrote" apart from "the user edited this since". One line per
 * entry, `<relativePath>\t<sha256>`.
 */
private const val WRITTEN_HASHES_PATH = "root/.config/and-code/agent-context-written.tsv"

private val AGENT_CONTEXT_PATHS =
    listOf(
        "root/.config/opencode/and-code-context.md",
        "root/.claude/CLAUDE.md",
        "root/.gemini/GEMINI.md",
    )

internal fun ensureAndCodeAgentContext(
    rootfs: File,
    context: Context,
) {
    val agentContext =
        context.assets.open(AND_CODE_AGENT_CONTEXT_ASSET).use { input ->
            input.readBytes()
        }
    installAndCodeAgentContext(rootfs, agentContext)
}

/**
 * Writes the AndCode environment blurb into each coding agent's instructions file (Claude Code's
 * CLAUDE.md, Gemini's GEMINI.md, and OpenCode's instructions file).
 *
 * These are the exact files users write their own custom instructions into, and this runs on
 * every runtime start, so it must never clobber content the user has added. A path is only
 * (re)written when its current content still matches the hash of what *we* wrote there last time
 * -- once the file diverges from that (the user edited it, or created it themselves), it is left
 * alone from then on, even as the bundled blurb changes in later app updates.
 *
 * Every path this function touches lives inside the PRoot guest filesystem, which an agent can
 * write arbitrary content into -- including replacing one of these paths (or a parent directory)
 * with a symlink that resolves outside `rootfs`. [manageablePathOrNull] guards every read and
 * write below so none of them ever follow such a link off the guest filesystem.
 *
 * Every one of those guarded paths degrades independently rather than aborting the whole install:
 * - The staging copy at [RUNTIME_CONTEXT_PATH] is only a convenience artifact nothing else reads,
 *   so an unmanageable path there is simply skipped.
 * - The written-hashes sidecar being unmanageable, unreadable, or unwritable does not stop the
 *   three instruction files below from being (re)installed. An unreadable sidecar is treated as
 *   an empty history: a target that already exists and differs from the current blurb is then
 *   treated as user-edited and left alone, which is the safe direction. A sidecar that can't be
 *   persisted afterwards simply means the write is retried on the next run; the install itself
 *   still succeeds.
 * - Each of the three instruction files is handled independently: an unmanageable path for one
 *   target does not affect the others.
 *
 * The blurb's hash is computed directly from [agentContext] (see [sha256Hex]) rather than from
 * the staging file, so installing the instruction files never depends on that staging copy
 * existing.
 */
internal fun installAndCodeAgentContext(
    rootfs: File,
    agentContext: ByteArray,
) {
    val rootfsCanonical = rootfs.canonicalFile
    val agentContextHash = sha256Hex(agentContext)

    // Best-effort: this is only the app's own staging copy of the blurb; nothing below depends
    // on it existing, so an unmanageable path here must not stop the real install.
    manageablePathOrNull(rootfsCanonical, File(rootfs, RUNTIME_CONTEXT_PATH))?.let { source ->
        source.parentFile?.mkdirs()
        if (!source.isFile || !source.readBytes().contentEquals(agentContext)) {
            source.writeBytes(agentContext)
        }
    }

    val writtenHashesFile = manageablePathOrNull(rootfsCanonical, File(rootfs, WRITTEN_HASHES_PATH))
    // An unmanageable or unreadable sidecar degrades to "no recorded hashes" rather than aborting
    // -- see the safe-to-manage comment below for why that is still correct.
    val writtenHashes = writtenHashesFile?.let(::readWrittenHashes).orEmpty().toMutableMap()
    var hashesChanged = false

    AGENT_CONTEXT_PATHS.forEach { relativePath ->
        val target = manageablePathOrNull(rootfsCanonical, File(rootfs, relativePath)) ?: return@forEach
        val currentHash = if (target.isFile) RuntimeArchive.sha256(target) else null
        // Safe to (re)write when there is nothing there yet, when it already holds exactly the
        // current blurb (a no-op either way), or when it still matches the last content we wrote.
        // Anything else means the user has put their own content in the file since. When the
        // sidecar could not be read, writtenHashes is empty, so this only stays safe for the
        // first two cases -- an existing, differing file is (correctly) left alone.
        val safeToManage =
            currentHash == null || currentHash == agentContextHash || currentHash == writtenHashes[relativePath]
        if (!safeToManage) {
            // The file no longer matches what we last wrote: the user (or the agent, on their
            // behalf) has customized it since. Leave their content alone.
            return@forEach
        }
        if (currentHash != agentContextHash) {
            target.parentFile?.mkdirs()
            target.writeBytes(agentContext)
        }
        if (writtenHashes[relativePath] != agentContextHash) {
            writtenHashes[relativePath] = agentContextHash
            hashesChanged = true
        }
    }

    // Persisting the sidecar is also best-effort: if the path is unmanageable, or was moments
    // ago but the write itself now fails, the files above are already installed correctly, and
    // the sidecar write is simply retried on the next run.
    if (hashesChanged && writtenHashesFile != null) {
        writeWrittenHashes(writtenHashesFile, writtenHashes)
    }
}

/** Lowercase-hex SHA-256 of [bytes], in the same format as [RuntimeArchive.sha256]. */
private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

/**
 * Resolves [target]'s canonical path and returns it only when that path is still inside
 * [rootfsCanonical], [target] is not itself a symlink, and [target] is either absent or a regular
 * file.
 *
 * Rejects anything that has escaped rootfs via a symlink, any non-regular-file target (a
 * directory, fifo, etc. left in its place would make `copyTo`/`writeBytes` throw), and any path
 * whose canonicalization itself fails -- `File.canonicalFile` can throw [IOException] on a
 * filesystem loop or I/O error, which must not crash runtime startup.
 *
 * A symlink *at* [target] is rejected outright, even a dangling one whose target does not exist
 * yet. This is not redundant with the canonical-in-rootfs check above: when a symlink's target
 * does not exist, `File.canonicalPath` can't resolve it via realpath, so the JDK falls back to
 * resolving the existing parent and appending the link's own name -- which makes a dangling link
 * canonicalize to its own (in-rootfs) path, sailing past the rootfs check with `exists()` false,
 * as if nothing were there yet. `Files.isSymbolicLink` checks the link itself rather than
 * following it, so it catches this case too. (A symlinked *parent* directory is still caught by
 * the canonical-in-rootfs check, since the parent does exist and canonicalizes normally.)
 */
private fun manageablePathOrNull(
    rootfsCanonical: File,
    target: File,
): File? {
    val canonical =
        try {
            target.canonicalFile
        } catch (e: IOException) {
            return null
        }
    if (!canonical.toPath().startsWith(rootfsCanonical.toPath())) return null
    if (Files.isSymbolicLink(target.toPath())) return null
    if (target.exists() && !target.isFile) return null
    return target
}

/**
 * Best-effort: an [IOException] reading the sidecar (a transient I/O error, permissions, etc.)
 * must not abort [installAndCodeAgentContext] or the runtime startup that calls it, so it is
 * treated the same as "no sidecar yet" -- an empty map.
 */
private fun readWrittenHashes(file: File): Map<String, String> {
    if (!file.isFile) return emptyMap()
    return try {
        file.readLines()
            .mapNotNull { line ->
                val separator = line.indexOf('\t')
                if (separator < 0) return@mapNotNull null
                line.substring(0, separator) to line.substring(separator + 1)
            }
            .toMap()
    } catch (e: IOException) {
        emptyMap()
    }
}

/**
 * Best-effort: an [IOException] persisting the sidecar must not fail the install that already
 * succeeded in (re)writing the instruction files above -- it is simply retried on the next run.
 */
private fun writeWrittenHashes(
    file: File,
    hashes: Map<String, String>,
) {
    try {
        file.parentFile?.mkdirs()
        file.writeText(hashes.entries.joinToString("\n") { (path, hash) -> "$path\t$hash" })
    } catch (e: IOException) {
        // Ignored: see the KDoc above.
    }
}
