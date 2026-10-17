package com.yugahashimoto.andcode.data.repository

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.core.api.selectablePrimaryAgents
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.RuntimeRegistry
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    /** The line of [error] that is about agents, so a later successful agent read can drop it. */
    val agentsError: String? = null,
    /**
     * True while the agent list is being read and none is on screen yet: a local OpenCode installing
     * its plugins can take tens of seconds on its first `GET /agent`, and the mode chip shows this
     * instead of looking dead.
     */
    val agentsLoading: Boolean = false,
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

    /**
     * The one agent retry in flight and the runtime it is for, so repeated taps on an empty mode
     * chip do not stack polls. Guarded by [agentRetryLock]: taps (main) and load (IO) both start it.
     */
    private var agentRetry: Pair<String, Job>? = null
    private val agentRetryLock = Any()

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
                    // A restarted local server can answer `/provider` with an empty `connected`
                    // set before it has re-read credentials. Adopting that blanked the model list
                    // the moment it was opened (issue #373); keep the list on screen instead. Like
                    // [load], only the target's own providers are ever kept.
                    val current = mutableState.value.providersFor(target)
                    if (shouldReplaceProviderCatalog(current, providers)) {
                        mutableState.update { it.copy(providers = providers) }
                    }
                    // Only a catalogue with a connected provider is worth remembering. An empty one
                    // is exactly the restarting-server shape above, and caching it would make the
                    // next launch - or the next load() - start from an empty list. (Not caching its
                    // `all` either means provider settings fall back to the chat runtime's list only
                    // while its own runtime is stopped with no providers connected.)
                    if (providers.connected.isNotEmpty()) {
                        val version =
                            mutableState.value.health?.version
                                ?: runCatching { target.health() }.getOrNull()?.version.orEmpty()
                        if (version.isNotBlank()) providerCache?.write(target.id, version, providers)
                    }
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
                    RuntimeCatalogState(runtime = target, isRefreshing = true, agentsLoading = true)
                } else {
                    current.copy(
                        runtime = target,
                        isRefreshing = true,
                        error = null,
                        agentsLoading = !current.agents.hasSelectableAgent(),
                    )
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
            // Only this runtime's own providers are ever kept: [previousProviders] is taken from the
            // state only while it still belongs to [target], so a switch can never leave one
            // runtime's models showing under another. A fresh catalogue with an empty `connected`
            // set is a restarting server, not a real empty list, so it must not replace what is on
            // screen (issue #373).
            val previousProviders = mutableState.value.providersFor(target)
            val storedProviders = catalog.providers.getOrNull()
            val providersToUse =
                if (storedProviders != null && shouldReplaceProviderCatalog(previousProviders, storedProviders)) {
                    storedProviders
                } else {
                    previousProviders
                }
            // The cache never records an empty `connected` set: that is the restarting-server shape,
            // and writing it would make the next load() read it straight back and adopt the empty
            // list the guard above just refused.
            if (storedProviders != null && storedProviders.connected.isNotEmpty()) {
                connection.getOrNull()?.version?.let { version ->
                    if (providerCache?.isStale(target.id, version, storedProviders) != false) {
                        providerCache?.write(target.id, version, storedProviders)
                    }
                }
            }
            // Like the providers above: a read that failed or offered no agent to pick must not blank
            // a list this runtime already showed, or a flaky `GET /agent` empties a working picker.
            val loadedAgents = catalog.agents.getOrNull()
            // Read and written in one update: a background agent retry can land between this load's
            // fetch and its write, and taking the list from before that would put an empty one back.
            mutableState.update { current ->
                val agentsToUse =
                    if (loadedAgents.hasSelectableAgent()) {
                        loadedAgents.orEmpty()
                    } else {
                        current.agentsFor(target).ifEmpty { loadedAgents.orEmpty() }
                    }
                val agentsFound = agentsToUse.hasSelectableAgent()
                // A list kept on screen - from an earlier load or a retry that already succeeded -
                // carries no failure of its own, so neither the chip nor the "Agents: ..." line in
                // the catalogue error reports this read's failure.
                val readFailure = catalog.agentsFailure(messages)
                val agentsFailure = readFailure.takeUnless { agentsFound && !loadedAgents.hasSelectableAgent() }
                val errors = catalog.failures(messages).filterNot { agentsFailure == null && it == readFailure }
                RuntimeCatalogState(
                    runtime = target,
                    health = connection.getOrNull(),
                    sessions = catalog.sessions.getOrDefault(emptyList()),
                    providers = providersToUse,
                    agents = agentsToUse,
                    workspaces = catalog.workspaces.getOrDefault(emptyList()),
                    isRefreshing = false,
                    error = errors.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                    agentsError = agentsFailure,
                    // Still loading when nothing selectable came back: the retry below takes over.
                    agentsLoading = !agentsFound,
                )
            }
        }
        // Outside the lock: a local OpenCode installing its plugins can take tens of seconds per
        // `GET /agent`, and the rest of the catalogue - and every other refresh - must not wait on it.
        if (!mutableState.value.agentsFor(target).hasSelectableAgent()) refreshAgents()
    }

    /**
     * The runtime's agents, retried a few times while the answer fails or offers nothing to pick.
     *
     * A local OpenCode with plugins (oh-my-opencode-slim, opencode-mem, ...) installs them on its
     * first `GET /agent` after a start, which can take tens of seconds or fail outright. That one
     * answer used to be final until something else refreshed the catalogue, so the agent chip kept
     * showing "build" over an empty menu that nothing would ever refill.
     */
    private suspend fun loadAgents(target: RuntimeTarget): Result<List<OpenCodeAgent>> {
        var result = runCatching { target.listAgents() }
        repeat(AGENT_RETRIES) {
            if (result.getOrNull().hasSelectableAgent() || registry.selected.value?.id != target.id) return result
            delay(AGENT_RETRY_DELAY_MILLIS)
            result = runCatching { target.listAgents() }
        }
        return result
    }

    /**
     * Re-reads the agent list in the background until the runtime offers one to pick, for a load
     * whose `GET /agent` failed or for a mode chip tapped while empty.
     *
     * Only one retry runs at a time. A runtime that never connected gets a full [refresh] instead,
     * so the chip does not fill in agents over an otherwise empty, errored catalogue. The rest of the
     * catalogue is left alone, and a failed retry keeps whatever agents are already on screen.
     */
    fun refreshAgents() {
        val target = registry.selected.value ?: return
        val current = mutableState.value
        if (current.runtime?.id == target.id && current.health == null) {
            // Not connected yet. A load still in flight will retry the agents itself once it
            // connects; a load that gave up gets a full refresh, not a poll of a dead server.
            if (!current.isRefreshing) {
                // Disables the "tap to retry" item straight away, so repeated taps cannot queue one
                // full reconnect each behind the refresh lock before the load gets to run.
                mutableState.update { if (it.runtime?.id == target.id) it.copy(agentsLoading = true) else it }
                refresh()
            }
            return
        }
        synchronized(agentRetryLock) {
            val running = agentRetry
            if (running != null && running.second.isActive) {
                // Still polling this runtime: let it finish. A retry left over from the runtime the
                // user switched away from would only give up, so it must not block this one's.
                if (running.first == target.id) return
                running.second.cancel()
            }
            mutableState.update {
                if (it.runtime?.id == target.id && !it.agents.hasSelectableAgent()) it.copy(agentsLoading = true) else it
            }
            agentRetry =
                target.id to
                scope.launch {
                    val list = loadAgents(target).getOrNull()?.takeIf { it.hasSelectableAgent() }
                    if (registry.selected.value?.id != target.id) return@launch
                    mutableState.update {
                        when {
                            it.runtime?.id != target.id -> it
                            list != null -> it.withAgents(list)
                            // A load still in flight for this runtime keeps the chip loading: it reads the
                            // agents again itself, and a "tap to retry" now would only race it.
                            else -> it.copy(agentsLoading = it.isRefreshing)
                        }
                    }
                }
        }
    }

    private data class LoadedCatalog(
        val sessions: Result<List<OpenCodeSession>>,
        val providers: Result<ProviderCatalog>,
        val agents: Result<List<OpenCodeAgent>>,
        val workspaces: Result<List<WorkspaceRef>>,
    ) {
        /**
         * One line, so [withAgents] can find it again in [RuntimeCatalogState.error]: decoding errors
         * carry the JSON input on a second line, which a line-by-line removal would never match.
         */
        fun agentsFailure(messages: RuntimeCatalogMessages): String? =
            agents.exceptionOrNull()?.let {
                messages.agents(it.safeMessage(messages.connectionFailed).replace(Regex("\\s*\\n\\s*"), " "))
            }

        fun failures(messages: RuntimeCatalogMessages): List<String> =
            listOfNotNull(
                sessions.exceptionOrNull()?.let { messages.sessions(it.safeMessage(messages.connectionFailed)) },
                providers.exceptionOrNull()?.let { messages.providers(it.safeMessage(messages.connectionFailed)) },
                agentsFailure(messages),
                workspaces.exceptionOrNull()?.let { messages.workspaces(it.safeMessage(messages.connectionFailed)) },
            )
    }

    private companion object {
        const val CONNECT_RETRIES = 6
        const val CONNECT_RETRY_DELAY_MILLIS = 2_500L
        const val AGENT_RETRIES = 4
        const val AGENT_RETRY_DELAY_MILLIS = 3_000L
    }
}

