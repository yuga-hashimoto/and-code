package com.yugahashimoto.andcode.data.connection

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.yugahashimoto.andcode.data.repository.UnreadSessionStore
import com.yugahashimoto.andcode.feature.wakeword.WakeWordGrammar
import com.yugahashimoto.andcode.runtime.RuntimeConnectionStore
import com.yugahashimoto.andcode.runtime.local.AdbConnectionStore

class SecureSettingsRepository(context: Context) : RuntimeConnectionStore, UnreadSessionStore, AdbConnectionStore {
    private val masterKey =
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

    private val preferences: SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    @Synchronized
    override fun connections(): List<ConnectionProfile> =
        runCatching {
            ConnectionProfileCodec.decode(preferences.getString(KEY_CONNECTIONS, "[]").orEmpty())
        }.getOrDefault(emptyList())

    @Synchronized
    override fun upsertConnection(profile: ConnectionProfile) {
        val updated = connections().filterNot { it.id == profile.id } + profile
        preferences.edit().putString(KEY_CONNECTIONS, ConnectionProfileCodec.encode(updated)).apply()
        if (selectedConnectionId.isNullOrBlank()) selectedConnectionId = profile.id
    }

    @Synchronized
    override fun deleteConnection(id: String) {
        val updated = connections().filterNot { it.id == id }
        preferences.edit().putString(KEY_CONNECTIONS, ConnectionProfileCodec.encode(updated)).apply()
        if (selectedConnectionId == id) selectedConnectionId = updated.firstOrNull()?.id
    }

    var selectedConnectionId: String?
        get() = preferences.getString(KEY_SELECTED_CONNECTION, null)
        set(value) = preferences.edit().putString(KEY_SELECTED_CONNECTION, value).apply()

    override var selectedRuntimeId: String?
        get() = selectedConnectionId
        set(value) {
            selectedConnectionId = value
        }

    fun selectedConnection(): ConnectionProfile? = selectedConnectionId?.let { selected -> connections().firstOrNull { it.id == selected } }

    @Synchronized
    override fun saveConnectedPort(port: Int) {
        preferences.edit().putInt(KEY_ADB_CONNECTION_PORT, port).apply()
    }

    @Synchronized
    override fun loadConnectedPort(): Int? =
        if (preferences.contains(KEY_ADB_CONNECTION_PORT)) {
            preferences.getInt(KEY_ADB_CONNECTION_PORT, 0)
        } else {
            null
        }

    @Synchronized
    override fun clearConnectedPort() {
        preferences.edit().remove(KEY_ADB_CONNECTION_PORT).apply()
    }

    var ttsEnabled: Boolean
        get() = preferences.getBoolean(KEY_TTS_ENABLED, true)
        set(value) = preferences.edit().putBoolean(KEY_TTS_ENABLED, value).apply()

    var ttsProvider: String
        get() = preferences.getString(KEY_TTS_PROVIDER, "android") ?: "android"
        set(value) = preferences.edit().putString(KEY_TTS_PROVIDER, value).apply()

    var ttsAndroidEngine: String?
        get() = preferences.getString(KEY_TTS_ANDROID_ENGINE, null)
        set(value) = preferences.edit().putString(KEY_TTS_ANDROID_ENGINE, value).apply()

    var ttsSpeechRate: Float
        get() = preferences.getFloat(KEY_TTS_SPEECH_RATE, 1.0f)
        set(value) = preferences.edit().putFloat(KEY_TTS_SPEECH_RATE, value).apply()

    var ttsPitch: Float
        get() = preferences.getFloat(KEY_TTS_PITCH, 1.0f)
        set(value) = preferences.edit().putFloat(KEY_TTS_PITCH, value).apply()

    var ttsOpenAiApiKey: String
        get() = preferences.getString(KEY_TTS_OPENAI_API_KEY, "").orEmpty()
        set(value) = preferences.edit().putString(KEY_TTS_OPENAI_API_KEY, value).apply()

    var ttsOpenAiVoice: String
        get() = preferences.getString(KEY_TTS_OPENAI_VOICE, "alloy") ?: "alloy"
        set(value) = preferences.edit().putString(KEY_TTS_OPENAI_VOICE, value).apply()

