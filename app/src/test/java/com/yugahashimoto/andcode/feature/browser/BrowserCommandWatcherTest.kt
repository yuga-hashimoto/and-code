package com.yugahashimoto.andcode.feature.browser

import com.yugahashimoto.andcode.core.storage.DeviceStorage
import com.yugahashimoto.andcode.runtime.LocalAgent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BrowserCommandWatcherTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val runtimeDirectory by lazy { temporaryFolder.newFolder("runtime") }

    @Test
    fun `each Alpine agent consumes the command from the guest rootfs`() {
        listOf(LocalAgent.OPEN_CODE, LocalAgent.CLAUDE_CODE, LocalAgent.CODEX).forEach { agent ->
            val file = command("environment/rootfs/root/project")

            assertEquals(BrowserCommand.Open("https://www.google.com/"), consume("/root/project", agent))
            assertFalse(file.exists())
            assertNull(consume("/root/project", agent))
        }
    }

    @Test
    fun `nested workspace commands are read from the bind mount`() {
        val file = command("workspace/team/project")
        val decoy = command("environment/rootfs/workspace/team/project", "https://example.com/decoy")

        assertEquals(BrowserCommand.Open("https://www.google.com/"), consume("/workspace/team/project"))
        assertFalse(file.exists())
        assertTrue(decoy.exists())
    }

    @Test
    fun `Antigravity consumes only the command in its Debian rootfs`() {
        val file = command("environment/antigravity-rootfs/root/project")
        val alpine = command("environment/rootfs/root/project", "https://example.com/alpine")

        assertEquals(BrowserCommand.Open("https://www.google.com/"), consume("/root/project", LocalAgent.ANTIGRAVITY))
        assertFalse(file.exists())
        assertTrue(alpine.exists())
    }

    @Test
    fun `Antigravity falls back to the shared rootfs when Debian is absent`() {
        val file = command("environment/rootfs/root/project")

        assertEquals(BrowserCommand.Open("https://www.google.com/"), consume("/root/project", LocalAgent.ANTIGRAVITY))
        assertFalse(file.exists())
    }

    @Test
    fun `commands in device storage use the mounted host directory`() {
        val shared = temporaryFolder.newFolder("shared")
        val file = writeCommand(File(shared, "Download/project"))
        val mounts = DeviceStorage.Mounts(sharedStorage = shared)

        assertEquals(BrowserCommand.Open("https://www.google.com/"), consume("/sdcard/Download/project", mounts = mounts))
        assertFalse(file.exists())
    }

    @Test
    fun `unmounted device storage does not consume a rootfs decoy`() {
        val file = command("environment/rootfs/sdcard/Download/project")

        assertNull(consume("/sdcard/Download/project"))
        assertTrue(file.exists())
    }

    @Test
    fun `remote runtimes and absent workspaces leave local commands untouched`() {
        val file = command("workspace/project")

        assertNull(consume("/workspace/project", agent = null))
        assertNull(consume(null))
        assertTrue(file.exists())
    }

    @Test
    fun `missing command does not open the browser`() {
        assertNull(consume("/workspace/project"))
    }

    @Test
    fun `partially written command is retried after the writer completes it`() {
        val file = command("workspace/project")
        file.writeText("{\"action\":\"open\",\"url\":")

        assertNull(consume("/workspace/project"))
        assertTrue(file.exists())

        file.writeText("{\"action\":\"open\",\"url\":\"http://127.0.0.1:8080/\"}")
        assertEquals(BrowserCommand.Open("http://127.0.0.1:8080/"), consume("/workspace/project"))
        assertFalse(file.exists())
    }

    @Test
    fun `preview commands pin a loopback page inside the chat`() {
        val file = command("workspace/project", "http://127.0.0.1:5173/", action = "preview")

        assertEquals(BrowserCommand.Preview("http://127.0.0.1:5173/"), consume("/workspace/project"))
        assertFalse(file.exists())
    }

    @Test
    fun `preview commands reject pages that are not on loopback`() {
        val file = command("workspace/project", "https://example.com/", action = "preview")

        assertNull(consume("/workspace/project"))
        assertTrue(file.exists())
    }

    @Test
    fun `preview commands allow a public page once the agent-control opt-in is on`() {
        val file = command("workspace/project", "https://example.com/", action = "preview")

        assertEquals(
            BrowserCommand.Preview("https://example.com/"),
            consume("/workspace/project", allowRemotePreview = true),
        )
        assertFalse(file.exists())
    }

    @Test
    fun `public previews must be https even with the opt-in`() {
        val file = command("workspace/project", "http://example.com/", action = "preview")

        assertNull(consume("/workspace/project", allowRemotePreview = true))
        assertTrue(file.exists())
    }

    @Test
    fun `handoff waits on the current page and records that it is waiting`() {
        val file = command("workspace/project", url = "", action = "handoff")

        val command = consume("/workspace/project")

        assertTrue(command is BrowserCommand.Handoff)
        assertFalse(file.exists())
        assertTrue((command as BrowserCommand.Handoff).statusFile.readText().contains(BROWSER_STATUS_AWAITING_USER))
    }

    @Test
    fun `handoff never carries a url because it acts on the page already open`() {
        listOf("https://example.com/login", "javascript:alert(1)").forEach { url ->
            val file = command("workspace/project", url = url, action = "handoff")

            assertNull(consume("/workspace/project"))
            assertTrue(file.exists())
        }
    }

    @Test
    fun `unsupported actions and invalid URLs do not open the browser`() {
        val file = command("workspace/project")
        listOf(
            "{\"action\":\"close\",\"url\":\"https://example.com/\"}",
            "{\"action\":\"open\",\"url\":\"javascript:alert(1)\"}",
            "{\"action\":\"open\",\"url\":\"file:///root/private\"}",
            "{\"action\":\"open\",\"url\":\"https://\"}",
            "{\"action\":\"open\",\"url\":\"\"}",
            "{\"action\":\"preview\",\"url\":\"file:///root/private\"}",
        ).forEach { text ->
            file.writeText(text)
            assertNull(consume("/workspace/project"))
            assertTrue(file.exists())
        }
    }

    @Test
    fun `workspace traversal does not consume a command outside its mount`() {
        val file = command("outside")

        assertNull(consume("/workspace/../outside"))
        assertTrue(file.exists())
    }

    /**
     * The guest browser is only worth opening for someone looking at the app, so the watcher stays
     * parked while it is in the background and hands over what arrived the moment it returns.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `watcher opens a pending command only while the app is in the foreground`() =
        runTest {
            val foreground = MutableStateFlow(false)
            val opened = mutableListOf<BrowserCommand>()
            var pending: BrowserCommand? = BrowserCommand.Open("https://www.google.com/")
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            try {
                scope.launch {
                    watchBrowserCommands(
                        active = foreground,
                        intervalMillis = 1_000L,
                        poll = { pending.also { pending = null } },
                        onCommand = { opened += it },
                    )
                }
                advanceTimeBy(60_000L)
                runCurrent()
                assertEquals(emptyList<BrowserCommand>(), opened)

                foreground.value = true
                runCurrent()

                assertEquals(listOf<BrowserCommand>(BrowserCommand.Open("https://www.google.com/")), opened)
            } finally {
                scope.cancel()
            }
        }

    private fun consume(
        path: String?,
        agent: LocalAgent? = LocalAgent.CODEX,
        mounts: DeviceStorage.Mounts = DeviceStorage.Mounts.None,
        allowRemotePreview: Boolean = false,
    ): BrowserCommand? = consumeBrowserCommand(path, agent, runtimeDirectory, mounts, allowRemotePreview)

    private fun command(
        relativeDirectory: String,
        url: String = "https://www.google.com/",
        action: String = "open",
    ): File = writeCommand(File(runtimeDirectory, relativeDirectory), url, action)

    private fun writeCommand(
        directory: File,
        url: String = "https://www.google.com/",
        action: String = "open",
    ): File =
        File(directory, ".and-code/browser-command.json").apply {
            parentFile!!.mkdirs()
            writeText("{\"action\":\"$action\",\"url\":\"$url\"}")
        }
}
