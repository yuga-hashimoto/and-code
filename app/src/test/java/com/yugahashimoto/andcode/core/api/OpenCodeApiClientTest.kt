package com.yugahashimoto.andcode.core.api

import com.yugahashimoto.andcode.data.connection.ConnectionProfile
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun `health uses official endpoint and basic authentication`() =
        runBlocking {
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
    fun `lists sessions and creates session using official endpoints`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody("""[{"id":"s1","title":"Existing","directory":"/repo","time":{"created":1,"updated":2}}]"""),
            )
            server.enqueue(MockResponse().setBody("""{"id":"s2","title":"New","directory":"/repo","time":{"created":3,"updated":3}}"""))
            val client = client()

            val sessions = client.sessions("/repo with space")
            val created = client.createSession("New", "/repo with space")

            assertEquals("s1", sessions.single().id)
            assertEquals("s2", created.id)
            assertEquals("/session?directory=%2Frepo%20with%20space", server.takeRequest().path)
            val createRequest = server.takeRequest()
            assertEquals("/session?directory=%2Frepo%20with%20space", createRequest.path)
            assertEquals("New", Json.parseToJsonElement(createRequest.body.readUtf8()).jsonObject["title"]!!.jsonPrimitive.content)
        }

    @Test
    fun `sends asynchronous prompt with selected model agent and text part`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(204))
            val client = client()

            client.promptAsync(
                sessionId = "s1",
                request =
                    PromptRequest(
                        providerId = "opencode",
                        modelId = "deepseek-v4-flash-free",
                        agent = "build",
                        text = "hello",
                    ),
            )

            val request = server.takeRequest()
            assertEquals("/session/s1/prompt_async", request.path)
            val json = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("build", json["agent"]!!.jsonPrimitive.content)
            assertEquals("opencode", json["model"]!!.jsonObject["providerID"]!!.jsonPrimitive.content)
            assertEquals("deepseek-v4-flash-free", json["model"]!!.jsonObject["modelID"]!!.jsonPrimitive.content)
            assertEquals("hello", json["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        }

    @Test
    fun `sends attachment as a file part`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(204))
            val client = client()

            client.promptAsync(
                "s1",
                PromptRequest(
                    text = "",
                    attachments = listOf(PromptAttachment("photo.jpg", "image/jpeg", "data:image/jpeg;base64,/9j/4AAQ")),
                ),
            )

            val parts =
                Json.parseToJsonElement(server.takeRequest().body.readUtf8())
                    .jsonObject["parts"]!!.jsonArray
            assertEquals("file", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("data:image/jpeg;base64,/9j/4AAQ", parts[0].jsonObject["url"]!!.jsonPrimitive.content)
        }

    @Test
    fun `summarizes a session with the selected model`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("true"))
            val client = client()

            assertTrue(client.summarizeSession("s1", "opencode", "model-1"))

            val request = server.takeRequest()
            assertEquals("/session/s1/summarize", request.path)
            val json = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("opencode", json["providerID"]!!.jsonPrimitive.content)
            assertEquals("model-1", json["modelID"]!!.jsonPrimitive.content)
        }

    @Test
    fun `responds to permission request`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("true"))
            val client = client()

            val result = client.respondPermission("s1", "perm1", "once")

            assertTrue(result)
            val request = server.takeRequest()
            assertEquals("/session/s1/permissions/perm1", request.path)
            val json = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("once", json["response"]!!.jsonPrimitive.content)
            assertTrue(!json.containsKey("remember"))
        }

    @Test
    fun `permission response succeeds with empty body`() =
        runBlocking {
            server.enqueue(MockResponse())
            val client = client()

            val result = client.respondPermission("s1", "perm1", "once")

            assertTrue(result)
        }

    @Test
    fun `remember once maps to always response`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("true"))
            val client = client()

            val result = client.respondPermission("s1", "perm1", "once", remember = true)

            assertTrue(result)
            val json = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("always", json["response"]!!.jsonPrimitive.content)
        }

    @Test
    fun `answers question request on the workspace it was asked in`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("true"))
            val client = client()

            val result = client.answerQuestion("q-1", listOf(listOf("src"), listOf("docs", "tests")), "/workspace/repo")

            assertTrue(result)
            val request = server.takeRequest()
            // Questions are answered on the request, and the route resolves one OpenCode instance:
            // without the directory the server looks in its own and reports the request as missing.
            assertEquals("/question/q-1/reply?directory=%2Fworkspace%2Frepo", request.path)
            val answers = Json.parseToJsonElement(request.body.readUtf8()).jsonObject["answers"]!!.jsonArray
            assertEquals("src", answers[0].jsonArray[0].jsonPrimitive.content)
            assertEquals("docs", answers[1].jsonArray[0].jsonPrimitive.content)
            assertEquals("tests", answers[1].jsonArray[1].jsonPrimitive.content)
        }

    @Test
    fun `rejects question request`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("true"))
            val client = client()

            assertTrue(client.rejectQuestion("q-1", "/workspace/repo"))
            assertEquals("/question/q-1/reject?directory=%2Fworkspace%2Frepo", server.takeRequest().path)
        }

    @Test
    fun `lists pending questions and tags them with the workspace`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """[{"id":"q-1","sessionID":"s1","questions":[{"question":"Run it?","options":[{"label":"Yes"}]}]}]""",
                ),
            )
            val client = client()

            val pending = client.pendingQuestions("/workspace/repo")

            assertEquals("/question?directory=%2Fworkspace%2Frepo", server.takeRequest().path)
            assertEquals("q-1", pending.single().id)
            assertEquals("s1", pending.single().sessionId)
            // The listing does not echo the directory back, but answering still needs it.
            assertEquals("/workspace/repo", pending.single().directory)
        }

    @Test
    fun `renames session using PATCH with title body`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"id":"s1","title":"Renamed","directory":"/repo","time":{"created":1,"updated":2}}"""))
            val client = client()

            val renamed = client.renameSession("s1", "Renamed", "/repo")

            assertEquals("Renamed", renamed.title)
            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("/session/s1?directory=%2Frepo", request.path)
            assertEquals("Renamed", Json.parseToJsonElement(request.body.readUtf8()).jsonObject["title"]!!.jsonPrimitive.content)
        }

    @Test
    fun `deletes session using DELETE`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("true"))
            val client = client()

            val result = client.deleteSession("s1", "/repo")

            assertTrue(result)
            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/session/s1?directory=%2Frepo", request.path)
        }

    @Test
    fun `failed requests include truncated response body`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
            val client = client()

            val error = runCatching { client.health() }.exceptionOrNull() as OpenCodeApiException
            assertEquals(500, error.statusCode)
            assertTrue(error.message!!.contains("boom"))
        }

    @Test
    fun `lists and reads files for a workspace directory`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """[{"name":"src","path":"src","absolute":"/repo/src","type":"directory","ignored":false},{"name":"README.md","path":"README.md","absolute":"/repo/README.md","type":"file","ignored":false}]""",
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    """{"type":"text","content":"# Hello","mimeType":"text/markdown"}""",
                ),
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
                server.takeRequest().path,
            )
        }

    @Test
    fun `searches text and file paths in a workspace`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """[{"path":{"text":"src/Main.kt"},"lines":{"text":"fun main()"},"line_number":7,"absolute_offset":42,"submatches":[{"match":{"text":"main"},"start":4,"end":8}]}]""",
                ),
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
                server.takeRequest().path,
            )
        }

    @Test
    fun `loads vcs status session diff and todo`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """[{"file":"src/Main.kt","additions":3,"deletions":1,"status":"modified"}]""",
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    """[{"file":"src/Main.kt","patch":"@@ -1 +1 @@","additions":3,"deletions":1,"status":"modified"}]""",
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    """[{"content":"Run tests","status":"in_progress","priority":"high"}]""",
                ),
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
    fun `event stream reconnects after the server closes the connection`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"type\":\"server.connected\",\"properties\":{}}\n\n")
                    .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END),
            )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"type\":\"session.idle\",\"properties\":{\"sessionID\":\"s1\"}}\n\n"),
            )

            val events =
                withTimeout(3_000) {
                    client().events().take(2).toList()
                }

            assertTrue(events[0] is OpenCodeEvent.ServerConnected)
            assertEquals("s1", (events[1] as OpenCodeEvent.SessionIdle).sessionId)
            // The cross-instance stream is required: `/event` only carries events for the
            // instance rooted at the server's own working directory, so a session created in
            // any other workspace would emit nothing there.
            assertEquals("/global/event", server.takeRequest().path)
            assertEquals("/global/event", server.takeRequest().path)
        }

    @Test
    fun `event stream falls back to the instance route on older servers`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"type\":\"session.idle\",\"properties\":{\"sessionID\":\"s1\"}}\n\n"),
            )

            val events =
                withTimeout(3_000) {
                    client().events().take(1).toList()
                }

            assertEquals("s1", (events.single() as OpenCodeEvent.SessionIdle).sessionId)
            assertEquals("/global/event", server.takeRequest().path)
            assertEquals("/event", server.takeRequest().path)
        }

    @Test
    fun `redacts response body from authentication errors`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("token=super-secret"))

        val error =
            org.junit.Assert.assertThrows(OpenCodeApiException::class.java) {
                runBlocking { client().health() }
            }

        assertEquals(401, error.statusCode)
        assertTrue(error.message.orEmpty().contains("HTTP 401"))
        assertTrue(!error.message.orEmpty().contains("super-secret"))
    }

    @Test
    fun `loads provider auth methods and completes oauth callback`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """{"openai":[{"type":"oauth","label":"ChatGPT Plus/Pro"},{"type":"api","label":"API key"}]}""",
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    """{"url":"https://auth.example/login","method":"code","instructions":"Enter the code"}""",
                ),
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
            val callbackJson = Json.parseToJsonElement(callback.body.readUtf8()).jsonObject
            assertEquals(0, callbackJson["method"]!!.jsonPrimitive.int)
            assertEquals("abc", callbackJson["code"]!!.jsonPrimitive.content)
        }

    @Test
    fun `provider auth methods parse prompts and conditional rules`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """{"custom":[{"type":"oauth","label":"Workspace login","prompts":[{"type":"select","key":"region","message":"Region","options":[{"label":"US","value":"us","hint":"United States"}]},{"type":"text","key":"tenant","message":"Tenant","placeholder":"acme","when":{"key":"region","op":"eq","value":"us"}}]}]}""",
                ),
            )
            val method = client().providerAuthMethods().getValue("custom").single()

            assertEquals("Workspace login", method.label)
            assertEquals("United States", method.prompts.first().options.single().hint)
            assertTrue(method.prompts[1].isVisible(mapOf("region" to "us")))
            assertTrue(!method.prompts[1].isVisible(mapOf("region" to "eu")))
        }

    @Test
    fun `oauth authorize sends provider prompt inputs`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """{"url":"https://auth.example/login","method":"auto","instructions":"Enter code: ABCD"}""",
                ),
            )

            client().authorizeProvider(
                "custom/provider",
                2,
                mapOf("tenant" to "acme", "region" to "us"),
            )

            val request = server.takeRequest()
            assertEquals("/provider/custom%2Fprovider/oauth/authorize", request.path)
            val json = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(2, json["method"]!!.jsonPrimitive.int)
            assertEquals("acme", json["inputs"]!!.jsonObject["tenant"]!!.jsonPrimitive.content)
            assertEquals("us", json["inputs"]!!.jsonObject["region"]!!.jsonPrimitive.content)
        }

    @Test
    fun `sets and removes provider auth on selected runtime`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("true"))
            server.enqueue(MockResponse().setBody("true"))
            val client = client()

            assertTrue(client.setProviderApiKey("custom", "key-value", mapOf("region" to "us")))
            assertTrue(client.removeProviderAuth("custom"))

            val put = server.takeRequest()
            assertEquals("PUT", put.method)
            assertEquals("/auth/custom", put.path)
            val auth = Json.parseToJsonElement(put.body.readUtf8()).jsonObject
            assertEquals("api", auth["type"]!!.jsonPrimitive.content)
            assertEquals("key-value", auth["key"]!!.jsonPrimitive.content)
            assertEquals("us", auth["metadata"]!!.jsonObject["region"]!!.jsonPrimitive.content)
            val delete = server.takeRequest()
            assertEquals("DELETE", delete.method)
            assertEquals("/auth/custom", delete.path)
        }

    @Test
    fun `provider auth errors do not expose response bodies`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setBody("""{"error":"invalid key sk-super-secret"}"""),
            )

            val error =
                runCatching {
                    client().setProviderApiKey("custom", "sk-super-secret")
                }.exceptionOrNull() as OpenCodeApiException

            assertEquals(400, error.statusCode)
            assertTrue(!error.message.orEmpty().contains("sk-super-secret"))
            assertTrue(!error.message.orEmpty().contains("invalid key"))
        }

    @Test
    fun `lists MCP status map entries using map keys as names`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """{"github":{"status":"needs_auth"},"tools":{"status":"connected","tools":{"search":{}}}}""",
                ),
            )

            val result = client().mcpServers()

            assertEquals(listOf("github", "tools"), result.map { it.name })
            assertEquals("needs_auth", result[0].status)
            assertEquals(listOf("search"), result[1].tools)
            assertEquals("/mcp", server.takeRequest().path)
        }

    @Test
    fun `starts MCP OAuth with encoded server name and typed response`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """{"authorizationUrl":"https://auth.example/start","oauthState":"state-1"}""",
                ),
            )

            val result = client().mcpAuth("remote/server")

            assertEquals("https://auth.example/start", result.authorizationUrl)
            assertEquals("state-1", result.oauthState)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/mcp/remote%2Fserver/auth", request.path)
            assertEquals(emptySet<String>(), Json.parseToJsonElement(request.body.readUtf8()).jsonObject.keys)
        }

    @Test
    fun `completes MCP OAuth with code body and typed status`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"status":"connected"}"""))

            val result = client().mcpAuthCallback("server", "code-123")

            assertEquals("connected", result.status)
            val request = server.takeRequest()
            assertEquals("/mcp/server/auth/callback", request.path)
            assertEquals("code-123", Json.parseToJsonElement(request.body.readUtf8()).jsonObject["code"]!!.jsonPrimitive.content)
        }

    @Test
    fun `removes MCP OAuth and decodes success wrapper`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"success":true}"""))

            assertTrue(client().removeMcpAuth("server").success)

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/mcp/server/auth", request.path)
        }

    @Test
    fun `adds MCP server from status map response`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"tools":{"status":"connected"}}"""))
            val body = Json.parseToJsonElement("""{"name":"tools","config":{"type":"local","command":["node"]}}""").jsonObject

            val result = client().addMcpServer(body)

            assertEquals("tools", result.name)
            assertEquals("connected", result.status)
            assertEquals("/mcp", server.takeRequest().path)
        }

    private fun client(password: String? = null): OpenCodeApiClient {
        val profile =
            ConnectionProfile(
                id = "test",
                name = "Test",
                baseUrl = server.url("/").toString(),
                username = "opencode",
                password = password,
                allowInsecureLan = true,
            )
        return OpenCodeApiClient(profile, OkHttpClient())
    }
}
