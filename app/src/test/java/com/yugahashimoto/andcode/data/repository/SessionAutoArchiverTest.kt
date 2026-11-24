package com.yugahashimoto.andcode.data.repository

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.data.connection.ConnectionProfile
import com.yugahashimoto.andcode.data.settings.AppPreferences
import com.yugahashimoto.andcode.runtime.BackendKind
import com.yugahashimoto.andcode.runtime.PermissionResponse
import com.yugahashimoto.andcode.runtime.RuntimeConnectionStore
import com.yugahashimoto.andcode.runtime.RuntimeRegistry
import com.yugahashimoto.andcode.runtime.RuntimeState
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.runtime.RuntimeType
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class SessionAutoArchiverTest {
    @Test
    fun `does nothing while both rules are disabled`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget("mac", sessions = listOf(session("old", updatedDaysAgo = 90)))
            val registry = registry(target)
            val catalog = RuntimeCatalogRepository(registry, TestScope(dispatcher))
            val preferences = fakePreferences()
            val archiver =
                SessionAutoArchiver(registry, catalog, activeSessionIds = { emptySet() }, preferences, TestScope(dispatcher))

            archiver.start()
            advanceUntilIdle()

            assertTrue(target.archivedIds.isEmpty())
        }

    @Test
    fun `archives sessions untouched past the configured number of days`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target =
                FakeTarget(
                    "mac",
                    sessions =
                        listOf(
                            session("old", updatedDaysAgo = 45),
                            session("fresh", updatedDaysAgo = 1),
                        ),
                )
            val registry = registry(target)
            val catalog = RuntimeCatalogRepository(registry, TestScope(dispatcher))
            val preferences = fakePreferences(autoArchiveStaleEnabled = true, autoArchiveStaleDays = 30)
            val archiver =
                SessionAutoArchiver(registry, catalog, activeSessionIds = { emptySet() }, preferences, TestScope(dispatcher))

            archiver.start()
            advanceUntilIdle()

            assertEquals(listOf("old"), target.archivedIds)
        }

    @Test
    fun `archives the oldest sessions once the active count passes the configured cap`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target =
                FakeTarget(
                    "mac",
                    sessions =
                        listOf(
                            session("newest", updatedDaysAgo = 1),
                            session("middle", updatedDaysAgo = 2),
                            session("oldest", updatedDaysAgo = 3),
                        ),
                )
            val registry = registry(target)
            val catalog = RuntimeCatalogRepository(registry, TestScope(dispatcher))
            val preferences = fakePreferences(autoArchiveMaxSessionsEnabled = true, autoArchiveMaxSessions = 2)
            val archiver =
                SessionAutoArchiver(registry, catalog, activeSessionIds = { emptySet() }, preferences, TestScope(dispatcher))

            archiver.start()
            advanceUntilIdle()

            assertEquals(listOf("oldest"), target.archivedIds)
        }

    @Test
    fun `a running session still counts toward the active cap`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target =
                FakeTarget(
                    "mac",
                    sessions =
                        listOf(
                            session("running", updatedDaysAgo = 1),
                            session("kept", updatedDaysAgo = 2),
                            session("archived", updatedDaysAgo = 3),
                        ),
                )
            val registry = registry(target)
            val catalog = RuntimeCatalogRepository(registry, TestScope(dispatcher))
            val preferences = fakePreferences(autoArchiveMaxSessionsEnabled = true, autoArchiveMaxSessions = 2)
            val archiver =
                SessionAutoArchiver(registry, catalog, activeSessionIds = { setOf("running") }, preferences, TestScope(dispatcher))

            archiver.start()
            advanceUntilIdle()

            // The cap is 2: "running" occupies one slot without ever being archived, so only one of
            // the two remaining sessions may stay active and the older one is archived.
            assertEquals(listOf("archived"), target.archivedIds)
        }

    @Test
    fun `never archives a session that is actively running`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget("mac", sessions = listOf(session("old", updatedDaysAgo = 90)))
            val registry = registry(target)
            val catalog = RuntimeCatalogRepository(registry, TestScope(dispatcher))
            val preferences = fakePreferences(autoArchiveStaleEnabled = true, autoArchiveStaleDays = 30)
            val archiver =
                SessionAutoArchiver(registry, catalog, activeSessionIds = { setOf("old") }, preferences, TestScope(dispatcher))

            archiver.start()
            advanceUntilIdle()

            assertTrue(target.archivedIds.isEmpty())
        }

    @Test
    fun `never archives a stale session that is pinned`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target =
                FakeTarget(
                    "mac",
                    sessions =
                        listOf(
                            session("pinned", updatedDaysAgo = 90),
                            session("stale", updatedDaysAgo = 90),
                        ),
                )
            val registry = registry(target)
            val catalog = RuntimeCatalogRepository(registry, TestScope(dispatcher))
            val preferences =
                fakePreferences(
                    autoArchiveStaleEnabled = true,
                    autoArchiveStaleDays = 30,
                    pinnedSessionIds = setOf("pinned"),
                )
            val archiver =
                SessionAutoArchiver(registry, catalog, activeSessionIds = { emptySet() }, preferences, TestScope(dispatcher))

            archiver.start()
            advanceUntilIdle()

            assertEquals(listOf("stale"), target.archivedIds)
        }

    @Test
    fun `a session is retried after a failed archive call instead of being skipped forever`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget("mac", sessions = listOf(session("old", updatedDaysAgo = 90)))
            target.shouldFail = true
            val registry = registry(target)
            val catalog = RuntimeCatalogRepository(registry, TestScope(dispatcher))
            val preferences = fakePreferences(autoArchiveStaleEnabled = true, autoArchiveStaleDays = 30)
            val archiver =
                SessionAutoArchiver(registry, catalog, activeSessionIds = { emptySet() }, preferences, TestScope(dispatcher))

            archiver.start()
            advanceUntilIdle()
            assertTrue(target.archivedIds.isEmpty())

            // A settings change (or any other session-list recomputation) gives the failed session
            // another chance instead of leaving it permanently excluded.
            target.shouldFail = false
            preferences.value = preferences.value.copy(autoArchiveStaleDays = 31)
            advanceUntilIdle()

            assertEquals(listOf("old"), target.archivedIds)
        }

    private fun session(
        id: String,
        updatedDaysAgo: Long,
    ) = OpenCodeSession(
        id = id,
        title = id,
        time =
            OpenCodeTime(
                created = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(updatedDaysAgo),
                updated = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(updatedDaysAgo),
            ),
    )

    private fun registry(target: RuntimeTarget) =
        RuntimeRegistry(
            store = FakeStore(selectedRuntimeId = target.id),
            localTarget = target,
            remoteFactory = { error("unused") },
        )

    private fun fakePreferences(
        autoArchiveStaleEnabled: Boolean = false,
        autoArchiveStaleDays: Int = 30,
        autoArchiveMaxSessionsEnabled: Boolean = false,
        autoArchiveMaxSessions: Int = 50,
        pinnedSessionIds: Set<String> = emptySet(),
    ): MutableStateFlow<AppPreferences> =
        MutableStateFlow(
            AppPreferences(
                autoArchiveStaleEnabled = autoArchiveStaleEnabled,
                autoArchiveStaleDays = autoArchiveStaleDays,
                autoArchiveMaxSessionsEnabled = autoArchiveMaxSessionsEnabled,
                autoArchiveMaxSessions = autoArchiveMaxSessions,
                pinnedSessionIds = pinnedSessionIds,
            ),
        )

    private class FakeStore(
        profiles: List<ConnectionProfile> = emptyList(),
        override var selectedRuntimeId: String? = null,
    ) : RuntimeConnectionStore {
        private val values = profiles.toMutableList()

        override fun connections(): List<ConnectionProfile> = values.toList()

        override fun upsertConnection(profile: ConnectionProfile) {
            values.removeAll { it.id == profile.id }
            values += profile
        }

        override fun deleteConnection(id: String) {
            values.removeAll { it.id == id }
        }
    }

    private class FakeTarget(
        override val id: String,
        private val sessions: List<OpenCodeSession>,
    ) : RuntimeTarget {
        override val type: RuntimeType = RuntimeType.LOCAL
        override val displayName: String = id
        override val kind: BackendKind = BackendKind.LOCAL
        override val state = MutableStateFlow<RuntimeState>(RuntimeState.Disconnected)

        val archivedIds = mutableListOf<String>()
        var shouldFail = false

        override suspend fun connect(): Result<OpenCodeHealth> = Result.success(OpenCodeHealth(true, "1.0"))

        override fun disconnect() = Unit

        override suspend fun health(): OpenCodeHealth = OpenCodeHealth(true, "1.0")

        override suspend fun listSessions(directory: String?): List<OpenCodeSession> = sessions

        override suspend fun createSession(
            title: String?,
            directory: String?,
        ): OpenCodeSession = error("unused")

        override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = emptyList()

        override suspend fun listProviders(): ProviderCatalog = ProviderCatalog()

        override suspend fun listAgents(): List<OpenCodeAgent> = emptyList()

        override suspend fun listWorkspaces(): List<WorkspaceRef> = emptyList()

        override suspend fun sendMessage(
            sessionId: String,
            request: PromptRequest,
        ) = Unit

        override suspend fun abortSession(sessionId: String): Boolean = true

        override suspend fun respondToPermission(
            sessionId: String,
            permissionId: String,
            response: PermissionResponse,
            remember: Boolean,
        ): Boolean = true

        override suspend fun archiveSession(sessionId: String): OpenCodeSession {
            if (shouldFail) error("archive failed")
            archivedIds += sessionId
            return sessions.first { it.id == sessionId }
        }

        override fun events(): Flow<OpenCodeEvent> = emptyFlow()
    }
}
