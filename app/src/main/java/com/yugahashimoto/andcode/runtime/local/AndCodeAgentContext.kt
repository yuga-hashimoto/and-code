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

/**
 * Size past which the sidecar is ignored rather than read. A legitimate one holds a line per
 * [AGENT_CONTEXT_PATHS] entry -- a short path plus a 64-character hash, a few hundred bytes in
 * total -- so this is already far more room than it can honestly need; it exists only so a
 * guest-planted file cannot make runtime startup read an arbitrary amount into memory.
 */
private const val MAX_WRITTEN_HASHES_BYTES = 64L * 1024

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
 * This function is best-effort from top to bottom: it must never abort or throw out of runtime
 * startup, which calls it on every launch (four call sites in `LocalRuntimeInstaller`). Every
 * filesystem-touching step -- canonicalizing `rootfs` itself, the staging copy, the written-hashes
 * sidecar, and each of the three instruction files -- is handled independently, and a filesystem
 * problem on any single one of them is skipped rather than propagated:
 * - Canonicalizing `rootfs` can itself throw; that falls back to its absolute path rather than
 *   aborting before any guard below even runs (see the fallback comment at the call site).
 * - The staging copy at [RUNTIME_CONTEXT_PATH] is only a convenience artifact nothing else reads,
 *   so an unmanageable or unwritable path there is simply skipped.
 * - The written-hashes sidecar being unmanageable, unreadable, oversized, or unwritable does not
 *   stop the three instruction files below from being (re)installed. Any of those is treated as
 *   an empty history: a target that already exists and differs from the current blurb is then
 *   treated as user-edited and left alone, which is the safe direction. A sidecar that can't be
 *   persisted afterwards simply means the write is retried on the next run; the install itself
 *   still succeeds.
 * - Each of the three instruction files is handled independently: hashing or writing one target
 *   can throw (an unreadable file, a parent path replaced with a file, permission errors) without
 *   affecting the other two, and without recording that target's hash as successfully written.
 *
 * The trade-off is that a failed install is silent: there is no signal back to the caller when a
 * path was skipped, by design, since surfacing or retrying failures is not worth risking runtime
 * startup over a best-effort convenience file.
 *
 * The blurb's hash is computed directly from [agentContext] (see [sha256Hex]) rather than from
 * the staging file, so installing the instruction files never depends on that staging copy
 * existing.
 */
internal fun installAndCodeAgentContext(
    rootfs: File,
    agentContext: ByteArray,
) {
    // Best-effort: File.canonicalFile can throw IOException on a filesystem loop or I/O error
    // around rootfs itself, which must not crash runtime startup before any guard below even
    // runs. Falling back to absoluteFile only ever makes manageablePathOrNull's rootfs-prefix
    // check *stricter*, never more permissive: every target path is still canonicalized and
    // compared against this same value, so if rootfs sits behind a symlink the comparison simply
    // stops matching and every path is refused as unmanageable, rather than silently widening
    // what counts as "inside rootfs".
    val rootfsCanonical =
        try {
            rootfs.canonicalFile
        } catch (e: IOException) {
            rootfs.absoluteFile
        }
    val agentContextHash = sha256Hex(agentContext)

    // Best-effort: this is only the app's own staging copy of the blurb; nothing below depends on
    // it existing, so neither an unmanageable path nor a failing write here may stop the real
    // install. The bytes go down unconditionally rather than being compared against what is on
    // disk first: reading that file back would mean loading whatever the guest left in its place
    // into memory, and rewriting a kilobyte on each runtime start is cheaper than that risk.
    manageablePathOrNull(rootfsCanonical, File(rootfs, RUNTIME_CONTEXT_PATH))?.let { source ->
        try {
            source.parentFile?.mkdirs()
            source.writeBytes(agentContext)
        } catch (e: IOException) {
            // Skip the staging copy -- see the comment above.
        }
    }

    val writtenHashesFile = manageablePathOrNull(rootfsCanonical, File(rootfs, WRITTEN_HASHES_PATH))
    // An unmanageable or unreadable sidecar degrades to "no recorded hashes" rather than aborting
    // -- see the safe-to-manage comment below for why that is still correct.
    val writtenHashes = writtenHashesFile?.let(::readWrittenHashes).orEmpty().toMutableMap()
    var hashesChanged = false

    AGENT_CONTEXT_PATHS.forEach { relativePath ->
        val target = manageablePathOrNull(rootfsCanonical, File(rootfs, relativePath)) ?: return@forEach
        // Best-effort per target: hashing or writing this one path can still throw (unreadable
        // file, a parent replaced with a file, permission errors) even though manageablePathOrNull
        // passed moments ago. That must skip only this target, not abort the other two or runtime
        // startup, which is what the KDoc promises. If the write below fails partway, currentHash
        // is left null/stale and writtenHashes is never touched, so this target's hash is not
        // recorded as successfully written -- a later run will not mistake a failed write for
        // "still what we wrote".
        try {
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
        } catch (e: IOException) {
            // Skip just this target -- see the comment above.
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
 * Reads the sidecar [writeWrittenHashes] left behind, bounded in both size and content.
 *
 * The file lives in the guest filesystem, where an agent can replace it with something enormous,
 * so anything larger than [MAX_WRITTEN_HASHES_BYTES] is ignored outright and the lines are
 * streamed rather than slurped. That size cap is what actually protects startup here: an
 * `OutOfMemoryError` from reading a huge file whole is an `Error`, not an [IOException], so the
 * catch below would not stop it. Only keys naming a real [AGENT_CONTEXT_PATHS] entry are kept,
 * which bounds the returned map no matter what the file holds.
 *
 * Best-effort otherwise: an [IOException] (a transient I/O error, permissions, etc.) must not
 * abort [installAndCodeAgentContext] or the runtime startup that calls it, so it is treated the
 * same as "no sidecar yet" -- an empty map.
 */
private fun readWrittenHashes(file: File): Map<String, String> {
    if (!file.isFile) return emptyMap()
    if (file.length() > MAX_WRITTEN_HASHES_BYTES) return emptyMap()
    return try {
        file.useLines { lines ->
            lines
                .mapNotNull { line ->
                    val separator = line.indexOf('\t')
                    if (separator < 0) return@mapNotNull null
                    val path = line.substring(0, separator)
                    if (path !in AGENT_CONTEXT_PATHS) return@mapNotNull null
                    path to line.substring(separator + 1)
                }
                .toMap()
        }
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
