package com.yugahashimoto.andcode.data.settings

import com.yugahashimoto.andcode.core.locale.AppLanguage
import org.json.JSONArray
import org.json.JSONObject

enum class AgentSettingType(val wire: String) {
    BOOLEAN("boolean"),
    INTEGER("integer"),
    NUMBER("number"),
    STRING("string"),
    ENUM("enum"),
    STRING_LIST("string_list"),
}

/**
 * One app setting an agent may inspect or change through the `and-code-settings` MCP server.
 *
 * [read] pulls the live value out of [AppPreferences]; writing goes through
 * [AgentSettingsStore] so the descriptor stays free of Android types. A non-null [protectedReason]
 * makes the setting read-only for agents: it either widens what an agent may do without asking
 * (permission auto-approval, browser control, telemetry) or needs an on-device flow (microphone
 * permission) that a plain preference write would skip.
 */
data class AgentSettingDescriptor(
    val key: String,
    val category: String,
    val description: String,
    val type: AgentSettingType,
    val read: (AppPreferences) -> Any?,
    val nullable: Boolean = false,
    val options: List<String> = emptyList(),
    val min: Double? = null,
    val max: Double? = null,
    val secret: Boolean = false,
    val protectedReason: String? = null,
    val validate: ((String) -> String?)? = null,
) {
    val writable: Boolean get() = protectedReason == null
}

private fun modelKey(value: String): String? {
    val provider = value.substringBefore('/', "")
    val model = value.substringAfter('/', "")
    return if (provider.isBlank() || model.isBlank()) "must look like \"providerId/modelId\"" else null
}

object AgentSettingsCatalog {
    private const val PROTECTED_SECURITY =
        "Security-sensitive: only the user can change this, in the AndCode app (Settings)."

