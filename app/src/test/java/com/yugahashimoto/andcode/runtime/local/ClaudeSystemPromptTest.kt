package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The system-prompt presets requested in issue #294 (Coding, Debug, Research, Creative, plus the
 * user's own custom ones), and switching between them.
 */
class ClaudeSystemPromptTest {
    @get:Rule val folder = TemporaryFolder()

    private fun target(): ClaudeCodeTarget = ClaudeCodeTarget(ClaudeCodeRuntime(folder.root, { null }))

    @Test
    fun `the built-in presets are offered without any setup`() {
        val presets = target().systemPromptPresets.value

        assertEquals(ClaudeSystemPrompts.BUILT_IN.map(SystemPromptPreset::id), presets.map(SystemPromptPreset::id))
        assertTrue(presets.all(SystemPromptPreset::builtIn))
    }

    @Test
    fun `no preset is selected by default`() {
        assertNull(target().defaultSystemPromptId.value)
    }

    @Test
    fun `saving a custom preset adds it alongside the built-ins`() {
        val target = target()

        val saved = target.saveSystemPromptPreset("Release notes", "Write in a formal, changelog style.")

        assertEquals(ClaudeSystemPrompts.BUILT_IN.size + 1, target.systemPromptPresets.value.size)
        assertEquals(saved, target.systemPromptPresets.value.last())
        assertTrue(!saved.builtIn)
    }

    @Test
    fun `saving with an existing custom preset's id updates it in place`() {
        val target = target()
        val original = target.saveSystemPromptPreset("Release notes", "Write in a formal style.")

        val updated = target.saveSystemPromptPreset("Release notes v2", "Write casually.", id = original.id)

        assertEquals(original.id, updated.id)
        assertEquals(listOf(updated), target.systemPromptPresets.value.filterNot(SystemPromptPreset::builtIn))
    }

    @Test
    fun `deleting a custom preset removes it`() {
        val target = target()
        val preset = target.saveSystemPromptPreset("Scratch", "Anything goes.")

        target.deleteSystemPromptPreset(preset.id)

        assertTrue(target.systemPromptPresets.value.none { it.id == preset.id })
    }

    @Test
    fun `a built-in preset cannot be deleted`() {
        val target = target()
        val builtInId = ClaudeSystemPrompts.BUILT_IN.first().id

        target.deleteSystemPromptPreset(builtInId)

        assertTrue(target.systemPromptPresets.value.any { it.id == builtInId })
    }

    @Test
    fun `selecting a preset applies it to sessions created afterwards`() =
        runBlocking {
            val target = target()
            val coding = ClaudeSystemPrompts.BUILT_IN.first { it.id == "coding" }

            target.selectSystemPrompt(coding.id)
            target.createSession("New chat", "/workspace")

            // The record is a private implementation detail, so this reads the same file the app
            // itself reloads from on restart - the same proof [ClaudeCodeTargetArchiveTest] uses for
            // the permission mode default.
            val persisted = File(folder.root, "claude-sessions.json").readText()
            assertTrue(persisted.contains("\"promptId\":\"coding\""))
        }

    @Test
    fun `selecting a preset for an open session updates it immediately`() =
        runBlocking {
            val target = target()
            val session = target.createSession("New chat", "/workspace")
            val debug = ClaudeSystemPrompts.BUILT_IN.first { it.id == "debug" }

            target.selectSystemPrompt(debug.id, session.id)

            val persisted = File(folder.root, "claude-sessions.json").readText()
            assertTrue(persisted.contains("\"promptId\":\"debug\""))
        }

    @Test
    fun `deleting the selected default preset clears the selection`() {
        val target = target()
        val preset = target.saveSystemPromptPreset("Scratch", "Anything goes.")
        target.selectSystemPrompt(preset.id)

        target.deleteSystemPromptPreset(preset.id)

        assertNull(target.defaultSystemPromptId.value)
        val persisted = File(folder.root, "claude-system-prompts.json").readText()
        assertTrue(!persisted.contains(preset.id))
    }

    @Test
    fun `deleting a preset selected by an open session clears that session too`() =
        runBlocking {
            val target = target()
            val preset = target.saveSystemPromptPreset("Scratch", "Anything goes.")
            val session = target.createSession("New chat", "/workspace")
            target.selectSystemPrompt(preset.id, session.id)

            target.deleteSystemPromptPreset(preset.id)

            val persisted = File(folder.root, "claude-sessions.json").readText()
            assertTrue(!persisted.contains(preset.id))
        }

    @Test
    fun `a custom preset's selection survives a new target instance`() {
        val id: String
        runBlocking {
            val target = target()
            val preset = target.saveSystemPromptPreset("Scratch", "Anything goes.")
            target.selectSystemPrompt(preset.id)
            id = preset.id
        }

        val reloaded = target()

        assertEquals(id, reloaded.defaultSystemPromptId.value)
        assertTrue(reloaded.systemPromptPresets.value.any { it.id == id })
    }
}
