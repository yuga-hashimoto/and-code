package com.yugahashimoto.andcode.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StaticAssetResolverTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var resolver: StaticAssetResolver

    @Before
    fun setUp() {
        root = temporaryFolder.newFolder()
        File(root, "index.html").writeText("<html></html>")
        File(root, "assets").mkdirs()
        File(root, "assets/app.js").writeText("console.log(1)")
        File(root, "logo.svg").writeText("<svg/>")
        resolver = StaticAssetResolver(root)
    }

    @Test
    fun `serves the root document and typed assets`() {
        assertEquals(File(root, "index.html").canonicalFile, resolver.resolve("")?.file)
        assertEquals("text/html", resolver.resolve("/")?.mimeType)
        assertEquals("application/javascript", resolver.resolve("/assets/app.js")?.mimeType)
        assertEquals("image/svg+xml", resolver.resolve("/logo.svg")?.mimeType)
    }

    @Test
    fun `falls back to index for client-side routes`() {
        assertEquals(File(root, "index.html").canonicalFile, resolver.resolve("/some/spa/route")?.file)
    }

    @Test
    fun `refuses to escape the site root`() {
        File(root.parentFile, "secret.txt").writeText("secret")

        assertNull(resolver.resolve("/../secret.txt"))
    }

    @Test
    fun `missing asset with an extension is not served`() {
        assertNull(resolver.resolve("/missing.png"))
    }
}
