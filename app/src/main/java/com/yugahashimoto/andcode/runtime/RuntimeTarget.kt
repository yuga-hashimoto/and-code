package com.yugahashimoto.andcode.runtime

import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeProject
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RuntimeType {
    LOCAL,
    REMOTE,
}

sealed interface RuntimeState {
    data object Disconnected : RuntimeState

    data object Connecting : RuntimeState

    data class Connected(val version: String) : RuntimeState

    data class Unavailable(val reason: String) : RuntimeState

    data class Failed(val message: String) : RuntimeState
}

data class WorkspaceRef(
    val id: String,
    val name: String,
    val path: String,
)

internal fun mergeWorkspaceRefs(
    currentDirectory: String?,
    sessions: List<OpenCodeSession>,
    projects: List<OpenCodeProject>,
): List<WorkspaceRef> {
    val workspaces = linkedMapOf<String, WorkspaceRef>()

    fun add(
        path: String?,
        preferredName: String? = null,
    ) {
        val normalized = path?.trim()?.takeIf { it.isNotEmpty() && it != "/" } ?: return
        val fallbackName =
            normalized
                .trimEnd('/', '\\')
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .ifBlank { normalized }
        val existing = workspaces[normalized]
        val name =
            preferredName?.trim()?.takeIf(String::isNotEmpty)
                ?: existing?.name
                ?: fallbackName
        workspaces[normalized] =
            WorkspaceRef(
                id = normalized,
                name = name,
                path = normalized,
            )
    }

    add(currentDirectory)
    sessions.forEach { add(it.directory) }
    projects
        .asSequence()
        .filterNot { it.id == "global" }
        .forEach { add(it.worktree, it.name) }

    return workspaces.values.toList()
}

internal fun mergeSessionLists(sessionLists: List<List<OpenCodeSession>>): List<OpenCodeSession> {
    val byId = linkedMapOf<String, OpenCodeSession>()
    sessionLists.flatten().forEach { session ->
        val existing = byId[session.id]
        val sessionUpdatedAt = session.time.updated ?: session.time.created
        val existingUpdatedAt = existing?.let { it.time.updated ?: it.time.created } ?: Long.MIN_VALUE
        if (existing == null || sessionUpdatedAt >= existingUpdatedAt) {
            byId[session.id] = session
        }
    }
    return byId.values.sortedByDescending { it.time.updated ?: it.time.created }
}

interface RuntimeTarget : OpenCodeBackend {
    val type: RuntimeType
    val state: StateFlow<RuntimeState>

    /**
     * Which Android-local agent this target drives, or null for remote targets.
     *
     * The UI branches on this rather than on [id] so that adding an agent never means hunting for
     * hardcoded id comparisons.
     */
    val agent: LocalAgent?
        get() = null

    val capabilities: RuntimeCapabilities
        get() = RuntimeCapabilities()

    /** Latest known subscription rate-limit snapshot for this target, or null when unsupported/unknown. */
    val rateLimits: StateFlow<SubscriptionRateLimit?>
        get() = emptyRateLimits

    /** True when this target can report subscription rate limits (actively or passively). */
    val supportsRateLimits: Boolean
        get() = false

    /** True when [refreshRateLimits] can actively fetch updated rate limits on demand. */
    val canRefreshRateLimits: Boolean
        get() = false

    /** Refreshes [rateLimits] on demand when supported; no-op otherwise. */
    suspend fun refreshRateLimits() {}

    suspend fun connect(): Result<OpenCodeHealth>

    fun disconnect()

    suspend fun listWorkspaces(): List<WorkspaceRef>
}

private val emptyRateLimits: StateFlow<SubscriptionRateLimit?> = MutableStateFlow<SubscriptionRateLimit?>(null).asStateFlow()

interface RuntimeConnectionStore {
    var selectedRuntimeId: String?

    fun connections(): List<com.yugahashimoto.andcode.data.connection.ConnectionProfile>

    fun upsertConnection(profile: com.yugahashimoto.andcode.data.connection.ConnectionProfile)

    fun deleteConnection(id: String)
}
