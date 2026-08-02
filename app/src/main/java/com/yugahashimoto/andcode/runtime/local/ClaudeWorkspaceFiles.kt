package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeFileContent
import com.yugahashimoto.andcode.core.api.OpenCodeFileNode
import com.yugahashimoto.andcode.core.api.OpenCodeSearchMatch
import com.yugahashimoto.andcode.core.api.OpenCodeSearchSubmatch
import com.yugahashimoto.andcode.core.api.OpenCodeSearchText
import java.io.File

/**
 * File access for the Claude Code runtime.
 *
 * OpenCode answers the explorer's file questions over HTTP; Claude Code has no such server. It does
 * not need one: `/workspace` inside the sandbox is a plain directory on the device, so these read it
 * directly. Without this the explorer throws "unsupported" the moment a Claude session is open.
 *
 * [rootfsHostDir] is the Linux rootfs, for workspaces set to a folder outside the `/workspace` mount.
 */
class ClaudeWorkspaceFiles(
    private val workspaceHostDir: File,
    private val rootfsHostDir: File? = null,
) {
    fun list(
        directory: String,
        path: String,
    ): List<OpenCodeFileNode> {
        // Canonical throughout: listFiles() returns children of the resolved directory, and a
        // relative path taken against an unresolved root climbs back out through every symlink.
        val root = canonical(resolveRoot(directory)) ?: return emptyList()
        val target = resolve(root, path) ?: return emptyList()
        val children = target.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).orEmpty()
        return children.map { child ->
            OpenCodeFileNode(
                name = child.name,
                path = child.relativeTo(root).path,
                absolute = sandboxPath(directory, child.relativeTo(root).path),
                type = if (child.isDirectory) "directory" else "file",
                ignored = child.name.startsWith("."),
            )
        }
    }

    fun read(
        directory: String,
        path: String,
    ): OpenCodeFileContent {
        val root = canonical(resolveRoot(directory))
        val file = root?.let { resolve(it, path) }
        require(file != null && file.isFile) { "File not found: $path" }
        require(file.length() <= MAX_READ_BYTES) { "File is too large to open" }
        val bytes = file.readBytes()
        // A NUL byte in the first block is the usual signal that this is not text.
        val binary = bytes.take(BINARY_SNIFF_BYTES).any { it == 0.toByte() }
        return OpenCodeFileContent(
            type = if (binary) "binary" else "text",
            content = if (binary) "" else String(bytes),
        )
    }

    fun find(
        directory: String,
        query: String,
        includeDirectories: Boolean?,
        limit: Int?,
    ): List<String> {
        val root = canonical(resolveRoot(directory)) ?: return emptyList()
        if (query.isBlank()) return emptyList()
        return walk(root)
            .filter { includeDirectories == true || it.isFile }
            .filter { it.name.contains(query, ignoreCase = true) }
            .map { it.relativeTo(root).path }
            .take(limit ?: DEFAULT_LIMIT)
            .toList()
    }

    fun search(
        directory: String,
        pattern: String,
    ): List<OpenCodeSearchMatch> {
        val root = canonical(resolveRoot(directory)) ?: return emptyList()
        if (pattern.isBlank()) return emptyList()
        val matches = mutableListOf<OpenCodeSearchMatch>()
        for (file in walk(root).filter { it.isFile && it.length() <= MAX_READ_BYTES }) {
            if (matches.size >= DEFAULT_LIMIT) break
            val relative = file.relativeTo(root).path
            runCatching {
                file.useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        if (matches.size >= DEFAULT_LIMIT) return@forEachIndexed
                        val column = line.indexOf(pattern, ignoreCase = true)
                        if (column < 0) return@forEachIndexed
                        matches +=
                            OpenCodeSearchMatch(
                                path = OpenCodeSearchText(relative),
                                lines = OpenCodeSearchText(line.take(MAX_LINE_CHARS)),
                                lineNumber = index + 1,
                                absoluteOffset = column,
                                submatches = listOf(OpenCodeSearchSubmatch(OpenCodeSearchText(pattern), column, column + pattern.length)),
                            )
                    }
                }
            }
        }
        return matches
    }

    /**
     * Lines in a file, or null when it cannot be counted.
     *
     * Untracked files are absent from `git diff`, so their size has to come from the file itself if
     * the changes list is to say the same thing OpenCode's server says about the same repository.
     */
    fun countLines(
        directory: String,
        path: String,
    ): Int? {
        val root = canonical(resolveRoot(directory)) ?: return null
        val file = resolve(root, path)?.takeIf { it.isFile && it.length() <= MAX_READ_BYTES } ?: return null
        return runCatching { file.useLines { lines -> lines.count() } }.getOrNull()
    }

    private fun walk(root: File) =
        root.walkTopDown()
            .onEnter { it.name != ".git" && it.name != "node_modules" }
            .maxDepth(MAX_DEPTH)

    /**
     * Host directory backing [directory].
     *
     * Sessions record sandbox paths such as `/workspace/project`; everything under `/workspace` maps
     * into the app's own workspace directory. A workspace can also be set to a folder the Linux
     * environment already has — `/root/project`, say — and those live in the rootfs instead; without
     * [rootfsHostDir] they were resolved under the workspace mount, which is a different folder
     * entirely.
     */
    private fun resolveRoot(directory: String): File {
        val trimmed = directory.trim().trimEnd('/')
        if (trimmed == WORKSPACE_MOUNT || trimmed.startsWith("$WORKSPACE_MOUNT/")) {
            val relative = trimmed.removePrefix(WORKSPACE_MOUNT).trim('/')
            return if (relative.isEmpty()) workspaceHostDir else File(workspaceHostDir, relative)
        }
        val rootfs = rootfsHostDir ?: return File(workspaceHostDir, trimmed.trim('/'))
        val relative = trimmed.trim('/')
        return if (relative.isEmpty()) rootfs else File(rootfs, relative)
    }

    private fun sandboxPath(
        directory: String,
        relative: String,
    ): String = directory.trimEnd('/') + "/" + relative

    private fun canonical(file: File): File? = runCatching { file.canonicalFile }.getOrNull()

    /** Null when [path] escapes [root]; the explorer must not reach outside the workspace. */
    private fun resolve(
        root: File,
        path: String,
    ): File? {
        val candidate = if (path.isBlank() || path == ".") root else File(root, path)
        val resolved = canonical(candidate) ?: return null
        return resolved.takeIf { it == root || it.path.startsWith(root.path + File.separator) }
    }

    private companion object {
        const val WORKSPACE_MOUNT = "/workspace"
        const val MAX_READ_BYTES = 2L * 1024 * 1024
        const val BINARY_SNIFF_BYTES = 1024
        const val DEFAULT_LIMIT = 200
        const val MAX_DEPTH = 12
        const val MAX_LINE_CHARS = 400
    }
}
