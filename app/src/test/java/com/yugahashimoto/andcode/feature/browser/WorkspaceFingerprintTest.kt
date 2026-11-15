package com.yugahashimoto.andcode.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceFingerprintTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `fingerprint changes when a web file changes`() {
        val root = temporaryFolder.newFolder()
        val page = File(root, "index.html").apply { writeText("a") }
        val before = workspaceFingerprint(root)

        page.writeText("a longer body")

        assertNotEquals(before, workspaceFingerprint(root))
    }

    @Test
    fun `ignores vendored and non-web files`() {
        val root = temporaryFolder.newFolder()
        File(root, "index.html").writeText("a")
        val before = workspaceFingerprint(root)

        File(root, "node_modules").mkdirs()
        File(root, "node_modules/x.js").writeText("big change")
        File(root, "README.md").writeText("docs")

        assertEquals(before, workspaceFingerprint(root))
    }
}
