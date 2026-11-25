package com.yugahashimoto.andcode.runtime.local

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentMcpConfigTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun config(vararg rootfs: String): Pair<AgentMcpConfig, List<File>> {
        val dirs = rootfs.map { folder.newFolder(it) }
        return AgentMcpConfig { dirs } to dirs
    }

    private fun json(
        rootfs: File,
        path: String,
    ): JSONObject = JSONObject(File(rootfs, path).readText())

    @Test
    fun `adding a local server writes each agent's own shape into every rootfs`() {
        val (config, dirs) = config("rootfs", "antigravity-rootfs")
        config.add(McpServerSpec(name = "files", command = listOf("npx", "-y", "srv"), env = mapOf("K" to "v")))

        dirs.forEach { rootfs ->
            val opencode = json(rootfs, "root/.config/opencode/opencode.json").getJSONObject("mcp").getJSONObject("files")
            assertEquals("local", opencode.getString("type"))
            assertEquals("npx", opencode.getJSONArray("command").getString(0))
            assertEquals("v", opencode.getJSONObject("environment").getString("K"))

            val claude = json(rootfs, "root/.claude.json").getJSONObject("mcpServers").getJSONObject("files")
            assertEquals("stdio", claude.getString("type"))
            assertEquals("npx", claude.getString("command"))
            assertEquals("-y", claude.getJSONArray("args").getString(0))

            val antigravity = json(rootfs, "root/.gemini/config/mcp_config.json").getJSONObject("mcpServers").getJSONObject("files")
            assertEquals("npx", antigravity.getString("command"))
            assertEquals("v", antigravity.getJSONObject("env").getString("K"))
        }
    }

    @Test
    fun `adding a remote server and restricting agents`() {
        val (config, dirs) = config("rootfs")
        config.add(
            McpServerSpec(
                name = "docs",
                url = "https://example.com/mcp",
                headers = mapOf("A" to "b"),
                agents = setOf("claude", "antigravity"),
            ),
        )

        assertFalse(File(dirs[0], "root/.config/opencode/opencode.json").exists())
        val claude = json(dirs[0], "root/.claude.json").getJSONObject("mcpServers").getJSONObject("docs")
        assertEquals("http", claude.getString("type"))
        assertEquals("b", claude.getJSONObject("headers").getString("A"))
        val antigravity = json(dirs[0], "root/.gemini/config/mcp_config.json").getJSONObject("mcpServers").getJSONObject("docs")
        assertEquals("https://example.com/mcp", antigravity.getString("serverUrl"))
    }

    @Test
    fun `existing config content and other servers are preserved`() {
        val (config, dirs) = config("rootfs")
        File(dirs[0], "root/.claude.json").apply {
            parentFile.mkdirs()
            writeText("""{"theme":"dark","mcpServers":{"mine":{"type":"stdio","command":"x"}}}""")
        }
        config.add(McpServerSpec(name = "files", command = listOf("srv")))

        val root = json(dirs[0], "root/.claude.json")
        assertEquals("dark", root.getString("theme"))
        assertTrue(root.getJSONObject("mcpServers").has("mine"))
        assertTrue(root.getJSONObject("mcpServers").has("files"))
    }

    @Test
    fun `list merges agents per server and hides env values`() {
        val (config, _) = config("rootfs")
        config.add(McpServerSpec(name = "files", command = listOf("srv"), env = mapOf("TOKEN" to "super-secret")))

        val servers = config.list()
        assertEquals(1, servers.length())
        val entry = servers.getJSONObject(0)
        assertEquals("files", entry.getString("name"))
        assertFalse(entry.getBoolean("builtIn"))
        assertEquals(3, entry.getJSONArray("agents").length())
        assertEquals("TOKEN", entry.getJSONArray("envKeys").getString(0))
        assertFalse(servers.toString().contains("super-secret"))
    }

    @Test
    fun `remove deletes from the selected agents only`() {
        val (config, dirs) = config("rootfs")
        config.add(McpServerSpec(name = "files", command = listOf("srv")))

        assertEquals(1, config.remove("files", setOf("claude")))
        assertFalse(json(dirs[0], "root/.claude.json").getJSONObject("mcpServers").has("files"))
        assertTrue(json(dirs[0], "root/.config/opencode/opencode.json").getJSONObject("mcp").has("files"))
        assertEquals(2, config.remove("files", AgentMcpConfig.AGENTS))
    }

    @Test
    fun `built-in servers can be neither replaced nor removed`() {
        val (config, _) = config("rootfs")
        assertThrows(IllegalArgumentException::class.java) { config.add(McpServerSpec(name = "and-code-settings", command = listOf("x"))) }
        assertThrows(IllegalArgumentException::class.java) { config.remove("and-code-browser", AgentMcpConfig.AGENTS) }
    }

    @Test
    fun `invalid specs are rejected`() {
        val (config, _) = config("rootfs")
        assertThrows(IllegalArgumentException::class.java) { config.add(McpServerSpec(name = "bad name", command = listOf("x"))) }
        assertThrows(IllegalArgumentException::class.java) { config.add(McpServerSpec(name = "n")) }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { config.add(McpServerSpec(name = "n", command = listOf("x"), url = "https://a")) }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { config.add(McpServerSpec(name = "n", command = emptyList(), url = "https://a")) }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { config.add(McpServerSpec(name = "n", command = listOf(" "), url = "https://a")) }
        assertThrows(IllegalArgumentException::class.java) { config.add(McpServerSpec(name = "n", command = emptyList())) }
        assertThrows(IllegalArgumentException::class.java) { config.add(McpServerSpec(name = "n", url = "ftp://a")) }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { config.add(McpServerSpec(name = "n", command = listOf("x"), agents = setOf("codex"))) }
        assertThrows(IllegalArgumentException::class.java) { config.remove("n", emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { config.remove("n", setOf("codex")) }
    }

    @Test
    fun `a corrupt config file is not overwritten and prevents partial writes`() {
        val (config, dirs) = config("rootfs")
        File(dirs[0], "root/.claude.json").apply {
            parentFile.mkdirs()
            writeText("{not json")
        }
        assertThrows(IllegalStateException::class.java) {
            config.add(McpServerSpec(name = "files", command = listOf("srv")))
        }
        assertEquals("{not json", File(dirs[0], "root/.claude.json").readText())
        assertFalse(File(dirs[0], "root/.config/opencode/opencode.json").exists())
    }
}
