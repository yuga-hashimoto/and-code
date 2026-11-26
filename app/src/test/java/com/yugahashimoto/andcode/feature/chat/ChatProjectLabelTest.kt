package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.runtime.WorkspaceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatProjectLabelTest {
    @Test
    fun `blank chat uses selected workspace name when it matches a known workspace`() {
        val label =
            resolveChatProjectLabel(
                state = ChatUiState(selectedWorkspacePath = "/workspace/and-code"),
                workspaces = listOf(WorkspaceRef("/workspace/and-code", "AndCode", "/workspace/and-code")),
            )

        assertEquals("AndCode", label)
    }

    @Test
    fun `blank chat falls back to folder display name when workspace is not in the list`() {
        val label =
            resolveChatProjectLabel(
                state = ChatUiState(selectedWorkspacePath = "/workspace/opendots-android/"),
                workspaces = emptyList(),
            )

        assertEquals("opendots-android", label)
    }

    @Test
    fun `open session uses sessionDirectory and ignores selectedWorkspacePath`() {
        val label =
            resolveChatProjectLabel(
                state =
                    ChatUiState(
                        sessionId = "s1",
                        sessionDirectory = "/workspace/alpha",
                        selectedWorkspacePath = "/workspace/beta",
                    ),
                workspaces =
                    listOf(
                        WorkspaceRef("/workspace/alpha", "alpha", "/workspace/alpha"),
                        WorkspaceRef("/workspace/beta", "beta", "/workspace/beta"),
                    ),
            )

        assertEquals("alpha", label)
    }

    @Test
    fun `open session with no sessionDirectory returns null instead of leaking selectedWorkspacePath`() {
        val label =
            resolveChatProjectLabel(
                state =
                    ChatUiState(
                        sessionId = "s1",
                        sessionDirectory = null,
                        selectedWorkspacePath = "/workspace/beta",
                    ),
                workspaces = listOf(WorkspaceRef("/workspace/beta", "beta", "/workspace/beta")),
            )

        assertNull(label)
    }

    @Test
    fun `blank chat with no selected workspace returns null`() {
        assertNull(resolveChatProjectLabel(ChatUiState(), workspaces = emptyList()))
    }
}
