package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.runtime.PermissionResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class ClaudePermissionBridgeTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `pollPending surfaces a new permission request once`() {
        val bridge = ClaudePermissionBridge(folder.root)
        val requestId = UUID.randomUUID().toString()
        bridge.writeGuestRequest(
            ClaudePermissionBridge.Request(
                requestId = requestId,
                androidSessionId = "session-1",
                kind = ClaudePermissionBridge.Kind.PERMISSION,
                toolName = "Bash",
                toolInputJson = """{"command":"ls"}""",
                permissionLabel = "Bash",
            ),
        )

        val first = bridge.pollPending()
        assertEquals(1, first.size)
        assertEquals(requestId, first.single().requestId)
        assertEquals("Bash", first.single().toolName)
        assertTrue(first.single().permissionLabel.contains("Bash"))

        assertTrue(bridge.pollPending().isEmpty())
    }

    @Test
    fun `respond writes a response the hook can read`() {
        val bridge = ClaudePermissionBridge(folder.root)
        val requestId = UUID.randomUUID().toString()
        bridge.writeGuestRequest(
            ClaudePermissionBridge.Request(
                requestId = requestId,
                androidSessionId = "session-1",
                kind = ClaudePermissionBridge.Kind.PERMISSION,
                toolName = "Bash",
                toolInputJson = "{}",
                permissionLabel = "Bash",
            ),
        )
        bridge.pollPending()

        assertTrue(bridge.respond(requestId, PermissionResponse.ONCE, remember = false))
        val response = bridge.readResponse(requestId)
        assertNotNull(response)
        assertEquals("allow", response!!.decision)
        assertFalse(response.remember)
    }

    @Test
    fun `reject writes deny`() {
        val bridge = ClaudePermissionBridge(folder.root)
        val requestId = UUID.randomUUID().toString()
        bridge.writeGuestRequest(
            ClaudePermissionBridge.Request(
                requestId = requestId,
                androidSessionId = "session-1",
                kind = ClaudePermissionBridge.Kind.PERMISSION,
                toolName = "Bash",
                toolInputJson = "{}",
                permissionLabel = "Bash",
            ),
        )
        bridge.pollPending()
        assertTrue(bridge.respond(requestId, PermissionResponse.REJECT, remember = false, message = "nope"))
        assertEquals("deny", bridge.readResponse(requestId)!!.decision)
        assertEquals("nope", bridge.readResponse(requestId)!!.message)
    }

    @Test
    fun `always allow records a remembered rule`() {
        val bridge = ClaudePermissionBridge(folder.root)
        val requestId = UUID.randomUUID().toString()
        bridge.writeGuestRequest(
            ClaudePermissionBridge.Request(
                requestId = requestId,
                androidSessionId = "session-1",
                kind = ClaudePermissionBridge.Kind.PERMISSION,
                toolName = "Bash",
                toolInputJson = """{"command":"git status"}""",
                permissionLabel = "Bash",
            ),
        )
        bridge.pollPending()
        assertTrue(bridge.respond(requestId, PermissionResponse.ALWAYS, remember = true))
        assertTrue(bridge.isAlwaysAllowed("Bash", """{"command":"git status"}"""))
        assertFalse(bridge.isAlwaysAllowed("Write", """{"file_path":"a"}"""))
    }

    @Test
    fun `answer question writes answers payload`() {
        val bridge = ClaudePermissionBridge(folder.root)
        val requestId = UUID.randomUUID().toString()
        bridge.writeGuestRequest(
            ClaudePermissionBridge.Request(
                requestId = requestId,
                androidSessionId = "session-1",
                kind = ClaudePermissionBridge.Kind.QUESTION,
                toolName = "AskUserQuestion",
                toolInputJson = """{"questions":[{"question":"Pick?","options":[{"label":"A"}]}]}""",
                permissionLabel = "AskUserQuestion",
            ),
        )
        bridge.pollPending()
        assertTrue(
            bridge.answerQuestion(
                requestId,
                questionsJson = """[{"question":"Pick?","options":[{"label":"A"}]}]""",
                answers = mapOf("Pick?" to "A"),
            ),
        )
        val response = bridge.readResponse(requestId)!!
        assertEquals("allow", response.decision)
        assertNotNull(response.answersJson)
        assertTrue(response.answersJson!!.contains("Pick?"))
    }

    @Test
    fun `unknown request id returns false`() {
        val bridge = ClaudePermissionBridge(folder.root)
        assertFalse(bridge.respond("missing", PermissionResponse.ONCE, remember = false))
        assertNull(bridge.readResponse("missing"))
    }

    @Test
    fun `hook settings fragment marks and-code permission hook`() {
        val fragment = ClaudePermissionHooks.settingsFragment()
        assertTrue(fragment.contains("PermissionRequest"))
        assertTrue(fragment.contains(ClaudePermissionHooks.HOOK_GUEST_PATH))
        assertTrue(fragment.contains("and-code-claude-permission"))
    }

    @Test
    fun `mergeSettings injects hook without dropping existing hooks`() {
        val existing =
            """
            {
              "hooks": {
                "Stop": [{ "matcher": "*", "hooks": [{ "type": "command", "command": "echo hi" }] }]
              }
            }
            """.trimIndent()
        val merged = ClaudePermissionHooks.mergeSettingsJson(existing)
        assertTrue(merged.contains("echo hi"))
        assertTrue(merged.contains("PermissionRequest"))
        assertTrue(merged.contains(ClaudePermissionHooks.HOOK_GUEST_PATH))
    }
}
