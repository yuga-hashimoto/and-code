package com.yugahashimoto.andcode.runtime.local

import android.content.Context
import java.io.File

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
 */
internal fun installAndCodeAgentContext(
    rootfs: File,
    agentContext: ByteArray,
) {
    val source = File(rootfs, RUNTIME_CONTEXT_PATH)
    source.parentFile?.mkdirs()
    if (!source.isFile || !source.readBytes().contentEquals(agentContext)) {
        source.writeBytes(agentContext)
    }

    val writtenHashes = readWrittenHashes(rootfs).toMutableMap()
    val agentContextHash = RuntimeArchive.sha256(source)
    var hashesChanged = false

    val rootfsCanonical = rootfs.canonicalFile
    AGENT_CONTEXT_PATHS.forEach { relativePath ->
        val target = File(rootfs, relativePath)
        // The rootfs is a PRoot guest filesystem an agent can write arbitrary files into. If
        // `target` (or a parent directory) was replaced with a symlink pointing outside rootfs,
        // following it here would let this write clobber a file elsewhere on the device. Refuse
        // to manage anything whose canonical path has escaped rootfs instead of writing through
        // the link.
        if (!target.canonicalFile.toPath().startsWith(rootfsCanonical.toPath())) {
            return@forEach
        }
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
        writeWrittenHashes(rootfs, writtenHashes)
    }
}

private fun readWrittenHashes(rootfs: File): Map<String, String> {
    val file = File(rootfs, WRITTEN_HASHES_PATH)
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
    rootfs: File,
    hashes: Map<String, String>,
) {
    val file = File(rootfs, WRITTEN_HASHES_PATH)
    file.parentFile?.mkdirs()
    file.writeText(hashes.entries.joinToString("\n") { (path, hash) -> "$path\t$hash" })
}
