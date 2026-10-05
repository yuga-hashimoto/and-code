package com.yugahashimoto.andcode.data.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class Draft(
    val text: String,
    val attachments: List<String> = emptyList(),
    val model: String? = null,
    val agent: String? = null,
)

/** Where unsent composer text is kept per session, so tests can swap in an in-memory store. */
interface DraftStore {
    fun save(
        sessionId: String,
        draft: Draft,
    )

    fun load(sessionId: String): Draft?

    fun clear(sessionId: String)
}

class DraftRepository(context: Context) : DraftStore {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    @Synchronized
    override fun save(
        sessionId: String,
        draft: Draft,
    ) {
        preferences.edit()
            .putString(key(sessionId), json.encodeToString(draft))
            .apply()
    }

    @Synchronized
    override fun load(sessionId: String): Draft? =
        runCatching {
            preferences.getString(key(sessionId), null)?.let { json.decodeFromString<Draft>(it) }
        }.getOrNull()

    @Synchronized
    override fun clear(sessionId: String) {
        preferences.edit().remove(key(sessionId)).apply()
    }

    @Synchronized
    fun clearAll() {
        preferences.edit().clear().apply()
    }

    private fun key(sessionId: String): String = "$KEY_PREFIX$sessionId"

    companion object {
        private const val PREFS_NAME = "opencode_android_drafts"
        private const val KEY_PREFIX = "draft_"
    }
}
