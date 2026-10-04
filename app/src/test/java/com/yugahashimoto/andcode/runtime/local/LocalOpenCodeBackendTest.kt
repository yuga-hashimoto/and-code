package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.data.connection.ConnectionProfile
import com.yugahashimoto.andcode.runtime.remote.RemoteOpenCodeBackend
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalOpenCodeBackendTest {
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
    fun `reuses backend for same port and rebuilds after invalidate or port change`() {
        var port = 4097
        val created = mutableListOf<RemoteOpenCodeBackend>()
        val backend =
            LocalOpenCodeBackend(
                portProvider = { port },
                backendFactory = { profile ->
                    RemoteOpenCodeBackend(profile).also { created += it }
                },
            )

        val first = backend.delegate()
        val second = backend.delegate()
        assertSame(first, second)
        assertEquals(1, created.size)

        backend.invalidate()
        val third = backend.delegate()
        assertNotSame(first, third)
        assertEquals(2, created.size)

        port = 4098
        val fourth = backend.delegate()
        assertNotSame(third, fourth)
        assertEquals(3, created.size)
    }

    @Test
    fun `delegates answerQuestion through remote backend`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("true"))

            val backend =
                LocalOpenCodeBackend(
                    portProvider = { server.port },
                    backendFactory = { profile ->
                        RemoteOpenCodeBackend(
                            profile.copy(baseUrl = server.url("/").toString()),
                        )
                    },
                )

            assertTrue(backend.answerQuestion("q-1", listOf(listOf("src"), listOf("docs", "tests")), "/workspace/repo"))

            val request = server.takeRequest()
            assertEquals("/question/q-1/reply?directory=%2Fworkspace%2Frepo", request.path)
            assertEquals("""{"answers":[["src"],["docs","tests"]]}""", request.body.readUtf8())
        }

    @Test
    fun `supplies the local server password and rebuilds when it changes`() {
        var password: String? = "first-secret"
        val profiles = mutableListOf<ConnectionProfile>()
        val backend =
            LocalOpenCodeBackend(
                portProvider = { 4097 },
                passwordProvider = { password },
                backendFactory = { profile ->
                    profiles += profile
                    RemoteOpenCodeBackend(profile)
                },
            )

        val first = backend.delegate()
        assertSame(first, backend.delegate())
        assertEquals(1, profiles.size)
        assertEquals("first-secret", profiles.single().password)
        assertEquals(LocalServerAuth.USERNAME, profiles.single().username)

        password = "second-secret"
        val second = backend.delegate()
        assertNotSame(first, second)
        assertEquals("second-secret", profiles.last().password)
    }

    @Test
    fun `sends basic auth to the local server when a password is set`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("true"))

            val backend =
                LocalOpenCodeBackend(
                    portProvider = { server.port },
                    passwordProvider = { "local-server-secret" },
                    backendFactory = { profile ->
                        RemoteOpenCodeBackend(
                            profile.copy(baseUrl = server.url("/").toString()),
                        )
                    },
                )

            assertTrue(backend.answerQuestion("q-1", emptyList(), "/workspace/repo"))

            val request = server.takeRequest()
            assertEquals(
                Credentials.basic(LocalServerAuth.USERNAME, "local-server-secret"),
                request.getHeader("Authorization"),
            )
        }
}
