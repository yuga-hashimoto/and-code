package com.yugahashimoto.andcode.ui

import com.yugahashimoto.andcode.data.repository.RuntimeSessionRef
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.ui.components.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Unpinned chats beyond this many are left out of the drawer; pinned chats are always kept. */
private const val MAX_UNPINNED_SESSIONS = 25

/** How long a removal waits for the session catalog to catch up before letting the row reappear. */
internal const val REMOVAL_REFRESH_WAIT_MILLIS = 5_000L

data class DrawerRecentSession(
    val id: String,
    val title: String,
    val relativeTime: String,
    val directory: String? = null,
    val isActive: Boolean = false,
    val hasUnread: Boolean = false,
    val status: SessionStatus = SessionStatus.IDLE,
    val hasAttention: Boolean = false,
    /** The runtime that owns this chat - opening it has to switch to that runtime first. */
    val runtimeId: String? = null,
    /** Null for a remote runtime, which is not one of the three local agents. */
    val agent: LocalAgent? = null,
    /** Pinned chats live in their own section and are exempt from the unpinned cap. */
    val isPinned: Boolean = false,
)

/** An agent offered by the drawer's switcher. */
data class DrawerAgent(
    val runtimeId: String,
    val agent: LocalAgent,
)

/**
 * Turns the catalog into drawer rows. Top-level chats only, and pinned ones are kept regardless of
 * the cap so pinning one never has to push another chat out to stay visible. Catalog order is
 * preserved for both groups.
 */
internal fun drawerRecentSessions(
    allSessions: List<RuntimeSessionRef>,
    activeSessionIds: Set<String>,
    completedSessionIds: Set<String>,
    pinnedSessionIds: Set<String>,
    pendingRemoval: Set<String>,
    maxUnpinned: Int = MAX_UNPINNED_SESSIONS,
    timeLabel: (Long) -> String,
): List<DrawerRecentSession> {
    var unpinnedCount = 0
    return allSessions.mapNotNull { ref ->
        val session = ref.session
        if (session.parentId != null || session.id in pendingRemoval) return@mapNotNull null

        val pinned = session.id in pinnedSessionIds
        if (!pinned) {
            if (unpinnedCount >= maxUnpinned) return@mapNotNull null
            unpinnedCount++
        }

        DrawerRecentSession(
            id = session.id,
            title = session.title.ifBlank { session.slug ?: session.id },
            relativeTime = timeLabel(session.time.updated ?: session.time.created),
            directory = session.directory,
            isActive = session.id in activeSessionIds,
            hasUnread = session.id in completedSessionIds,
            status =
                when {
                    session.id in activeSessionIds -> SessionStatus.RUNNING
                    session.id in completedSessionIds -> SessionStatus.COMPLETED_UNREAD
                    else -> SessionStatus.IDLE
                },
            runtimeId = ref.runtimeId,
            agent = ref.agent,
            isPinned = pinned,
        )
    }
}

/** Every term has to appear somewhere in the title or directory, so "fix api" narrows the list. */
internal fun filterDrawerSessions(
    sessions: List<DrawerRecentSession>,
    query: String,
): List<DrawerRecentSession> {
    val terms = query.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (terms.isEmpty()) return sessions
    return sessions.filter { session ->
        val haystack = "${session.title} ${session.directory.orEmpty()}"
        terms.all { term -> haystack.contains(term, ignoreCase = true) }
    }
}

internal fun toggledSection(
    collapsed: Set<String>,
    section: String,
): Set<String> = if (section in collapsed) collapsed - section else collapsed + section

/** The browse view keeps the same cap the catalog builder used to apply, without hiding search hits. */
internal fun browsableSessions(
    sessions: List<DrawerRecentSession>,
    maxUnpinned: Int = MAX_UNPINNED_SESSIONS,
): List<DrawerRecentSession> {
    var unpinnedCount = 0
    return sessions.filter { session ->
        if (session.isPinned) {
            true
        } else if (unpinnedCount < maxUnpinned) {
            unpinnedCount++
            true
        } else {
            false
        }
    }
}

/** Drops selected chats that the current search no longer shows; a blank query shows them all. */
internal fun retainVisibleSelection(
    selectedSessionIds: Set<String>,
    sessions: List<DrawerRecentSession>,
    query: String,
): Set<String> {
    if (query.isBlank()) return selectedSessionIds
    val visibleIds = filterDrawerSessions(sessions, query).map { it.id }.toSet()
    return selectedSessionIds.intersect(visibleIds)
}

/**
 * Suspends until [sessions] no longer lists [id], or [timeoutMillis] passes (the removal did not
 * take). The caller keeps its pending-removal mark until this returns, so a slow catalog cannot
 * flash the removed row back into the drawer.
 */
internal suspend fun awaitSessionGone(
    sessions: Flow<List<RuntimeSessionRef>>,
    id: String,
    timeoutMillis: Long = REMOVAL_REFRESH_WAIT_MILLIS,
) {
    withTimeoutOrNull(timeoutMillis) {
        sessions.first { refs -> refs.none { it.session.id == id } }
    }
}