    val descriptors: List<AgentSettingDescriptor> =
        listOf(
            AgentSettingDescriptor(
                key = "selectedModel",
                category = "model",
                description =
                    "Model used for new chats, as \"providerId/modelId\" (e.g. \"anthropic/claude-sonnet-4\"). " +
                        "null clears the pick.",
                type = AgentSettingType.STRING,
                nullable = true,
                read = { prefs ->
                    if (prefs.providerId != null && prefs.modelId != null) "${prefs.providerId}/${prefs.modelId}" else null
                },
                validate = ::modelKey,
            ),
            AgentSettingDescriptor(
                key = "selectedAgent",
                category = "model",
                description = "OpenCode primary agent used for new chats (build, plan, ...). null follows the default.",
                type = AgentSettingType.STRING,
                nullable = true,
                read = { it.agentId },
            ),
            AgentSettingDescriptor(
                key = "favoriteModelKeys",
                category = "model",
                description = "Favorite models, each \"providerId/modelId\". Shown first in the model picker.",
                type = AgentSettingType.STRING_LIST,
                read = { it.favoriteModelKeys.sorted() },
                validate = ::modelKey,
            ),
            AgentSettingDescriptor(
                key = "hiddenModelKeys",
                category = "model",
                description = "Models hidden from the model picker, each \"providerId/modelId\".",
                type = AgentSettingType.STRING_LIST,
                read = { it.hiddenModelKeys.sorted() },
                validate = ::modelKey,
            ),
            AgentSettingDescriptor(
                key = "theme",
                category = "appearance",
                description = "Color theme. \"dynamic\" (Material You) falls back to \"auto\" before Android 12.",
                type = AgentSettingType.ENUM,
                options = listOf("dark", "light", "zinc", "midnight", "claude", "ghostty", "auto", "dynamic"),
                read = { it.theme },
            ),
            AgentSettingDescriptor(
                key = "uiFontSize",
                category = "appearance",
                description = "UI font size in sp.",
                type = AgentSettingType.INTEGER,
                min = 12.0,
                max = 22.0,
                read = { it.uiFontSize },
            ),
            AgentSettingDescriptor(
                key = "codeFontSize",
                category = "appearance",
                description = "Code font size in sp.",
                type = AgentSettingType.INTEGER,
                min = 10.0,
                max = 20.0,
                read = { it.codeFontSize },
            ),
            AgentSettingDescriptor(
                key = "syntaxTheme",
                category = "appearance",
                description = "Syntax highlighting theme for code blocks and the code viewer.",
                type = AgentSettingType.ENUM,
                options = listOf("one-dark", "monokai", "github-dark", "solarized-dark"),
                read = { it.syntaxTheme },
            ),
            AgentSettingDescriptor(
                key = "language",
                category = "appearance",
                description = "App language. Takes effect the next time the app UI is recreated.",
                type = AgentSettingType.ENUM,
                options = AppLanguage.supported,
                read = { it.language },
            ),
            AgentSettingDescriptor(
                key = "toolCallDetailLevel",
                category = "chat",
                description = "How much of each tool call the chat shows.",
                type = AgentSettingType.ENUM,
                options = listOf("detailed", "overview"),
                read = { it.toolCallDetailLevel },
            ),
            AgentSettingDescriptor(
                key = "autoExpandReasoning",
                category = "chat",
                description = "Expand reasoning blocks automatically.",
                type = AgentSettingType.BOOLEAN,
                read = { it.autoExpandReasoning },
            ),
            AgentSettingDescriptor(
                key = "sendBehavior",
                category = "chat",
                description = "What sending a message does while the agent is still running: interrupt it, or queue the message.",
                type = AgentSettingType.ENUM,
                options = listOf("interrupt", "queue"),
                read = { it.sendBehavior },
            ),
            AgentSettingDescriptor(
                key = "enterToSend",
                category = "chat",
                description = "Enter sends the message (hardware keyboards).",
                type = AgentSettingType.BOOLEAN,
                read = { it.enterToSend },
            ),
            AgentSettingDescriptor(
                key = "codexFastMode",
                category = "chat",
                description = "Codex fast mode. null follows each model's default.",
                type = AgentSettingType.BOOLEAN,
                nullable = true,
                read = { it.codexFastMode },
            ),
            AgentSettingDescriptor(
                key = "pinnedSessionIds",
                category = "sessions",
                description = "Pinned chat session ids.",
                type = AgentSettingType.STRING_LIST,
                read = { it.pinnedSessionIds.sorted() },
            ),
            AgentSettingDescriptor(
                key = "autoArchiveStaleEnabled",
                category = "sessions",
                description = "Archive chats that have been idle for autoArchiveStaleDays.",
                type = AgentSettingType.BOOLEAN,
                read = { it.autoArchiveStaleEnabled },
            ),
            AgentSettingDescriptor(
                key = "autoArchiveStaleDays",
                category = "sessions",
                description = "Idle days before a chat is auto-archived.",
                type = AgentSettingType.INTEGER,
                min = 1.0,
                max = 180.0,
                read = { it.autoArchiveStaleDays },
            ),
            AgentSettingDescriptor(
                key = "autoArchiveMaxSessionsEnabled",
                category = "sessions",
                description = "Archive the oldest chats beyond autoArchiveMaxSessions.",
                type = AgentSettingType.BOOLEAN,
                read = { it.autoArchiveMaxSessionsEnabled },
            ),
            AgentSettingDescriptor(
                key = "autoArchiveMaxSessions",
                category = "sessions",
                description = "How many chats to keep before the oldest are auto-archived.",
                type = AgentSettingType.INTEGER,
                min = 5.0,
                max = 500.0,
                read = { it.autoArchiveMaxSessions },
            ),
            AgentSettingDescriptor(
                key = "localRuntimeIdleStopEnabled",
                category = "runtime",
                description = "Stop the on-device runtime after it has been idle in the background (saves battery).",
                type = AgentSettingType.BOOLEAN,
                read = { it.localRuntimeIdleStopEnabled },
            ),
            AgentSettingDescriptor(
                key = "ttsEnabled",
                category = "voice",
                description = "Read agent replies aloud.",
                type = AgentSettingType.BOOLEAN,
                read = { it.ttsEnabled },
            ),
            AgentSettingDescriptor(
                key = "ttsProvider",
                category = "voice",
                description = "Text-to-speech engine.",
                type = AgentSettingType.ENUM,
                options = listOf("android", "openai", "elevenlabs"),
                read = { it.ttsProvider },
            ),
            AgentSettingDescriptor(
                key = "ttsAndroidEngine",
                category = "voice",
                description = "Package name of the Android TTS engine. null uses the system default.",
                type = AgentSettingType.STRING,
                nullable = true,
                read = { it.ttsAndroidEngine },
            ),
            AgentSettingDescriptor(
                key = "ttsSpeechRate",
                category = "voice",
                description = "Speech rate multiplier.",
                type = AgentSettingType.NUMBER,
                min = 0.5,
                max = 2.0,
                read = { it.ttsSpeechRate },
            ),
            AgentSettingDescriptor(
                key = "ttsPitch",
                category = "voice",
                description = "Speech pitch multiplier.",
                type = AgentSettingType.NUMBER,
                min = 0.5,
                max = 2.0,
                read = { it.ttsPitch },
            ),
            AgentSettingDescriptor(
                key = "ttsOpenAiApiKey",
                category = "voice",
                description = "OpenAI API key for text-to-speech. Write-only: reads only report whether a key is set.",
                type = AgentSettingType.STRING,
                secret = true,
                read = { it.ttsOpenAiApiKey },
            ),
            AgentSettingDescriptor(
                key = "ttsOpenAiVoice",
                category = "voice",
                description = "OpenAI TTS voice name.",
                type = AgentSettingType.STRING,
                read = { it.ttsOpenAiVoice },
            ),
            AgentSettingDescriptor(
                key = "ttsOpenAiModel",
                category = "voice",
                description = "OpenAI TTS model id.",
                type = AgentSettingType.STRING,
                read = { it.ttsOpenAiModel },
            ),
            AgentSettingDescriptor(
                key = "ttsElevenLabsApiKey",
                category = "voice",
                description = "ElevenLabs API key. Write-only: reads only report whether a key is set.",
                type = AgentSettingType.STRING,
                secret = true,
                read = { it.ttsElevenLabsApiKey },
            ),
            AgentSettingDescriptor(
                key = "ttsElevenLabsVoiceId",
                category = "voice",
                description = "ElevenLabs voice id.",
                type = AgentSettingType.STRING,
                read = { it.ttsElevenLabsVoiceId },
            ),
            AgentSettingDescriptor(
                key = "ttsElevenLabsModel",
                category = "voice",
                description = "ElevenLabs model id.",
                type = AgentSettingType.STRING,
                read = { it.ttsElevenLabsModel },
            ),
            AgentSettingDescriptor(
                key = "ttsBargeInEnabled",
                category = "voice",
                description = "Let the user interrupt speech by talking.",
                type = AgentSettingType.BOOLEAN,
                read = { it.ttsBargeInEnabled },
            ),
            AgentSettingDescriptor(
                key = "continuousConversation",
                category = "voice",
                description = "Keep listening after each spoken reply.",
                type = AgentSettingType.BOOLEAN,
                read = { it.continuousConversation },
            ),
            AgentSettingDescriptor(
                key = "liveTranscriptEnabled",
                category = "voice",
                description = "Show a live transcript overlay while dictating.",
                type = AgentSettingType.BOOLEAN,
                read = { it.liveTranscriptEnabled },
            ),
            AgentSettingDescriptor(
                key = "wakeWordEnabled",
                category = "voice",
                description = "Always-on wake word listening.",
                type = AgentSettingType.BOOLEAN,
                read = { it.wakeWordEnabled },
                protectedReason = "Needs the microphone permission and service start: turn it on in the AndCode app (Settings > Voice).",
            ),
            AgentSettingDescriptor(
                key = "wakeWordPhrase",
                category = "voice",
                description = "Wake phrase.",
                type = AgentSettingType.STRING,
                read = { it.wakeWordPhrase },
            ),
            AgentSettingDescriptor(
                key = "wakeWordSensitivity",
                category = "voice",
                description = "Wake word sensitivity, 0 (strict) to 1 (loose).",
                type = AgentSettingType.NUMBER,
                min = 0.0,
                max = 1.0,
                read = { it.wakeWordSensitivity },
            ),
            AgentSettingDescriptor(
                key = "wakeWordModelLanguage",
                category = "voice",
                description = "Language of the offline wake word model. null follows the app language.",
                type = AgentSettingType.STRING,
                nullable = true,
                read = { it.wakeWordModelLanguage },
            ),
            AgentSettingDescriptor(
                key = "autoAcceptPermissions",
                category = "security",
                description = "Auto-approve every tool permission request from agents.",
                type = AgentSettingType.BOOLEAN,
                read = { it.autoAcceptPermissions },
                protectedReason = PROTECTED_SECURITY,
            ),
            AgentSettingDescriptor(
                key = "guestBrowserAgentControlEnabled",
                category = "security",
                description = "Let agents drive the Guest Browser (and preview public https pages).",
                type = AgentSettingType.BOOLEAN,
                read = { it.guestBrowserAgentControlEnabled },
                protectedReason = PROTECTED_SECURITY,
            ),
            AgentSettingDescriptor(
                key = "analyticsEnabled",
                category = "security",
                description = "Send anonymous usage analytics and crash reports.",
                type = AgentSettingType.BOOLEAN,
                read = { it.analyticsEnabled },
                protectedReason = PROTECTED_SECURITY,
            ),
        )

