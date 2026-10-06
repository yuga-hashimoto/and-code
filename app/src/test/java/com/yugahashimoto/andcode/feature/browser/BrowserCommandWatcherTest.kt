package com.yugahashimoto.andcode.feature.browser

import com.yugahashimoto.andcode.core.storage.DeviceStorage
import com.yugahashimoto.andcode.runtime.LocalAgent
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

            assertEquals("https://www.google.com/", consume("/root/project", agent))
            assertFalse(file.exists())
            assertNull(consume("/root/project", agent))
        }
    }

    @Test
    fun `nested workspace commands are read from the bind mount`() {
        val file = command("workspace/team/project")
        val decoy = command("environment/rootfs/workspace/team/project", "https://example.com/decoy")

        assertEquals("https://www.google.com/", consume("/workspace/team/project"))
        assertFalse(file.exists())
        assertTrue(decoy.exists())
    }

    @Test
    fun `Antigravity consumes only the command in its Debian rootfs`() {
        val file = command("environment/antigravity-rootfs/root/project")
        val alpine = command("environment/rootfs/root/project", "https://example.com/alpine")

        assertEquals("https://www.google.com/", consume("/root/project", LocalAgent.ANTIGRAVITY))
        assertFalse(file.exists())
        assertTrue(alpine.exists())
    }

    @Test
    fun `Antigravity falls back to the shared rootfs when Debian is absent`() {
        val file = command("environment/rootfs/root/project")

        assertEquals("https://www.google.com/", consume("/root/project", LocalAgent.ANTIGRAVITY))
        assertFalse(file.exists())
    }

    @Test
    fun `commands in device storage use the mounted host directory`() {
        val shared = temporaryFolder.newFolder("shared")
        val file = writeCommand(File(shared, "Download/project"))
        val mounts = DeviceStorage.Mounts(sharedStorage = shared)

        assertEquals("https://www.google.com/", consume("/sdcard/Download/project", mounts = mounts))
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
        assertEquals("http://127.0.0.1:8080/", consume("/workspace/project"))
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
    fun `workspace traversal does not consume a command outside its mount`() {
        val file = command("outside")

        assertNull(consume("/workspace/../outside"))
        assertTrue(file.exists())
    }

    private fun consume(
        path: String?,
        agent: LocalAgent? = LocalAgent.CODEX,
        mounts: DeviceStorage.Mounts = DeviceStorage.Mounts.None,
    ): String? = consumeBrowserOpenCommand(path, agent, runtimeDirectory, mounts)

    private fun command(
        relativeDirectory: String,
        url: String = "https://www.google.com/",
    ): File = writeCommand(File(runtimeDirectory, relativeDirectory), url)

    private fun writeCommand(
        directory: File,
        url: String = "https://www.google.com/",
    ): File =
        File(directory, ".and-code/browser-command.json").apply {
            parentFile!!.mkdirs()
            writeText("{\"action\":\"open\",\"url\":\"$url\"}")
        }
}