    var ttsOpenAiModel: String
        get() = preferences.getString(KEY_TTS_OPENAI_MODEL, "gpt-4o-mini-tts") ?: "gpt-4o-mini-tts"
        set(value) = preferences.edit().putString(KEY_TTS_OPENAI_MODEL, value).apply()

    var ttsElevenLabsApiKey: String
        get() = preferences.getString(KEY_TTS_ELEVENLABS_API_KEY, "").orEmpty()
        set(value) = preferences.edit().putString(KEY_TTS_ELEVENLABS_API_KEY, value).apply()

    var ttsElevenLabsVoiceId: String
        get() = preferences.getString(KEY_TTS_ELEVENLABS_VOICE_ID, "").orEmpty()
        set(value) = preferences.edit().putString(KEY_TTS_ELEVENLABS_VOICE_ID, value).apply()

    var ttsElevenLabsModel: String
        get() = preferences.getString(KEY_TTS_ELEVENLABS_MODEL, "eleven_multilingual_v2") ?: "eleven_multilingual_v2"
        set(value) = preferences.edit().putString(KEY_TTS_ELEVENLABS_MODEL, value).apply()

    // On by default: being unable to stop a long answer without reaching for the screen is the
    // problem this exists to solve, and it costs nothing when the wake word is switched off.
    var ttsBargeInEnabled: Boolean
        get() = preferences.getBoolean(KEY_TTS_BARGE_IN_ENABLED, true)
        set(value) = preferences.edit().putBoolean(KEY_TTS_BARGE_IN_ENABLED, value).apply()

    var continuousConversation: Boolean
        get() = preferences.getBoolean(KEY_CONTINUOUS_CONVERSATION, false)
        set(value) = preferences.edit().putBoolean(KEY_CONTINUOUS_CONVERSATION, value).apply()

    var wakeWordEnabled: Boolean
        get() = preferences.getBoolean(KEY_WAKE_WORD_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_WAKE_WORD_ENABLED, value).apply()

    /** Free text now: the recogniser is constrained to whatever this says rather than to a
     * phrase someone trained a network for in advance. */
    var wakeWordPhrase: String
        get() = preferences.getString(KEY_WAKE_WORD_PHRASE, null) ?: WakeWordGrammar.DEFAULT_PHRASE
        set(value) = preferences.edit().putString(KEY_WAKE_WORD_PHRASE, value).apply()

    /** How sure the recogniser has to be. Higher is harder to trigger. */
    var wakeWordSensitivity: Float
        get() = preferences.getFloat(KEY_WAKE_WORD_SENSITIVITY, 0.7f)
        set(value) = preferences.edit().putFloat(KEY_WAKE_WORD_SENSITIVITY, value).apply()

    /** Which downloaded speech model listens, as a [com.yugahashimoto.andcode.feature.wakeword.VoskModelLanguage] id. */
    var wakeWordModelLanguage: String?
        get() = preferences.getString(KEY_WAKE_WORD_MODEL_LANGUAGE, null)
        set(value) = preferences.edit().putString(KEY_WAKE_WORD_MODEL_LANGUAGE, value).apply()

    var autoAcceptPermissions: Boolean
        get() = preferences.getBoolean(KEY_AUTO_ACCEPT_PERMISSIONS, false)
        set(value) = preferences.edit().putBoolean(KEY_AUTO_ACCEPT_PERMISSIONS, value).apply()

    var assistantSessionId: String?
        get() = preferences.getString(KEY_ASSISTANT_SESSION_ID, null)
        set(value) = preferences.edit().putString(KEY_ASSISTANT_SESSION_ID, value).apply()

    var selectedProviderId: String?
        get() = preferences.getString(KEY_PROVIDER_ID, null)
        set(value) = preferences.edit().putString(KEY_PROVIDER_ID, value).apply()

    var selectedModelId: String?
        get() = preferences.getString(KEY_MODEL_ID, null)
        set(value) = preferences.edit().putString(KEY_MODEL_ID, value).apply()

    var selectedAgentId: String?
        get() = preferences.getString(KEY_AGENT_ID, null)
        set(value) = preferences.edit().putString(KEY_AGENT_ID, value).apply()

    var favoriteModelKeys: Set<String>
        get() = preferences.getStringSet(KEY_FAVORITE_MODELS, emptySet()).orEmpty()
        set(value) = preferences.edit().putStringSet(KEY_FAVORITE_MODELS, value).apply()

