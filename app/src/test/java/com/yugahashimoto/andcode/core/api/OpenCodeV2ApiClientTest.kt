package com.yugahashimoto.andcode.core.api

import com.yugahashimoto.andcode.data.connection.ConnectionProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Pins the V2 REST mapping against response bodies captured from a live OpenCode 2.0.28 server
 * (`raw/` samples), so a schema drift shows up here instead of as a silently empty chat.
 */
class OpenCodeV2ApiClientTest {
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

    private fun profile(
        password: String? = "secret",
        username: String = "opencode",
    ) = ConnectionProfile(
        id = "test",
        name = "Test",
        baseUrl = server.url("/").toString(),
        username = username,
        password = password,
        allowInsecureLan = true,
    )

    private fun client(password: String? = "secret"): OpenCodeV2ApiClient = OpenCodeV2ApiClient(profile(password), OkHttpClient())

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun noContent() = MockResponse().setResponseCode(204)

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    // ---- probe (ProtocolDetectingOpenCodeApi) ----

    @Test
    fun `probe detects V2 from api info JSON and uses the V2 client`() =
        runBlocking {
            server.enqueue(json(V2_INFO))
            val detector = ProtocolDetectingOpenCodeApi(profile())

            val health = detector.health()

            assertEquals("2.0.28", health.version)
            assertTrue(health.healthy)
            assertEquals(OpenCodeProtocol.V2, detector.protocol)
            val request = server.takeRequest()
            assertEquals("/api/info", request.path)
            assertEquals(Credentials.basic("opencode", "secret"), request.getHeader("Authorization"))
        }

