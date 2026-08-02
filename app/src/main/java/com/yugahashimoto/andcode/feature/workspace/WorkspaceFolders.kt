package com.yugahashimoto.andcode.feature.workspace

import com.yugahashimoto.andcode.core.storage.DeviceStorage
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import java.io.File

/**
 * Path bookkeeping for the workspace list.
 *
 * Kept out of [WorkspaceViewModel] so it can be tested without Android's encrypted preferences,
 * which is where the deletion rules belong: they decide what gets erased from the device.
 */
object WorkspaceFolders {
    /** The sandbox path every runtime mounts the app's workspace directory at. */
    const val WORKSPACE_ROOT = "/workspace"

    const val GUEST_ROOT = "/"

    fun displayName(path: String): String = path.trimEnd('/').substringAfterLast('/').ifBlank { path }

    /** Leading slash, no trailing slash, no empty segments — `/` stays `/`. */
    fun normalize(path: String): String {
        val segments = path.trim().split('/').filter { it.isNotEmpty() && it != "." }
        return if (segments.isEmpty()) GUEST_ROOT else "/" + segments.joinToString("/")
    }

    fun parentOf(path: String): String {
        val normalized = normalize(path)
        if (normalized == GUEST_ROOT) return GUEST_ROOT
        return normalize(normalized.substringBeforeLast('/'))
    }

    fun childOf(
        path: String,
        name: String,
    ): String = normalize("${normalize(path)}/$name")

    fun isInsideWorkspaceRoot(path: String): Boolean {
        val normalized = normalize(path)
        return normalized != WORKSPACE_ROOT && normalized.startsWith("$WORKSPACE_ROOT/")
    }

    /**
     * The folders to show: what the runtime reports, plus the ones this device registered, minus
     * the ones the user removed.
     *
     * Removals have to be remembered rather than just unregistered. Most rows are not registrations
     * at all — the runtimes report a folder because a past chat ran there or because it sits on
     * disk — so dropping the registration left the row exactly where it was, which is what made
     * "remove from list" and "delete files" both look like they had done nothing.
     */
    fun visibleWorkspaces(
        runtimeWorkspaces: List<WorkspaceRef>,
        registered: List<WorkspaceRef>,
        hidden: Set<String>,
    ): List<WorkspaceRef> {
        val byPath = linkedMapOf<String, WorkspaceRef>()
        registered.forEach { byPath[it.path] = it }
        runtimeWorkspaces.forEach { byPath.putIfAbsent(it.path, it) }
        hidden.forEach { byPath.remove(it) }
        return byPath.values.toList()
    }

    /**
     * The on-device folder holding a workspace's files, or null when there is nothing to delete.
     *
     * Only folders under [WORKSPACE_ROOT] answer: that mount is the app's own directory, so it is
     * the one place a "delete files" is unambiguously about files this app put there. The whole
     * relative path is used — resolving by basename alone, as this once did, pointed at a
     * same-named folder directly under the mount whenever the workspace was nested.
     */
    fun deletableHostDirectory(
        workspaceHostDir: File,
        path: String,
    ): File? {
        if (!isInsideWorkspaceRoot(path)) return null
        val relative = normalize(path).removePrefix(WORKSPACE_ROOT).trim('/')
        if (relative.isEmpty()) return null
        return containedIn(workspaceHostDir, relative)
    }

    /**
     * The host directory behind a sandbox path, for browsing.
     *
     * `/workspace` is a bind mount of the app's workspace directory, `/sdcard` and `/storage` are
     * bind mounts of the device's own storage, and everything else lives in the Linux rootfs the
     * runtimes are unpacked into, which is a plain directory tree this app owns.
     */
    fun hostDirectory(
        rootfsHostDir: File?,
        workspaceHostDir: File,
        path: String,
        deviceStorage: DeviceStorage.Mounts = DeviceStorage.Mounts.None,
    ): File? {
        val normalized = normalize(path)
        if (normalized == WORKSPACE_ROOT) return workspaceHostDir
        if (isInsideWorkspaceRoot(normalized)) {
            return containedIn(workspaceHostDir, normalized.removePrefix(WORKSPACE_ROOT).trim('/'))
        }
        // Ahead of the rootfs, so a `/sdcard` directory that happens to exist inside the rootfs
        // cannot shadow the device's own files once the mount is there.
        if (DeviceStorage.isDeviceStoragePath(normalized)) {
            return DeviceStorage.hostDirectory(deviceStorage, normalized)
        }
        val rootfs = rootfsHostDir ?: return null
        val relative = normalized.trim('/')
        return if (relative.isEmpty()) rootfs.absoluteFile.normalize() else containedIn(rootfs, relative)
    }

    /** [relative] resolved under [root], or null when it climbs back out of it. */
    private fun containedIn(
        root: File,
        relative: String,
    ): File? {
        val base = root.absoluteFile.normalize()
        val resolved = File(base, relative).absoluteFile.normalize()
        return resolved.takeIf { it.path.startsWith(base.path + File.separator) }
    }
}