    private val byKey: Map<String, AgentSettingDescriptor> = descriptors.associateBy { it.key }

    val categories: List<String> = descriptors.map { it.category }.distinct()

    fun find(key: String): AgentSettingDescriptor =
        byKey[key] ?: throw IllegalArgumentException("unknown setting: $key (call settings_list for the available keys)")

    fun defaultOf(descriptor: AgentSettingDescriptor): Any? = normalize(descriptor.read(AppPreferences()))

    /**
     * Turns a JSON value from the agent into the Kotlin value the store expects (Boolean, Int,
     * Float, String, or a distinct List<String>), or throws [IllegalArgumentException] saying
     * exactly what is wrong. Does not check [AgentSettingDescriptor.protectedReason].
     */
    fun coerce(
        descriptor: AgentSettingDescriptor,
        raw: Any?,
    ): Any? {
        if (raw == null || raw == JSONObject.NULL) {
            require(descriptor.nullable) { "${descriptor.key} cannot be null (settings_reset restores the default)" }
            return null
        }
        return when (descriptor.type) {
            AgentSettingType.BOOLEAN -> coerceBoolean(descriptor, raw)
            AgentSettingType.INTEGER -> coerceInteger(descriptor, raw)
            AgentSettingType.NUMBER -> coerceNumber(descriptor, raw)
            AgentSettingType.ENUM -> coerceEnum(descriptor, raw)
            AgentSettingType.STRING -> coerceString(descriptor, raw)
            AgentSettingType.STRING_LIST -> coerceStringList(descriptor, raw)
        }
    }

