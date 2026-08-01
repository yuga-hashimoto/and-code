package com.yugahashimoto.andcode.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ResolveImageFileTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `guest workspace path maps to host workspace dir`() {
        val image = File(folder.root, "rabbit.png").apply { writeText("x") }
        assertEquals(image, resolveImageFile("/workspace/rabbit.png", folder.root))
    }

    @Test
    fun `guest rootfs path maps to rootfs directory`() {
        val rootfs = folder.newFolder("antigravity-rootfs")
        val image =
            File(rootfs, "tmp/rabbit.png").apply {
                parentFile.mkdirs()
                writeText("x")
            }
        assertEquals(image, resolveImageFile("/tmp/rabbit.png", folder.root, listOf(rootfs)))
    }

    @Test
    fun `absolute host path is resolved as-is`() {
        val image = folder.newFile("host.png").apply { writeText("x") }
        assertEquals(image, resolveImageFile(image.absolutePath, folder.root))
    }

    @Test
    fun `relative path is resolved against workspace dir`() {
        val image =
            File(folder.root, "sub/img.png").apply {
                parentFile.mkdirs()
                writeText("x")
            }
        assertEquals(image, resolveImageFile("sub/img.png", folder.root))
    }

    @Test
    fun `data and http urls are not file paths`() {
        assertNull(resolveImageFile("data:image/png;base64,abc", folder.root))
        assertNull(resolveImageFile("https://example.com/a.png", folder.root))
    }

    @Test
    fun `missing file returns null`() {
        assertNull(resolveImageFile("/workspace/missing.png", folder.root))
    }
}
