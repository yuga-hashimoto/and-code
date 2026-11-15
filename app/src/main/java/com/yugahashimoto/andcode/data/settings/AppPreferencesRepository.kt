package com.yugahashimoto.andcode.data.settings

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.core.api.defaultAgentName
import com.yugahashimoto.andcode.core.api.selectablePrimaryAgents
import com.yugahashimoto.andcode.data.connection.SecureSettingsRepository
import com.yugahashimoto.andcode.feature.assistant.TtsTuning
import com.yugahashimoto.andcode.feature.wakeword.WakeWordGrammar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AppPreferences(
    val providerId: String? = null,
    val modelId: String? = null,
    val agentId: String? = null,
    val ttsEnabled: Boolean = true,
    val ttsProvider: String = "android",
    val ttsAndroidEngine: String? = null,
    val ttsSpeechRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val ttsOpenAiApiKey: String = "",
    val ttsOpenAiVoice: String = "alloy",
    val ttsOpenAiModel: String = "gpt-4o-mini-tts",
    val ttsElevenLabsApiKey: String = "",
    val ttsElevenLabsVoiceId: String = "",
    val ttsElevenLabsModel: String = "eleven_multilingual_v2",
    val ttsBargeInEnabled: Boolean = true,
    val continuousConversation: Boolean = false,
    val wakeWordEnabled: Boolean = false,
    val wakeWordPhrase: String = WakeWordGrammar.DEFAULT_PHRASE,
    val wakeWordSensitivity: Float = 0.7f,
    val wakeWordModelLanguage: String? = null,
    val autoAcceptPermissions: Boolean = false,
    val favoriteModelKeys: Set<String> = emptySet(),
    val recentModelKeys: List<String> = emptyList(),
    val hiddenModelKeys: Set<String> = emptySet(),
    val theme: String = "dark",
    val uiFontSize: Int = 16,
    val codeFontSize: Int = 12,
    val syntaxTheme: String = "one-dark",
    val toolCallDetailLevel: String = "detailed",
    val autoExpandReasoning: Boolean = false,
    /** Codex fast mode: null follows each model's default, otherwise the user's explicit choice. */
    val codexFastMode: Boolean? = null,
    val sendBehavior: String = "interrupt",
    val enterToSend: Boolean = false,
    val sidebarGrouping: String = "project",
    val workspaceTitleSource: String = "title",
    val language: String = "system",
    val liveTranscriptEnabled: Boolean = false,
    val analyticsEnabled: Boolean = false,
    val guestBrowserAgentControlEnabled: Boolean = false,
    val localRuntimeIdleStopEnabled: Boolean = true,
    val autoArchiveStaleEnabled: Boolean = false,
    val autoArchiveStaleDays: Int = 30,
    val autoArchiveMaxSessionsEnabled: Boolean = false,
    val autoArchiveMaxSessions: Int = 50,
)

