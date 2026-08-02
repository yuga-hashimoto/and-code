package com.yugahashimoto.andcode.data.repository

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.RuntimeRegistry
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A chat together with the runtime that owns it; see [RuntimeCatalogRepository.allSessions]. */
data class RuntimeSessionRef(
    val runtimeId: String,
    val agent: LocalAgent?,
    val session: OpenCodeSession,
)

data class RuntimeCatalogState(
    val runtime: RuntimeTarget? = null,
    val health: OpenCodeHealth? = null,
    val sessions: List<OpenCodeSession> = emptyList(),
    val providers: ProviderCatalog = ProviderCatalog(),
    val agents: List<OpenCodeAgent> = emptyList(),
    val workspaces: List<WorkspaceRef> = emptyList(),
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

class RuntimeCatalogRepository(
    private val registry: RuntimeRegistry,
    private val scope: CoroutineScope,
    private val providerCache: ProviderCatalogCache? = null,
    private val messages: RuntimeCatalogMessages = RuntimeCatalogMessages,
) {
    private val mutableState = MutableStateFlow(RuntimeCatalogState(runtime = registry.selected.value))

    /**
     * Last catalogue stored for [runtimeId], whatever wrote it.
     *
     * Provider settings ask about the runtime that owns providers, which the user may have stopped
     * — it is not the one their chat is on. Without this the screen has nothing to show and falls
     * back to the other agent's models, which is worse than a slightly old list.
     */
    fun cachedProviders(runtimeId: String): ProviderCatalog? = providerCache?.readAny(runtimeId)

    val state: StateFlow<RuntimeCatalogState> = mutableState.asStateFlow()
    private val refreshMutex = Mutex()

    private val mutableAllSessions = MutableStateFlow<List<RuntimeSessionRef>>(emptyList())

    /**
     * Every runtime's chats in one list, newest first.
     *
     * [state] carries only the selected runtime's sessions, which is right for a chat screen but
     * wrong for the drawer: switching agent there made the whole history appear to vanish. Each
     * entry keeps the runtime it came from so a chat can be opened on the agent that owns it.
     */
    val allSessions: StateFlow<List<RuntimeSessionRef>> = mutableAllSessions.asStateFlow()

    init {
        scope.launch {
            registry.selected.collectLatest { target ->
                mutableState.value = RuntimeCatalogState(runtime = target)
                if (target != null) load(target)
            }
        }
        scope.launch {
            registry.targets.collectLatest { refreshAllSessions() }
        }
    }

    fun refresh() {
        val target = registry.selected.value ?: return
        scope.launch { load(target) }
        refreshAllSessions()
    }

    /** Refresh only the session list for surfaces that show recent chats. */
    fun refreshSessionsOnly() {
        val target = registry.selected.value ?: return
        scope.launch {
            runCatching { target.listSessions() }
                .onSuccess { sessions ->
                    if (registry.selected.value?.id == target.id) {
                        mutableState.update { it.copy(sessions = sessions) }
                    }
                }
        }
        refreshAllSessions()
    }

    /**
     * Asks every runtime for its chats, in parallel and independently.
     *
     * A runtime that is stopped or unreachable contributes nothing rather than failing the whole
     * list - the local agents answer from their own on-disk records and cost almost nothing, while
     * a remote endpoint may simply be off.
     */
    fun refreshAllSessions() {
        scope.launch {
            val targets = registry.targets.value
            val loaded =
                supervisorScope {
                    targets
                        .map { target -> async { target to runCatching { target.listSessions() }.getOrDefault(emptyList()) } }
                        .map { it.await() }
                }
            mutableAllSessions.value =
                loaded
                    .flatMap { (target, sessions) ->
                        sessions.map { session -> RuntimeSessionRef(target.id, target.agent, session) }
                    }
                    .sortedByDescending { it.session.time.updated ?: it.session.time.created }
        }
    }

    fun refreshProvidersOnly() {
        val target = registry.selected.value ?: return
        scope.launch {
            runCatching { target.listProviders() }
                .onSuccess { providers ->
                    if (registry.selected.value?.id != target.id) return@onSuccess
                    mutableState.update { it.copy(providers = providers) }
                    // The version keys the cache, and it is often still unknown here: the first
                    // load runs before the runtime has finished starting, so its connect failed.
                    val version =
                        mutableState.value.health?.version
                            ?: runCatching { target.health() }.getOrNull()?.version.orEmpty()
                    if (version.isNotBlank()) providerCache?.write(target.id, version, providers)
                }
        }
    }

    private suspend fun load(target: RuntimeTarget) {
        refreshMutex.withLock {
            if (registry.selected.value?.id != target.id) return
            mutableState.update { current ->
                // Switching runtimes discards the old catalogue outright. Keeping it meant the
                // picker showed the new agent's name over the previous agent's models for as long
                // as the fetch took — seconds, while a stopped runtime is retried — and OpenCode
                // models appeared under Claude Code.
                if (current.runtime?.id != target.id) {
                    RuntimeCatalogState(runtime = target, isRefreshing = true)
                } else {
                    current.copy(runtime = target, isRefreshing = true, error = null)
                }
            }

            // Show whatever was cached before even trying to connect: the runtime takes seconds to
            // start, and an empty picker for that whole window is what made this feel slow.
            providerCache?.readAny(target.id)?.let { cached ->
                mutableState.update { it.copy(runtime = target, providers = cached) }
            }

            // The local runtime is usually still starting when the app opens, so a single attempt
            // fails and nothing ever retried: the catalogue stayed empty until something else asked
            // for it, which is what made the model picker look slow.
            var connection = target.connect()
            repeat(CONNECT_RETRIES) {
                if (connection.isSuccess || registry.selected.value?.id != target.id) return@repeat
                delay(CONNECT_RETRY_DELAY_MILLIS)
                connection = target.connect()
            }
            if (connection.isFailure) {
                mutableState.value =
                    RuntimeCatalogState(
                        runtime = target,
                        isRefreshing = false,
                        error = connection.exceptionOrNull().safeMessage(messages.connectionFailed),
                    )
                return
            }

            val catalog =
                supervisorScope {
                    val sessions = async { runCatching { target.listSessions() } }
                    val providers = async { runCatching { target.listProviders() } }
                    val agents = async { runCatching { target.listAgents() } }
                    val workspaces = async { runCatching { target.listWorkspaces() } }
                    LoadedCatalog(
                        sessions = sessions.await(),
                        providers = providers.await(),
                        agents = agents.await(),
                        workspaces = workspaces.await(),
                    )
                }

            if (registry.selected.value?.id != target.id) return
            connection.getOrNull()?.version?.let { version ->
                catalog.providers.getOrNull()?.let { providers ->
                    if (providerCache?.isStale(target.id, version, providers) != false) {
                        providerCache?.write(target.id, version, providers)
                    }
                }
            }
            val errors = catalog.failures(messages)
            mutableState.value =
                RuntimeCatalogState(
                    runtime = target,
                    health = connection.getOrNull(),
                    sessions = catalog.sessions.getOrDefault(emptyList()),
                    // Only this runtime's own providers: the fallback exists so a failed refresh
                    // does not blank a list the user is looking at, never to borrow another
                    // runtime's catalogue.
                    providers =
                        catalog.providers.getOrElse {
                            mutableState.value.takeIf { it.runtime?.id == target.id }?.providers ?: ProviderCatalog()
                        },
                    agents = catalog.agents.getOrDefault(emptyList()),
                    workspaces = catalog.workspaces.getOrDefault(emptyList()),
                    isRefreshing = false,
                    error = errors.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                )
        }
    }

    private data class LoadedCatalog(
        val sessions: Result<List<OpenCodeSession>>,
        val providers: Result<ProviderCatalog>,
        val agents: Result<List<OpenCodeAgent>>,
        val workspaces: Result<List<WorkspaceRef>>,
    ) {
        fun failures(messages: RuntimeCatalogMessages): List<String> =
            listOfNotNull(
                sessions.exceptionOrNull()?.let { messages.sessions(it.safeMessage(messages.connectionFailed)) },
                providers.exceptionOrNull()?.let { messages.providers(it.safeMessage(messages.connectionFailed)) },
                agents.exceptionOrNull()?.let { messages.agents(it.safeMessage(messages.connectionFailed)) },
                workspaces.exceptionOrNull()?.let { messages.workspaces(it.safeMessage(messages.connectionFailed)) },
            )
    }

    private companion object {
        const val CONNECT_RETRIES = 6
        const val CONNECT_RETRY_DELAY_MILLIS = 2_500L
    }
}

private fun Throwable?.safeMessage(fallback: String): String = this?.message?.takeIf { it.isNotBlank() } ?: fallback