    var recentModelKeys: List<String>
        get() =
            preferences.getString(KEY_RECENT_MODELS, null)
                ?.split('\n')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        set(value) {
            preferences.edit()
                .putString(KEY_RECENT_MODELS, value.take(MAX_RECENT_MODELS).joinToString("\n"))
                .apply()
        }

    var hiddenModelKeys: Set<String>
        get() = preferences.getStringSet(KEY_HIDDEN_MODELS, emptySet()).orEmpty()
        set(value) = preferences.edit().putStringSet(KEY_HIDDEN_MODELS, value).apply()

    var providerApiKeys: Map<String, String>
        get() = providerApiKeys()
        set(value) {
            preferences.edit()
                .putString(
                    KEY_PROVIDER_API_KEYS,
                    com.yugahashimoto.andcode.runtime.local.LocalProviderCredentialStore.encodeMap(value),
                )
                .apply()
        }

    fun providerApiKeys(): Map<String, String> =
        com.yugahashimoto.andcode.runtime.local.LocalProviderCredentialStore.decodeMap(
            preferences.getString(KEY_PROVIDER_API_KEYS, null),
        )

    val hasManagedProviderApiKeyIds: Boolean
        get() = preferences.contains(KEY_MANAGED_PROVIDER_API_KEY_IDS)

    var managedProviderApiKeyIds: Set<String>
        get() =
            preferences.getStringSet(KEY_MANAGED_PROVIDER_API_KEY_IDS, emptySet())
                .orEmpty()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()
        set(value) {
            preferences.edit()
                .putStringSet(
                    KEY_MANAGED_PROVIDER_API_KEY_IDS,
                    value.map(String::trim).filter(String::isNotEmpty).toSet(),
                )
                .apply()
        }

    var assistantRuntimeId: String?
        get() = preferences.getString(KEY_ASSISTANT_RUNTIME_ID, null)
        set(value) = preferences.edit().putString(KEY_ASSISTANT_RUNTIME_ID, value).apply()

    var assistantWorkspacePath: String?
        get() = preferences.getString(KEY_ASSISTANT_WORKSPACE_PATH, null)
        set(value) = preferences.edit().putString(KEY_ASSISTANT_WORKSPACE_PATH, value).apply()

    /**
     * Model the voice assistant talks to, independent of the chat's own pick.
     *
     * Null means "whatever the chat is using". The assistant can be pointed at another agent than
     * the one open in the chat, and reusing [selectedModelId] then sent that agent a model it does
     * not serve.
     */
    var assistantProviderId: String?
        get() = preferences.getString(KEY_ASSISTANT_PROVIDER_ID, null)
        set(value) = preferences.edit().putString(KEY_ASSISTANT_PROVIDER_ID, value).apply()

    var assistantModelId: String?
        get() = preferences.getString(KEY_ASSISTANT_MODEL_ID, null)
        set(value) = preferences.edit().putString(KEY_ASSISTANT_MODEL_ID, value).apply()

    var safWorkspaceUris: List<String>
        get() =
            preferences.getString(KEY_SAF_WORKSPACE_URIS, null)
                ?.split('\n')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        set(value) {
            preferences.edit()
                .putString(KEY_SAF_WORKSPACE_URIS, value.joinToString("\n"))
                .apply()
        }

    var projectPaths: List<String>
        get() =
            preferences.getString(KEY_PROJECT_PATHS, null)
                ?.split('\n')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        set(value) {
            preferences.edit()
                .putString(KEY_PROJECT_PATHS, value.joinToString("\n"))
                .apply()
        }

    /**
     * Workspace folders the user removed from the list.
     *
     * Remembered because most rows are not registrations: the runtimes report a folder from their
     * own chat history and from what is on disk, so a removal that only cleared [projectPaths] was
     * undone by the next refresh. Registering a path again — importing or cloning into it — clears
     * it from here.
     */
    var hiddenWorkspacePaths: List<String>
        get() =
            preferences.getString(KEY_HIDDEN_WORKSPACE_PATHS, null)
                ?.split('\n')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        set(value) {
            preferences.edit()
                .putString(KEY_HIDDEN_WORKSPACE_PATHS, value.joinToString("\n"))
                .apply()
        }

