package com.yugahashimoto.andcode.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeProvider
import com.yugahashimoto.andcode.core.api.ProviderAuthMethod
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.core.security.SecretRedaction
import com.yugahashimoto.andcode.data.connection.SecureSettingsRepository
import com.yugahashimoto.andcode.data.repository.RuntimeCatalogRepository
import com.yugahashimoto.andcode.data.repository.RuntimeCatalogState
import com.yugahashimoto.andcode.data.settings.AppPreferences
import com.yugahashimoto.andcode.data.settings.AppPreferencesRepository
import com.yugahashimoto.andcode.feature.assistant.TtsSettings
import com.yugahashimoto.andcode.feature.assistant.ttsSettings
import com.yugahashimoto.andcode.feature.wakeword.VoskModelCatalog
import com.yugahashimoto.andcode.feature.wakeword.VoskModelLanguage
import com.yugahashimoto.andcode.feature.wakeword.VoskModelState
import com.yugahashimoto.andcode.feature.wakeword.VoskModelStore
import com.yugahashimoto.andcode.feature.wakeword.VoskVocabulary
import com.yugahashimoto.andcode.feature.wakeword.WakeWordGrammar
import com.yugahashimoto.andcode.runtime.BackendKind
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.RuntimeRegistry
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.runtime.local.LocalProviderCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class SettingsUiState(
    val providers: List<OpenCodeProvider> = emptyList(),
    val availableProviders: List<OpenCodeProvider> = emptyList(),
    val connectedProviderIds: Set<String> = emptySet(),
    val agents: List<OpenCodeAgent> = emptyList(),
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
    val wakeWordModelLanguage: VoskModelLanguage = VoskModelLanguage.ENGLISH,
    val wakeWordModelStates: Map<VoskModelLanguage, VoskModelState> = emptyMap(),
    val autoAcceptPermissions: Boolean = false,
    val credentialStatuses: Map<String, Boolean> = emptyMap(),
    val draftProviderId: String = "",
    val draftApiKey: String = "",
    val credentialMessage: String? = null,
    val runtimeOptions: List<Pair<String, String>> = emptyList(),
    val assistantRuntimeId: String? = null,
    val assistantWorkspacePath: String? = null,
    val assistantProviderId: String? = null,
    val assistantModelId: String? = null,
    val openCodeVersion: String? = null,
    val providerAuthMethods: Map<String, List<ProviderAuthMethod>> = emptyMap(),
    val oauthMessage: String? = null,
    val providerAuthDialog: ProviderAuthDialogState? = null,
    val providerAuthNotice: ProviderAuthNotice? = null,
    val favoriteModelKeys: Set<String> = emptySet(),
    val recentModelKeys: List<String> = emptyList(),
    val hiddenModelKeys: Set<String> = emptySet(),
    val githubConfigured: Boolean = false,
    val githubLogin: String? = null,
    val githubMessage: String? = null,
    val githubUserCode: String? = null,
    val githubVerificationUrl: String? = null,
    val githubPolling: Boolean = false,
)

