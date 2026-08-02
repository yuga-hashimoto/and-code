package com.yugahashimoto.andcode.feature.workspace

import com.yugahashimoto.andcode.core.storage.DeviceStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuntimeFolderBrowserTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var rootfs: File
    private lateinit var workspace: File
    private lateinit var sharedStorage: File

    private fun browser(deviceStorage: DeviceStorage.Mounts = DeviceStorage.Mounts.None): RuntimeFolderBrowser {
        rootfs = temporaryFolder.newFolder("rootfs")
        workspace = temporaryFolder.newFolder("workspace")
        return RuntimeFolderBrowser(rootfs, workspace) { deviceStorage }
    }

    private fun grantedStorage(): DeviceStorage.Mounts {
        sharedStorage = temporaryFolder.newFolder("emulated")
        return DeviceStorage.Mounts(sharedStorage = sharedStorage, volumes = temporaryFolder.newFolder("volumes"))
    }

    @Test
    fun `the root lists the rootfs plus the workspace mount that is not part of it`() {
        val browser = browser()
        File(rootfs, "root").mkdirs()
        File(rootfs, "etc").mkdirs()
        File(rootfs, "init").writeText("not a directory")

        assertEquals(listOf("etc", "root", "workspace"), browser.children("/"))
    }

    @Test
    fun `descending into the mount lists imported and cloned projects`() {
        val browser = browser()
        File(workspace, "and-code").mkdirs()
        File(workspace, "Notes").mkdirs()

        assertEquals(listOf("and-code", "Notes"), browser.children("/workspace"))
    }

    @Test
    fun `hidden folders sort last so real projects are seen first`() {
        val browser = browser()
        File(rootfs, "root/.cache").mkdirs()
        File(rootfs, "root/project").mkdirs()

        assertEquals(listOf("project", ".cache"), browser.children("/root"))
    }

    @Test
    fun `a path that climbs out of the environment lists nothing`() {
        val browser = browser()
        File(rootfs, "root").mkdirs()

        assertEquals(emptyList<String>(), browser.children("/../../etc"))
    }

    @Test
    fun `the root offers the device's own storage once access is granted`() {
        val mounts = grantedStorage()
        val browser = browser(mounts)
        File(rootfs, "root").mkdirs()

        assertEquals(listOf("root", "sdcard", "storage", "workspace"), browser.children("/"))
    }

    @Test
    fun `the device's folders are listed like any other`() {
        val mounts = grantedStorage()
        val browser = browser(mounts)
        File(sharedStorage, "Download").mkdirs()
        File(sharedStorage, "Documents").mkdirs()
        File(sharedStorage, "notes.txt").writeText("not a directory")

        assertEquals(listOf("Documents", "Download"), browser.children("/sdcard"))
    }

    @Test
    fun `without access the device's storage is not even offered`() {
        val browser = browser()
        File(rootfs, "root").mkdirs()

        assertEquals(listOf("root", "workspace"), browser.children("/"))
        assertEquals(emptyList<String>(), browser.children("/sdcard"))
    }

    @Test
    fun `browsing is unavailable until the runtime is installed`() {
        val installed = browser()
        val missing = File(temporaryFolder.root, "not-installed")

        assertFalse(RuntimeFolderBrowser(missing, workspace).isAvailable())
        assertTrue(installed.isAvailable())
    }
}
