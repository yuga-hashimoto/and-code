package com.yugahashimoto.andcode.feature.settings

import com.yugahashimoto.andcode.data.settings.AppPreferences
import com.yugahashimoto.andcode.runtime.local.McpConfigStore
import com.yugahashimoto.andcode.runtime.local.McpServerSpec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class AgentSettingsBridgeTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class FakeStore(
        var prefs: AppPreferences = AppPreferences(),
    ) : AgentSettingsStore {
        val writes = mutableListOf<Pair<String, Any?>>()

        override fun current(): AppPreferences = prefs

        override fun write(
            key: String,
            value: Any?,
        ) {
            writes += key to value
            prefs =
                when (key) {
                    "theme" -> prefs.copy(theme = value as String)
                    "uiFontSize" -> prefs.copy(uiFontSize = value as Int)
                    "favoriteModelKeys" -> prefs.copy(favoriteModelKeys = (value as List<*>).map { it.toString() }.toSet())
                    "ttsOpenAiApiKey" -> prefs.copy(ttsOpenAiApiKey = value as String)
                    else -> prefs
                }
        }
    }

    private class FakeMcp : McpConfigStore {
        val added = mutableListOf<McpServerSpec>()
        val removed = mutableListOf<Pair<String, Set<String>>>()

        override fun list(): JSONArray = JSONArray().put(JSONObject().put("name", "demo"))

        override fun add(spec: McpServerSpec) {
            added += spec
        }

        override fun remove(
            name: String,
            agents: Set<String>,
        ): Int {
            removed += name to agents
            return 3
        }
    }

    private fun bridge(
        store: AgentSettingsStore = FakeStore(),
        mcp: McpConfigStore = FakeMcp(),
        clock: () -> Long = System::currentTimeMillis,
    ) = AgentSettingsBridge(folder.root, store, mcp, clock = clock)

    private fun call(
        bridge: AgentSettingsBridge,
        op: String,
        args: JSONObject = JSONObject(),
        createdAtMs: Long = 0L,
    ): JSONObject {
        val id = UUID.randomUUID().toString()
        val pending = File(bridge.bridgeDir, "pending/$id.json")
        pending.parentFile?.mkdirs()
        pending.writeText(JSONObject().put("op", op).put("args", args).put("createdAtMs", createdAtMs).toString())
        bridge.pollOnce()
        assertFalse("request is consumed", pending.exists())
        return JSONObject(File(bridge.bridgeDir, "responses/$id.json").readText())
    }

    @Test
    fun `list returns every setting and filters by category`() {
        val bridge = bridge()
        val all = call(bridge, "list").getJSONObject("data").getJSONArray("settings")
        assertTrue(all.length() > 20)
        val voice = call(bridge, "list", JSONObject().put("category", "voice")).getJSONObject("data").getJSONArray("settings")
        assertTrue(voice.length() in 1 until all.length())
        val bad = call(bridge, "list", JSONObject().put("category", "nope"))
        assertFalse(bad.getBoolean("ok"))
    }

    @Test
    fun `set validates writes and echoes the new value`() {
        val store = FakeStore()
        val bridge = bridge(store)
        val response = call(bridge, "set", JSONObject().put("key", "theme").put("value", "zinc"))
        assertTrue(response.toString(), response.getBoolean("ok"))
        assertEquals(listOf("theme" to "zinc"), store.writes)
        val applied = response.getJSONObject("data").getJSONArray("applied").getJSONObject(0)
        assertEquals("zinc", applied.getString("value"))
    }

    @Test
    fun `set rejects invalid values and unknown keys without writing`() {
        val store = FakeStore()
        val bridge = bridge(store)
        assertFalse(call(bridge, "set", JSONObject().put("key", "theme").put("value", "neon")).getBoolean("ok"))
        assertFalse(call(bridge, "set", JSONObject().put("key", "nothing").put("value", 1)).getBoolean("ok"))
        assertFalse(call(bridge, "set", JSONObject().put("key", "theme")).getBoolean("ok"))
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `protected settings cannot be changed by agents`() {
        val store = FakeStore()
        val bridge = bridge(store)
        val response = call(bridge, "set", JSONObject().put("key", "autoAcceptPermissions").put("value", true))
        assertFalse(response.getBoolean("ok"))
        assertTrue(response.getString("error").contains("read-only"))
        assertFalse(call(bridge, "reset", JSONObject().put("key", "analyticsEnabled")).getBoolean("ok"))
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `setMany applies nothing when one entry is invalid`() {
        val store = FakeStore()
        val bridge = bridge(store)
        val values = JSONObject().put("theme", "light").put("uiFontSize", 99)
        assertFalse(call(bridge, "setMany", JSONObject().put("values", values)).getBoolean("ok"))
        assertTrue(store.writes.isEmpty())

        val good = JSONObject().put("theme", "light").put("uiFontSize", 20)
        assertTrue(call(bridge, "setMany", JSONObject().put("values", good)).getBoolean("ok"))
        assertEquals(setOf("theme", "uiFontSize"), store.writes.map { it.first }.toSet())
    }

    @Test
    fun `setMany refuses a batch that contains a protected setting`() {
        val store = FakeStore()
        val bridge = bridge(store)
        val values = JSONObject().put("theme", "light").put("autoAcceptPermissions", true)
        assertFalse(call(bridge, "setMany", JSONObject().put("values", values)).getBoolean("ok"))
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `reset restores the default`() {
        val store = FakeStore(AppPreferences(theme = "light"))
        val bridge = bridge(store)
        assertTrue(call(bridge, "reset", JSONObject().put("key", "theme")).getBoolean("ok"))
        assertEquals("dark", store.prefs.theme)
    }

    @Test
    fun `updateList adds and removes items`() {
        val store = FakeStore(AppPreferences(favoriteModelKeys = setOf("a/b", "c/d")))
        val bridge = bridge(store)
        val args = JSONObject().put("key", "favoriteModelKeys").put("add", JSONArray(listOf("e/f"))).put("remove", JSONArray(listOf("a/b")))
        assertTrue(call(bridge, "updateList", args).getBoolean("ok"))
        assertEquals(setOf("c/d", "e/f"), store.prefs.favoriteModelKeys)

        assertFalse(call(bridge, "updateList", JSONObject().put("key", "theme").put("add", JSONArray(listOf("x")))).getBoolean("ok"))
        assertFalse(call(bridge, "updateList", JSONObject().put("key", "favoriteModelKeys")).getBoolean("ok"))
    }

    @Test
    fun `secret values are never echoed`() {
        val store = FakeStore()
        val bridge = bridge(store)
        val response = call(bridge, "set", JSONObject().put("key", "ttsOpenAiApiKey").put("value", "sk-secret"))
        assertTrue(response.getBoolean("ok"))
        assertEquals("sk-secret", store.prefs.ttsOpenAiApiKey)
        assertFalse(response.toString().contains("sk-secret"))
        assertFalse(call(bridge, "get", JSONObject().put("key", "ttsOpenAiApiKey")).toString().contains("sk-secret"))
    }

    @Test
    fun `mcp operations go through the config store`() {
        val mcp = FakeMcp()
        val bridge = bridge(mcp = mcp)
        assertEquals("demo", call(bridge, "mcpList").getJSONObject("data").getJSONArray("servers").getJSONObject(0).getString("name"))

        val add =
            JSONObject()
                .put("name", "files")
                .put("command", JSONArray(listOf("npx", "-y", "server")))
                .put("env", JSONObject().put("TOKEN", "x"))
                .put("agents", JSONArray(listOf("claude")))
        assertTrue(call(bridge, "mcpAdd", add).getBoolean("ok"))
        val spec = mcp.added.single()
        assertEquals("files", spec.name)
        assertEquals(listOf("npx", "-y", "server"), spec.command)
        assertEquals(mapOf("TOKEN" to "x"), spec.env)
        assertEquals(setOf("claude"), spec.agents)

        assertTrue(call(bridge, "mcpRemove", JSONObject().put("name", "files")).getBoolean("ok"))
        assertEquals("files", mcp.removed.single().first)
    }

    @Test
    fun `unknown and malformed requests get an error response`() {
        val bridge = bridge()
        assertFalse(call(bridge, "explode").getBoolean("ok"))
        val id = "bad"
        File(bridge.bridgeDir, "pending").mkdirs()
        File(bridge.bridgeDir, "pending/$id.json").writeText("{not json")
        bridge.pollOnce()
        assertFalse(JSONObject(File(bridge.bridgeDir, "responses/$id.json").readText()).getBoolean("ok"))
    }

    @Test
    fun `expired requests are not executed`() {
        val store = FakeStore()
        val now = 1_000_000L
        val bridge = bridge(store, clock = { now })
        val response =
            call(
                bridge,
                "set",
                JSONObject().put("key", "theme").put("value", "zinc"),
                createdAtMs = now - AgentSettingsBridge.REQUEST_EXPIRES_AFTER_MILLIS - 1,
            )
        assertFalse(response.getBoolean("ok"))
        assertTrue(store.writes.isEmpty())
    }
}
