package com.yugahashimoto.andcode.feature.workspace

import com.yugahashimoto.andcode.core.storage.DeviceStorage
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceFoldersTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun ref(path: String) = WorkspaceRef(id = path, name = WorkspaceFolders.displayName(path), path = path)

    @Test
    fun `removed folder stays out of the list even when the runtime keeps reporting it`() {
        val visible =
            WorkspaceFolders.visibleWorkspaces(
                runtimeWorkspaces = listOf(ref("/workspace/app"), ref("/workspace/notes")),
                registered = emptyList(),
                hidden = setOf("/workspace/app"),
            )

        assertEquals(listOf("/workspace/notes"), visible.map { it.path })
    }

    @Test
    fun `registered folders come first and are not duplicated by the runtime listing`() {
        val visible =
            WorkspaceFolders.visibleWorkspaces(
                runtimeWorkspaces = listOf(ref("/workspace/app"), ref("/root/scratch")),
                registered = listOf(ref("/workspace/app")),
                hidden = emptySet(),
            )

        assertEquals(listOf("/workspace/app", "/root/scratch"), visible.map { it.path })
    }

    @Test
    fun `nested workspace resolves to its own folder rather than a same-named one at the top`() {
        val root = temporaryFolder.newFolder("workspace")
        val nested = File(root, "team/app").apply { mkdirs() }
        File(root, "app").mkdirs()

        val resolved = WorkspaceFolders.deletableHostDirectory(root, "/workspace/team/app")

        assertEquals(nested.canonicalFile, resolved?.canonicalFile)
    }

    @Test
    fun `nothing outside the workspace mount is deletable`() {
        val root = temporaryFolder.newFolder("workspace")

        assertNull(WorkspaceFolders.deletableHostDirectory(root, "/workspace"))
        assertNull(WorkspaceFolders.deletableHostDirectory(root, "/"))
        assertNull(WorkspaceFolders.deletableHostDirectory(root, "/root/project"))
        assertNull(WorkspaceFolders.deletableHostDirectory(root, "/workspace/../../secrets"))
        assertNull(WorkspaceFolders.deletableHostDirectory(root, "/home/user/and-code"))
    }

    @Test
    fun `browsing maps the workspace mount and the rootfs to their own directories`() {
        val rootfs = temporaryFolder.newFolder("rootfs")
        val workspace = temporaryFolder.newFolder("workspace")

        assertEquals(
            File(workspace, "app").canonicalFile,
            WorkspaceFolders.hostDirectory(rootfs, workspace, "/workspace/app")?.canonicalFile,
        )
        assertEquals(workspace.canonicalFile, WorkspaceFolders.hostDirectory(rootfs, workspace, "/workspace")?.canonicalFile)
        assertEquals(
            File(rootfs, "root/project").canonicalFile,
            WorkspaceFolders.hostDirectory(rootfs, workspace, "/root/project")?.canonicalFile,
        )
        assertEquals(rootfs.canonicalFile, WorkspaceFolders.hostDirectory(rootfs, workspace, "/")?.canonicalFile)
        assertNull(WorkspaceFolders.hostDirectory(rootfs, workspace, "/../outside"))
    }

    @Test
    fun `browsing maps device storage to the phone's own directories`() {
        val rootfs = temporaryFolder.newFolder("rootfs")
        val workspace = temporaryFolder.newFolder("workspace")
        val shared = temporaryFolder.newFolder("emulated")
        val mounts = DeviceStorage.Mounts(sharedStorage = shared, volumes = temporaryFolder.newFolder("volumes"))

        assertEquals(
            File(shared, "Download/repo").canonicalFile,
            WorkspaceFolders.hostDirectory(rootfs, workspace, "/sdcard/Download/repo", mounts)?.canonicalFile,
        )
        // A same-named directory inside the rootfs must not stand in for the real mount.
        File(rootfs, "sdcard/decoy").mkdirs()
        assertEquals(
            shared.canonicalFile,
            WorkspaceFolders.hostDirectory(rootfs, workspace, "/sdcard", mounts)?.canonicalFile,
        )
    }

    @Test
    fun `device storage stays unreachable until access is granted`() {
        val rootfs = temporaryFolder.newFolder("rootfs")
        val workspace = temporaryFolder.newFolder("workspace")

        assertNull(WorkspaceFolders.hostDirectory(rootfs, workspace, "/sdcard/Download"))
    }

    @Test
    fun `paths are normalised so the same folder is never two entries`() {
        assertEquals("/workspace/app", WorkspaceFolders.normalize("/workspace/app/"))
        assertEquals("/workspace/app", WorkspaceFolders.normalize("//workspace//app"))
        assertEquals("/", WorkspaceFolders.normalize(""))
        assertEquals("/workspace", WorkspaceFolders.parentOf("/workspace/app"))
        assertEquals("/", WorkspaceFolders.parentOf("/workspace"))
        assertEquals("/", WorkspaceFolders.parentOf("/"))
        assertEquals("/workspace/app", WorkspaceFolders.childOf("/workspace", "app"))
    }

    @Test
    fun `only folders under the mount count as inside it`() {
        assertTrue(WorkspaceFolders.isInsideWorkspaceRoot("/workspace/app"))
        assertFalse(WorkspaceFolders.isInsideWorkspaceRoot("/workspace"))
        assertFalse(WorkspaceFolders.isInsideWorkspaceRoot("/workspaces/app"))
        assertFalse(WorkspaceFolders.isInsideWorkspaceRoot("/root/app"))
    }
}
