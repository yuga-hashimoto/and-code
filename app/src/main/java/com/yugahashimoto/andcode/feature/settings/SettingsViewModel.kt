package com.yugahashimoto.andcode.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeProvider
import com.yugahashimoto.andcode.core.api.ProviderAuthMethod
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.core.api.selectablePrimaryAgents
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
import com.yugahashimoto.andcode.runtime.local.CustomProviderDefinition
import com.yugahashimoto.andcode.runtime.local.CustomProviderModelFetcher
import com.yugahashimoto.andcode.runtime.local.CustomProviderStore
import com.yugahashimoto.andcode.runtime.local.LocalProviderCredentialStore
import com.yugahashimoto.andcode.runtime.local.toProviderConfig
import kotlinx.coroutines.CancellationException
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

data class SettingsUiState(
    val providers: List<OpenCodeProvider> = emptyList(),
    val availableProviders: List<OpenCodeProvider> = emptyList(),
    val connectedProviderIds: Set<String> = emptySet(),
    val agents: List<OpenCodeAgent> = emptyList(),
    /** The selected runtime is still reading its agents; see [RuntimeCatalogState.agentsLoading]. */
    val agentsLoading: Boolean = false,
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
    val customProviderIds: Set<String> = emptySet(),
    val customProviderDialog: CustomProviderDialogState? = null,
    /**
     * False when the OpenCode runtime cannot take custom provider definitions over the API (V2).
     * The add button is hidden rather than offering a write that the server would reject.
     */
    val customProvidersEditable: Boolean = true,
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
    private val customProviders: CustomProviderStore,
    private val settings: SecureSettingsRepository,
    private val registry: RuntimeRegistry,
    private val voskModels: VoskModelStore,
    private val providerDisconnectRejectedMessage: String = "Provider disconnect was not accepted",
    private val customProviderInvalidMessage: String = "Enter a provider id, base URL, and at least one model id",
    private val modelFetcher: CustomProviderModelFetcher = CustomProviderModelFetcher(),
    private val customProviderFetchNeedsBaseUrlMessage: String = "Enter a base URL first",
    private val customProviderFetchEmptyMessage: String = "The endpoint returned no models",
    private val customProviderFetchFailedMessage: String = "Could not fetch the model list",
) : ViewModel() {
    private val settingsTick = MutableStateFlow(0)
    private val oauthState = MutableStateFlow(OAuthState())
    private val customProviderDialogState = MutableStateFlow<CustomProviderDialogState?>(null)
    private var customProviderJob: Job? = null

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
        /**
         * Providers the user just disconnected, kept separate from the server's report.
         *
         * OpenCode's `/provider` endpoint can keep reporting a provider as connected after its
         * credentials were removed - the removal itself still succeeds - so without this override
         * the row snaps right back to "Connected" and disconnect looks like it silently did
         * nothing.
         */
        val locallyDisconnected: Set<String> = emptySet(),
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

    /** [settingsTick] and the custom-provider dialog draft share one combine slot, since [combine] tops out at five flows. */
    private val tickAndCustomDialog =
        combine(settingsTick, customProviderDialogState) { tick, dialog -> tick to dialog }

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
            tickAndCustomDialog,
            oauthState,
            githubState,
            voskModels.state,
        ) { core, (_, customProviderDialog), oauth, github, voskModelStates ->
            // Two different questions, two different catalogues.
            //
            // `providers` answers "what can this chat talk to", so it follows the selected runtime.
            // `availableProviders` answers "whose credentials can I manage", which is always the
            // provider-owning runtime. Serving both from one catalogue put OpenCode's models in the
            // model picker while Claude Code was the active agent.
            val chatConnected =
                (core.runtime.providers.connected.toSet() + oauth.locallyConnected) - oauth.locallyDisconnected
            val managed = core.providerCatalog ?: core.runtime.providers
            SettingsUiState(
                providers = core.runtime.providers.all.filter { it.id in chatConnected },
                availableProviders = managed.all,
                connectedProviderIds = (managed.connected.toSet() + oauth.locallyConnected) - oauth.locallyDisconnected,
                agents = core.runtime.agents.selectablePrimaryAgents(),
                agentsLoading = core.runtime.agentsLoading,
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
                customProviderIds = customProviders.definitions().map { it.id }.toSet(),
                customProviderDialog = customProviderDialog,
                customProvidersEditable = providerTarget()?.capabilities?.configEditing ?: true,
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
                                credentials.setCredential(dialog.providerId, apiKey)
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
                                it.copy(
                                    locallyConnected = it.locallyConnected - providerId,
                                    locallyDisconnected = it.locallyDisconnected + providerId,
                                )
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

    /** Opens the draft dialog for registering an OpenAI-compatible provider OpenCode's catalogue doesn't offer. */
    fun openAddCustomProvider() {
        // Guarded here as well as in the UI, so no other entry point can open a dialog that cannot save.
        if (providerTarget()?.capabilities?.configEditing == false) return
        customProviderDialogState.value = CustomProviderDialogState()
    }

    /**
     * Opens the same dialog prefilled with a registered provider, so its model list can be changed
     * (or re-fetched) without removing and re-adding it. The id stays fixed: it keys the stored
     * credential and every saved model selection.
     */
    fun openEditCustomProvider(providerId: String) {
        val definition = customProviders.definitions().firstOrNull { it.id == providerId } ?: return
        customProviderDialogState.value =
            CustomProviderDialogState(
                id = definition.id,
                name = definition.name,
                baseUrl = definition.baseUrl,
                models = definition.models.joinToString(", "),
                editing = true,
                hasStoredApiKey = credentials.hasCredential(definition.id),
            )
    }

    fun updateCustomProviderId(value: String) {
        customProviderDialogState.update { if (it == null || it.editing) it else it.copy(id = value, error = null) }
    }

    fun updateCustomProviderName(value: String) {
        customProviderDialogState.update { it?.copy(name = value, error = null) }
    }

    fun updateCustomProviderBaseUrl(value: String) {
        customProviderDialogState.update { it?.copy(baseUrl = value, error = null, fetchError = null) }
    }

    fun updateCustomProviderModels(value: String) {
        customProviderDialogState.update { it?.copy(models = value, error = null, fetchedModelCount = null) }
    }

    fun updateCustomProviderApiKey(value: String) {
        customProviderDialogState.update { it?.copy(apiKey = value, error = null, fetchError = null) }
    }

    /**
     * Fills the model field from the endpoint's own `GET <baseURL>/models` listing, using the key
     * typed into the dialog or, while editing a registered provider, the one already stored for it.
     */
    fun fetchCustomProviderModels() {
        val dialog = customProviderDialogState.value ?: return
        if (dialog.isBusy || customProviderJob?.isActive == true) return
        val baseUrl = dialog.baseUrl.trim()
        if (baseUrl.isEmpty()) {
            customProviderDialogState.update { it?.copy(fetchError = customProviderFetchNeedsBaseUrlMessage) }
            return
        }
        // The stored key only falls back while editing that very provider: in add mode the id is
        // free text, and typing an existing id (say `openai`) would otherwise send that provider's
        // saved key to whatever base URL was typed into the dialog.
        val apiKey =
            dialog.apiKey.trim().ifEmpty {
                if (dialog.editing) credentials.credentials()[dialog.id.trim()].orEmpty() else ""
            }
        customProviderJob =
            viewModelScope.launch {
                customProviderDialogState.update {
                    it?.copy(isFetchingModels = true, fetchError = null, fetchedModelCount = null, error = null)
                }
                runCatching { modelFetcher.fetch(baseUrl, apiKey) }
                    .onSuccess { models ->
                        customProviderJob = null
                        customProviderDialogState.update {
                            if (models.isEmpty()) {
                                it?.copy(isFetchingModels = false, fetchError = customProviderFetchEmptyMessage)
                            } else {
                                it?.copy(
                                    isFetchingModels = false,
                                    models = models.joinToString(", "),
                                    fetchedModelCount = models.size,
                                )
                            }
                        }
                    }.onFailure { error ->
                        // A dismissed dialog cancels this job; its late failure must not land in a
                        // dialog opened afterwards, nor clear that dialog's own job reference.
                        if (error is CancellationException) throw error
                        customProviderJob = null
                        val detail = error.message?.takeIf(String::isNotBlank)?.let(SecretRedaction::redact)
                        customProviderDialogState.update {
                            it?.copy(
                                isFetchingModels = false,
                                fetchError = listOfNotNull(customProviderFetchFailedMessage, detail).joinToString("\n"),
                            )
                        }
                    }
            }
    }

    fun dismissCustomProviderDialog() {
        customProviderJob?.cancel()
        customProviderJob = null
        customProviderDialogState.value = null
    }

    /**
     * Registers the draft as an OpenCode `provider` config entry (OpenCode's OpenAI-compatible AI
     * SDK adapter), then reopens the standard [openProviderAuth] flow so the user enters its API
     * key through the same dialog every other provider uses.
     */
    fun submitCustomProvider() {
        val dialog = customProviderDialogState.value ?: return
        if (dialog.isBusy || customProviderJob?.isActive == true) return
        val target = providerTarget() ?: return
        val definition =
            CustomProviderDefinition(
                id = dialog.id.trim(),
                name = dialog.name.trim().ifEmpty { dialog.id.trim() },
                baseUrl = dialog.baseUrl.trim(),
                models = parseModelIdList(dialog.models),
            )
        val typedApiKey = dialog.apiKey.trim()
        if (definition.id.isEmpty() || definition.baseUrl.isEmpty() || definition.models.isEmpty()) {
            customProviderDialogState.update { it?.copy(error = customProviderInvalidMessage) }
            return
        }
        customProviderJob =
            viewModelScope.launch {
                customProviderDialogState.update { it?.copy(isSubmitting = true, error = null) }
                runCatching {
                    target.updateConfig(
                        buildJsonObject {
                            put("provider", buildJsonObject { put(definition.id, definition.toProviderConfig()) })
                        },
                    )
                }.onSuccess {
                    customProviders.upsert(definition)
                    customProviderJob = null
                    customProviderDialogState.value = null
                    catalog.refreshProvidersOnly()
                    catalog.refresh()
                    refreshProviderAuth()
                    finishCustomProviderSubmit(definition.id, dialog.editing, typedApiKey, target)
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    customProviderJob = null
                    customProviderDialogState.update {
                        it?.copy(isSubmitting = false, error = error.message?.takeIf(String::isNotBlank) ?: customProviderInvalidMessage)
                    }
                }
            }
    }

    private fun finishCustomProviderSubmit(
        providerId: String,
        editing: Boolean,
        typedApiKey: String,
        target: RuntimeTarget,
    ) {
        if (editing && typedApiKey.isEmpty() && credentials.hasCredential(providerId)) {
            // Only the definition changed and the stored key still applies: skip the key dialog,
            // but the runtime still has to reload to pick up the new model list.
            settingsTick.update { it + 1 }
            if (target.kind == BackendKind.LOCAL) onLocalRuntimeRestartNeeded?.invoke()
            return
        }
        openProviderAuth(providerId)
        if (typedApiKey.isEmpty()) return
        // The key was already typed to fetch models; prefill it so the user just confirms instead
        // of pasting it a second time.
        val apiIndex = oauthState.value.dialog?.methods?.indexOfFirst { it.type == "api" } ?: -1
        if (apiIndex >= 0) {
            selectProviderAuthMethod(apiIndex)
            updateProviderApiKey(typedApiKey)
        }
    }

    /** Forgets a custom provider entirely: its definition and its stored credential. */
    fun removeCustomProvider(providerId: String) {
        customProviders.remove(providerId)
        disconnectProvider(providerId)
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
                locallyDisconnected =
                    if (notice == ProviderAuthNotice.CONNECTED && connectedId != null) {
                        it.locallyDisconnected - connectedId
                    } else {
                        it.locallyDisconnected
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
