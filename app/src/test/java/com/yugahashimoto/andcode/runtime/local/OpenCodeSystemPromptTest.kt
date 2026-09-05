package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Writing the selected preset where OpenCode reads it.
 *
 * The file lives in the guest filesystem, which an agent can write to, so the write is guarded the
 * same way [installAndCodeAgentContext]'s are.
 */
class OpenCodeSystemPromptTest {
    @get:Rule val folder = TemporaryFolder()

    private fun rootfs(): File = folder.newFolder("rootfs")

    private fun promptFile(rootfs: File): File = File(rootfs, OPENCODE_SYSTEM_PROMPT_PATH)

    @Test
    fun `the selected prompt is written where the instructions config points`() {
        val rootfs = rootfs()

        applyOpenCodeSystemPrompt(rootfs, "Focus on debugging.")

        assertEquals("Focus on debugging.", promptFile(rootfs).readText())
    }

    @Test
    fun `switching presets replaces the previous prompt rather than appending`() {
        val rootfs = rootfs()

        applyOpenCodeSystemPrompt(rootfs, "Focus on debugging.")
        applyOpenCodeSystemPrompt(rootfs, "Be creative.")

        assertEquals("Be creative.", promptFile(rootfs).readText())
    }

    /** An empty file would still be announced to the model as "Instructions from: ...". */
    @Test
    fun `selecting no preset removes the file`() {
        val rootfs = rootfs()
        applyOpenCodeSystemPrompt(rootfs, "Focus on debugging.")

        applyOpenCodeSystemPrompt(rootfs, null)

        assertFalse(promptFile(rootfs).exists())
    }

    @Test
    fun `a blank prompt is treated as no preset`() {
        val rootfs = rootfs()
        applyOpenCodeSystemPrompt(rootfs, "Focus on debugging.")

        applyOpenCodeSystemPrompt(rootfs, "   ")

        assertFalse(promptFile(rootfs).exists())
    }

    @Test
    fun `applying to a rootfs that has no config directory yet still writes`() {
        val rootfs = File(folder.root, "not-created-yet")

        applyOpenCodeSystemPrompt(rootfs, "Focus on debugging.")

        assertEquals("Focus on debugging.", promptFile(rootfs).readText())
    }

    /**
     * The guest can replace the target with a link pointing out of the sandbox; following it would
     * let a prompt switch write to an arbitrary path on the device.
     */
    @Test
    fun `a symlink pointing outside the rootfs is refused`() {
        val rootfs = rootfs()
        val outside = folder.newFile("outside.md").apply { writeText("untouched") }
        val target = promptFile(rootfs)
        target.parentFile?.mkdirs()
        Files.createSymbolicLink(target.toPath(), outside.toPath())

        applyOpenCodeSystemPrompt(rootfs, "Focus on debugging.")

        assertEquals("untouched", outside.readText())
    }

    @Test
    fun `a directory in the way is left alone`() {
        val rootfs = rootfs()
        val target = promptFile(rootfs)
        target.mkdirs()

        applyOpenCodeSystemPrompt(rootfs, "Focus on debugging.")

        assertTrue(target.isDirectory)
    }
}
