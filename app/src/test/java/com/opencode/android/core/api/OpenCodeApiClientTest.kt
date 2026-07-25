package com.opencode.android.core.api

import com.google.gson.JsonParser
import com.opencode.android.data.connection.ConnectionProfile
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenCodeApiClientTest {
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
    fun `health uses official endpoint and basic authentication`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"healthy":true,"version":"1.2.3"}"""))
        val client = client(password = "secret")

        val result = client.health()

        assertTrue(result.healthy)
        assertEquals("1.2.3", result.version)
        val request = server.takeRequest()
        assertEquals("/global/health", request.path)
        assertEquals(Credentials.basic("opencode", "secret"), request.getHeader("Authorization"))
    }

    @Test
    fun `lists sessions and creates session using official endpoints`() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"id":"s1","title":"Existing","directory":"/repo","time":{"created":1,"updated":2}}]"""))
        server.enqueue(MockResponse().setBody("""{"id":"s2","title":"New","directory":"/repo","time":{"created":3,"updated":3}}"""))
        val client = client()

        val sessions = client.sessions("/repo with space")
        val created = client.createSession("New", "/repo with space")

        assertEquals("s1", sessions.single().id)
        assertEquals("s2", created.id)
        assertEquals("/session?directory=%2Frepo%20with%20space", server.takeRequest().path)
        val createRequest = server.takeRequest()
        assertEquals("/session?directory=%2Frepo%20with%20space", createRequest.path)
        assertEquals("New", JsonParser.parseString(createRequest.body.readUtf8()).asJsonObject["title"].asString)
    }

    @Test
    fun `sends asynchronous prompt with selected model agent and text part`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val client = client()

        client.promptAsync(
            sessionId = "s1",
            request = PromptRequest(
                providerId = "opencode",
                modelId = "deepseek-v4-flash-free",
                agent = "build",
                text = "hello"
            )
        )

        val request = server.takeRequest()
        assertEquals("/session/s1/prompt_async", request.path)
        val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("build", json["agent"].asString)
        assertEquals("opencode", json.getAsJsonObject("model")["providerID"].asString)
        assertEquals("deepseek-v4-flash-free", json.getAsJsonObject("model")["modelID"].asString)
        assertEquals("hello", json.getAsJsonArray("parts")[0].asJsonObject["text"].asString)
    }

    @Test
    fun `responds to permission request`() = runBlocking {
        server.enqueue(MockResponse().setBody("true"))
        val client = client()

        val result = client.respondPermission("s1", "perm1", "once")

        assertTrue(result)
        val request = server.takeRequest()
        assertEquals("/session/s1/permissions/perm1", request.path)
        val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("once", json["response"].asString)
        assertTrue(!json.has("remember"))
    }

    @Test
    fun `remember once maps to always response`() = runBlocking {
        server.enqueue(MockResponse().setBody("true"))
        val client = client()

        val result = client.respondPermission("s1", "perm1", "once", remember = true)

        assertTrue(result)
        val json = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("always", json["response"].asString)
    }

    @Test
    fun `renames session using PATCH with title body`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"s1","title":"Renamed","directory":"/repo","time":{"created":1,"updated":2}}"""))
        val client = client()

        val renamed = client.renameSession("s1", "Renamed", "/repo")

        assertEquals("Renamed", renamed.title)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/session/s1?directory=%2Frepo", request.path)
        assertEquals("Renamed", JsonParser.parseString(request.body.readUtf8()).asJsonObject["title"].asString)
    }

    @Test
    fun `deletes session using DELETE`() = runBlocking {
        server.enqueue(MockResponse().setBody("true"))
        val client = client()

        val result = client.deleteSession("s1", "/repo")

        assertTrue(result)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/session/s1?directory=%2Frepo", request.path)
    }

    @Test
    fun `failed requests include truncated response body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        val client = client()

        val error = runCatching { client.health() }.exceptionOrNull() as OpenCodeApiException
        assertEquals(500, error.statusCode)
        assertTrue(error.message!!.contains("boom"))
    }

    @Test
    fun `lists and reads files for a workspace directory`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"name":"src","path":"src","absolute":"/repo/src","type":"directory","ignored":false},{"name":"README.md","path":"README.md","absolute":"/repo/README.md","type":"file","ignored":false}]"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{"type":"text","content":"# Hello","mimeType":"text/markdown"}"""
            )
        )
        val client = client()

        val files = client.files(directory = "/repo with space", path = ".")
        val content = client.fileContent(directory = "/repo with space", path = "README.md")

        assertEquals(listOf("src", "README.md"), files.map { it.name })
        assertEquals("directory", files.first().type)
        assertEquals("# Hello", content.content)
        assertEquals("/file?directory=%2Frepo%20with%20space&path=.", server.takeRequest().path)
        assertEquals(
            "/file/content?directory=%2Frepo%20with%20space&path=README.md",
            server.takeRequest().path
        )
    }

    @Test
    fun `searches text and file paths in a workspace`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"path":{"text":"src/Main.kt"},"lines":{"text":"fun main()"},"line_number":7,"absolute_offset":42,"submatches":[{"match":{"text":"main"},"start":4,"end":8}]}]"""
            )
        )
        server.enqueue(MockResponse().setBody("""["src/Main.kt","src/MainTest.kt"]"""))
        val client = client()

        val textMatches = client.searchText("/repo", "main\\(")
        val fileMatches = client.findFiles("/repo", "Main", type = "file", limit = 20)

        assertEquals("src/Main.kt", textMatches.single().path.text)
        assertEquals(7, textMatches.single().lineNumber)
        assertEquals(listOf("src/Main.kt", "src/MainTest.kt"), fileMatches)
        assertEquals("/find?directory=%2Frepo&pattern=main%5C%28", server.takeRequest().path)
        assertEquals(
            "/find/file?directory=%2Frepo&query=Main&type=file&limit=20",
            server.takeRequest().path
        )
    }

    @Test
    fun `loads vcs status session diff and todo`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"file":"src/Main.kt","additions":3,"deletions":1,"status":"modified"}]"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """[{"file":"src/Main.kt","patch":"@@ -1 +1 @@","additions":3,"deletions":1,"status":"modified"}]"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """[{"content":"Run tests","status":"in_progress","priority":"high"}]"""
            )
        )
        val client = client()

        val status = client.vcsStatus("/repo")
        val diff = client.sessionDiff("ses_123", "/repo")
        val todo = client.sessionTodo("ses_123", "/repo")

        assertEquals("modified", status.single().status)
        assertEquals(3.0, diff.single().additions, 0.0)
        assertEquals("Run tests", todo.single().content)
        assertEquals("/vcs/status?directory=%2Frepo", server.takeRequest().path)
        assertEquals("/session/ses_123/diff?directory=%2Frepo", server.takeRequest().path)
        assertEquals("/session/ses_123/todo?directory=%2Frepo", server.takeRequest().path)
    }

    @Test
    fun `event stream reconnects after the server closes the connection`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"server.connected\",\"properties\":{}}\n\n")
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"session.idle\",\"properties\":{\"sessionID\":\"s1\"}}\n\n")
        )

        val events = withTimeout(3_000) {
            client().events().take(2).toList()
        }

        assertTrue(events[0] is OpenCodeEvent.ServerConnected)
        assertEquals("s1", (events[1] as OpenCodeEvent.SessionIdle).sessionId)
        // The cross-instance stream is required: `/event` only carries events for the instance
        // rooted at the server's own working directory, not for other workspaces.
        assertEquals("/global/event", server.takeRequest().path)
        assertEquals("/global/event", server.takeRequest().path)
    }

    @Test
    fun `event stream falls back to the instance route on older servers`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"session.idle\",\"properties\":{\"sessionID\":\"s1\"}}\n\n")
        )

        val events = withTimeout(3_000) {
            client().events().take(1).toList()
        }

        assertEquals("s1", (events.single() as OpenCodeEvent.SessionIdle).sessionId)
        assertEquals("/global/event", server.takeRequest().path)
        assertEquals("/event", server.takeRequest().path)
    }

    @Test
    fun `redacts response body from authentication errors`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("token=super-secret"))

        val error = org.junit.Assert.assertThrows(OpenCodeApiException::class.java) {
            runBlocking { client().health() }
        }

        assertEquals(401, error.statusCode)
        assertTrue(error.message.orEmpty().contains("HTTP 401"))
        assertTrue(!error.message.orEmpty().contains("super-secret"))
    }

    @Test
    fun `loads provider auth methods and completes oauth callback`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"openai":[{"type":"oauth","label":"ChatGPT Plus/Pro"},{"type":"api","label":"API key"}]}"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{"url":"https://auth.example/login","method":"code","instructions":"Enter the code"}"""
            )
        )
        server.enqueue(MockResponse().setBody("true"))
        val client = client()

        val methods = client.providerAuthMethods()
        val authorization = client.authorizeProvider("openai", 0)
        val completed = client.completeProviderOAuth("openai", 0, "abc")

        assertEquals("ChatGPT Plus/Pro", methods.getValue("openai").first().label)
        assertEquals("https://auth.example/login", authorization.url)
        assertTrue(completed)
        assertEquals("/provider/auth", server.takeRequest().path)
        assertEquals("/provider/openai/oauth/authorize", server.takeRequest().path)
        val callback = server.takeRequest()
        assertEquals("/provider/openai/oauth/callback", callback.path)
        val callbackJson = JsonParser.parseString(callback.body.readUtf8()).asJsonObject
        assertEquals(0, callbackJson["method"].asInt)
        assertEquals("abc", callbackJson["code"].asString)
    }

    @Test
    fun `provider auth methods parse prompts and conditional rules`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"custom":[{"type":"oauth","label":"Workspace login","prompts":[{"type":"select","key":"region","message":"Region","options":[{"label":"US","value":"us","hint":"United States"}]},{"type":"text","key":"tenant","message":"Tenant","placeholder":"acme","when":{"key":"region","op":"eq","value":"us"}}]}]}"""
            )
        )
        val method = client().providerAuthMethods().getValue("custom").single()

        assertEquals("Workspace login", method.label)
        assertEquals("United States", method.prompts.first().options.single().hint)
        assertTrue(method.prompts[1].isVisible(mapOf("region" to "us")))
        assertTrue(!method.prompts[1].isVisible(mapOf("region" to "eu")))
    }

    @Test
    fun `oauth authorize sends provider prompt inputs`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"url":"https://auth.example/login","method":"auto","instructions":"Enter code: ABCD"}"""
            )
        )

        client().authorizeProvider(
            "custom/provider",
            2,
            mapOf("tenant" to "acme", "region" to "us")
        )

        val request = server.takeRequest()
        assertEquals("/provider/custom%2Fprovider/oauth/authorize", request.path)
        val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals(2, json["method"].asInt)
        assertEquals("acme", json.getAsJsonObject("inputs")["tenant"].asString)
        assertEquals("us", json.getAsJsonObject("inputs")["region"].asString)
    }

    @Test
    fun `sets and removes provider auth on selected runtime`() = runBlocking {
        server.enqueue(MockResponse().setBody("true"))
        server.enqueue(MockResponse().setBody("true"))
        val client = client()

        assertTrue(client.setProviderApiKey("custom", "key-value", mapOf("region" to "us")))
        assertTrue(client.removeProviderAuth("custom"))

        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/auth/custom", put.path)
        val auth = JsonParser.parseString(put.body.readUtf8()).asJsonObject
        assertEquals("api", auth["type"].asString)
        assertEquals("key-value", auth["key"].asString)
        assertEquals("us", auth.getAsJsonObject("metadata")["region"].asString)
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/auth/custom", delete.path)
    }

    @Test
    fun `provider auth errors do not expose response bodies`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":"invalid key sk-super-secret"}""")
        )

        val error = runCatching {
            client().setProviderApiKey("custom", "sk-super-secret")
        }.exceptionOrNull() as OpenCodeApiException

        assertEquals(400, error.statusCode)
        assertTrue(!error.message.orEmpty().contains("sk-super-secret"))
        assertTrue(!error.message.orEmpty().contains("invalid key"))
    }

    private fun client(password: String? = null): OpenCodeApiClient {
        val profile = ConnectionProfile(
            id = "test",
            name = "Test",
            baseUrl = server.url("/").toString(),
            username = "opencode",
            password = password,
            allowInsecureLan = true
        )
        return OpenCodeApiClient(profile, OkHttpClient())
    }
}