    private fun coerceBoolean(
        descriptor: AgentSettingDescriptor,
        raw: Any,
    ): Boolean =
        when {
            raw is Boolean -> raw
            raw is String && raw.equals("true", ignoreCase = true) -> true
            raw is String && raw.equals("false", ignoreCase = true) -> false
            else -> throw IllegalArgumentException("${descriptor.key} must be true or false")
        }

    private fun coerceInteger(
        descriptor: AgentSettingDescriptor,
        raw: Any,
    ): Int {
        val number = numberOf(descriptor, raw)
        require(number == Math.rint(number)) { "${descriptor.key} must be a whole number" }
        inRange(descriptor, number)
        return number.toInt()
    }

    private fun coerceNumber(
        descriptor: AgentSettingDescriptor,
        raw: Any,
    ): Float {
        val number = numberOf(descriptor, raw)
        inRange(descriptor, number)
        return number.toFloat()
    }

    private fun coerceEnum(
        descriptor: AgentSettingDescriptor,
        raw: Any,
    ): String {
        val value = (raw as? String)?.trim().orEmpty()
        require(value in descriptor.options) {
            "${descriptor.key} must be one of: ${descriptor.options.joinToString(", ")}"
        }
        return value
    }

    private fun coerceString(
        descriptor: AgentSettingDescriptor,
        raw: Any,
    ): String? {
        require(raw is String) { "${descriptor.key} must be a string" }
        val value = raw.trim()
        if (value.isEmpty() && descriptor.nullable) return null
        if (value.isNotEmpty()) checkItem(descriptor, value)
        return value
    }