class SettingsViewModel(
    private val catalog: RuntimeCatalogRepository,
    private val preferences: AppPreferencesRepository,
    private val credentials: LocalProviderCredentialStore,
    private val settings: SecureSettingsRepository,
    private val registry: RuntimeRegistry,
    private val voskModels: VoskModelStore,
    private val providerDisconnectRejectedMessage: String = "Provider disconnect was not accepted",
) : ViewModel() {
    private val settingsTick = MutableStateFlow(0)
    private val oauthState = MutableStateFlow(OAuthState())

    /**
     * Providers of the runtime that owns them, which is not always the selected one.
     *
     * The catalogue behind [catalog] follows the chat's runtime, as the model picker needs. Provider
     * settings must not: with Claude Code selected the screen listed Claude Code as the only
     * provider and hid every real one, because Claude's catalogue is its own models.
     */
    private val providerCatalog = MutableStateFlow<ProviderCatalog?>(null)
    private val githubState = MutableStateFlow(GitHubState())
    private var providerAuthJob: Job? = null
    private val githubAuth =
        GitHubAuthRepository(
            settings = settings,
            clientId = com.yugahashimoto.andcode.BuildConfig.GITHUB_CLIENT_ID,
        )
    var onLocalRuntimeRestartNeeded: (() -> Unit)? = null
    var onProviderAuthCompleted: (() -> Unit)? = null

    private data class CoreState(
        val runtime: RuntimeCatalogState,
        val preferences: AppPreferences,
        val targets: List<RuntimeTarget>,
        val selected: RuntimeTarget?,
        val providerCatalog: ProviderCatalog?,
    )

    private data class OAuthState(
        val methods: Map<String, List<ProviderAuthMethod>> = emptyMap(),
        val dialog: ProviderAuthDialogState? = null,
        val notice: ProviderAuthNotice? = null,
        val message: String? = null,
        val locallyConnected: Set<String> = emptySet(),
    )

    private data class GitHubState(
        val deviceCode: GitHubDeviceCode? = null,
        val polling: Boolean = false,
        val message: String? = null,
    )

    init {
        viewModelScope.launch {
            registry.selected.collect {
                dismissProviderAuth()
                refreshProviderAuth()
            }
        }
    }

    val state: StateFlow<SettingsUiState> =
        combine(
            combine(
                catalog.state,
                preferences.state,
                registry.targets,
                registry.selected,
                providerCatalog,
            ) { runtime, prefs, targets, selected, providers ->
                CoreState(runtime, prefs, targets, selected, providers)
            },
            settingsTick,
            oauthState,
            githubState,
            voskModels.state,
        ) { core, _, oauth, github, voskModelStates ->
            // Two different questions, two different catalogues.
            //
            // `providers` answers "what can this chat talk to", so it follows the selected runtime.
            // `availableProviders` answers "whose credentials can I manage", which is always the
            // provider-owning runtime. Serving both from one catalogue put OpenCode's models in the
            // model picker while Claude Code was the active agent.
            val chatConnected = core.runtime.providers.connected.toSet() + oauth.locallyConnected
            val managed = core.providerCatalog ?: core.runtime.providers
            SettingsUiState(
                providers = core.runtime.providers.all.filter { it.id in chatConnected },
                availableProviders = managed.all,
                connectedProviderIds = managed.connected.toSet() + oauth.locallyConnected,
                agents = core.runtime.agents.filter { it.mode == null || it.mode == "primary" },
                providerId = core.preferences.providerId,
                modelId = core.preferences.modelId,
                agentId = core.preferences.agentId,
                ttsEnabled = core.preferences.ttsEnabled,
                ttsProvider = core.preferences.ttsProvider,
                ttsAndroidEngine = core.preferences.ttsAndroidEngine,
                ttsSpeechRate = core.preferences.ttsSpeechRate,
                ttsPitch = core.preferences.ttsPitch,
                ttsOpenAiApiKey = core.preferences.ttsOpenAiApiKey,
                ttsOpenAiVoice = core.preferences.ttsOpenAiVoice,
                ttsOpenAiModel = core.preferences.ttsOpenAiModel,
                ttsElevenLabsApiKey = core.preferences.ttsElevenLabsApiKey,
                ttsElevenLabsVoiceId = core.preferences.ttsElevenLabsVoiceId,
                ttsElevenLabsModel = core.preferences.ttsElevenLabsModel,
                ttsBargeInEnabled = core.preferences.ttsBargeInEnabled,
                continuousConversation = core.preferences.continuousConversation,
                wakeWordEnabled = core.preferences.wakeWordEnabled,
                wakeWordPhrase = core.preferences.wakeWordPhrase,
                wakeWordSensitivity = core.preferences.wakeWordSensitivity,
                wakeWordModelLanguage = wakeWordLanguage(core.preferences.wakeWordModelLanguage),
                wakeWordModelStates = voskModelStates,
                autoAcceptPermissions = core.preferences.autoAcceptPermissions,
                runtimeOptions = core.targets.map { it.id to it.displayName },
                assistantRuntimeId = settings.assistantRuntimeId ?: core.selected?.id,
                assistantWorkspacePath = settings.assistantWorkspacePath,
                assistantProviderId = settings.assistantProviderId ?: core.preferences.providerId,
                assistantModelId = settings.assistantModelId ?: core.preferences.modelId,
                openCodeVersion = core.runtime.health?.version,
                providerAuthMethods = oauth.methods,
                oauthMessage = oauth.message,
                providerAuthDialog = oauth.dialog,
                providerAuthNotice = oauth.notice,
                favoriteModelKeys = core.preferences.favoriteModelKeys,
                recentModelKeys = core.preferences.recentModelKeys,
                hiddenModelKeys = core.preferences.hiddenModelKeys,
                githubConfigured = githubAuth.isConfigured,
                githubLogin = settings.githubLogin,
                githubMessage = github.message,
                githubUserCode = github.deviceCode?.userCode,
                githubVerificationUrl = github.deviceCode?.verificationUriComplete ?: github.deviceCode?.verificationUri,
                githubPolling = github.polling,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    fun selectModel(
        providerId: String,
        modelId: String,
    ) = preferences.selectModel(providerId, modelId)

    fun selectAgent(agentId: String) = preferences.selectAgent(agentId)

    fun setTtsEnabled(enabled: Boolean) = preferences.setTtsEnabled(enabled)

    fun setTtsProvider(provider: String) = preferences.setTtsProvider(provider)

    fun setTtsAndroidEngine(engine: String?) = preferences.setTtsAndroidEngine(engine)

    /**
     * The stored model language, or the one matching the device's own language the first time.
     *
     * Guessing beats defaulting everyone to English: a Japanese phrase against the English model
     * cannot match at all, and that failure looks identical to a broken microphone.
     */
    private fun wakeWordLanguage(stored: String?): VoskModelLanguage =
        VoskModelLanguage.fromId(stored) ?: VoskModelCatalog.defaultLanguageFor(Locale.getDefault())

    /** The voice preferences as they stand, for the settings screen's own test playback. */
    internal fun ttsSettings(): TtsSettings = settings.ttsSettings()

    fun setTtsSpeechRate(rate: Float) = preferences.setTtsSpeechRate(rate)

    fun setTtsPitch(pitch: Float) = preferences.setTtsPitch(pitch)

    fun setTtsOpenAiApiKey(apiKey: String) = preferences.setTtsOpenAiApiKey(apiKey)

    fun setTtsOpenAiVoice(voice: String) = preferences.setTtsOpenAiVoice(voice)

    fun setTtsOpenAiModel(model: String) = preferences.setTtsOpenAiModel(model)

    fun setTtsElevenLabsApiKey(apiKey: String) = preferences.setTtsElevenLabsApiKey(apiKey)

    fun setTtsElevenLabsVoiceId(voiceId: String) = preferences.setTtsElevenLabsVoiceId(voiceId)

    fun setTtsElevenLabsModel(model: String) = preferences.setTtsElevenLabsModel(model)

    fun setTtsBargeInEnabled(enabled: Boolean) = preferences.setTtsBargeInEnabled(enabled)

    fun setContinuousConversation(enabled: Boolean) = preferences.setContinuousConversation(enabled)

    fun setWakeWordEnabled(enabled: Boolean) = preferences.setWakeWordEnabled(enabled)

    fun setWakeWordPhrase(phrase: String) = preferences.setWakeWordPhrase(phrase)

    fun setWakeWordSensitivity(sensitivity: Float) = preferences.setWakeWordSensitivity(sensitivity)

    /**
     * Which words of [phrase] the model for [language] cannot recognise.
     *
     * Empty also covers "cannot tell": with no model on disk, or a dictionary that will not read,
     * there is nothing to hold against the phrase, and blocking on a failed check would be a worse
     * outcome than the silent mis-detection it exists to prevent.
     */
    suspend fun unknownWakeWordWords(
        language: VoskModelLanguage,
        phrase: String,
    ): List<String> =
        withContext(Dispatchers.IO) {
            val directory = voskModels.directoryFor(language) ?: return@withContext emptyList()
            VoskVocabulary.unknownWords(directory, phrase).orEmpty()
        }

    fun setWakeWordModelLanguage(language: VoskModelLanguage) = preferences.setWakeWordModelLanguage(language.id)

    fun downloadWakeWordModel(language: VoskModelLanguage) = voskModels.install(language)

    fun cancelWakeWordModelDownload(language: VoskModelLanguage) = voskModels.cancel(language)

    fun removeWakeWordModel(language: VoskModelLanguage) = voskModels.remove(language)

    fun setAutoAcceptPermissions(enabled: Boolean) = preferences.setAutoAcceptPermissions(enabled)

    fun toggleFavoriteModel(
        providerId: String,
        modelId: String,
    ) = preferences.toggleFavoriteModel(providerId, modelId)

    fun toggleModelVisibility(
        providerId: String,
        modelId: String,
    ) = preferences.toggleModelVisibility(providerId, modelId)

    fun beginGitHubDeviceFlow() {
        viewModelScope.launch {
            runCatching {
                val code = githubAuth.requestDeviceCode()
                githubState.value = GitHubState(deviceCode = code, polling = true)
                val accessToken = githubAuth.pollToken(code.deviceCode, code.intervalSeconds, code.expiresInSeconds)
                checkNotNull(githubAuth.refreshAccount(accessToken)) { "GitHub account could not be verified" }
                githubAuth.saveToken(accessToken)
            }.onSuccess {
                githubState.value = GitHubState()
                settingsTick.update { it + 1 }
                onLocalRuntimeRestartNeeded?.invoke()
            }.onFailure { error ->
                githubState.value = GitHubState(message = error.message ?: "GitHub authorization failed")
            }
        }
    }

    fun disconnectGitHub() {
        githubAuth.disconnect()
        githubState.value = GitHubState()
        settingsTick.update { it + 1 }
        onLocalRuntimeRestartNeeded?.invoke()
    }

    suspend fun listGitHubRepos(): List<GitHubRepo> = githubAuth.listRepos()

    fun saveLocalBootstrapApiKey(
        providerId: String,
        apiKey: String,
    ) {
        credentials.setCredential(providerId, apiKey)
        settingsTick.update { it + 1 }
    }

    /**
     * Runtime that owns provider credentials.
     *
     * Providers are an OpenCode concept: Claude Code authenticates as itself and has no catalogue.
     * With Claude selected, every one of these calls used to go to a runtime that cannot answer, so
     * the connect button simply did nothing.
     */
    private fun providerTarget(): RuntimeTarget? = registry.targetFor(LocalAgent.OPEN_CODE)

    fun openProviderAuth(providerId: String) {
        val methods = oauthState.value.methods[providerId].orEmpty()
        val effectiveMethods =
            methods.ifEmpty {
                listOf(ProviderAuthMethod(type = "api", label = "API key"))
            }
        val providerName =
            state.value.availableProviders
                .firstOrNull { it.id == providerId }
                ?.name
                ?: providerId
        oauthState.update {
            it.copy(
                dialog =
                    ProviderAuthDialogState(
                        providerId = providerId,
                        providerName = providerName,
                        methods = effectiveMethods,
                    ),
                notice = null,
                message = null,
            )
        }
    }

    fun selectProviderAuthMethod(methodIndex: Int) {
        val current = oauthState.value.dialog ?: return
        val method = current.methods.getOrNull(methodIndex) ?: return
        val updated =
            current.copy(
                methodIndex = methodIndex,
                inputs = emptyMap(),
                apiKey = "",
                authorization = null,
                isSubmitting = false,
                failed = false,
                error = null,
            )
        oauthState.update { it.copy(dialog = updated, notice = null, message = null) }
        if (method.type == "oauth" && method.prompts.orEmpty().isEmpty()) submitProviderAuth()
    }

    fun updateProviderAuthInput(
        key: String,
        value: String,
    ) {
        oauthState.update { current ->
            val dialog = current.dialog ?: return@update current
            current.copy(
                dialog =
                    dialog.copy(
                        inputs = dialog.inputs + (key to value),
                        failed = false,
                        error = null,
                    ),
            )
        }
    }

    fun updateProviderApiKey(value: String) {
        oauthState.update { current ->
            val dialog = current.dialog ?: return@update current
            current.copy(dialog = dialog.copy(apiKey = value, failed = false, error = null))
        }
    }

    fun submitProviderAuth() {
        val dialog = oauthState.value.dialog ?: return
        val methodIndex = dialog.methodIndex ?: return
        val method = dialog.selectedMethod ?: return
        if (dialog.isSubmitting || providerAuthJob?.isActive == true || !dialog.promptsComplete) return
        when (method.type) {
            "api" -> submitProviderApiKey(dialog)
            "oauth" -> beginProviderOAuth(dialog, methodIndex)
        }
    }

    private fun submitProviderApiKey(dialog: ProviderAuthDialogState) {
        val target = providerTarget() ?: return
        val apiKey = dialog.apiKey.trim()
        if (apiKey.isEmpty()) return
        providerAuthJob =
            viewModelScope.launch {
                oauthState.update {
                    it.copy(dialog = dialog.copy(isSubmitting = true, failed = false, error = null))
                }
                runCatching { target.setProviderApiKey(dialog.providerId, apiKey, dialog.inputs) }
                    .onSuccess { completed ->
                        if (completed) {
                            if (target.kind == BackendKind.LOCAL) {
                                credentials.unmanageProvider(dialog.providerId)
                            }
                            finishProviderAuth(ProviderAuthNotice.CONNECTED)
                        } else {
                            updateProviderAuthError(null)
                        }
                    }
                    .onFailure(::updateProviderAuthError)
            }
    }

    private fun beginProviderOAuth(
        dialog: ProviderAuthDialogState,
        methodIndex: Int,
    ) {
        val target = providerTarget() ?: return
        providerAuthJob =
            viewModelScope.launch {
                android.util.Log.w(TAG, "beginProviderOAuth: provider=${dialog.providerId} method=$methodIndex")
                oauthState.update {
                    it.copy(dialog = dialog.copy(isSubmitting = true, failed = false, error = null))
                }
                runCatching { target.authorizeProvider(dialog.providerId, methodIndex, dialog.inputs) }
                    .onSuccess { authorization ->
                        android.util.Log.w(
                            TAG,
                            "authorizeProvider OK: method=${authorization.method} " +
                                "url=${SecretRedaction.redactUrlQuery(authorization.url)}",
                        )
                        if (target.kind == BackendKind.LOCAL) {
                            credentials.unmanageProvider(dialog.providerId)
                        }
                        val authorized =
                            dialog.copy(
                                authorization = authorization,
                                isSubmitting = authorization.method == "auto",
                                failed = false,
                                error = null,
                            )
                        oauthState.update { it.copy(dialog = authorized, notice = null, message = null) }
                        if (authorization.method == "auto") {
                            val deadline = System.currentTimeMillis() + AUTO_OAUTH_TIMEOUT_MS
                            var completed = false
                            var attempt = 0
                            while (!completed && System.currentTimeMillis() < deadline) {
                                attempt++
                                val result =
                                    runCatching {
                                        target.completeProviderOAuth(dialog.providerId, methodIndex, null)
                                    }
                                completed = result.getOrDefault(false)
                                android.util.Log.w(
                                    TAG,
                                    "completeProviderOAuth attempt=$attempt completed=$completed error=${result.exceptionOrNull()?.message}",
                                )
                                if (!completed) delay(AUTO_OAUTH_POLL_MS)
                            }
                            android.util.Log.w(TAG, "OAuth polling finished: completed=$completed")
                            if (completed) {
                                finishProviderAuth(ProviderAuthNotice.CONNECTED)
                            } else {
                                updateProviderAuthError(null)
                            }
                        }
                    }
                    .onFailure { error ->
                        android.util.Log.e(TAG, "authorizeProvider FAILED: ${error.message}", error)
                        updateProviderAuthError(error)
                    }
            }
    }

    fun completeProviderOAuth(code: String) {
        val dialog = oauthState.value.dialog ?: return
        val methodIndex = dialog.methodIndex ?: return
        if (dialog.authorization?.method != "code" || code.isBlank()) return
        val target = providerTarget() ?: return
        if (providerAuthJob?.isActive == true) return
        providerAuthJob =
            viewModelScope.launch {
                oauthState.update {
                    it.copy(dialog = dialog.copy(isSubmitting = true, failed = false, error = null))
                }
                runCatching { target.completeProviderOAuth(dialog.providerId, methodIndex, code.trim()) }
                    .onSuccess { completed ->
                        if (completed) {
                            finishProviderAuth(ProviderAuthNotice.CONNECTED)
                        } else {
                            updateProviderAuthError(null)
                        }
                    }
                    .onFailure(::updateProviderAuthError)
            }
    }

    fun disconnectProvider(providerId: String) {
        val target = providerTarget() ?: return
        if (providerAuthJob?.isActive == true) return
        providerAuthJob =
            viewModelScope.launch {
                runCatching { target.removeProviderAuth(providerId) }
                    .onSuccess { removed ->
                        if (removed) {
                            if (target.kind == BackendKind.LOCAL) {
                                credentials.clearCredential(providerId)
                            }
                            oauthState.update {
                                it.copy(locallyConnected = it.locallyConnected - providerId)
                            }
                            finishProviderAuth(ProviderAuthNotice.DISCONNECTED)
                        } else {
                            oauthState.update { it.copy(message = providerDisconnectRejectedMessage) }
                        }
                    }
                    .onFailure { error ->
                        oauthState.update {
                            it.copy(message = error.message?.takeIf(String::isNotBlank))
                        }
                    }
            }
    }

    fun dismissProviderAuth() {
        providerAuthJob?.cancel()
        providerAuthJob = null
        oauthState.update { it.copy(dialog = null, message = null) }
    }

    fun consumeProviderAuthNotice() {
        oauthState.update { it.copy(notice = null) }
    }

    fun reportOAuthError(message: String) {
        providerAuthJob?.cancel()
        updateProviderAuthError(message.takeIf(String::isNotBlank))
    }

    private fun finishProviderAuth(notice: ProviderAuthNotice) {
        providerAuthJob = null
        val connectedId = oauthState.value.dialog?.providerId
        oauthState.update {
            it.copy(
                dialog = null,
                notice = notice,
                message = null,
                locallyConnected =
                    if (notice == ProviderAuthNotice.CONNECTED && connectedId != null) {
                        it.locallyConnected + connectedId
                    } else {
                        it.locallyConnected
                    },
            )
        }
        settingsTick.update { it + 1 }
        if (notice == ProviderAuthNotice.CONNECTED) {
            onProviderAuthCompleted?.invoke()
            if (providerTarget()?.kind == BackendKind.LOCAL) {
                onLocalRuntimeRestartNeeded?.invoke()
            }
        }
        catalog.refreshProvidersOnly()
        catalog.refresh()
        refreshProviderAuth()
    }

    private fun updateProviderAuthError(error: Throwable) {
        updateProviderAuthError(error.message?.takeIf(String::isNotBlank))
    }

    private fun updateProviderAuthError(message: String?) {
        providerAuthJob = null
        oauthState.update { current ->
            current.copy(
                dialog =
                    current.dialog?.copy(
                        isSubmitting = false,
                        failed = true,
                        error = message,
                    ),
                message = if (current.dialog == null) message else null,
            )
        }
    }

    fun refreshProviderAuth() {
        val target =
            providerTarget() ?: run {
                oauthState.value = OAuthState()
                return
            }
        viewModelScope.launch {
            // Only when the chat is on another agent: otherwise the shared catalogue already holds
            // this runtime's providers and a second fetch would be pure duplication.
            providerCatalog.value =
                if (target.id == registry.selected.value?.id) {
                    null
                } else {
                    // A failure keeps whatever was fetched before: the runtime may simply not be
                    // running yet, and falling back would put the other agent's models on screen,
                    // which is the very thing this exists to prevent.
                    runCatching { target.listProviders() }.getOrNull()
                        ?: providerCatalog.value
                        // The runtime may simply be stopped. Its stored catalogue is still the
                        // truth about which providers exist and which are connected.
                        ?: catalog.cachedProviders(target.id)
                }
            runCatching { target.providerAuthMethods() }
                .onSuccess { methods ->
                    oauthState.update { current -> current.copy(methods = methods, message = null) }
                }
                .onFailure { error ->
                    oauthState.update { current ->
                        current.copy(
                            methods = emptyMap(),
                            message = error.message?.takeIf(String::isNotBlank),
                        )
                    }
                }
        }
    }

    fun setAssistantRuntimeId(runtimeId: String?) {
        if (settings.assistantRuntimeId != runtimeId) {
            // A provider/model/workspace belongs to the runtime that supplied it. Keeping those
            // values while switching Agent makes the next voice request send stale identifiers to
            // the new runtime, and leaves the settings screen showing a choice that cannot work.
            settings.assistantProviderId = null
            settings.assistantModelId = null
            settings.assistantWorkspacePath = null
        }
        settings.assistantRuntimeId = runtimeId
        settingsTick.update { it + 1 }
    }

    fun setAssistantWorkspacePath(path: String?) {
        settings.assistantWorkspacePath = path?.trim()?.ifBlank { null }
        settingsTick.update { it + 1 }
    }

    fun setAssistantModel(
        providerId: String?,
        modelId: String?,
    ) {
        settings.assistantProviderId = providerId?.takeIf(String::isNotBlank)
        settings.assistantModelId = modelId?.takeIf(String::isNotBlank)
        settingsTick.update { it + 1 }
    }

    private companion object {
        const val TAG = "SettingsVM"
        const val AUTO_OAUTH_TIMEOUT_MS = 6 * 60 * 1000L
        const val AUTO_OAUTH_POLL_MS = 3000L
    }
}
