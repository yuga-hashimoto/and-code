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

            assertEquals("https://www.google.com/", consume("/root/project", agent)?.url)
            assertFalse(file.exists())
            assertNull(consume("/root/project", agent))
        }
    }

    @Test
    fun `nested workspace commands are read from the bind mount`() {
        val file = command("workspace/team/project")
        val decoy = command("environment/rootfs/workspace/team/project", "https://example.com/decoy")

        assertEquals("https://www.google.com/", consume("/workspace/team/project")?.url)
        assertFalse(file.exists())
        assertTrue(decoy.exists())
    }

    @Test
    fun `Antigravity consumes only the command in its Debian rootfs`() {
        val file = command("environment/antigravity-rootfs/root/project")
        val alpine = command("environment/rootfs/root/project", "https://example.com/alpine")

        assertEquals("https://www.google.com/", consume("/root/project", LocalAgent.ANTIGRAVITY)?.url)
        assertFalse(file.exists())
        assertTrue(alpine.exists())
    }

    @Test
    fun `Antigravity falls back to the shared rootfs when Debian is absent`() {
        val file = command("environment/rootfs/root/project")

        assertEquals("https://www.google.com/", consume("/root/project", LocalAgent.ANTIGRAVITY)?.url)
        assertFalse(file.exists())
    }

    @Test
    fun `commands in device storage use the mounted host directory`() {
        val shared = temporaryFolder.newFolder("shared")
        val file = writeCommand(File(shared, "Download/project"))
        val mounts = DeviceStorage.Mounts(sharedStorage = shared)

        assertEquals("https://www.google.com/", consume("/sdcard/Download/project", mounts = mounts)?.url)
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
        assertEquals("http://127.0.0.1:8080/", consume("/workspace/project")?.url)
        assertFalse(file.exists())
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
        ).forEach { text ->
            file.writeText(text)
            assertNull(consume("/workspace/project"))
            assertTrue(file.exists())
        }
    }

    @Test
    fun `handoff may leave the current page untouched and records that it is waiting`() {
        val file = command("workspace/project", url = "", action = "handoff")

        val command = consume("/workspace/project")

        assertEquals(BrowserAction.HANDOFF, command?.action)
        assertEquals("", command?.url)
        assertFalse(file.exists())
        assertTrue(command!!.statusFile.readText().contains(BROWSER_STATUS_AWAITING_USER))
    }

    @Test
    fun `handoff never carries a URL because it acts on the page already open`() {
        listOf("https://example.com/login", "javascript:alert(1)").forEach { url ->
            val file = command("workspace/project", url = url, action = "handoff")

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
            val commands = mutableListOf<BrowserCommand>()
            val statusFile = File(runtimeDirectory, BROWSER_STATUS_FILE)
            var pending: BrowserCommand? = BrowserCommand(BrowserAction.OPEN, "https://www.google.com/", statusFile)
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            try {
                scope.launch {
                    watchBrowserCommands(
                        active = foreground,
                        intervalMillis = 1_000L,
                        poll = { pending.also { pending = null } },
                        onCommand = { commands += it },
                    )
                }
                advanceTimeBy(60_000L)
                runCurrent()
                assertEquals(emptyList<BrowserCommand>(), commands)

                foreground.value = true
                runCurrent()

                assertEquals(listOf("https://www.google.com/"), commands.map(BrowserCommand::url))
            } finally {
                scope.cancel()
            }
        }

    private fun consume(
        path: String?,
        agent: LocalAgent? = LocalAgent.CODEX,
        mounts: DeviceStorage.Mounts = DeviceStorage.Mounts.None,
    ): BrowserCommand? = consumeBrowserCommand(path, agent, runtimeDirectory, mounts)

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
        File(directory, BROWSER_COMMAND_FILE).apply {
            parentFile!!.mkdirs()
            writeText("{\"action\":\"$action\",\"url\":\"$url\"}")
        }
}
