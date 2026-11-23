package com.yugahashimoto.andcode.ui

import com.yugahashimoto.andcode.data.repository.RuntimeSessionRef
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.ui.components.SessionStatus

/** Unpinned chats beyond this many are left out of the drawer; pinned chats are always kept. */
private const val MAX_UNPINNED_SESSIONS = 25

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
    timeLabel: (Long) -> String,
): List<DrawerRecentSession> {
    var unpinnedCount = 0
    return allSessions.mapNotNull { ref ->
        val session = ref.session
        if (session.parentId != null || session.id in pendingRemoval) return@mapNotNull null

        val pinned = session.id in pinnedSessionIds
        if (!pinned) {
            if (unpinnedCount >= MAX_UNPINNED_SESSIONS) return@mapNotNull null
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