    private fun coerceStringList(
        descriptor: AgentSettingDescriptor,
        raw: Any,
    ): List<String> {
        val items =
            when (raw) {
                is JSONArray -> (0 until raw.length()).map { raw.opt(it) }
                is Collection<*> -> raw.toList()
                else -> throw IllegalArgumentException("${descriptor.key} must be an array of strings")
            }
        return items
            .map { item ->
                val text = (item as? String)?.trim()
                require(!text.isNullOrEmpty()) { "${descriptor.key} items must be non-empty strings" }
                checkItem(descriptor, text)
                text
            }.distinct()
    }

    private fun numberOf(
        descriptor: AgentSettingDescriptor,
        raw: Any,
    ): Double =
        when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull()
            else -> null
        }?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("${descriptor.key} must be a number")

    private fun inRange(
        descriptor: AgentSettingDescriptor,
        value: Double,
    ) {
        val min = descriptor.min
        val max = descriptor.max
        val belowMin = min != null && value < min
        val aboveMax = max != null && value > max
        require(!belowMin && !aboveMax) {
            "${descriptor.key} must be between ${format(min)} and ${format(max)}"
        }
    }

    private fun checkItem(
        descriptor: AgentSettingDescriptor,
        value: String,
    ) {
        descriptor.validate?.invoke(value)?.let { problem ->
            throw IllegalArgumentException("${descriptor.key}: \"$value\" $problem")
        }
    }

    /** JSON shape for one setting, including its current value (secrets are redacted). */
    fun toJson(
        descriptor: AgentSettingDescriptor,
        prefs: AppPreferences,
    ): JSONObject {
        val json =
            JSONObject()
                .put("key", descriptor.key)
                .put("category", descriptor.category)
                .put("description", descriptor.description)
                .put("type", descriptor.type.wire)
                .put("nullable", descriptor.nullable)
                .put("writable", descriptor.writable)
        if (descriptor.options.isNotEmpty()) json.put("options", JSONArray(descriptor.options))
        descriptor.min?.let { json.put("min", it) }
        descriptor.max?.let { json.put("max", it) }
        descriptor.protectedReason?.let { json.put("protectedReason", it) }
        if (descriptor.secret) {
            val isSet = (descriptor.read(prefs) as? String).orEmpty().isNotEmpty()
            json.put("secret", true).put("isSet", isSet).put("value", JSONObject.NULL)
        } else {
            json.put("value", jsonValue(normalize(descriptor.read(prefs))))
            json.put("default", jsonValue(defaultOf(descriptor)))
        }
        return json
    }

    fun jsonValue(value: Any?): Any =
        when (value) {
            null -> JSONObject.NULL
            is Float -> value.toString().toDouble()
            is Collection<*> -> JSONArray(value.toList())
            else -> value
        }

    private fun normalize(value: Any?): Any? = if (value is Set<*>) value.map { it.toString() }.sorted() else value

    private fun format(value: Double?): String =
        when {
            value == null -> "-inf/inf"
            value == Math.rint(value) -> value.toLong().toString()
            else -> value.toString()
        }
}
