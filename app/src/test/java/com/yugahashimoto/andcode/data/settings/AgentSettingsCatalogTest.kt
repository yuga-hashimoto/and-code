package com.yugahashimoto.andcode.data.settings

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSettingsCatalogTest {
    private fun coerce(
        key: String,
        raw: Any?,
    ): Any? = AgentSettingsCatalog.coerce(AgentSettingsCatalog.find(key), raw)

    @Test
    fun `keys are unique and every enum has options`() {
        val keys = AgentSettingsCatalog.descriptors.map { it.key }
        assertEquals(keys.distinct(), keys)
        AgentSettingsCatalog.descriptors
            .filter { it.type == AgentSettingType.ENUM }
            .forEach { assertTrue("${it.key} has options", it.options.isNotEmpty()) }
    }

    @Test
    fun `defaults of every setting satisfy the setting's own validation`() {
        AgentSettingsCatalog.descriptors.forEach { descriptor ->
            val default = AgentSettingsCatalog.defaultOf(descriptor)
            AgentSettingsCatalog.coerce(descriptor, default)
        }
    }

    @Test
    fun `security settings are read-only for agents`() {
        listOf("autoAcceptPermissions", "guestBrowserAgentControlEnabled", "analyticsEnabled", "wakeWordEnabled").forEach {
            assertFalse(it, AgentSettingsCatalog.find(it).writable)
        }
        assertTrue(AgentSettingsCatalog.find("theme").writable)
    }

    @Test
    fun `booleans accept real booleans and their string forms only`() {
        assertEquals(true, coerce("enterToSend", true))
        assertEquals(false, coerce("enterToSend", "FALSE"))
        assertThrows(IllegalArgumentException::class.java) { coerce("enterToSend", "yes") }
        assertThrows(IllegalArgumentException::class.java) { coerce("enterToSend", 1) }
    }

    @Test
    fun `integers are range checked and must be whole`() {
        assertEquals(18, coerce("uiFontSize", 18))
        assertEquals(18, coerce("uiFontSize", 18.0))
        assertThrows(IllegalArgumentException::class.java) { coerce("uiFontSize", 40) }
        assertThrows(IllegalArgumentException::class.java) { coerce("uiFontSize", 15.5) }
        assertThrows(IllegalArgumentException::class.java) { coerce("uiFontSize", "big") }
    }

    @Test
    fun `numbers become floats within range`() {
        assertEquals(1.25f, coerce("ttsSpeechRate", 1.25) as Float, 0f)
        assertThrows(IllegalArgumentException::class.java) { coerce("ttsSpeechRate", 3.0) }
        assertThrows(IllegalArgumentException::class.java) { coerce("wakeWordSensitivity", -0.1) }
    }

    @Test
    fun `enums must match an option`() {
        assertEquals("light", coerce("theme", " light "))
        val error = assertThrows(IllegalArgumentException::class.java) { coerce("theme", "neon") }
        assertTrue(error.message!!.contains("dark"))
    }

    @Test
    fun `null clears nullable settings and is rejected elsewhere`() {
        assertNull(coerce("selectedModel", JSONObject.NULL))
        assertNull(coerce("ttsAndroidEngine", ""))
        assertNull(coerce("codexFastMode", null))
        assertThrows(IllegalArgumentException::class.java) { coerce("theme", JSONObject.NULL) }
    }

    @Test
    fun `model keys need provider and model`() {
        assertEquals("anthropic/claude", coerce("selectedModel", "anthropic/claude"))
        assertThrows(IllegalArgumentException::class.java) { coerce("selectedModel", "claude") }
        assertThrows(IllegalArgumentException::class.java) { coerce("favoriteModelKeys", JSONArray(listOf("ok/model", "bad"))) }
    }

    @Test
    fun `lists are trimmed and de-duplicated`() {
        val value = coerce("pinnedSessionIds", JSONArray(listOf(" a ", "b", "a")))
        assertEquals(listOf("a", "b"), value)
        assertThrows(IllegalArgumentException::class.java) { coerce("pinnedSessionIds", JSONArray(listOf(""))) }
        assertThrows(IllegalArgumentException::class.java) { coerce("pinnedSessionIds", "a") }
    }

    @Test
    fun `secrets are redacted in json and report whether they are set`() {
        val descriptor = AgentSettingsCatalog.find("ttsOpenAiApiKey")
        val unset = AgentSettingsCatalog.toJson(descriptor, AppPreferences())
        assertFalse(unset.getBoolean("isSet"))
        val set = AgentSettingsCatalog.toJson(descriptor, AppPreferences(ttsOpenAiApiKey = "sk-secret"))
        assertTrue(set.getBoolean("isSet"))
        assertTrue(set.isNull("value"))
        assertFalse(set.toString().contains("sk-secret"))
    }

    @Test
    fun `json reports current value default and writability`() {
        val prefs = AppPreferences(theme = "zinc", providerId = "p", modelId = "m", ttsSpeechRate = 0.7f)
        val theme = AgentSettingsCatalog.toJson(AgentSettingsCatalog.find("theme"), prefs)
        assertEquals("zinc", theme.getString("value"))
        assertEquals("dark", theme.getString("default"))
        assertTrue(theme.getBoolean("writable"))
        assertEquals("p/m", AgentSettingsCatalog.toJson(AgentSettingsCatalog.find("selectedModel"), prefs).getString("value"))
        assertEquals(0.7, AgentSettingsCatalog.toJson(AgentSettingsCatalog.find("ttsSpeechRate"), prefs).getDouble("value"), 0.0)
        val locked = AgentSettingsCatalog.toJson(AgentSettingsCatalog.find("autoAcceptPermissions"), prefs)
        assertFalse(locked.getBoolean("writable"))
        assertTrue(locked.has("protectedReason"))
    }
}
