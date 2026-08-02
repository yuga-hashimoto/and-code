package com.yugahashimoto.andcode.feature.workspace

import java.io.File

/**
 * Walks the on-device Linux environment so the user can point a workspace at a folder that is
 * already there.
 *
 * Importing a folder and cloning a repository both create something new under `/workspace`. Neither
 * helps with a directory the environment already has — a repository cloned from the terminal, or
 * anything under `/root` — so those were unreachable from the settings screen.
 *
 * The listing is read from the host filesystem rather than through an agent: the rootfs is a plain
 * directory tree inside the app's own storage, so this works while the runtime is stopped, which is
 * exactly when a user is setting a workspace up.
 */
class RuntimeFolderBrowser(
    private val rootfsHostDir: File,
    private val workspaceHostDir: File,
) {
    /** False before the runtime is installed, when there is no filesystem to walk yet. */
    fun isAvailable(): Boolean = rootfsHostDir.isDirectory

    fun children(path: String): List<String> {
        val host = WorkspaceFolders.hostDirectory(rootfsHostDir, workspaceHostDir, path) ?: return emptyList()
        val names =
            host.listFiles().orEmpty()
                .filter { it.isDirectory }
                .map { it.name }
        // `/workspace` is a bind mount, so the rootfs usually has no entry for it and the folder
        // holding every imported and cloned project would be missing from the top level.
        val merged =
            if (WorkspaceFolders.normalize(path) == WorkspaceFolders.GUEST_ROOT) {
                names + WorkspaceFolders.displayName(WorkspaceFolders.WORKSPACE_ROOT)
            } else {
                names
            }
        return merged.distinct().sortedWith(compareBy({ it.startsWith(".") }, { it.lowercase() }))
    }
}