    /** True once the user has completed (or explicitly skipped) first-run onboarding. */
    var onboardingCompleted: Boolean
        get() = preferences.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) = preferences.edit().putBoolean(KEY_ONBOARDING_COMPLETED, value).apply()

    /** Chats that finished without being read, kept across restarts for the drawer markers. */
    override var unreadSessionIds: Set<String>
        get() =
            preferences
                .getStringSet(KEY_UNREAD_SESSIONS, emptySet())
                .orEmpty()
                .filter { it.isNotBlank() }
                .toSet()
        set(value) {
            preferences
                .edit()
                .putStringSet(KEY_UNREAD_SESSIONS, value.filter { it.isNotBlank() }.toSet())
                .apply()
        }

    var githubToken: String?
        get() = preferences.getString(KEY_GITHUB_TOKEN, null)
        set(value) = preferences.edit().putString(KEY_GITHUB_TOKEN, value).apply()

    var githubLogin: String?
        get() = preferences.getString(KEY_GITHUB_LOGIN, null)
        set(value) = preferences.edit().putString(KEY_GITHUB_LOGIN, value).apply()

    var githubStarPromptShown: Boolean
        get() = preferences.getBoolean(KEY_GITHUB_STAR_PROMPT_SHOWN, false)
        set(value) = preferences.edit().putBoolean(KEY_GITHUB_STAR_PROMPT_SHOWN, value).apply()

    var githubStarPromptDeferred: Boolean
        get() = preferences.getBoolean(KEY_GITHUB_STAR_PROMPT_DEFERRED, false)
        set(value) = preferences.edit().putBoolean(KEY_GITHUB_STAR_PROMPT_DEFERRED, value).apply()

    var githubStarSecondPromptShown: Boolean
        get() = preferences.getBoolean(KEY_GITHUB_STAR_SECOND_PROMPT_SHOWN, false)
        set(value) = preferences.edit().putBoolean(KEY_GITHUB_STAR_SECOND_PROMPT_SHOWN, value).apply()

    var githubStarThankYouShown: Boolean
        get() = preferences.getBoolean(KEY_GITHUB_STAR_THANK_YOU_SHOWN, false)
        set(value) = preferences.edit().putBoolean(KEY_GITHUB_STAR_THANK_YOU_SHOWN, value).apply()

    var githubStarredCache: Boolean?
        get() = if (preferences.contains(KEY_GITHUB_STARRED_CACHE)) preferences.getBoolean(KEY_GITHUB_STARRED_CACHE, false) else null
        set(value) {
            val editor = preferences.edit()
            if (value == null) editor.remove(KEY_GITHUB_STARRED_CACHE) else editor.putBoolean(KEY_GITHUB_STARRED_CACHE, value)
            editor.apply()
        }

    var githubStarStatusCheckedAt: Long
        get() = preferences.getLong(KEY_GITHUB_STAR_STATUS_CHECKED_AT, 0L)
        set(value) = preferences.edit().putLong(KEY_GITHUB_STAR_STATUS_CHECKED_AT, value).apply()

    var githubStarCountCache: Int?
        get() = if (preferences.contains(KEY_GITHUB_STAR_COUNT_CACHE)) preferences.getInt(KEY_GITHUB_STAR_COUNT_CACHE, 0) else null
        set(value) {
            val editor = preferences.edit()
            if (value == null) editor.remove(KEY_GITHUB_STAR_COUNT_CACHE) else editor.putInt(KEY_GITHUB_STAR_COUNT_CACHE, value)
            editor.apply()
        }

    var githubStarCountCheckedAt: Long
        get() = preferences.getLong(KEY_GITHUB_STAR_COUNT_CHECKED_AT, 0L)
        set(value) = preferences.edit().putLong(KEY_GITHUB_STAR_COUNT_CHECKED_AT, value).apply()

    var theme: String
        get() = preferences.getString(KEY_THEME, "dark") ?: "dark"
        set(value) = preferences.edit().putString(KEY_THEME, value).apply()

    var uiFontSize: Int
        get() = preferences.getInt(KEY_UI_FONT_SIZE, 16)
        set(value) = preferences.edit().putInt(KEY_UI_FONT_SIZE, value).apply()

    var codeFontSize: Int
        get() = preferences.getInt(KEY_CODE_FONT_SIZE, 12)
        set(value) = preferences.edit().putInt(KEY_CODE_FONT_SIZE, value).apply()

    var syntaxTheme: String
        get() = preferences.getString(KEY_SYNTAX_THEME, "one-dark") ?: "one-dark"
        set(value) = preferences.edit().putString(KEY_SYNTAX_THEME, value).apply()

    var toolCallDetailLevel: String
        get() = preferences.getString(KEY_TOOL_CALL_DETAIL_LEVEL, "detailed") ?: "detailed"
        set(value) = preferences.edit().putString(KEY_TOOL_CALL_DETAIL_LEVEL, value).apply()

    var autoExpandReasoning: Boolean
        get() = preferences.getBoolean(KEY_AUTO_EXPAND_REASONING, false)
        set(value) = preferences.edit().putBoolean(KEY_AUTO_EXPAND_REASONING, value).apply()

    var sendBehavior: String
        get() = preferences.getString(KEY_SEND_BEHAVIOR, "interrupt") ?: "interrupt"
        set(value) = preferences.edit().putString(KEY_SEND_BEHAVIOR, value).apply()

    var enterToSend: Boolean
        get() = preferences.getBoolean(KEY_ENTER_TO_SEND, false)
        set(value) = preferences.edit().putBoolean(KEY_ENTER_TO_SEND, value).apply()

    var sidebarGrouping: String
        get() = preferences.getString(KEY_SIDEBAR_GROUPING, "project") ?: "project"
        set(value) = preferences.edit().putString(KEY_SIDEBAR_GROUPING, value).apply()

    var workspaceTitleSource: String
        get() = preferences.getString(KEY_WORKSPACE_TITLE_SOURCE, "title") ?: "title"
        set(value) = preferences.edit().putString(KEY_WORKSPACE_TITLE_SOURCE, value).apply()

    var language: String
        get() = preferences.getString(KEY_LANGUAGE, "system") ?: "system"
        set(value) = preferences.edit().putString(KEY_LANGUAGE, value).apply()

    var liveTranscriptEnabled: Boolean
        get() = preferences.getBoolean(KEY_LIVE_TRANSCRIPT_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_LIVE_TRANSCRIPT_ENABLED, value).apply()

    /** Analytics is opt-in because this app handles source code and provider credentials. */
    var analyticsEnabled: Boolean
        get() = preferences.getBoolean(KEY_ANALYTICS_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_ANALYTICS_ENABLED, value).apply()

    var collapsedSidebarSections: Set<String>
        get() = preferences.getStringSet(KEY_COLLAPSED_SIDEBAR_SECTIONS, emptySet()).orEmpty()
        set(value) = preferences.edit().putStringSet(KEY_COLLAPSED_SIDEBAR_SECTIONS, value).apply()

    companion object {
        fun readLanguage(context: Context): String =
            runCatching {
                val masterKey =
                    MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()
                val preferences =
                    EncryptedSharedPreferences.create(
                        context,
                        PREFS_NAME,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                    )
                preferences.getString(KEY_LANGUAGE, "system") ?: "system"
            }.getOrDefault("system")

        private const val PREFS_NAME = "opencode_android_secure_settings"
        private const val KEY_CONNECTIONS = "connections"
        private const val KEY_SELECTED_CONNECTION = "selected_connection"
        private const val KEY_ADB_CONNECTION_PORT = "adb_connection_port"
        private const val KEY_TTS_ENABLED = "tts_enabled"
        private const val KEY_TTS_PROVIDER = "tts_provider"
        private const val KEY_TTS_ANDROID_ENGINE = "tts_android_engine"
        private const val KEY_TTS_SPEECH_RATE = "tts_speech_rate"
        private const val KEY_TTS_PITCH = "tts_pitch"
        private const val KEY_TTS_OPENAI_API_KEY = "tts_openai_api_key"
        private const val KEY_TTS_OPENAI_VOICE = "tts_openai_voice"
        private const val KEY_TTS_OPENAI_MODEL = "tts_openai_model"
        private const val KEY_TTS_ELEVENLABS_API_KEY = "tts_elevenlabs_api_key"
        private const val KEY_TTS_ELEVENLABS_VOICE_ID = "tts_elevenlabs_voice_id"
        private const val KEY_TTS_ELEVENLABS_MODEL = "tts_elevenlabs_model"
        private const val KEY_CONTINUOUS_CONVERSATION = "continuous_conversation"
        private const val KEY_TTS_BARGE_IN_ENABLED = "tts_barge_in_enabled"
        private const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
        private const val KEY_WAKE_WORD_PHRASE = "wake_word_phrase"
        private const val KEY_WAKE_WORD_SENSITIVITY = "wake_word_sensitivity"
        private const val KEY_WAKE_WORD_MODEL_LANGUAGE = "wake_word_model_language"
        private const val KEY_AUTO_ACCEPT_PERMISSIONS = "auto_accept_permissions"
        private const val KEY_ASSISTANT_SESSION_ID = "assistant_session_id"
        private const val KEY_PROVIDER_ID = "provider_id"
        private const val KEY_MODEL_ID = "model_id"
        private const val KEY_AGENT_ID = "agent_id"
        private const val KEY_FAVORITE_MODELS = "favorite_models"
        private const val KEY_RECENT_MODELS = "recent_models"
        private const val MAX_RECENT_MODELS = 3
        private const val KEY_HIDDEN_MODELS = "hidden_models"
        private const val KEY_PROVIDER_API_KEYS = "provider_api_keys"
        private const val KEY_MANAGED_PROVIDER_API_KEY_IDS = "managed_provider_api_key_ids"
        private const val KEY_ASSISTANT_RUNTIME_ID = "assistant_runtime_id"
        private const val KEY_ASSISTANT_WORKSPACE_PATH = "assistant_workspace_path"
        private const val KEY_ASSISTANT_PROVIDER_ID = "assistant_provider_id"
        private const val KEY_ASSISTANT_MODEL_ID = "assistant_model_id"
        private const val KEY_SAF_WORKSPACE_URIS = "saf_workspace_uris"
        private const val KEY_PROJECT_PATHS = "project_paths"
        private const val KEY_HIDDEN_WORKSPACE_PATHS = "hidden_workspace_paths"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_UNREAD_SESSIONS = "unread_sessions"
        private const val KEY_GITHUB_TOKEN = "github_token"
        private const val KEY_GITHUB_LOGIN = "github_login"
        private const val KEY_GITHUB_STAR_PROMPT_SHOWN = "github_star_prompt_shown"
        private const val KEY_GITHUB_STAR_PROMPT_DEFERRED = "github_star_prompt_deferred"
        private const val KEY_GITHUB_STAR_SECOND_PROMPT_SHOWN = "github_star_second_prompt_shown"
        private const val KEY_GITHUB_STAR_THANK_YOU_SHOWN = "github_star_thank_you_shown"
        private const val KEY_GITHUB_STARRED_CACHE = "github_starred_cache"
        private const val KEY_GITHUB_STAR_STATUS_CHECKED_AT = "github_star_status_checked_at"
        private const val KEY_GITHUB_STAR_COUNT_CACHE = "github_star_count_cache"
        private const val KEY_GITHUB_STAR_COUNT_CHECKED_AT = "github_star_count_checked_at"
        private const val KEY_THEME = "theme"
        private const val KEY_UI_FONT_SIZE = "ui_font_size"
        private const val KEY_CODE_FONT_SIZE = "code_font_size"
        private const val KEY_SYNTAX_THEME = "syntax_theme"
        private const val KEY_TOOL_CALL_DETAIL_LEVEL = "tool_call_detail_level"
        private const val KEY_AUTO_EXPAND_REASONING = "auto_expand_reasoning"
        private const val KEY_SEND_BEHAVIOR = "send_behavior"
        private const val KEY_ENTER_TO_SEND = "enter_to_send"
        private const val KEY_SIDEBAR_GROUPING = "sidebar_grouping"
        private const val KEY_WORKSPACE_TITLE_SOURCE = "workspace_title_source"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_LIVE_TRANSCRIPT_ENABLED = "live_transcript_enabled"
        private const val KEY_ANALYTICS_ENABLED = "analytics_enabled"
        private const val KEY_COLLAPSED_SIDEBAR_SECTIONS = "collapsed_sidebar_sections"
    }
}