    @Test
    fun `probe treats a V2 401 JSON body as V2 and surfaces a 401`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"_tag":"UnauthorizedError","message":"Authentication required"}"""),
            )
            val detector = ProtocolDetectingOpenCodeApi(profile(password = "wrong"))

            try {
                detector.health()
                fail("expected 401")
            } catch (error: OpenCodeApiException) {
                assertEquals(401, error.statusCode)
            }
            assertEquals(OpenCodeProtocol.V2, detector.protocol)
        }

    @Test
    fun `probe confirms a V1 server that uses a custom username`() =
        runBlocking {
            // A V1 server with OPENCODE_SERVER_USERNAME=alice answers 401 to V2's fixed user and
            // only accepts alice on global/health.
            server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
            server.enqueue(json("""{"healthy":true,"version":"1.18.5"}"""))
            val detector = ProtocolDetectingOpenCodeApi(profile(username = "alice"))

            val health = detector.health()

            assertEquals("1.18.5", health.version)
            assertEquals(OpenCodeProtocol.V1, detector.protocol)
            val info = server.takeRequest()
            assertEquals("/api/info", info.path)
            assertEquals(Credentials.basic("opencode", "secret"), info.getHeader("Authorization"))
            val legacy = server.takeRequest()
            assertEquals("/global/health", legacy.path)
            assertEquals(Credentials.basic("alice", "secret"), legacy.getHeader("Authorization"))
        }

    @Test
    fun `probe falls back to V1 when api info returns the HTML shell`() =
        runBlocking {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>spa</html>"))
            server.enqueue(json("""{"healthy":true,"version":"1.18.5"}"""))
            val detector = ProtocolDetectingOpenCodeApi(profile())

            val health = detector.health()

            assertEquals("1.18.5", health.version)
            assertEquals(OpenCodeProtocol.V1, detector.protocol)
            assertEquals("/api/info", server.takeRequest().path)
            assertEquals("/global/health", server.takeRequest().path)
        }

    @Test
    fun `probe does not cache a transport failure`() =
        runBlocking {
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
            // The legacy fallback after a transport failure finds no V1 server either.
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
            server.enqueue(json(V2_INFO))
            val detector = ProtocolDetectingOpenCodeApi(profile())

            val failed = runCatching { detector.health() }.exceptionOrNull()

            assertTrue(failed is java.io.IOException)
            assertNull("a transport failure must not pin a protocol", detector.protocol)
            assertEquals("2.0.28", detector.health().version)
            assertEquals(OpenCodeProtocol.V2, detector.protocol)
        }

    @Test
    fun `a 5xx from api info neither decides the protocol nor is cached`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(503).setBody("upstream"))
            // A V2 server answers the legacy health path with its SPA shell, not V1 JSON.
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html/>"))
            server.enqueue(json(V2_INFO))
            val detector = ProtocolDetectingOpenCodeApi(profile())

            val failed = runCatching { detector.health() }.exceptionOrNull()

            assertTrue(failed is OpenCodeApiException)
            assertEquals(503, (failed as OpenCodeApiException).statusCode)
            assertNull("a 5xx must not classify the server as V1", detector.protocol)
            assertEquals("2.0.28", detector.health().version)
        }

    @Test
    fun `a V1 server whose api info proxy fails is still confirmed through global health`() =
        runBlocking {
            // Older V1 servers proxy unknown paths to app.opencode.ai; when that host is
            // unreachable, api/info is a 500 even though the server itself is healthy.
            server.enqueue(MockResponse().setResponseCode(500).setBody("proxy error"))
            server.enqueue(json("""{"healthy":true,"version":"1.2.0"}"""))
            val detector = ProtocolDetectingOpenCodeApi(profile())

            val health = detector.health()

            assertEquals("1.2.0", health.version)
            assertEquals(OpenCodeProtocol.V1, detector.protocol)
        }

    @Test
    fun `a 5xx from api info with a non 1 x global health is not taken as V1`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(json("""{"healthy":true,"version":"2.0.28"}"""))
            val detector = ProtocolDetectingOpenCodeApi(profile())

            val failed = runCatching { detector.health() }.exceptionOrNull()

            assertEquals(500, (failed as OpenCodeApiException).statusCode)
            assertNull(detector.protocol)
        }

    @Test
    fun `V1 is only confirmed when global health succeeds`() =
        runBlocking {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html/>"))
            server.enqueue(MockResponse().setResponseCode(502))
            val detector = ProtocolDetectingOpenCodeApi(profile())

            val failed = runCatching { detector.health() }.exceptionOrNull()

            assertTrue(failed is OpenCodeApiException)
            assertNull("an unhealthy global/health is not a V1 verdict", detector.protocol)
        }

    @Test
    fun `v1 health is fetched once, using the global health body from the probe`() =
        runBlocking {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html/>"))
            server.enqueue(json("""{"healthy":true,"version":"1.18.5"}"""))
            val detector = ProtocolDetectingOpenCodeApi(profile())

            assertEquals("1.18.5", detector.health().version)

            assertEquals(2, server.requestCount)
        }

    // ---- sessions ----

    @Test
    fun `lists sessions across pages with directory query and maps location and parent`() =
        runBlocking {
            server.enqueue(json(SESSION_PAGE_1))
            server.enqueue(json(SESSION_PAGE_2))
            val client = client()

            val sessions = client.sessions("/repo")

            assertEquals(listOf("ses_child", "ses_root"), sessions.map { it.id })
            val child = sessions.first { it.id == "ses_child" }
            assertEquals("ses_root", child.parentId)
            assertEquals("/repo", child.directory)
            assertEquals("global", child.projectId)
            assertEquals(1792059546296L, child.time.created)

            val first = server.takeRequest()
            assertEquals("/api/session?directory=%2Frepo&order=desc&limit=200", first.path)
            val second = server.takeRequest()
            assertTrue(second.path!!.contains("cursor=CURSOR_PAGE_2"))
            // The live server answers 400 InvalidCursorError when a cursor page repeats `order`.
            assertFalse(second.path!!.contains("order="))
        }

    @Test
    fun `session list hides sessions archived through metadata`() =
        runBlocking {
            server.enqueue(json(SESSION_ARCHIVED))
            val client = client()

            val sessions = client.sessions()

            assertEquals(listOf("ses_active"), sessions.map { it.id })
        }

    @Test
    fun `archive writes metadata and keeps existing entries`() =
        runBlocking {
            server.enqueue(json(SESSION_WITH_METADATA))
            server.enqueue(noContent())
            server.enqueue(json(SESSION_ONE_ARCHIVED))
            val client = client()

            val archived = client.archiveSession("ses_one")

            assertNotNull(archived.time.archived)
            server.takeRequest()
            val patch = server.takeRequest()
            assertEquals("PATCH", patch.method)
            val body = Json.parseToJsonElement(patch.body.readUtf8()).jsonObject
            val metadata = body["metadata"]!!.jsonObject
            assertEquals("keep", metadata["existing"]!!.jsonPrimitive.content)
            assertTrue(metadata[ARCHIVED_METADATA_KEY]!!.jsonPrimitive.content.toLong() > 0)
            assertNull(body["time"])
        }

    @Test
    fun `create session sends the location directory in the body`() =
        runBlocking {
            server.enqueue(json(SESSION_ONE))
            val client = client()

            val created = client.createSession(title = "contract-A", directory = "/work")

            assertEquals("ses_one", created.id)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/session", request.path)
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("contract-A", body.str("title"))
            assertEquals("/work", body["location"]!!.jsonObject.str("directory"))
        }

    @Test
    fun `session by id unwraps data`() =
        runBlocking {
            server.enqueue(json(SESSION_ONE))

            val session = client().session("ses_one")

            assertEquals("contract-A", session.title)
            assertEquals("/work", session.directory)
            assertEquals("/api/session/ses_one", server.takeRequest().path)
        }

    @Test
    fun `rename patches title then reads the session back`() =
        runBlocking {
            server.enqueue(noContent())
            server.enqueue(json(SESSION_ONE))

            client().renameSession("ses_one", "Renamed")

            val patch = server.takeRequest()
            assertEquals("PATCH", patch.method)
            assertEquals("Renamed", Json.parseToJsonElement(patch.body.readUtf8()).jsonObject.str("title"))
            assertEquals("GET", server.takeRequest().method)
        }

    @Test
    fun `delete session returns true on 204`() =
        runBlocking {
            server.enqueue(noContent())

            assertTrue(client().deleteSession("ses_one"))
            assertEquals("DELETE", server.takeRequest().method)
        }

    // ---- messages ----

    @Test
    fun `maps user with file, assistant reasoning text and tool, idle failed and interrupted, shell`() =
        runBlocking {
            // The fixture is written chronologically; V2 serves newest-first, so reverse the rows on the wire.
            val newestFirst =
                Json.parseToJsonElement(MESSAGES_MIXED).jsonObject["data"]!!.jsonArray.reversed()
            server.enqueue(json(buildJsonObject { put("data", JsonArray(newestFirst)) }.toString()))
            val messages = client().messages("ses_one")

            // Skipped: agentSelected and succeeded-idle rows.
            assertEquals(listOf("msg_user", "msg_assistant", "msg_failed", "msg_interrupted", "msg_shell"), messages.map { it.info.id })

            val user = messages[0]
            assertEquals("user", user.info.role)
            assertEquals(OpenCodeV2Ids.textPartId("msg_user", 0), user.parts[0].id)
            assertEquals("hello", user.text)
            val file = user.parts[1]
            assertEquals("file", file.type)
            assertEquals("data:image/png;base64,AAAA", file.url)
            assertEquals("red.png", file.filename)

            val assistant = messages[1]
            assertEquals("assistant", assistant.info.role)
            assertEquals("build", assistant.info.agent)
            assertEquals("cat-jam-free", assistant.info.model!!.modelId)
            assertEquals("opencode", assistant.info.model!!.providerId)
            assertEquals(OpenCodeV2Ids.reasoningPartId("msg_assistant", 0), assistant.parts[0].id)
            assertEquals("reasoning", assistant.parts[0].type)
            assertEquals(OpenCodeV2Ids.textPartId("msg_assistant", 0), assistant.parts[1].id)
            assertEquals("DONE", assistant.parts[1].text)
            val tool = assistant.parts[2]
            assertEquals("tool", tool.type)
            assertEquals("shell", tool.tool)
            assertEquals("call-1", tool.callID)
            assertEquals("completed", tool.state!!["status"]!!.jsonPrimitive.content)
            assertEquals("README.md\n", tool.state!!["output"]!!.jsonPrimitive.content)
            assertEquals(38, assistant.info.tokens!!.input.toInt())

            val failed = messages[2].info
            assertEquals("ApiError", failed.error!!.name)
            assertEquals("upstream down", failed.error!!.message)
            assertTrue(messages[2].parts.isEmpty())

            assertEquals("MessageAbortedError", messages[3].info.error!!.name)
            assertTrue(messages[3].info.error!!.isAbort)

            val shell = messages[4]
            assertEquals("shell", shell.parts.single().tool)
            assertEquals("pwd", shell.parts.single().state!!["input"]!!.jsonObject.str("command"))
            assertEquals("/work\n", shell.parts.single().state!!["output"]!!.jsonPrimitive.content)
        }

    @Test
    fun `streaming tool state is reported as pending`() =
        runBlocking {
            server.enqueue(json(MESSAGE_STREAMING_TOOL))

            val tool = client().messages("ses_one").single().parts.single()

            assertEquals("pending", tool.state!!["status"]!!.jsonPrimitive.content)
        }

    @Test
    fun `messages follows the cursor newest first and returns chronological order`() =
        runBlocking {
            server.enqueue(json(MESSAGE_PAGE_1))
            server.enqueue(json(MESSAGE_PAGE_2))

            val messages = client().messages("ses_one")

            assertEquals(listOf("msg_a", "msg_b"), messages.map { it.info.id })
            val first = server.takeRequest()
            assertTrue(first.path!!.contains("order=desc"))
            val second = server.takeRequest().path!!
            assertTrue(second.contains("cursor=MSG_CURSOR"))
            assertFalse(second.contains("order="))
        }

    @Test
    fun `a repeated cursor stops the walk instead of looping`() =
        runBlocking {
            // The server keeps returning the same cursor; the walk must stop after the repeat.
            server.enqueue(json("""{"data":[{"id":"msg_x","time":{"created":1},"type":"user","text":"x"}],"cursor":{"next":"LOOP"}}"""))
            server.enqueue(json("""{"data":[{"id":"msg_y","time":{"created":2},"type":"user","text":"y"}],"cursor":{"next":"LOOP"}}"""))

            val messages = client().messages("ses_one")

            assertEquals(2, server.requestCount)
            // Page 1 (msg_x) is the newer record, so chronological order is msg_y then msg_x.
            assertEquals(listOf("msg_y", "msg_x"), messages.map { it.info.id })
        }

    @Test
    fun `when the page cap is reached the newest records are kept`() =
        runBlocking {
            // Every page advances the cursor, so only the cap ends the walk. The walk is newest-first, so
            // page 0 (msg_0) is the newest record; it must survive the cap and be last in chronological order.
            val pages = 100
            repeat(pages) { index ->
                server.enqueue(
                    json(
                        """{"data":[{"id":"msg_$index","time":{"created":$index},"type":"user","text":"t"}],""" +
                            """"cursor":{"next":"C$index"}}""",
                    ),
                )
            }

            val messages = client().messages("ses_one")

            assertEquals(pages, server.requestCount)
            assertEquals("msg_0", messages.last().info.id)
            assertEquals("msg_${pages - 1}", messages.first().info.id)
        }

    @Test
    fun `delete message is unsupported on V2`() {
        val failure = runCatching { runBlocking { client().deleteMessage("ses_one", "msg_a") } }.exceptionOrNull()

        assertTrue(failure is UnsupportedOperationException)
    }

    // ---- prompt flow ----

    @Test
    fun `every prompt re-applies agent and model so an external switch cannot be skipped`() =
        runBlocking {
            // Each prompt sends agent, model, then prompt, even when the selection matches the previous one.
            repeat(2) {
                server.enqueue(noContent()) // agent
                server.enqueue(noContent()) // model
                server.enqueue(json(PROMPT_ACCEPTED)) // prompt
            }
            val client = client()

            client.promptAsync("ses_one", PromptRequest(text = "hi", providerId = "opencode", modelId = "cat-jam-free", agent = "build"))
            client.promptAsync("ses_one", PromptRequest(text = "again", providerId = "opencode", modelId = "cat-jam-free", agent = "build"))

            assertEquals("/api/session/ses_one/agent", server.takeRequest().path)
            val model = server.takeRequest()
            assertEquals("/api/session/ses_one/model", model.path)
            val modelBody = Json.parseToJsonElement(model.body.readUtf8()).jsonObject["model"]!!.jsonObject
            assertEquals("cat-jam-free", modelBody.str("id"))
            assertEquals("opencode", modelBody.str("providerID"))

            val prompt = server.takeRequest()
            assertEquals("/api/session/ses_one/prompt", prompt.path)
            val promptBody = Json.parseToJsonElement(prompt.body.readUtf8()).jsonObject
            assertEquals("hi", promptBody.str("text"))
            assertTrue(promptBody["resume"]!!.jsonPrimitive.content.toBoolean())
            assertNull(promptBody["model"])
            assertNull(promptBody["agent"])

            // The second prompt applies agent and model again, because another client could have changed them.
            assertEquals("/api/session/ses_one/agent", server.takeRequest().path)
            assertEquals("/api/session/ses_one/model", server.takeRequest().path)
            assertEquals("/api/session/ses_one/prompt", server.takeRequest().path)
        }

    @Test
    fun `prompt sends attachments as files with mention and noReply as resume false`() =
        runBlocking {
            server.enqueue(json(PROMPT_ACCEPTED))
            val client = client()

            client.promptAsync(
                "ses_one",
                PromptRequest(
                    text = "see",
                    attachments = listOf(PromptAttachment(filename = "x.png", mime = "image/png", url = "file:///a/x.png")),
                    noReply = true,
                ),
            )

            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            val file = body["files"]!!.jsonArray.single().jsonObject
            assertEquals("file:///a/x.png", file.str("uri"))
            assertEquals("x.png", file.str("name"))
            assertEquals("@x.png", file.getValue("mention").jsonObject.str("text"))
            assertEquals(false, body["resume"]!!.jsonPrimitive.content.toBoolean())
        }

    @Test
    fun `abort posts interrupt and reads interrupted flag`() =
        runBlocking {
            server.enqueue(json("""{"interrupted":true}"""))

            assertTrue(client().abortSession("ses_one"))
            assertEquals("/api/session/ses_one/interrupt", server.takeRequest().path)
        }

    @Test
    fun `concurrent prompts on one session never interleave agent model and prompt`() =
        runBlocking {
            // Two prompts race: the lock must keep each prompt's agent, model and prompt calls together.
            repeat(2) {
                server.enqueue(noContent())
                server.enqueue(noContent())
                server.enqueue(json(PROMPT_ACCEPTED))
            }
            val client = client()
            val request = PromptRequest(text = "x", providerId = "opencode", modelId = "m", agent = "build")

            coroutineScope {
                awaitAll(
                    async { client.promptAsync("ses_one", request) },
                    async { client.promptAsync("ses_one", request.copy(text = "y")) },
                )
            }

            // Two prompts make six requests: each block of three is one prompt, with no other call between.
            val paths = List(6) { server.takeRequest().path }
            paths.chunked(3).forEach { block ->
                assertEquals(
                    listOf("/api/session/ses_one/agent", "/api/session/ses_one/model", "/api/session/ses_one/prompt"),
                    block,
                )
            }
        }

    @Test
    fun `provider catalog includes authenticable integrations without models and marks connections`() =
        runBlocking {
            // Request order inside providers(): provider, model, integration, then the default model.
            server.enqueue(json(PROVIDER_LIST))
            server.enqueue(json(MODEL_LIST))
            server.enqueue(json(INTEGRATION_LIST))
            server.enqueue(json(MODEL_DEFAULT))

            val catalog = client().providers()

            val openai = catalog.all.single { it.id == "302ai" }
            assertEquals("302.AI", openai.name)
            assertTrue("an integration with no models must not gain any", openai.models.isEmpty())
            // 302ai has no connections, so it is listed for credentials but is not connected to chat.
            assertTrue(catalog.all.any { it.id == "302ai" })
            assertTrue("302ai" !in catalog.connected)
        }

    @Test
    fun `key connect sends the dialog prompt inputs as the form answer`() =
        runBlocking {
            server.enqueue(json(INTEGRATION_WITH_FORM))
            server.enqueue(noContent())
            val client = client()
            client.providerAuthMethods()
            server.takeRequest()

            client.setProviderApiKey("custom", "sk-x", mapOf("baseUrl" to "https://api.example.test"))

            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("sk-x", body.str("key"))
            assertEquals("https://api.example.test", body["answer"]!!.jsonObject.str("baseUrl"))
        }

    @Test
    fun `integration methods with unrepresentable forms are left out of auth`() =
        runBlocking {
            server.enqueue(json(INTEGRATION_WITH_UNREPRESENTABLE))

            val methods = client().providerAuthMethods()

            assertNull("a boolean-field method cannot be shown, so the provider offers no auth", methods["bool-only"])
        }

    @Test
    fun `form prompt answer is sent as the option value, not the label`() =
        runBlocking {
            val registry = OpenCodeV2FormRegistry()
            registry.remember(
                V2Form(
                    id = "frm_v",
                    sessionId = "ses_one",
                    fields =
                        listOf(
                            V2FormField(
                                key = "q0",
                                type = "string",
                                options = listOf(V2FormOption(value = "red-value", label = "Red")),
                            ),
                        ),
                ),
            )
            server.enqueue(noContent())
            val client = OpenCodeV2ApiClient(profile(), OkHttpClient(), formRegistry = registry)

            client.answerQuestion("frm_v", listOf(listOf("Red")), null)

            // The form is cached, so the answer is the only request.
            val answer = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["answer"]!!.jsonObject
            assertEquals("red-value", answer["q0"]!!.jsonPrimitive.content)
        }

    @Test
    fun `custom free text passes through an answer unchanged`() =
        runBlocking {
            val registry = OpenCodeV2FormRegistry()
            registry.remember(
                V2Form(
                    id = "frm_c",
                    sessionId = "ses_one",
                    fields = listOf(V2FormField(key = "q0", type = "string", options = listOf(V2FormOption(value = "a")))),
                ),
            )
            server.enqueue(noContent())
            val client = OpenCodeV2ApiClient(profile(), OkHttpClient(), formRegistry = registry)

            client.answerQuestion("frm_c", listOf(listOf("something else")), null)

            // The form is cached, so the answer is the only request.
            val answer = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["answer"]!!.jsonObject
            assertEquals("something else", answer["q0"]!!.jsonPrimitive.content)
        }

    @Test
    fun `aborted error type maps to the abort name so it is not shown as a failure`() =
        runBlocking {
            server.enqueue(json(MESSAGE_ABORTED_ERROR))

            val message = client().messages("ses_one").single()

            assertEquals("MessageAbortedError", message.info.error!!.name)
            assertTrue(message.info.error!!.isAbort)
        }

    @Test
    fun `update config fails with a readable message on V2`() {
        val failure =
            runCatching { runBlocking { client().updateConfig(JsonObject(emptyMap())) } }.exceptionOrNull()

        assertTrue(failure is UnsupportedOperationException)
        assertTrue(failure!!.message!!.contains("shell settings"))
    }

    @Test
    fun `summarize posts compact`() =
        runBlocking {
            server.enqueue(json("""{"data":{"id":"msg_c","type":"compaction"}}"""))

            assertTrue(client().summarizeSession("ses_one", "opencode", "cat-jam-free"))
            assertEquals("/api/session/ses_one/compact", server.takeRequest().path)
        }

    // ---- permissions and forms ----

    @Test
    fun `permission reply posts decision and maps remember once to always`() =
        runBlocking {
            server.enqueue(noContent())
            server.enqueue(noContent())

            client().respondPermission("ses_one", "per_1", "once", remember = false)
            client().respondPermission("ses_one", "per_2", "once", remember = true)

            val first = server.takeRequest()
            assertEquals("/api/session/ses_one/permission/per_1/reply", first.path)
            assertEquals("once", Json.parseToJsonElement(first.body.readUtf8()).jsonObject.str("decision"))
            assertEquals("always", Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.run { str("decision") })
        }

    @Test
    fun `answer form posts flat answer map keyed by field`() =
        runBlocking {
            server.enqueue(json(FORM_LIST))
            server.enqueue(noContent())
            val client = client()

            assertTrue(client.answerQuestion("frm_1", listOf(listOf("Blue")), "/work"))

            val list = server.takeRequest()
            assertEquals("/api/form?location%5Bdirectory%5D=%2Fwork", list.path)
            val reply = server.takeRequest()
            assertEquals("/api/session/ses_one/form/frm_1/reply", reply.path)
            val answer = Json.parseToJsonElement(reply.body.readUtf8()).jsonObject["answer"]!!.jsonObject
            assertEquals("Blue", answer["q0"]!!.jsonPrimitive.content)
        }

    @Test
    fun `answer uses cached form and multiselect as an array`() =
        runBlocking {
            val registry = OpenCodeV2FormRegistry()
            registry.remember(
                V2Form(
                    id = "frm_m",
                    sessionId = "ses_one",
                    fields = listOf(V2FormField(key = "tags", type = "multiselect", options = listOf(V2FormOption("a")))),
                ),
            )
            server.enqueue(noContent())
            val client = OpenCodeV2ApiClient(profile(), OkHttpClient(), formRegistry = registry)

            client.answerQuestion("frm_m", listOf(listOf("a", "b")), null)

            val reply = server.takeRequest()
            assertEquals("/api/session/ses_one/form/frm_m/reply", reply.path)
            val tags = Json.parseToJsonElement(reply.body.readUtf8()).jsonObject["answer"]!!.jsonObject["tags"]!!.jsonArray
            assertEquals(listOf("a", "b"), tags.map { it.jsonPrimitive.content })
        }

    @Test
    fun `reject form deletes it by session and form id`() =
        runBlocking {
            server.enqueue(json(FORM_LIST))
            server.enqueue(noContent())

            assertTrue(client().rejectQuestion("frm_1", "/work"))

            server.takeRequest()
            val delete = server.takeRequest()
            assertEquals("DELETE", delete.method)
            assertEquals("/api/session/ses_one/form/frm_1", delete.path)
        }

    @Test
    fun `pending questions map forms to question requests`() =
        runBlocking {
            server.enqueue(json(FORM_LIST))

            val requests = client().pendingQuestions("/work")

            val request = requests.single()
            assertEquals("frm_1", request.id)
            assertEquals("ses_one", request.sessionId)
            assertEquals("/work", request.directory)
            val prompt = request.questions.single()
            assertEquals("Which color do you prefer?", prompt.question)
            assertEquals("Color preference", prompt.header)
            assertEquals(listOf("Red", "Blue"), prompt.options.map { it.label })
        }

    // ---- providers ----

    @Test
    fun `provider catalog groups models by provider and reads the default`() =
        runBlocking {
            // Request order inside providers(): provider, model, integration, then the default model.
            server.enqueue(json(PROVIDER_LIST))
            server.enqueue(json(MODEL_LIST))
            server.enqueue(json(INTEGRATION_LIST))
            server.enqueue(json(MODEL_DEFAULT))

            val catalog = client().providers()

            val opencode = catalog.all.single { it.id == "opencode" }
            assertEquals("OpenCode", opencode.name)
            assertEquals(setOf("cat-jam-free", "longcat-2.5-preview-free"), opencode.models.keys)
            assertEquals(listOf("low", "medium"), opencode.models.getValue("cat-jam-free").variants.keys.toList().take(2))
            assertEquals(mapOf("opencode" to "cat-jam-free"), catalog.default)
            assertEquals(listOf("opencode"), catalog.connected)
            assertTrue(catalog.all.none { provider -> provider.models.containsKey("disabled-model") })
        }

    @Test
    fun `agents map id to name, keep mode and drop hidden agents`() =
        runBlocking {
            server.enqueue(json(AGENT_LIST))

            val agents = client().agents()

            assertEquals(listOf("build", "orchestrator"), agents.map { it.name })
            assertEquals("primary", agents[0].mode)
        }

    @Test
    fun `provider auth lists key and oauth methods and sends the key`() =
        runBlocking {
            server.enqueue(json(INTEGRATION_LIST))
            server.enqueue(noContent())
            val client = client()

            val methods = client.providerAuthMethods()

            assertEquals("API key", methods.getValue("302ai").single().label)
            assertEquals("oauth", methods.getValue("opencode").last().type)
            assertEquals("/api/integration", server.takeRequest().path)
            assertTrue(client.setProviderApiKey("302ai", "sk-test"))
            // Key-based connect is on the integration endpoint.
            val connect = server.takeRequest()
            assertEquals("/api/integration/302ai/connect/key", connect.path)
            assertEquals("sk-test", Json.parseToJsonElement(connect.body.readUtf8()).jsonObject.str("key"))
        }

    @Test
    fun `remove provider auth deletes every credential of that integration`() =
        runBlocking {
            server.enqueue(json(CREDENTIAL_LIST))
            server.enqueue(noContent())
            server.enqueue(noContent())

            assertTrue(client().removeProviderAuth("302ai"))

            server.takeRequest()
            assertEquals("/api/credential/cred_1", server.takeRequest().path)
            assertEquals("/api/credential/cred_2", server.takeRequest().path)
        }

    @Test
    fun `oauth authorize posts the method id and remembers the attempt for completion`() =
        runBlocking {
            server.enqueue(json(INTEGRATION_LIST))
            server.enqueue(json(OAUTH_ATTEMPT))
            server.enqueue(noContent())
            val client = client()
            client.providerAuthMethods()
            assertEquals("/api/integration", server.takeRequest().path)

            val auth = client.authorizeProvider("opencode", methodIndex = 1)

            assertEquals("https://example.test/auth", auth.url)
            val authorize = server.takeRequest()
            assertEquals("/api/integration/opencode/connect/oauth", authorize.path)
            assertEquals("device", Json.parseToJsonElement(authorize.body.readUtf8()).jsonObject.str("methodID"))

            assertTrue(client.completeProviderOAuth("opencode", methodIndex = 1, code = null))
            assertEquals("/api/integration/opencode/connect/oauth/att_1/complete", server.takeRequest().path)
        }

    @Test
    fun `projects and location map to the V1 shapes`() =
        runBlocking {
            server.enqueue(json(PROJECT_LIST))
            server.enqueue(json(LOCATION))
            server.enqueue(json(LOCATION))
            val client = client()

            val projects = client.projects()
            val current = client.currentProject("/work")
            val path = client.pathInfo("/work")

            assertEquals("global", projects.single().id)
            assertEquals("/work", current.worktree)
            assertEquals("/work", path.directory)
            assertEquals("/api/project", server.takeRequest().path)
            assertEquals("location%5Bdirectory%5D=%2Fwork", server.takeRequest().path!!.substringAfter("?"))
        }

    // ---- files, vcs ----

    @Test
    fun `file content reads the raw body as text`() =
        runBlocking {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/markdown").setBody("hello\n"))

            val content = client().fileContent("/work", "README.md")

            assertEquals("text", content.type)
            assertEquals("hello\n", content.content)
            assertEquals("/api/fs/read/README.md?location%5Bdirectory%5D=%2Fwork", server.takeRequest().path)
        }

    @Test
    fun `file content marks binary bodies as base64`() =
        runBlocking {
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))

            val content = client().fileContent("/work", "logo.png")

            assertEquals("binary", content.type)
            assertEquals("base64", content.encoding)
            assertEquals("AQID", content.content)
        }

    @Test
    fun `file list maps entries to nodes`() =
        runBlocking {
            server.enqueue(json(FS_LIST))

            val nodes = client().files("/work", "")

            assertEquals(listOf(".git", "README.md"), nodes.map { it.name })
            assertEquals("directory", nodes[0].type)
            assertEquals("/work/README.md", nodes[1].absolute)
        }

    @Test
    fun `vcs info reads the current branch`() =
        runBlocking {
            server.enqueue(json(VCS_INFO))
            server.enqueue(json("""{"data":{"name":"main","ref":"origin/main","source":"default"}}"""))

            val info = client().vcsInfo("/work")

            assertEquals("master", info.branch)
            assertEquals("main", info.defaultBranch)
        }

    @Test
    fun `vcs diff maps the V1 git mode to working`() =
        runBlocking {
            server.enqueue(json(VCS_DIFF))

            val changes = client().vcsDiff("/work", mode = "git")

            assertEquals("README.md", changes.single().displayPath)
            assertEquals("working", server.takeRequest().requestUrl!!.queryParameter("mode"))
        }

    @Test
    fun `find files filters by type`() =
        runBlocking {
            server.enqueue(json(FS_FIND))

            val paths = client().findFiles("/work", "README", includeDirectories = false)

            assertEquals(listOf("README.md"), paths)
            val request = server.takeRequest()
            assertEquals("file", request.requestUrl!!.queryParameter("type"))
            assertEquals("README", request.requestUrl!!.queryParameter("query"))
        }

    @Test
    fun `session todo is always empty on V2`() =
        runBlocking {
            assertEquals(emptyList<OpenCodeTodo>(), client().sessionTodo("ses_one"))
            assertEquals(0, server.requestCount)
        }

    // ---- misc, auth ----

    @Test
    fun `basic auth always uses username opencode`() =
        runBlocking {
            server.enqueue(json(SESSION_ONE))
            val client =
                OpenCodeV2ApiClient(
                    profile().copy(username = "someone-else"),
                    OkHttpClient(),
                )

            client.session("ses_one")

            assertEquals(Credentials.basic("opencode", "secret"), server.takeRequest().getHeader("Authorization"))
        }

    @Test
    fun `config merges document info objects`() =
        runBlocking {
            server.enqueue(json(CONFIG_DOCUMENTS))

            val config = client().config().jsonObject

            assertEquals("ask", config.getValue("permissions").jsonArray.first().jsonObject.str("effect"))
            assertEquals("https://opencode.ai/config.json", config.str("\$schema"))
        }

    @Test
    fun `events stream parses data lines through the translator`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(
                        "data: {\"id\":\"evt_1\",\"type\":\"server.connected\",\"data\":{}}\n\n" +
                            "data: {\"id\":\"evt_2\",\"type\":\"session.idle\",\"data\":{}}\n\n",
                    ),
            )
            // server.connected triggers reconciliation: permissions, forms and active sessions, all empty here.
            server.enqueue(json("""{"data":[]}"""))
            server.enqueue(json("""{"data":[]}"""))
            server.enqueue(json("""{"data":{}}"""))
            val client = client()

            val first = withTimeout(10_000) { client.events().first() }

            assertEquals(OpenCodeEvent.ServerConnected, first)
            assertEquals("/api/event", server.takeRequest().path)
        }

    @Test
    fun `reconnect replays pending permissions forms and busy sessions after server connected`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"id\":\"evt_1\",\"type\":\"server.connected\",\"data\":{}}\n\n"),
            )
            server.enqueue(json("""{"data":[{"id":"per_1","sessionID":"ses_one","action":"shell","resources":["pwd"]}]}"""))
            server.enqueue(json(FORM_LIST))
            server.enqueue(json("""{"data":{"ses_one":{"type":"running"}}}"""))
            server.enqueue(json(MESSAGE_RUNNING_TOOL))
            val client = client()

            val events = withTimeout(10_000) { client.events().take(4).toList() }

            assertEquals(OpenCodeEvent.ServerConnected, events[0])
            val permission = events[1] as OpenCodeEvent.PermissionAsked
            assertEquals("per_1", permission.request.id)
            assertEquals("shell", permission.request.permission)
            assertTrue(events[2] is OpenCodeEvent.QuestionAsked)
            assertEquals(OpenCodeEvent.SessionStatusChanged("ses_one", "busy"), events[3])
        }

    @Test
    fun `a failing reconcile step does not end the stream`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"id\":\"evt_1\",\"type\":\"server.connected\",\"data\":{}}\n\n"),
            )
            server.enqueue(MockResponse().setResponseCode(500)) // permissions fail
            server.enqueue(json("""{"data":[]}""")) // forms
            server.enqueue(json("""{"data":{}}""")) // active
            val client = client()

            // take(1) cancels the stream once ServerConnected arrives, so the failing reconcile steps never block the test.
            val events = withTimeout(10_000) { client.events().take(1).toList() }

            assertEquals(listOf<OpenCodeEvent>(OpenCodeEvent.ServerConnected), events)
        }

    private companion object {
        const val ARCHIVED_METADATA_KEY = "andcode.archivedAt"

        val MESSAGE_RUNNING_TOOL =
            """{"data":[{"id":"msg_r","time":{"created":1},"type":"assistant","content":[""" +
                """{"type":"tool","id":"call-r","name":"shell","executed":false,"state":{"status":"running","input":{"command":"pwd"}}}]}]}"""

        val V2_INFO = """{"version":"2.0.28","pid":1,"urls":["http://127.0.0.1"],"paths":{"tmp":"/tmp"},"capabilities":{}}"""

        val SESSION_ONE =
            """{"data":{"id":"ses_one","projectID":"global","cost":0,"tokens":{"input":0,"output":0,"reasoning":0,""" +
                """"cache":{"read":0,"write":0}},"time":{"created":1792059536138,"updated":1792059536138},""" +
                """"title":"contract-A","location":{"directory":"/work"}}}"""

        val SESSION_WITH_METADATA =
            """{"data":{"id":"ses_one","projectID":"global","time":{"created":1,"updated":2},"title":"contract-A",""" +
                """"location":{"directory":"/work"},"metadata":{"existing":"keep"}}}"""

        val SESSION_ONE_ARCHIVED =
            """{"data":{"id":"ses_one","projectID":"global","time":{"created":1,"updated":2},"title":"contract-A",""" +
                """"location":{"directory":"/work"},"metadata":{"existing":"keep","andcode.archivedAt":9}}}"""

        val SESSION_PAGE_1 =
            """{"data":[{"id":"ses_child","parentID":"ses_root","projectID":"global","time":{"created":1792059546296,""" +
                """"updated":1792059546296},"title":"child","location":{"directory":"/repo"}}],""" +
                """"cursor":{"previous":null,"next":"CURSOR_PAGE_2"}}"""

        val SESSION_PAGE_2 =
            """{"data":[{"id":"ses_root","projectID":"global","time":{"created":1,"updated":1},"title":"root",""" +
                """"location":{"directory":"/repo"}}],"cursor":{"previous":null,"next":null}}"""

        val SESSION_ARCHIVED =
            """{"data":[{"id":"ses_active","projectID":"global","time":{"created":1,"updated":1},"title":"a",""" +
                """"location":{"directory":"/repo"}},{"id":"ses_gone","projectID":"global","time":{"created":1,"updated":1},""" +
                """"title":"b","location":{"directory":"/repo"},"metadata":{"andcode.archivedAt":5}}],""" +
                """"cursor":{"previous":null,"next":null}}"""

        val MESSAGES_MIXED =
            """{"data":[""" +
                """{"id":"msg_user","time":{"created":10},"type":"user","text":"hello","files":[{"data":"AAAA","mime":"image/png",""" +
                """"source":{"type":"uri","uri":"file:///x/red.png"},"name":"red.png","mention":{"start":0,"end":0,"text":"@red.png"}}]},""" +
                """{"id":"msg_sel","time":{"created":11},"type":"agentSelected","agent":"build"},""" +
                """{"id":"msg_assistant","time":{"created":12,"completed":13},"type":"assistant","agent":"build",""" +
                """"model":{"id":"cat-jam-free","providerID":"opencode"},"content":[""" +
                """{"type":"reasoning","text":"thinking"},{"type":"text","text":"DONE"},""" +
                """{"type":"tool","id":"call-1","name":"shell","executed":false,"state":{"status":"completed","input":{"command":"ls"},""" +
                """"content":[{"type":"text","text":"README.md\n"}],"metadata":{"exit":0}}}],""" +
                """"tokens":{"input":38,"output":2,"reasoning":1,"cache":{"read":0,"write":0}}},""" +
                """{"id":"msg_failed","time":{"created":14},"type":"idle","outcome":"failed","error":{"type":"ApiError","message":"upstream down"}},""" +
                """{"id":"msg_interrupted","time":{"created":15},"type":"idle","outcome":"interrupted"},""" +
                """{"id":"msg_ok","time":{"created":16},"type":"idle","outcome":"succeeded"},""" +
                """{"id":"msg_shell","time":{"created":17},"type":"shell","shellID":"sh_1","command":"pwd","status":"exited","exit":0,""" +
                """"output":{"output":"/work\n","cursor":0,"size":5,"truncated":false}}]}"""

        val MESSAGE_STREAMING_TOOL =
            """{"data":[{"id":"msg_s","time":{"created":1},"type":"assistant","content":[""" +
                """{"type":"tool","id":"call-s","name":"shell","executed":false,"state":{"status":"streaming","input":{}}}]}]}"""

        // The server pages newest-first, so page 1 holds the most recent record.
        val MESSAGE_PAGE_1 =
            """{"data":[{"id":"msg_b","time":{"created":2},"type":"user","text":"b"}],"cursor":{"previous":null,"next":"MSG_CURSOR"}}"""

        val MESSAGE_PAGE_2 =
            """{"data":[{"id":"msg_a","time":{"created":1},"type":"user","text":"a"}],"cursor":{"previous":null,"next":null}}"""

        val PROMPT_ACCEPTED = """{"data":{"id":"msg_p","sessionID":"ses_one","time":{"created":1},"type":"user","payload":{"text":"hi"},"delivery":"steer"}}"""

        val FORM_LIST =
            """{"data":[{"id":"frm_1","sessionID":"ses_one","title":"Questions","fields":[{"key":"q0","title":"Color preference",""" +
                """"description":"Which color do you prefer?","type":"string","options":[{"value":"Red","label":"Red"},""" +
                """{"value":"Blue","label":"Blue"}],"custom":true}]}]}"""

        val PROVIDER_LIST =
            """{"data":[{"id":"opencode","integrationID":"opencode","name":"OpenCode","activation":"enabled"}]}"""

        val MODEL_LIST =
            """{"data":[{"id":"cat-jam-free","modelID":"cat-jam-free","providerID":"opencode","name":"Cat Jam Free",""" +
                """"variants":[{"id":"low","settings":{"reasoningEffort":"low"}},{"id":"medium","settings":{"reasoningEffort":"medium"}}],""" +
                """"status":"active","enabled":true,"limit":{"context":262144,"output":131072}},""" +
                """{"id":"longcat-2.5-preview-free","modelID":"longcat-2.5-preview-free","providerID":"opencode",""" +
                """"name":"LongCat","variants":[],"status":"active","enabled":true},""" +
                """{"id":"disabled-model","modelID":"disabled-model","providerID":"opencode","name":"Off","enabled":false}]}"""

        val MODEL_DEFAULT =
            """{"data":{"id":"cat-jam-free","modelID":"cat-jam-free","providerID":"opencode","name":"Cat Jam Free","variants":[]}}"""

        val AGENT_LIST =
            """{"data":[{"id":"build","name":"Build","description":"The default agent.","mode":"primary","hidden":false,"permissions":[]},""" +
                """{"id":"orchestrator","name":"Orchestrator","mode":"primary","hidden":null,"permissions":[]},""" +
                """{"id":"compaction","name":"Compaction","description":"internal","mode":"primary","hidden":true,"permissions":[]}]}"""

        val INTEGRATION_LIST =
            """{"data":[{"id":"302ai","name":"302.AI","methods":[{"type":"key"},{"type":"env","names":["302AI_API_KEY"]}],"connections":[]},""" +
                """{"id":"opencode","name":"OpenCode Console","methods":[{"type":"key","label":"API key (service account)"},""" +
                """{"id":"device","type":"oauth","label":"OpenCode Console account"}],"connections":[]}]}"""

        // A key method whose form asks for a base URL (a text field the dialog can render).
        val INTEGRATION_WITH_FORM =
            """{"data":[{"id":"custom","name":"Custom","methods":[{"type":"key","label":"API key","form":[""" +
                """{"key":"baseUrl","type":"string","title":"Base URL","description":"Endpoint","placeholder":"https://"}]}],""" +
                """"connections":[]}]}"""

        // A method with a boolean field: the dialog cannot render it, so the whole method must be dropped.
        val INTEGRATION_WITH_UNREPRESENTABLE =
            """{"data":[{"id":"bool-only","name":"Bool","methods":[{"type":"key","form":[""" +
                """{"key":"enabled","type":"boolean"}]}],"connections":[]}]}"""

        val MESSAGE_ABORTED_ERROR =
            """{"data":[{"id":"msg_err","time":{"created":1},"type":"assistant","content":[],""" +
                """"error":{"type":"aborted","message":"stopped"}}]}"""

        val CREDENTIAL_LIST =
            """{"data":[{"id":"cred_1","integrationID":"302ai","label":"a","active":true,"value":{"type":"key","key":"x"}},""" +
                """{"id":"cred_2","integrationID":"302ai","label":"b","active":false,"value":{"type":"key","key":"y"}},""" +
                """{"id":"cred_3","integrationID":"other","label":"c","active":true,"value":{"type":"key","key":"z"}}]}"""

        val OAUTH_ATTEMPT =
            """{"data":{"attemptID":"att_1","url":"https://example.test/auth","instructions":"Enter the code","mode":"auto",""" +
                """"time":{"created":1,"expires":2}}}"""

        val PROJECT_LIST = """[{"id":"global","worktree":"/work","canonical":"/work"}]"""

        val LOCATION = """{"location":{"directory":"/work"},"data":{"directory":"/work","project":{"id":"global","canonical":"/work"}}}"""

        val VCS_INFO = """{"data":{"provider":"git","branch":{"current":"master"}}}"""

        val VCS_DIFF =
            """{"data":[{"file":"README.md","patch":"diff --git a/README.md b/README.md\n","additions":1,"deletions":0,"status":"added"}]}"""

        val FS_LIST = """{"data":[{"path":".git/","type":"directory"},{"path":"README.md","type":"file"}]}"""

        val FS_FIND = """{"data":[{"path":"README.md","type":"file"}]}"""

        val CONFIG_DOCUMENTS =
            """[{"type":"document","path":"/cfg/opencode.json","info":{"${'$'}schema":"https://opencode.ai/config.json",""" +
                """"permissions":[{"action":"shell","resource":"*","effect":"ask"}]}},{"type":"directory","path":"/cfg"}]"""
    }
}
