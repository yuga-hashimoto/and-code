package com.yugahashimoto.andcode.feature.workspace

import com.yugahashimoto.andcode.core.storage.DeviceStorage
import java.io.File

/**
 * Walks what the on-device sandbox can see, so the user can point a workspace at a folder that is
 * already there.
 *
 * Importing a folder and cloning a repository both create something new under `/workspace`. Neither
 * helps with a directory that already exists — a repository cloned from the terminal, anything
 * under `/root`, or a project the user keeps in the phone's own `Download` folder — so those were
 * unreachable from the settings screen.
 *
 * The listing is read from the host filesystem rather than through an agent: the rootfs is a plain
 * directory tree inside the app's own storage, so this works while the runtime is stopped, which is
 * exactly when a user is setting a workspace up.
 */
class RuntimeFolderBrowser(
    private val rootfsHostDir: File,
    private val workspaceHostDir: File,
    private val deviceStorage: () -> DeviceStorage.Mounts = DeviceStorage::mounts,
) {
    /** False before the runtime is installed, when there is no filesystem to walk yet. */
    fun isAvailable(): Boolean = rootfsHostDir.isDirectory

    fun children(path: String): List<String> {
        val mounts = deviceStorage()
        val host =
            WorkspaceFolders.hostDirectory(rootfsHostDir, workspaceHostDir, path, mounts)
                ?: return emptyList()
        val names =
            host.listFiles().orEmpty()
                .filter { it.isDirectory }
                .map { it.name }
        // `/workspace` and the device storage mounts are bind mounts, so the rootfs usually has no
        // entry for any of them: the folder holding every imported and cloned project, and the
        // phone's own files, would both be missing from the top level.
        val merged =
            if (WorkspaceFolders.normalize(path) == WorkspaceFolders.GUEST_ROOT) {
                names +
                    WorkspaceFolders.displayName(WorkspaceFolders.WORKSPACE_ROOT) +
                    DeviceStorage.guestRoots(mounts).map(WorkspaceFolders::displayName)
            } else {
                names
            }
        return merged.distinct().sortedWith(compareBy({ it.startsWith(".") }, { it.lowercase() }))
    }
}
