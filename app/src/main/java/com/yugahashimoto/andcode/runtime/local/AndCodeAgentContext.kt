package com.yugahashimoto.andcode.runtime.local

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.Files

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
 */
internal fun installAndCodeAgentContext(
    rootfs: File,
    agentContext: ByteArray,
) {
    val rootfsCanonical = rootfs.canonicalFile

    val source = manageablePathOrNull(rootfsCanonical, File(rootfs, RUNTIME_CONTEXT_PATH)) ?: return
    source.parentFile?.mkdirs()
    if (!source.isFile || !source.readBytes().contentEquals(agentContext)) {
        source.writeBytes(agentContext)
    }

    val writtenHashesFile = manageablePathOrNull(rootfsCanonical, File(rootfs, WRITTEN_HASHES_PATH)) ?: return
    val writtenHashes = readWrittenHashes(writtenHashesFile).toMutableMap()
    val agentContextHash = RuntimeArchive.sha256(source)
    var hashesChanged = false

    AGENT_CONTEXT_PATHS.forEach { relativePath ->
        val target = manageablePathOrNull(rootfsCanonical, File(rootfs, relativePath)) ?: return@forEach
        val currentHash = if (target.isFile) RuntimeArchive.sha256(target) else null
        // Safe to (re)write when there is nothing there yet, when it already holds exactly the
        // current blurb (a no-op either way), or when it still matches the last content we wrote.
        // Anything else means the user has put their own content in the file since.
        val safeToManage =
            currentHash == null || currentHash == agentContextHash || currentHash == writtenHashes[relativePath]
        if (!safeToManage) {
            // The file no longer matches what we last wrote: the user (or the agent, on their
            // behalf) has customized it since. Leave their content alone.
            return@forEach
        }
        if (currentHash != agentContextHash) {
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
        }
        if (writtenHashes[relativePath] != agentContextHash) {
            writtenHashes[relativePath] = agentContextHash
            hashesChanged = true
        }
    }

    if (hashesChanged) {
        writeWrittenHashes(writtenHashesFile, writtenHashes)
    }
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

private fun readWrittenHashes(file: File): Map<String, String> {
    if (!file.isFile) return emptyMap()
    return file.readLines()
        .mapNotNull { line ->
            val separator = line.indexOf('\t')
            if (separator < 0) return@mapNotNull null
            line.substring(0, separator) to line.substring(separator + 1)
        }
        .toMap()
}

private fun writeWrittenHashes(
    file: File,
    hashes: Map<String, String>,
) {
    file.parentFile?.mkdirs()
    file.writeText(hashes.entries.joinToString("\n") { (path, hash) -> "$path\t$hash" })
}
