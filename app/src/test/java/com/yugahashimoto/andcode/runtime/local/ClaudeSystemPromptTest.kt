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

    /**
     * `ProcessBuilder` refuses an argument containing a NUL outright, so a pasted preset with one
     * in it would be persisted and then fail every later Claude turn - the same trap as an
     * over-long prompt, and just as hard to trace back to the preset that caused it.
     */
    @Test
    fun `a prompt pasted with control characters is stripped of what exec refuses`() {
        val target = target()

        val saved = target.saveSystemPromptPreset("Pasted", "Focus\u0000 on\u0007 debugging.\nSecond line.\tIndented.")

        assertEquals("Focus on debugging.\nSecond line.\tIndented.", saved.prompt)
    }

    /**
     * The prompt travels as one `--append-system-prompt` argv entry, and one argument past Linux's
     * MAX_ARG_STRLEN fails `exec`. Since the preset is persisted, an over-long one would fail every
     * later Claude process start, not just its own turn.
     */
    @Test
    fun `an over-long prompt is clamped rather than persisted whole`() {
        val target = target()

        val saved = target.saveSystemPromptPreset("Pasted", "x".repeat(MAX_SYSTEM_PROMPT_LENGTH * 2))

        assertEquals(MAX_SYSTEM_PROMPT_LENGTH, saved.prompt.length)
        assertEquals(MAX_SYSTEM_PROMPT_LENGTH, target.systemPromptPresets.value.last().prompt.length)
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

    /**
     * The composer chip is the per-chat control and the settings screen is the default's. A chip
     * switch that also moved the default would silently retune every future chat - and, because the
     * store is shared with OpenCode, rewrite the instructions file every OpenCode session reads.
     */
    @Test
    fun `switching an open session's preset leaves the default alone`() =
        runBlocking {
            val target = target()
            val session = target.createSession("New chat", "/workspace")

            target.selectSystemPrompt(ClaudeSystemPrompts.DEBUG, session.id)

            assertNull(target.defaultSystemPromptId.value)
            assertEquals(ClaudeSystemPrompts.DEBUG, target.promptIdFor(session.id))
        }

    /**
     * A caller can hold an id the user has since deleted: the composer keeps a blank chat's choice
     * while they navigate away, so picking a preset, deleting it in settings, and then sending
     * arrives here naming nothing. Recording it would persist a dangling reference and send with no
     * prompt anyway - the third shape this same class of bug has taken in this feature.
     */
    @Test
    fun `a preset deleted before it is applied records no preset at all`() =
        runBlocking {
            val target = target()
            val preset = target.saveSystemPromptPreset("Scratch", "Anything goes.")
            val session = target.createSession("New chat", "/workspace")
            target.deleteSystemPromptPreset(preset.id)

            target.selectSystemPrompt(preset.id, session.id)

            assertNull(target.promptIdFor(session.id))
            assertTrue(!File(folder.root, "claude-sessions.json").readText().contains(preset.id))
        }

    /**
     * A session id the target has no record for names a chat it does not have - deleted while open,
     * or another agent's. Falling through to the default would retune every later chat, and the
     * shared OpenCode instructions with it, for a chat that no longer exists.
     *
     * This test was lost when the staging-slot tests around it were removed; the guard it covers
     * never went anywhere.
     */
    @Test
    fun `a preset for an unknown session changes nothing`() =
        runBlocking {
            val target = target()
            target.selectSystemPrompt(ClaudeSystemPrompts.CODING)

            target.selectSystemPrompt(ClaudeSystemPrompts.DEBUG, "not-a-session")

            assertEquals(ClaudeSystemPrompts.CODING, target.defaultSystemPromptId.value)
        }

    @Test
    fun `editing a preset keeps its place in the list`() {
        val target = target()
        val first = target.saveSystemPromptPreset("First", "One.")
        val second = target.saveSystemPromptPreset("Second", "Two.")

        target.saveSystemPromptPreset("First, renamed", "One, rewritten.", id = first.id)

        val custom = target.systemPromptPresets.value.filterNot(SystemPromptPreset::builtIn)
        assertEquals(listOf(first.id, second.id), custom.map(SystemPromptPreset::id))
        assertEquals("First, renamed", custom.first().name)
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

    /**
     * What the composer names has to be what the send path will use, and it has to be observable -
     * a switch must move the chip on the tap rather than whenever something else recomposes. So the
     * target publishes each session's preset as state and the UI resolves it with
     * [resolveSystemPromptId]; this exercises the pair the way the composer does.
     */
    private fun ClaudeCodeTarget.promptIdFor(sessionId: String?): String? =
        resolveSystemPromptId(sessionId, sessionSystemPromptIds.value, defaultSystemPromptId.value)

    /**
     * A session snapshots the default when it is created and keeps it, so an older chat's preset is
     * not the current default - showing the default there would name a preset the next turn is not
     * going to carry.
     */
    @Test
    fun `an existing session keeps its own preset when the default moves on`() =
        runBlocking {
            val target = target()
            val session = target.createSession("Older chat", "/workspace")

            target.selectSystemPrompt(ClaudeSystemPrompts.CODING)

            assertNull(target.promptIdFor(session.id))
            assertEquals(ClaudeSystemPrompts.CODING, target.promptIdFor(null))
        }

    @Test
    fun `a session switched from the composer reports its own preset`() =
        runBlocking {
            val target = target()
            val switched = target.createSession("Switched", "/workspace")
            val untouched = target.createSession("Untouched", "/workspace")

            target.selectSystemPrompt(ClaudeSystemPrompts.DEBUG, switched.id)

            assertEquals(ClaudeSystemPrompts.DEBUG, target.promptIdFor(switched.id))
            assertNull(target.promptIdFor(untouched.id))
        }

    /**
     * The regression behind this: a per-chat switch only mutated the session map and persisted it,
     * so nothing the composer observed changed and the chip went on naming the previous preset
     * until an unrelated state change recomposed it - while the next turn already carried the new
     * one.
     */
    @Test
    fun `a per-chat switch is published as state`() =
        runBlocking {
            val target = target()
            val session = target.createSession("New chat", "/workspace")
            assertEquals(mapOf(session.id to null), target.sessionSystemPromptIds.value)

            target.selectSystemPrompt(ClaudeSystemPrompts.RESEARCH, session.id)

            assertEquals(mapOf(session.id to ClaudeSystemPrompts.RESEARCH), target.sessionSystemPromptIds.value)
        }

    /** The composer resolves off the state, so the state has to answer the same way. */
    @Test
    fun `the ui state resolves a session's preset the same way`() {
        val state =
            ClaudeCodeUiState(
                systemPromptId = ClaudeSystemPrompts.CODING,
                sessionSystemPromptIds = mapOf("older" to null, "switched" to ClaudeSystemPrompts.DEBUG),
            )

        assertNull(state.systemPromptIdFor("older"))
        assertEquals(ClaudeSystemPrompts.DEBUG, state.systemPromptIdFor("switched"))
        assertEquals(ClaudeSystemPrompts.CODING, state.systemPromptIdFor("never-seen"))
        assertEquals(ClaudeSystemPrompts.CODING, state.systemPromptIdFor(null))
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