class AppPreferencesRepository(
    private val settings: SecureSettingsRepository,
) {
    private val mutableState =
        MutableStateFlow(
            AppPreferences(
                providerId = settings.selectedProviderId,
                modelId = settings.selectedModelId,
                agentId = settings.selectedAgentId,
                ttsEnabled = settings.ttsEnabled,
                ttsProvider = settings.ttsProvider,
                ttsAndroidEngine = settings.ttsAndroidEngine,
                ttsSpeechRate = settings.ttsSpeechRate,
                ttsPitch = settings.ttsPitch,
                ttsOpenAiApiKey = settings.ttsOpenAiApiKey,
                ttsOpenAiVoice = settings.ttsOpenAiVoice,
                ttsOpenAiModel = settings.ttsOpenAiModel,
                ttsElevenLabsApiKey = settings.ttsElevenLabsApiKey,
                ttsElevenLabsVoiceId = settings.ttsElevenLabsVoiceId,
                ttsElevenLabsModel = settings.ttsElevenLabsModel,
                ttsBargeInEnabled = settings.ttsBargeInEnabled,
                continuousConversation = settings.continuousConversation,
                wakeWordEnabled = settings.wakeWordEnabled,
                wakeWordPhrase = settings.wakeWordPhrase,
                wakeWordSensitivity = settings.wakeWordSensitivity,
                wakeWordModelLanguage = settings.wakeWordModelLanguage,
                autoAcceptPermissions = settings.autoAcceptPermissions,
                favoriteModelKeys = settings.favoriteModelKeys,
                recentModelKeys = settings.recentModelKeys,
                hiddenModelKeys = settings.hiddenModelKeys,
                theme = settings.theme,
                uiFontSize = settings.uiFontSize,
                codeFontSize = settings.codeFontSize,
                syntaxTheme = settings.syntaxTheme,
                toolCallDetailLevel = settings.toolCallDetailLevel,
                autoExpandReasoning = settings.autoExpandReasoning,
                codexFastMode = settings.codexFastMode,
                sendBehavior = settings.sendBehavior,
                enterToSend = settings.enterToSend,
                sidebarGrouping = settings.sidebarGrouping,
                workspaceTitleSource = settings.workspaceTitleSource,
                language = settings.language,
                liveTranscriptEnabled = settings.liveTranscriptEnabled,
                analyticsEnabled = settings.analyticsEnabled,
                guestBrowserAgentControlEnabled = settings.guestBrowserAgentControlEnabled,
                localRuntimeIdleStopEnabled = settings.localRuntimeIdleStopEnabled,
                autoArchiveStaleEnabled = settings.autoArchiveStaleEnabled,
                autoArchiveStaleDays = settings.autoArchiveStaleDays,
                autoArchiveMaxSessionsEnabled = settings.autoArchiveMaxSessionsEnabled,
                autoArchiveMaxSessions = settings.autoArchiveMaxSessions,
            ),
        )
    val state: StateFlow<AppPreferences> = mutableState.asStateFlow()

    fun selectModel(
        providerId: String?,
        modelId: String?,
    ) {
        settings.selectedProviderId = providerId
        settings.selectedModelId = modelId
        mutableState.update { it.copy(providerId = providerId, modelId = modelId) }
        if (providerId != null && modelId != null) {
            val key = "$providerId/$modelId"
            val updated = (listOf(key) + settings.recentModelKeys.filterNot { it == key }).take(3)
            settings.recentModelKeys = updated
            mutableState.update { it.copy(recentModelKeys = updated) }
        }
    }

    fun selectAgent(agentId: String?) {
        settings.selectedAgentId = agentId
        mutableState.update { it.copy(agentId = agentId) }
    }

    fun reconcile(
        catalog: ProviderCatalog,
        agents: List<OpenCodeAgent>,
    ) {
        val current = mutableState.value
        val reconciled =
            reconcileModelSelection(
                currentProviderId = current.providerId,
                currentModelId = current.modelId,
                catalog = catalog,
                recentModelKeys = settings.recentModelKeys,
            )
        if (reconciled != null &&
            (reconciled.providerId != current.providerId || reconciled.modelId != current.modelId)
        ) {
            selectModel(reconciled.providerId, reconciled.modelId)
        }
        val primaryAgents = agents.selectablePrimaryAgents()
        val agentId =
            current.agentId?.takeIf { selected -> primaryAgents.any { it.name == selected } }
                ?: agents.defaultAgentName()
        if (agentId != current.agentId) {
            selectAgent(agentId)
        }
    }

    fun setTtsEnabled(enabled: Boolean) {
        settings.ttsEnabled = enabled
        mutableState.update { it.copy(ttsEnabled = enabled) }
    }

    fun setTtsProvider(provider: String) {
        settings.ttsProvider = provider
        mutableState.update { it.copy(ttsProvider = provider) }
    }

    fun setTtsAndroidEngine(engine: String?) {
        settings.ttsAndroidEngine = engine
        mutableState.update { it.copy(ttsAndroidEngine = engine) }
    }

    // Clamped on the way in as well as on the way out: a value the sliders cannot produce would
    // otherwise sit in storage until the voice session tries to build a config from it and throws.
    fun setTtsSpeechRate(rate: Float) {
        val clamped = TtsTuning.rate(rate)
        settings.ttsSpeechRate = clamped
        mutableState.update { it.copy(ttsSpeechRate = clamped) }
    }

    fun setTtsPitch(pitch: Float) {
        val clamped = TtsTuning.pitch(pitch)
        settings.ttsPitch = clamped
        mutableState.update { it.copy(ttsPitch = clamped) }
    }

    fun setTtsOpenAiApiKey(apiKey: String) {
        settings.ttsOpenAiApiKey = apiKey
        mutableState.update { it.copy(ttsOpenAiApiKey = apiKey) }
    }

    fun setTtsOpenAiVoice(voice: String) {
        settings.ttsOpenAiVoice = voice
        mutableState.update { it.copy(ttsOpenAiVoice = voice) }
    }

    fun setTtsOpenAiModel(model: String) {
        settings.ttsOpenAiModel = model
        mutableState.update { it.copy(ttsOpenAiModel = model) }
    }

    fun setTtsElevenLabsApiKey(apiKey: String) {
        settings.ttsElevenLabsApiKey = apiKey
        mutableState.update { it.copy(ttsElevenLabsApiKey = apiKey) }
    }

    fun setTtsElevenLabsVoiceId(voiceId: String) {
        settings.ttsElevenLabsVoiceId = voiceId
        mutableState.update { it.copy(ttsElevenLabsVoiceId = voiceId) }
    }

    fun setTtsElevenLabsModel(model: String) {
        settings.ttsElevenLabsModel = model
        mutableState.update { it.copy(ttsElevenLabsModel = model) }
    }

    fun setTtsBargeInEnabled(enabled: Boolean) {
        settings.ttsBargeInEnabled = enabled
        mutableState.update { it.copy(ttsBargeInEnabled = enabled) }
    }

    fun setContinuousConversation(enabled: Boolean) {
        settings.continuousConversation = enabled
        mutableState.update { it.copy(continuousConversation = enabled) }
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        settings.wakeWordEnabled = enabled
        mutableState.update { it.copy(wakeWordEnabled = enabled) }
    }

    fun setWakeWordPhrase(phrase: String) {
        settings.wakeWordPhrase = phrase
        mutableState.update { it.copy(wakeWordPhrase = phrase) }
    }

    fun setWakeWordSensitivity(sensitivity: Float) {
        val clamped = sensitivity.coerceIn(0f, 1f)
        settings.wakeWordSensitivity = clamped
        mutableState.update { it.copy(wakeWordSensitivity = clamped) }
    }

    fun setWakeWordModelLanguage(language: String?) {
        settings.wakeWordModelLanguage = language
        mutableState.update { it.copy(wakeWordModelLanguage = language) }
    }

    fun setAutoAcceptPermissions(enabled: Boolean) {
        settings.autoAcceptPermissions = enabled
        mutableState.update { it.copy(autoAcceptPermissions = enabled) }
    }

    fun toggleFavoriteModel(
        providerId: String,
        modelId: String,
    ) {
        val key = "$providerId/$modelId"
        val current = mutableState.value.favoriteModelKeys
        val updated = if (key in current) current - key else current + key
        settings.favoriteModelKeys = updated
        mutableState.update { it.copy(favoriteModelKeys = updated) }
    }

    fun toggleModelVisibility(
        providerId: String,
        modelId: String,
    ) {
        val key = "$providerId/$modelId"
        val current = mutableState.value.hiddenModelKeys
        val updated = if (key in current) current - key else current + key
        settings.hiddenModelKeys = updated
        mutableState.update { it.copy(hiddenModelKeys = updated) }
    }

    fun setTheme(theme: String) {
        settings.theme = theme
        mutableState.update { it.copy(theme = theme) }
    }

    fun setUiFontSize(size: Int) {
        settings.uiFontSize = size
        mutableState.update { it.copy(uiFontSize = size) }
    }

    fun setCodeFontSize(size: Int) {
        settings.codeFontSize = size
        mutableState.update { it.copy(codeFontSize = size) }
    }

    fun setSyntaxTheme(theme: String) {
        settings.syntaxTheme = theme
        mutableState.update { it.copy(syntaxTheme = theme) }
    }

    fun setToolCallDetailLevel(level: String) {
        settings.toolCallDetailLevel = level
        mutableState.update { it.copy(toolCallDetailLevel = level) }
    }

    fun setAutoExpandReasoning(enabled: Boolean) {
        settings.autoExpandReasoning = enabled
        mutableState.update { it.copy(autoExpandReasoning = enabled) }
    }

    fun setCodexFastMode(enabled: Boolean?) {
        settings.codexFastMode = enabled
        mutableState.update { it.copy(codexFastMode = enabled) }
    }

    fun setSendBehavior(behavior: String) {
        settings.sendBehavior = behavior
        mutableState.update { it.copy(sendBehavior = behavior) }
    }

    fun setEnterToSend(enabled: Boolean) {
        settings.enterToSend = enabled
        mutableState.update { it.copy(enterToSend = enabled) }
    }

    fun setSidebarGrouping(grouping: String) {
        settings.sidebarGrouping = grouping
        mutableState.update { it.copy(sidebarGrouping = grouping) }
    }

    fun setWorkspaceTitleSource(source: String) {
        settings.workspaceTitleSource = source
        mutableState.update { it.copy(workspaceTitleSource = source) }
    }

    fun setLanguage(language: String) {
        settings.language = language
        mutableState.update { it.copy(language = language) }
    }

    fun setLiveTranscriptEnabled(enabled: Boolean) {
        settings.liveTranscriptEnabled = enabled
        mutableState.update { it.copy(liveTranscriptEnabled = enabled) }
    }

    fun setAnalyticsEnabled(enabled: Boolean) {
        settings.analyticsEnabled = enabled
        com.yugahashimoto.andcode.core.diagnostics.AnalyticsReporter.setEnabled(enabled)
        com.yugahashimoto.andcode.core.diagnostics.CrashReporter.setEnabled(enabled)
        mutableState.update { it.copy(analyticsEnabled = enabled) }
    }

    fun setGuestBrowserAgentControlEnabled(enabled: Boolean) {
        settings.guestBrowserAgentControlEnabled = enabled
        mutableState.update { it.copy(guestBrowserAgentControlEnabled = enabled) }
    }

    fun setLocalRuntimeIdleStopEnabled(enabled: Boolean) {
        settings.localRuntimeIdleStopEnabled = enabled
        mutableState.update { it.copy(localRuntimeIdleStopEnabled = enabled) }
    }

    fun setAutoArchiveStaleEnabled(enabled: Boolean) {
        settings.autoArchiveStaleEnabled = enabled
        mutableState.update { it.copy(autoArchiveStaleEnabled = enabled) }
    }

    fun setAutoArchiveStaleDays(days: Int) {
        // Matches the slider range in SettingsScreenV2's dialog - keep the two in sync.
        val clamped = days.coerceIn(1, 180)
        settings.autoArchiveStaleDays = clamped
        mutableState.update { it.copy(autoArchiveStaleDays = clamped) }
    }

    fun setAutoArchiveMaxSessionsEnabled(enabled: Boolean) {
        settings.autoArchiveMaxSessionsEnabled = enabled
        mutableState.update { it.copy(autoArchiveMaxSessionsEnabled = enabled) }
    }

    fun setAutoArchiveMaxSessions(count: Int) {
        // Matches the slider range in SettingsScreenV2's dialog - keep the two in sync.
        val clamped = count.coerceIn(5, 500)
        settings.autoArchiveMaxSessions = clamped
        mutableState.update { it.copy(autoArchiveMaxSessions = clamped) }
    }
}

