package com.opencode.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.android.core.api.OpenCodeAgent
import com.opencode.android.core.api.OpenCodeProvider
import com.opencode.android.core.api.ProviderAuthMethod
import com.opencode.android.data.connection.SecureSettingsRepository
import com.opencode.android.data.repository.RuntimeCatalogRepository
import com.opencode.android.data.repository.RuntimeCatalogState
import com.opencode.android.data.settings.AppPreferences
import com.opencode.android.data.settings.AppPreferencesRepository
import com.opencode.android.runtime.BackendKind
import com.opencode.android.runtime.RuntimeRegistry
import com.opencode.android.runtime.RuntimeTarget
import com.opencode.android.runtime.local.LocalProviderCredentialStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val providers: List<OpenCodeProvider> = emptyList(),
    val availableProviders: List<OpenCodeProvider> = emptyList(),
    val connectedProviderIds: Set<String> = emptySet(),
    val agents: List<OpenCodeAgent> = emptyList(),
    val providerId: String? = null,
    val modelId: String? = null,
    val agentId: String? = null,
    val ttsEnabled: Boolean = true,
    val continuousConversation: Boolean = false,
    val wakeWordEnabled: Boolean = false,
    val autoAcceptPermissions: Boolean = false,
    val credentialStatuses: Map<String, Boolean> = emptyMap(),
    val draftProviderId: String = "",
    val draftApiKey: String = "",
    val credentialMessage: String? = null,
    val runtimeOptions: List<Pair<String, String>> = emptyList(),
    val assistantRuntimeId: String? = null,
    val assistantWorkspacePath: String? = null,
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
) : ViewModel() {
    private val settingsTick = MutableStateFlow(0)
    private val oauthState = MutableStateFlow(OAuthState())
    private val githubState = MutableStateFlow(GitHubState())
    private var providerAuthJob: Job? = null
    private val githubAuth =
        GitHubAuthRepository(
            settings = settings,
            clientId = com.opencode.android.BuildConfig.GITHUB_CLIENT_ID,
        )
    var onLocalRuntimeRestartNeeded: (() -> Unit)? = null

    private data class CoreState(
        val runtime: RuntimeCatalogState,
        val preferences: AppPreferences,
        val targets: List<RuntimeTarget>,
        val selected: RuntimeTarget?,
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
            combine(catalog.state, preferences.state, registry.targets, registry.selected) { runtime, prefs, targets, selected ->
                CoreState(runtime, prefs, targets, selected)
            },
            settingsTick,
            oauthState,
            githubState,
        ) { core, _, oauth, github ->
            val connected = core.runtime.providers.connected.toSet() + oauth.locallyConnected
            SettingsUiState(
                providers = core.runtime.providers.all.filter { it.id in connected },
                availableProviders = core.runtime.providers.all,
                connectedProviderIds = connected,
                agents = core.runtime.agents.filter { it.mode == null || it.mode == "primary" },
                providerId = core.preferences.providerId,
                modelId = core.preferences.modelId,
                agentId = core.preferences.agentId,
                ttsEnabled = core.preferences.ttsEnabled,
                continuousConversation = core.preferences.continuousConversation,
                wakeWordEnabled = core.preferences.wakeWordEnabled,
                autoAcceptPermissions = core.preferences.autoAcceptPermissions,
                runtimeOptions = core.targets.map { it.id to it.displayName },
                assistantRuntimeId = settings.assistantRuntimeId ?: core.selected?.id,
                assistantWorkspacePath = settings.assistantWorkspacePath,
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

    fun setContinuousConversation(enabled: Boolean) = preferences.setContinuousConversation(enabled)

    fun setWakeWordEnabled(enabled: Boolean) = preferences.setWakeWordEnabled(enabled)

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
        val target = registry.selected.value ?: return
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
        val target = registry.selected.value ?: return
        providerAuthJob =
            viewModelScope.launch {
                android.util.Log.w(TAG, "beginProviderOAuth: provider=${dialog.providerId} method=$methodIndex")
                oauthState.update {
                    it.copy(dialog = dialog.copy(isSubmitting = true, failed = false, error = null))
                }
                runCatching { target.authorizeProvider(dialog.providerId, methodIndex, dialog.inputs) }
                    .onSuccess { authorization ->
                        android.util.Log.w(TAG, "authorizeProvider OK: method=${authorization.method} url=${authorization.url.take(80)}")
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
        val target = registry.selected.value ?: return
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
        val target = registry.selected.value ?: return
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
                            oauthState.update { it.copy(message = "Provider disconnect was not accepted") }
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
        if (notice == ProviderAuthNotice.CONNECTED && registry.selected.value?.kind == BackendKind.LOCAL) {
            onLocalRuntimeRestartNeeded?.invoke()
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
            registry.selected.value ?: run {
                oauthState.value = OAuthState()
                return
            }
        viewModelScope.launch {
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
        settings.assistantRuntimeId = runtimeId
        settingsTick.update { it + 1 }
    }

    fun setAssistantWorkspacePath(path: String?) {
        settings.assistantWorkspacePath = path?.trim()?.ifBlank { null }
        settingsTick.update { it + 1 }
    }

    private companion object {
        const val TAG = "SettingsVM"
        const val AUTO_OAUTH_TIMEOUT_MS = 6 * 60 * 1000L
        const val AUTO_OAUTH_POLL_MS = 3000L
    }
}