private fun Throwable?.safeMessage(fallback: String): String = this?.message?.takeIf { it.isNotBlank() } ?: fallback

/**
 * The providers on screen, but only while the state still belongs to [target]: another runtime's
 * catalogue must never be treated as this one's, so its models cannot show under a runtime that
 * has never heard of them.
 */
private fun RuntimeCatalogState.providersFor(target: RuntimeTarget): ProviderCatalog =
    takeIf { runtime?.id == target.id }?.providers ?: ProviderCatalog()

/**
 * Whether a freshly fetched [fresh] catalogue may replace the [current] one for what the user sees.
 *
 * OpenCode's local server restarts under memory pressure and, until it has re-read its
 * credentials, answers `/provider` with a populated `all` but an empty `connected` set. The picker
 * only lists providers in `connected`, so adopting that transient response emptied the model list
 * (issue #373). Keep the current list instead, and only adopt [fresh] when it carries at least one
 * connected provider - or when there is nothing to lose because [current] has none either.
 *
 * The disk cache never stores an empty-`connected` catalogue either (see [load] and
 * [refreshProvidersOnly]), so a later [load] cannot read that shape back and defeat this guard. A
 * genuine disconnect is unaffected: OpenCode keeps reporting the provider as connected (#321), so
 * the response cached there is non-empty, and the picker hides it through its own locally
 * disconnected set.
 */
internal fun shouldReplaceProviderCatalog(
    current: ProviderCatalog,
    fresh: ProviderCatalog,
): Boolean = fresh.connected.isNotEmpty() || current.connected.isEmpty()

/** The agents on screen, but only while the state still belongs to [target]; see [providersFor]. */
private fun RuntimeCatalogState.agentsFor(target: RuntimeTarget): List<OpenCodeAgent> =
    if (runtime?.id == target.id) agents else emptyList()

/** Whether this answer offers at least one agent the mode chip can show. */
private fun List<OpenCodeAgent>?.hasSelectableAgent(): Boolean = this?.selectablePrimaryAgents()?.isNotEmpty() == true

/** This state with [agents] in place, and the earlier agents failure removed from [error]. */
private fun RuntimeCatalogState.withAgents(agents: List<OpenCodeAgent>): RuntimeCatalogState {
    val remaining =
        error?.lineSequence()?.filterNot { it == agentsError }?.joinToString("\n")?.takeIf { it.isNotBlank() }
    return copy(agents = agents, error = remaining, agentsError = null, agentsLoading = false)
}
