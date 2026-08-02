package com.yugahashimoto.andcode.feature.workspace

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

    private fun browser(): RuntimeFolderBrowser {
        rootfs = temporaryFolder.newFolder("rootfs")
        workspace = temporaryFolder.newFolder("workspace")
        return RuntimeFolderBrowser(rootfs, workspace)
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
    fun `browsing is unavailable until the runtime is installed`() {
        val installed = browser()
        val missing = File(temporaryFolder.root, "not-installed")

        assertFalse(RuntimeFolderBrowser(missing, workspace).isAvailable())
        assertTrue(installed.isAvailable())
    }
}