internal data class ReconciledModelSelection(
    val providerId: String?,
    val modelId: String?,
)

internal fun reconcileModelSelection(
    currentProviderId: String?,
    currentModelId: String?,
    catalog: ProviderCatalog,
    recentModelKeys: List<String> = emptyList(),
): ReconciledModelSelection? {
    val connected = catalog.connected.toSet()
    val providers = catalog.all.filter { it.id in connected }
    if (providers.isEmpty()) return null

    fun modelsOf(providerId: String?) = providers.firstOrNull { it.id == providerId }?.models.orEmpty()

    if (currentProviderId != null && currentProviderId in connected) {
        val models = modelsOf(currentProviderId)
        val modelId =
            currentModelId?.takeIf { it in models }
                ?: catalog.default[currentProviderId]?.takeIf { it in models }
                ?: models.values.firstOrNull { it.status == null || it.status == "active" }?.id
        return ReconciledModelSelection(currentProviderId, modelId)
    }

    // A restart often reports only the default provider as connected before NVIDIA / Copilot /
    // DeepSeek credentials are applied. Overwriting the saved pick with that default is what made
    // configuration appear to reset after every restart.
    if (!currentProviderId.isNullOrBlank() && !currentModelId.isNullOrBlank()) {
        return null
    }

    val recent =
        recentModelKeys.asSequence()
            .mapNotNull { key -> key.substringBefore('/').takeIf { it in connected }?.to(key.substringAfter('/')) }
            .firstOrNull { (provider, model) -> model in modelsOf(provider) }
    val providerId =
        recent?.first
            ?: "opencode".takeIf { it in connected }
            ?: providers.firstOrNull()?.id
    val models = modelsOf(providerId)
    val modelId =
        recent?.second?.takeIf { providerId == recent.first && it in models }
            ?: providerId?.let { catalog.default[it] }?.takeIf { it in models }
            ?: models.values.firstOrNull { it.status == null || it.status == "active" }?.id
    return ReconciledModelSelection(providerId, modelId)
}
