package com.yugahashimoto.andcode.data.repository

import com.yugahashimoto.andcode.core.api.GitHubApiClient
import com.yugahashimoto.andcode.core.api.PullRequestRef
import com.yugahashimoto.andcode.core.api.PullRequestState
import com.yugahashimoto.andcode.core.api.PullRequestStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

private const val AWAIT_TIMEOUT_MS = 5_000L

class PullRequestStatusRepositoryTest {
    private lateinit var server: MockWebServer
    private val ref = PullRequestRef("yuga-hashimoto", "and-code", 170)
    private var now = 1_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `fetches a linked pull request and reports its state and diff size`() {
        server.enqueue(pullResponse(state = "closed", merged = true, additions = 604, deletions = 92))
        val repository = repository()

        val status =
            runBlocking {
                repository.track(listOf(ref))
                awaitStatus(repository)
            }

        assertEquals(PullRequestState.MERGED, status.state)
        assertEquals(604, status.additions)
        assertEquals(92, status.deletions)
        assertEquals(ref, status.ref)
    }

    @Test
    fun `sends the stored token so private pull requests resolve`() {
        server.enqueue(pullResponse())
        val repository = repository(token = "gho_secret")

        runBlocking {
            repository.track(listOf(ref))
            awaitStatus(repository)
        }

        val request = server.takeRequest(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        assertEquals("/repos/yuga-hashimoto/and-code/pulls/170", request?.path)
        assertEquals("Bearer gho_secret", request?.getHeader("Authorization"))
    }

    @Test
    fun `does not refetch a pull request whose state is still fresh`() {
        server.enqueue(pullResponse())
        val repository = repository()

        runBlocking {
            repository.track(listOf(ref))
            awaitStatus(repository)
            now += 30_000L
            repository.track(listOf(ref))
        }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `refetches once the state has gone stale`() {
        server.enqueue(pullResponse(draft = true))
        server.enqueue(pullResponse(state = "closed", merged = true))
        val repository = repository()

        val merged =
            runBlocking {
                repository.track(listOf(ref))
                assertEquals(PullRequestState.DRAFT, awaitStatus(repository).state)
                now += 61_000L
                repository.track(listOf(ref))
                withTimeout(AWAIT_TIMEOUT_MS) {
                    repository.statuses.first { it[ref.key]?.state == PullRequestState.MERGED }
                }
            }

        assertEquals(PullRequestState.MERGED, merged[ref.key]?.state)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `keeps the last known state when GitHub cannot be reached`() {
        server.enqueue(pullResponse(additions = 10))
        server.enqueue(MockResponse().setResponseCode(500))
        val repository = repository()

        runBlocking {
            repository.track(listOf(ref))
            awaitStatus(repository)
            now += 61_000L
            repository.track(listOf(ref))
            server.takeRequest(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            server.takeRequest(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }

        assertEquals(10, repository.statuses.value[ref.key]?.additions)
    }

    @Test
    fun `an untracked pull request has no state`() {
        assertNull(repository().statuses.value[ref.key])
    }

    private suspend fun awaitStatus(repository: PullRequestStatusRepository): PullRequestStatus =
        withTimeout(AWAIT_TIMEOUT_MS) {
            repository.statuses.first { it.containsKey(ref.key) }.getValue(ref.key)
        }

    private fun repository(token: String? = null) =
        PullRequestStatusRepository(
            api =
                GitHubApiClient(
                    token = { token },
                    client = OkHttpClient(),
                    baseUrl = server.url("/").toString().trimEnd('/'),
                ),
            scope = scope,
            clock = { now },
        )

    private fun pullResponse(
        state: String = "open",
        draft: Boolean = false,
        merged: Boolean = false,
        mergeableState: String = "clean",
        additions: Int = 1,
        deletions: Int = 1,
    ): MockResponse =
        MockResponse().setBody(
            """
            {
              "number": 170,
              "title": "fix: stop the crash",
              "state": "$state",
              "draft": $draft,
              "merged": $merged,
              "mergeable_state": "$mergeableState",
              "additions": $additions,
              "deletions": $deletions,
              "changed_files": 12
            }
            """.trimIndent(),
        )
}
