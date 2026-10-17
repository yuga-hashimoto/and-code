package com.yugahashimoto.andcode.data.repository

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeModel
import com.yugahashimoto.andcode.core.api.OpenCodeProvider
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.data.connection.ConnectionProfile
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
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeCatalogRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `loads selected runtime catalog and workspaces`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget("mac")
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = "mac"),
                    localTarget = FakeTarget("local-android", RuntimeType.LOCAL),
                    remoteFactory = { target },
                )
            val repository =
                RuntimeCatalogRepository(
                    registry = registry,
                    scope = TestScope(dispatcher),
                )

            advanceUntilIdle()

            val state = repository.state.value
            assertEquals("mac", state.runtime?.id)
            assertEquals("1.18.3", state.health?.version)
            assertEquals(listOf("s1"), state.sessions.map { it.id })
            assertEquals(listOf("opencode"), state.providers.connected)
            assertEquals(listOf("build"), state.agents.map { it.name })
            assertEquals(listOf("/workspace/app"), state.workspaces.map { it.path })
            assertFalse(state.isRefreshing)
            assertNull(state.error)
        }

    @Test
    fun `switching runtime clears previous catalog and loads the new target`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val mac = FakeTarget("mac", version = "1.18.3")
            val linux = FakeTarget("linux", version = "1.18.4")
            val store =
                FakeStore(
                    profiles = listOf(profile("mac"), profile("linux")),
                    selectedRuntimeId = "mac",
                )
            val registry =
                RuntimeRegistry(
                    store = store,
                    localTarget = FakeTarget("local-android", RuntimeType.LOCAL),
                    remoteFactory = { if (it.id == "mac") mac else linux },
                )
            val repository = RuntimeCatalogRepository(registry, TestScope(dispatcher))
            advanceUntilIdle()

            registry.select("linux")
            advanceUntilIdle()

            assertEquals("linux", repository.state.value.runtime?.id)
            assertEquals("1.18.4", repository.state.value.health?.version)
        }

    @Test
    fun `connection failure is exposed without stale data`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val failing = FakeTarget("mac", connectError = IllegalStateException("offline"))
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = "mac"),
                    localTarget = FakeTarget("local-android", RuntimeType.LOCAL),
                    remoteFactory = { failing },
                )
            val repository = RuntimeCatalogRepository(registry, TestScope(dispatcher))

            advanceUntilIdle()

            val state = repository.state.value
            assertEquals("offline", state.error)
            assertTrue(state.sessions.isEmpty())
            assertTrue(state.providers.all.isEmpty())
            assertFalse(state.isRefreshing)
        }

    @Test
    fun `refreshing sessions only keeps the existing catalog and updates recent chats`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget("mac")
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = "mac"),
                    localTarget = FakeTarget("local-android", RuntimeType.LOCAL),
                    remoteFactory = { target },
                )
            val repository = RuntimeCatalogRepository(registry, TestScope(dispatcher))
            advanceUntilIdle()

            repository.refreshSessionsOnly()
            advanceUntilIdle()

            assertEquals(listOf("s1"), repository.state.value.sessions.map { it.id })
            assertEquals(listOf("opencode"), repository.state.value.providers.connected)
        }

    private fun profile(id: String) =
        ConnectionProfile(
            id = id,
            name = id,
            baseUrl = "https://$id.example.test",
        )

    @Test
    fun `a transiently empty connected set does not blank the model picker`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget("mac")
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = "mac"),
                    localTarget = FakeTarget("local-android", RuntimeType.LOCAL),
                    remoteFactory = { target },
                )
            val repository = RuntimeCatalogRepository(registry, TestScope(dispatcher))
            advanceUntilIdle()
            assertEquals(listOf("opencode"), repository.state.value.providers.connected)

            // A restarted server answers /provider before it has re-read its credentials.
            target.providersOverride =
                ProviderCatalog(
                    all = listOf(OpenCodeProvider(id = "opencode", name = "OpenCode Zen")),
                    connected = emptyList(),
                )
            repository.refreshProvidersOnly()
            advanceUntilIdle()

            assertEquals(listOf("opencode"), repository.state.value.providers.connected)
            assertTrue(repository.state.value.providers.all.any { it.id == "opencode" })
        }

    @Test
    fun `a refresh that names a connected provider replaces the catalogue`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget("mac")
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = "mac"),
                    localTarget = FakeTarget("local-android", RuntimeType.LOCAL),
                    remoteFactory = { target },
                )
            val repository = RuntimeCatalogRepository(registry, TestScope(dispatcher))
            advanceUntilIdle()

            target.providersOverride =
                ProviderCatalog(
                    all = listOf(OpenCodeProvider(id = "other", name = "Other")),
                    connected = listOf("other"),
                )
            repository.refreshProvidersOnly()
            advanceUntilIdle()

            assertEquals(listOf("other"), repository.state.value.providers.connected)
        }

    @Test
    fun `only a catalogue with a connected provider replaces a usable one`() {
        fun catalog(vararg connected: String) = ProviderCatalog(connected = connected.toList())

        assertFalse(shouldReplaceProviderCatalog(catalog("opencode"), catalog()))
        assertTrue(shouldReplaceProviderCatalog(catalog(), catalog()))
        assertTrue(shouldReplaceProviderCatalog(catalog(), catalog("opencode")))
        assertTrue(shouldReplaceProviderCatalog(catalog("opencode"), catalog("other")))
    }

    private fun cache() = ProviderCatalogCache(temporaryFolder.newFolder("cache"), Json { ignoreUnknownKeys = true })

    private fun repositoryWithCache(
        target: RuntimeTarget,
        providerCache: ProviderCatalogCache,
        scope: TestScope,
    ): RuntimeCatalogRepository {
        val registry =
            RuntimeRegistry(
                store = FakeStore(selectedRuntimeId = "mac"),
                localTarget = FakeTarget("local-android", RuntimeType.LOCAL),
                remoteFactory = { target },
            )
        return RuntimeCatalogRepository(registry, scope, providerCache = providerCache)
    }

    @Test
    fun `a transiently empty refresh keeps the list and leaves the cache alone`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget("mac")
            val repository = repositoryWithCache(target, cache(), TestScope(dispatcher))
            advanceUntilIdle()
            assertEquals(listOf("opencode"), repository.cachedProviders("mac")?.connected)

            // The server restarted and answered /provider before re-reading credentials.
            target.providersOverride = restartingServerCatalog()
            repository.refreshProvidersOnly()
            advanceUntilIdle()

            assertEquals(listOf("opencode"), repository.state.value.providers.connected)
            assertEquals(listOf("opencode"), repository.cachedProviders("mac")?.connected)
        }

    @Test
    fun `repeated loads during a transient empty response keep the list`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget("mac")
            val repository = repositoryWithCache(target, cache(), TestScope(dispatcher))
            advanceUntilIdle()
            target.providersOverride = restartingServerCatalog()

            // A second load reads the cache back into the state first; if the cache had been allowed
            // to record the empty response, that read would blank the list on this pass.
            repository.refresh()
            advanceUntilIdle()
            repository.refresh()
            advanceUntilIdle()

            assertEquals(listOf("opencode"), repository.state.value.providers.connected)
            assertEquals(listOf("opencode"), repository.cachedProviders("mac")?.connected)
        }

    private fun restartingServerCatalog() =
        ProviderCatalog(
            all = listOf(OpenCodeProvider(id = "opencode", name = "OpenCode Zen")),
            connected = emptyList(),
        )

    @Test
    fun `agents that fail on the first read are retried until the runtime answers`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget("mac")
            // A local OpenCode installing its plugins: the first GET /agent fails (with a two-line
            // message, as a decoding error has), the next is empty.
            target.agentAnswers.add { error("Unexpected JSON token\nJSON input: [{\"hidden\":null}]") }
            target.agentAnswers.add { emptyList() }
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = "mac"),
                    localTarget = FakeTarget("local-android", RuntimeType.LOCAL),
                    remoteFactory = { target },
                )
            val repository = RuntimeCatalogRepository(registry = registry, scope = TestScope(dispatcher))

            advanceUntilIdle()

            // The load publishes after one read; the retry runs after it, outside the refresh lock.
            assertEquals(listOf("build"), repository.state.value.agents.map { it.name })
            assertEquals(3, target.agentCalls)
            assertFalse(repository.state.value.isRefreshing)
            // The load's "Agents: ..." failure no longer applies once the retry got them.
            assertNull(repository.state.value.error)
        }

    @Test
    fun `an agent retry for a runtime that never connected refreshes the whole catalogue`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget("mac", connectError = IllegalStateException("offline"))
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = "mac"),
                    localTarget = FakeTarget("local-android", RuntimeType.LOCAL),
                    remoteFactory = { target },
                )
            val repository = RuntimeCatalogRepository(registry = registry, scope = TestScope(dispatcher))
            advanceUntilIdle()
            assertNull(repository.state.value.health)
            val callsBefore = target.agentCalls

            repository.refreshAgents()
            advanceUntilIdle()

            // A full refresh retried the connection instead of asking a dead server for agents.
            assertEquals(callsBefore, target.agentCalls)
            assertEquals("offline", repository.state.value.error)
        }

    @Test
    fun `refreshing agents fills an empty picker without touching the rest of the catalogue`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget("mac")
            // The load's own read plus its background retry (1 + 5 reads) all fail.
            repeat(6) { target.agentAnswers.add { error("plugin load failed") } }
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = "mac"),
                    localTarget = FakeTarget("local-android", RuntimeType.LOCAL),
                    remoteFactory = { target },
                )
            val repository = RuntimeCatalogRepository(registry = registry, scope = TestScope(dispatcher))
            advanceUntilIdle()
            assertTrue(repository.state.value.agents.isEmpty())
            assertEquals(6, target.agentCalls)
            val sessionsBefore = repository.state.value.sessions

            repository.refreshAgents()
            advanceUntilIdle()

            assertEquals(listOf("build"), repository.state.value.agents.map { it.name })
            assertEquals(sessionsBefore, repository.state.value.sessions)
        }

    @Test
    fun `a refresh whose agent read fails keeps the agents already on screen`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget("mac")
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = "mac"),
                    localTarget = FakeTarget("local-android", RuntimeType.LOCAL),
                    remoteFactory = { target },
                )
            val repository = RuntimeCatalogRepository(registry = registry, scope = TestScope(dispatcher))
            advanceUntilIdle()
            assertEquals(listOf("build"), repository.state.value.agents.map { it.name })

            // Every read of the next refresh fails, retries included.
            repeat(10) { target.agentAnswers.add { error("plugin load failed") } }
            repository.refresh()
            advanceUntilIdle()

            assertEquals(listOf("build"), repository.state.value.agents.map { it.name })
            // The kept list is not this read's: no agents failure on the chip or in the catalogue error.
            assertNull(repository.state.value.agentsError)
            assertNull(repository.state.value.error)
        }

    @Test
    fun `repeated agent refreshes run a single retry at a time`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget("mac")
            // The load and its background retry give up empty-handed (1 + 5 failed reads).
            repeat(6) { target.agentAnswers.add { error("plugin load failed") } }
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = "mac"),
                    localTarget = FakeTarget("local-android", RuntimeType.LOCAL),
                    remoteFactory = { target },
                )
            val repository = RuntimeCatalogRepository(registry = registry, scope = TestScope(dispatcher))
            advanceUntilIdle()
            val callsBefore = target.agentCalls

            assertTrue(repository.state.value.agents.isEmpty())

            repeat(5) { repository.refreshAgents() }
            advanceUntilIdle()

            assertEquals(listOf("build"), repository.state.value.agents.map { it.name })
            assertEquals(callsBefore + 1, target.agentCalls)
        }

    @Test
    fun `agents read as loading until they arrive, and stop loading once retries give up`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val target = FakeTarget("mac")
            // The load's read and the whole background retry (1 + 5 reads) fail.
            repeat(6) { target.agentAnswers.add { error("plugin load failed") } }
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = "mac"),
                    localTarget = FakeTarget("local-android", RuntimeType.LOCAL),
                    remoteFactory = { target },
                )
            val repository = RuntimeCatalogRepository(registry = registry, scope = TestScope(dispatcher))

            // The load's own read has failed and published; the background retry is still to come,
            // so the chip has nothing to pick yet and reads as loading.
            testScheduler.runCurrent()
            assertTrue(repository.state.value.agentsLoading)

            advanceUntilIdle()
            assertTrue(repository.state.value.agents.isEmpty())
            assertFalse(repository.state.value.agentsLoading)

            // A tap on the empty chip marks it loading straight away, before the retry has run.
            repository.refreshAgents()
            assertTrue(repository.state.value.agentsLoading)
            advanceUntilIdle()
            assertEquals(listOf("build"), repository.state.value.agents.map { it.name })
            assertFalse(repository.state.value.agentsLoading)
        }

    private class FakeStore(
        profiles: List<ConnectionProfile> = listOf(profileStatic("mac")),
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

        companion object {
            private fun profileStatic(id: String) =
                ConnectionProfile(
                    id = id,
                    name = id,
                    baseUrl = "https://$id.example.test",
                )
        }
    }

    @Test
    fun `switching runtime never shows the previous runtime's providers`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val openCode = FakeTarget("local-android", RuntimeType.LOCAL, providerId = "opencode")
            // A runtime that cannot answer: the worst case, where the old catalogue is most tempting
            // to keep. Claude Code models must never be listed under OpenCode, or the reverse.
            val claude = FakeTarget("claude-code-local", RuntimeType.LOCAL, providersError = IllegalStateException("busy"))
            val registry =
                RuntimeRegistry(
                    store = FakeStore(selectedRuntimeId = "local-android"),
                    localTarget = openCode,
                    additionalTargets = listOf(claude),
                )
            val repository = RuntimeCatalogRepository(registry = registry, scope = TestScope(dispatcher))
            advanceUntilIdle()
            assertEquals(listOf("opencode"), repository.state.value.providers.connected)

            registry.select("claude-code-local")
            advanceUntilIdle()

            assertTrue(repository.state.value.providers.all.isEmpty())
            assertEquals("claude-code-local", repository.state.value.runtime?.id)
        }

    private class FakeTarget(
        override val id: String,
        override val type: RuntimeType = RuntimeType.REMOTE,
        private val version: String = "1.18.3",
        private val connectError: Throwable? = null,
        private val providerId: String = "opencode",
        private val providersError: Throwable? = null,
    ) : RuntimeTarget {
        override val displayName: String = id
        override val kind: BackendKind = if (type == RuntimeType.LOCAL) BackendKind.LOCAL else BackendKind.REMOTE
        override val state = MutableStateFlow<RuntimeState>(RuntimeState.Disconnected)

        /** What [listProviders] returns, when a test needs to change the catalogue mid-run. */
        var providersOverride: ProviderCatalog? = null

        override suspend fun connect(): Result<OpenCodeHealth> =
            connectError?.let(Result.Companion::failure)
                ?: Result.success(OpenCodeHealth(true, version))

        override fun disconnect() = Unit

        override suspend fun health(): OpenCodeHealth = OpenCodeHealth(true, version)

        override suspend fun listSessions(directory: String?): List<OpenCodeSession> =
            listOf(
                OpenCodeSession(id = "s1", title = "Test", directory = "/workspace/app", time = OpenCodeTime(created = 1)),
            )

        override suspend fun createSession(
            title: String?,
            directory: String?,
        ): OpenCodeSession = error("unused")

        override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = emptyList()

        override suspend fun listProviders(): ProviderCatalog {
            providersError?.let { throw it }
            providersOverride?.let { return it }
            return ProviderCatalog(
                all =
                    listOf(
                        OpenCodeProvider(
                            id = providerId,
                            name = "OpenCode Zen",
                            models = mapOf("big-pickle" to OpenCodeModel("big-pickle", providerId, "Big Pickle")),
                        ),
                    ),
                default = mapOf(providerId to "big-pickle"),
                connected = listOf(providerId),
            )
        }

        /** Answers for successive [listAgents] calls; once used up, the default single `build`. */
        val agentAnswers = ArrayDeque<() -> List<OpenCodeAgent>>()
        var agentCalls = 0

        override suspend fun listAgents(): List<OpenCodeAgent> {
            agentCalls++
            return agentAnswers.removeFirstOrNull()?.invoke() ?: listOf(OpenCodeAgent("build", mode = "primary"))
        }

        override suspend fun listWorkspaces(): List<WorkspaceRef> =
            listOf(
                WorkspaceRef("/workspace/app", "app", "/workspace/app"),
            )

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

        override fun events(): Flow<OpenCodeEvent> = emptyFlow()
    }
}
