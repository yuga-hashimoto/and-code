package com.yugahashimoto.andcode.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.core.api.McpServer
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.OpenCodeBackend
import com.yugahashimoto.andcode.runtime.RuntimeRegistry
import com.yugahashimoto.andcode.runtime.local.ClaudeCodeTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class McpUiState(
    val servers: List<McpServer> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val showAddDialog: Boolean = false,
    val addName: String = "",
    val addCommand: String = "",
    val addUrl: String = "",
    val isAdding: Boolean = false,
    val supportsOAuth: Boolean = false,
    val oauthServerName: String? = null,
    val oauthCode: String = "",
    val isAuthenticating: Boolean = false,
    /**
     * Whether connecting and disconnecting a configured server means anything here.
     *
     * Claude Code and Antigravity both connect to every server they know about, so they offer
     * removal instead of a live toggle.
     */
    val supportsConnectToggle: Boolean = true,
)

/**
 * MCP servers for one agent.
 *
 * Each agent keeps its own server list, so this deliberately does not follow the chat's selected
 * runtime: the screen is reached from that agent's settings and must configure that agent.
 */
class McpViewModel(
    private val backendProvider: (LocalAgent) -> OpenCodeBackend?,
    private val agent: LocalAgent = LocalAgent.OPEN_CODE,
    private val authNotRemovedMessage: String = "Authentication was not removed",
    private val authFailedTemplate: String = "Authentication failed: %1\$s",
) : ViewModel() {
    constructor(
        registry: RuntimeRegistry,
        agent: LocalAgent = LocalAgent.OPEN_CODE,
        authNotRemovedMessage: String = "Authentication was not removed",
        authFailedTemplate: String = "Authentication failed: %1\$s",
    ) : this(registry::targetFor, agent, authNotRemovedMessage, authFailedTemplate)

    private val _state =
        MutableStateFlow(
            McpUiState(
                supportsConnectToggle = agent !in setOf(LocalAgent.CLAUDE_CODE, LocalAgent.ANTIGRAVITY),
                supportsOAuth = agent == LocalAgent.OPEN_CODE,
            ),
        )
    val state: StateFlow<McpUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val backend = backendProvider(agent) ?: return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            runCatching { backend.mcpServers() }
                .onSuccess { servers ->
                    _state.update { it.copy(servers = servers, isLoading = false) }
                }
                .onFailure { e ->
                    _state.update { it.copy(error = e.message, isLoading = false) }
                }
        }
    }

    fun connect(name: String) {
        val backend = backendProvider(agent) ?: return
        viewModelScope.launch {
            runCatching { backend.connectMcpServer(name) }
                .onSuccess { refresh() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    /** Disconnects an OpenCode server, or deletes a Claude Code one — see [McpUiState.supportsConnectToggle]. */
    fun disconnect(name: String) {
        val backend = backendProvider(agent) ?: return
        viewModelScope.launch {
            runCatching {
                (backend as? ClaudeCodeTarget)?.removeMcpServer(name) ?: backend.disconnectMcpServer(name)
            }
                .onSuccess { refresh() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun removeAuth(name: String) {
        val backend = backendProvider(agent) ?: return
        if (!_state.value.supportsOAuth || _state.value.isAuthenticating) return
        _state.update { it.copy(isAuthenticating = true, error = null) }
        viewModelScope.launch {
            runCatching { backend.removeMcpAuth(name) }
                .onSuccess { result ->
                    _state.update { it.copy(isAuthenticating = false) }
                    if (result.success) refresh() else _state.update { it.copy(error = authNotRemovedMessage) }
                }
                .onFailure { e -> _state.update { it.copy(error = e.message, isAuthenticating = false) } }
        }
    }

    fun startAuth(
        name: String,
        onAuthorizationUrl: (String) -> Unit,
    ) {
        val backend = backendProvider(agent) ?: return
        if (!_state.value.supportsOAuth || _state.value.isAuthenticating) return
        _state.update { it.copy(isAuthenticating = true, error = null) }
        viewModelScope.launch {
            runCatching { backend.mcpAuth(name) }
                .onSuccess { auth ->
                    runCatching { onAuthorizationUrl(auth.authorizationUrl) }
                        .onSuccess {
                            _state.update {
                                it.copy(
                                    oauthServerName = name,
                                    oauthCode = "",
                                    isAuthenticating = false,
                                )
                            }
                        }.onFailure { error ->
                            _state.update { it.copy(error = error.message, isAuthenticating = false) }
                        }
                }.onFailure { error ->
                    _state.update { it.copy(error = error.message, isAuthenticating = false) }
                }
        }
    }

    fun updateOAuthCode(value: String) {
        _state.update { it.copy(oauthCode = value) }
    }

    fun completeAuth() {
        val backend = backendProvider(agent) ?: return
        val current = _state.value
        val name = current.oauthServerName ?: return
        val code = current.oauthCode.trim()
        if (code.isEmpty() || current.isAuthenticating) return
        _state.update { it.copy(isAuthenticating = true, error = null) }
        viewModelScope.launch {
            runCatching { backend.mcpAuthCallback(name, code) }
                .onSuccess { status ->
                    if (status.status == "connected") {
                        _state.update {
                            it.copy(
                                oauthServerName = null,
                                oauthCode = "",
                                isAuthenticating = false,
                            )
                        }
                        refresh()
                    } else {
                        _state.update {
                            it.copy(
                                error = status.error ?: authFailedTemplate.format(status.status),
                                isAuthenticating = false,
                            )
                        }
                    }
                }.onFailure { error ->
                    _state.update { it.copy(error = error.message, isAuthenticating = false) }
                }
        }
    }

    fun dismissAuth() {
        if (_state.value.isAuthenticating) return
        _state.update { it.copy(oauthServerName = null, oauthCode = "") }
    }

    fun showAddDialog() {
        _state.update { it.copy(showAddDialog = true, addName = "", addCommand = "", addUrl = "") }
    }

    fun dismissAddDialog() {
        _state.update { it.copy(showAddDialog = false) }
    }

    fun updateAddName(value: String) {
        _state.update { it.copy(addName = value) }
    }

    fun updateAddCommand(value: String) {
        _state.update { it.copy(addCommand = value) }
    }

    fun updateAddUrl(value: String) {
        _state.update { it.copy(addUrl = value) }
    }

    fun addServer() {
        val backend = backendProvider(agent) ?: return
        val current = _state.value
        if (current.addName.isBlank() || (current.addCommand.isBlank() && current.addUrl.isBlank())) return
        viewModelScope.launch {
            _state.update { it.copy(isAdding = true) }
            val config =
                buildJsonObject {
                    if (current.addUrl.isNotBlank()) {
                        put("type", "remote")
                        put("url", current.addUrl.trim())
                    } else if (current.addCommand.isNotBlank()) {
                        put("type", "local")
                        put("command", buildJsonArray { commandParts(current.addCommand).forEach(::add) })
                    }
                }
            val body =
                if (agent == LocalAgent.OPEN_CODE) {
                    buildJsonObject {
                        put("name", current.addName.trim())
                        put("config", config)
                    }
                } else {
                    buildJsonObject {
                        put("name", current.addName.trim())
                        if (current.addUrl.isNotBlank()) {
                            put("type", "remote")
                            put("url", current.addUrl.trim())
                        } else if (current.addCommand.isNotBlank()) {
                            put("type", "local")
                            put("command", current.addCommand.trim())
                        }
                    }
                }
            runCatching { backend.addMcpServer(body) }
                .onSuccess {
                    _state.update { it.copy(showAddDialog = false, isAdding = false) }
                    refresh()
                }
                .onFailure { e ->
                    _state.update { it.copy(error = e.message, isAdding = false) }
                }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    private fun commandParts(command: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false

        fun finishPart() {
            if (current.isNotEmpty()) {
                parts += current.toString()
                current.clear()
            }
        }

        command.trim().forEach { character ->
            when {
                escaped -> {
                    current.append(character)
                    escaped = false
                }
                character == '\\' && quote != '\'' -> escaped = true
                quote != null && character == quote -> quote = null
                quote == null && (character == '\'' || character == '"') -> quote = character
                quote == null && character.isWhitespace() -> finishPart()
                else -> current.append(character)
            }
        }
        if (escaped) current.append('\\')
        finishPart()
        return parts
    }
}
