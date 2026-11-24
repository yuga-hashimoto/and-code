package com.yugahashimoto.andcode.data.repository

import com.yugahashimoto.andcode.data.settings.AppPreferences
import com.yugahashimoto.andcode.runtime.RuntimeRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.TimeUnit

/**
 * Keeps the chat drawer from growing without bound by archiving sessions that the user's settings
 * say are no longer worth keeping active: ones untouched for a configured number of days, and/or
 * the oldest ones once the active count passes a configured cap. Pinned chats are never archived.
 * Both rules are opt-in and off by default (see [AppPreferences.autoArchiveStaleEnabled] and
 * [AppPreferences.autoArchiveMaxSessionsEnabled]).
 */
class SessionAutoArchiver(
    private val registry: RuntimeRegistry,
    private val catalog: RuntimeCatalogRepository,
    private val activeSessionIds: () -> Set<String>,
    private val preferences: StateFlow<AppPreferences>,
    private val scope: CoroutineScope,
) {
    // Sessions an archive call was already issued for this process lifetime, so a session list that
    // has not yet caught up with a just-issued archive is not resubmitted on every recomputation.
    private val alreadyArchived = mutableSetOf<String>()

    fun start() {
        scope.launch {
            combine(catalog.allSessions, preferences) { sessions, prefs -> sessions to prefs }
                .collect { (sessions, prefs) -> maybeArchive(sessions, prefs) }
        }
    }

    private suspend fun maybeArchive(
        sessions: List<RuntimeSessionRef>,
        prefs: AppPreferences,
    ) {
        if (!prefs.autoArchiveStaleEnabled && !prefs.autoArchiveMaxSessionsEnabled) return

        // Never archive a chat that is actively running, pinned, or that an archive call was already
        // sent for - the latter avoids resubmitting the same id every time the list recomputes before
        // the runtime's own listSessions() catches up and drops it.
        val active = activeSessionIds()
        val candidates =
            sessions.filterNot {
                it.session.id in active || it.session.id in prefs.pinnedSessionIds || it.session.id in alreadyArchived
            }

        val stale =
            if (prefs.autoArchiveStaleEnabled) {
                val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(prefs.autoArchiveStaleDays.toLong())
                candidates.filter { updatedAt(it) < cutoff }
            } else {
                emptyList()
            }

        val overflow =
            if (prefs.autoArchiveMaxSessionsEnabled) {
                // A running session still occupies one of the allowed slots - it is just never the
                // one archived to make room, so the cap is honored by keeping fewer of the
                // archivable candidates rather than by ignoring active sessions altogether.
                val toKeep = (prefs.autoArchiveMaxSessions - active.size).coerceAtLeast(0)
                candidates.sortedByDescending(::updatedAt).drop(toKeep)
            } else {
                emptyList()
            }

        val toArchive = (stale + overflow).distinctBy { it.session.id }
        if (toArchive.isEmpty()) return

        val targets = registry.targets.value
        val archivedIds =
            supervisorScope {
                toArchive
                    .mapNotNull { ref -> targets.firstOrNull { it.id == ref.runtimeId }?.let { it to ref.session.id } }
                    .map { (target, sessionId) -> async { runCatching { target.archiveSession(sessionId) }.map { sessionId } } }
                    .mapNotNull { it.await().getOrNull() }
            }
        if (archivedIds.isEmpty()) return

        // Only mark ids that actually got archived: a missing target or a failed call must leave
        // the session eligible again on the next recomputation instead of being silently dropped
        // for the rest of the process lifetime.
        alreadyArchived += archivedIds
        catalog.refreshAllSessions()
    }

    private fun updatedAt(ref: RuntimeSessionRef): Long = ref.session.time.updated ?: ref.session.time.created
}
