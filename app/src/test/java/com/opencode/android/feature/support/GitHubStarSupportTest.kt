package com.opencode.android.feature.support

import com.opencode.android.core.ProjectLinks
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GitHubStarSupportTest {
    private lateinit var server: MockWebServer

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
    fun `project links point to the OpenCode Android repository`() {
        assertEquals("https://github.com/yuga-hashimoto/opencode-android", ProjectLinks.GITHUB_REPOSITORY)
        assertEquals("https://github.com/yuga-hashimoto/opencode-android/issues", ProjectLinks.GITHUB_ISSUES)
        assertEquals("https://github.com/yuga-hashimoto/opencode-android/releases", ProjectLinks.GITHUB_RELEASES)
    }

    @Test
    fun `initial prompt is only eligible before onboarding and before a previous response`() {
        assertTrue(GitHubStarPromptPolicy.shouldShowInitial(onboardingCompleted = false, promptHandled = false))
        assertFalse(GitHubStarPromptPolicy.shouldShowInitial(onboardingCompleted = true, promptHandled = false))
        assertFalse(GitHubStarPromptPolicy.shouldShowInitial(onboardingCompleted = false, promptHandled = true))
    }

    @Test
    fun `second prompt only appears once after the user deferred and has not been verified starred`() {
        assertTrue(
            GitHubStarPromptPolicy.shouldShowSecond(
                deferred = true,
                secondPromptShown = false,
                starred = false,
            ),
        )
        assertTrue(
            GitHubStarPromptPolicy.shouldShowSecond(
                deferred = true,
                secondPromptShown = false,
                starred = null,
            ),
        )
        assertFalse(GitHubStarPromptPolicy.shouldShowSecond(true, true, false))
        assertFalse(GitHubStarPromptPolicy.shouldShowSecond(false, false, false))
        assertFalse(GitHubStarPromptPolicy.shouldShowSecond(true, false, true))
    }

    @Test
    fun `repository metadata returns star count without authentication`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"stargazers_count":846}"""))
            val service = service(token = null)

            val snapshot = service.fetch()

            assertEquals(846, snapshot.stargazersCount)
            assertNull(snapshot.starred)
            val request = server.takeRequest()
            assertEquals("/repos/yuga-hashimoto/opencode-android", request.path)
            assertNull(request.getHeader("Authorization"))
        }

    @Test
    fun `authenticated verification reports starred on 204`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"stargazers_count":10}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            val service = service(token = "token-123")

            val snapshot = service.fetch()

            assertEquals(10, snapshot.stargazersCount)
            assertEquals(true, snapshot.starred)
            server.takeRequest()
            val starRequest = server.takeRequest()
            assertEquals("/user/starred/yuga-hashimoto/opencode-android", starRequest.path)
            assertEquals("Bearer token-123", starRequest.getHeader("Authorization"))
        }

    @Test
    fun `authenticated verification reports not starred on 404`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"stargazers_count":10}"""))
            server.enqueue(MockResponse().setResponseCode(404))

            val snapshot = service(token = "token-123").fetch()

            assertEquals(false, snapshot.starred)
        }

    @Test
    fun `API failures are non blocking and return unknown values`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setResponseCode(500))

            val snapshot = service(token = "token-123").fetch()

            assertNull(snapshot.stargazersCount)
            assertNull(snapshot.starred)
        }

    private fun service(token: String?): GitHubStarService =
        GitHubStarService(
            client = OkHttpClient(),
            tokenProvider = { token },
            apiBaseUrl = server.url("/").toString().removeSuffix("/"),
        )
}
