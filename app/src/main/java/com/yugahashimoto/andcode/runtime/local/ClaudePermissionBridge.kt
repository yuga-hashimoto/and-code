package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.PermissionRequest
import com.yugahashimoto.andcode.core.api.QuestionOption
import com.yugahashimoto.andcode.core.api.QuestionPrompt
import com.yugahashimoto.andcode.core.api.QuestionRequest
import com.yugahashimoto.andcode.runtime.PermissionResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * File bridge between Claude Code's PermissionRequest hook (guest) and the Android approval UI.
 *
 * The hook writes a request under [hostRoot]/pending], the app surfaces it as a
 * [PermissionRequest]/[QuestionRequest], and [respond]/[answerQuestion] writes the matching
 * response the hook is polling for.
 */
class ClaudePermissionBridge(
    hostRoot: File,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        },
) {
    enum class Kind {
        PERMISSION,
        QUESTION,
        ELICITATION,
    }

    data class Request(
        val requestId: String,
        val androidSessionId: String,
        val kind: Kind,
        val toolName: String,
        val toolInputJson: String,
        val permissionLabel: String,
        val claudeSessionId: String? = null,
    )

    @Serializable
    data class StoredRequest(
        val v: Int = 1,
        val kind: String,
        val requestId: String,
        val androidSessionId: String,
        val claudeSessionId: String? = null,
        val toolName: String,
        val toolInput: JsonElement = JsonObject(emptyMap()),
        val permissionLabel: String = "",
        val createdAtMs: Long = System.currentTimeMillis(),
    )

    @Serializable
    data class StoredResponse(
        val v: Int = 1,
        val decision: String,
        val remember: Boolean = false,
        val message: String? = null,
        val updatedInput: JsonElement? = null,
        val answers: JsonElement? = null,
    ) {
        val answersJson: String?
            get() = answers?.toString()
    }

    @Serializable
    private data class AlwaysRule(
        val toolName: String,
        val commandPrefix: String? = null,
    )

    @Serializable
    private data class AlwaysRulesFile(
        val rules: List<AlwaysRule> = emptyList(),
    )

    private val root = hostRoot.apply { mkdirs() }
    private val pendingDir = File(root, "pending").apply { mkdirs() }
    private val responsesDir = File(root, "responses").apply { mkdirs() }
    private val alwaysFile = File(root, "always-rules.json")
    private val emitted = ConcurrentHashMap.newKeySet<String>()

    fun writeGuestRequest(request: Request) {
        val toolInput =
            runCatching { json.parseToJsonElement(request.toolInputJson) }
                .getOrDefault(JsonObject(emptyMap()))
        val stored =
            StoredRequest(
                kind = request.kind.name.lowercase(),
                requestId = request.requestId,
                androidSessionId = request.androidSessionId,
                claudeSessionId = request.claudeSessionId,
                toolName = request.toolName,
                toolInput = toolInput,
                permissionLabel = request.permissionLabel.ifBlank { request.toolName },
            )
        val file = File(pendingDir, "${request.requestId}.json")
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(stored))
    }

    fun pollPending(): List<Request> {
        val files = pendingDir.listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.name }
        val out = mutableListOf<Request>()
        for (file in files) {
            val stored = runCatching { json.decodeFromString<StoredRequest>(file.readText()) }.getOrNull() ?: continue
            if (!emitted.add(stored.requestId)) continue
            out +=
                Request(
                    requestId = stored.requestId,
                    androidSessionId = stored.androidSessionId,
                    kind =
                        when (stored.kind.lowercase()) {
                            "question" -> Kind.QUESTION
                            "elicitation" -> Kind.ELICITATION
                            else -> Kind.PERMISSION
                        },
                    toolName = stored.toolName,
                    toolInputJson = stored.toolInput.toString(),
                    permissionLabel = stored.permissionLabel.ifBlank { stored.toolName },
                    claudeSessionId = stored.claudeSessionId,
                )
        }
        return out
    }

    fun respond(
        requestId: String,
        response: PermissionResponse,
        remember: Boolean,
        message: String? = null,
    ): Boolean {
        val pending = File(pendingDir, "$requestId.json")
        if (!pending.isFile && !File(responsesDir, "$requestId.json").isFile) {
            // Still allow writing if the guest already cleaned pending but we know the id.
            if (!emitted.contains(requestId) && !pending.isFile) return false
        }
        val storedRequest =
            pending.takeIf { it.isFile }?.let {
                runCatching { json.decodeFromString<StoredRequest>(it.readText()) }.getOrNull()
            }
        val decision =
            when (response) {
                PermissionResponse.REJECT -> "deny"
                PermissionResponse.ONCE, PermissionResponse.ALWAYS -> "allow"
            }
        val rememberFlag = remember || response == PermissionResponse.ALWAYS
        if (rememberFlag && storedRequest != null) {
            rememberRule(storedRequest)
        }
        writeResponse(
            requestId,
            StoredResponse(
                decision = decision,
                remember = rememberFlag,
                message = message ?: if (decision == "deny") "User rejected this action" else null,
            ),
        )
        return true
    }

    fun answerQuestion(
        requestId: String,
        questionsJson: String,
        answers: Map<String, String>,
    ): Boolean {
        val questions =
            runCatching { json.parseToJsonElement(questionsJson) }
                .getOrDefault(JsonArray(emptyList()))
        val answersElement =
            buildJsonObject {
                answers.forEach { (key, value) -> put(key, value) }
            }
        writeResponse(
            requestId,
            StoredResponse(
                decision = "allow",
                updatedInput =
                    buildJsonObject {
                        put("questions", questions)
                        put("answers", answersElement)
                    },
                answers = answersElement,
            ),
        )
        return true
    }

    fun readResponse(requestId: String): StoredResponse? {
        val file = File(responsesDir, "$requestId.json")
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<StoredResponse>(file.readText()) }.getOrNull()
    }

    fun readPending(requestId: String): Request? {
        val file = File(pendingDir, "$requestId.json")
        if (!file.isFile) return null
        val stored = runCatching { json.decodeFromString<StoredRequest>(file.readText()) }.getOrNull() ?: return null
        return Request(
            requestId = stored.requestId,
            androidSessionId = stored.androidSessionId,
            kind =
                when (stored.kind.lowercase()) {
                    "question" -> Kind.QUESTION
                    "elicitation" -> Kind.ELICITATION
                    else -> Kind.PERMISSION
                },
            toolName = stored.toolName,
            toolInputJson = stored.toolInput.toString(),
            permissionLabel = stored.permissionLabel.ifBlank { stored.toolName },
            claudeSessionId = stored.claudeSessionId,
        )
    }

    fun isAlwaysAllowed(
        toolName: String,
        toolInputJson: String,
    ): Boolean {
        val rules = loadAlwaysRules()
        val command =
            runCatching {
                json.parseToJsonElement(toolInputJson).jsonObject["command"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        return rules.any { rule ->
            rule.toolName == toolName &&
                (rule.commandPrefix.isNullOrBlank() || (command?.startsWith(rule.commandPrefix) == true))
        }
    }

    fun toPermissionRequest(request: Request): PermissionRequest =
        PermissionRequest(
            id = request.requestId,
            sessionId = request.androidSessionId,
            permission = request.permissionLabel.ifBlank { request.toolName },
            patterns = listOf(request.toolName),
            metadata =
                mapOf(
                    "toolName" to JsonPrimitive(request.toolName),
                    "toolInput" to runCatching { json.parseToJsonElement(request.toolInputJson) }.getOrDefault(JsonObject(emptyMap())),
                    "kind" to JsonPrimitive(request.kind.name.lowercase()),
                ),
        )

    fun toQuestionRequest(request: Request): QuestionRequest? {
        if (request.kind != Kind.QUESTION) return null
        val root = runCatching { json.parseToJsonElement(request.toolInputJson).jsonObject }.getOrNull() ?: return null
        val questions =
            root["questions"]?.jsonArray?.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val question = obj["question"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val options =
                    obj["options"]?.jsonArray?.mapNotNull { opt ->
                        val o = opt as? JsonObject ?: return@mapNotNull null
                        val label = o["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        QuestionOption(label = label, description = o["description"]?.jsonPrimitive?.contentOrNull)
                    }.orEmpty()
                QuestionPrompt(
                    question = question,
                    header = obj["header"]?.jsonPrimitive?.contentOrNull,
                    options = options,
                    multiple = obj["multiSelect"]?.jsonPrimitive?.contentOrNull == "true",
                    custom = true,
                )
            }.orEmpty()
        if (questions.isEmpty()) return null
        return QuestionRequest(id = request.requestId, sessionId = request.androidSessionId, questions = questions)
    }

    private fun writeResponse(
        requestId: String,
        response: StoredResponse,
    ) {
        responsesDir.mkdirs()
        val file = File(responsesDir, "$requestId.json")
        val tmp = File(responsesDir, "$requestId.json.tmp")
        tmp.writeText(json.encodeToString(response))
        if (!tmp.renameTo(file)) {
            file.writeText(json.encodeToString(response))
            tmp.delete()
        }
    }

    private fun rememberRule(stored: StoredRequest) {
        val command =
            (stored.toolInput as? JsonObject)?.get("command")?.jsonPrimitive?.contentOrNull
        val prefix = command?.split(" ")?.take(2)?.joinToString(" ")
        val existing = loadAlwaysRules().toMutableList()
        val rule = AlwaysRule(toolName = stored.toolName, commandPrefix = prefix)
        if (existing.none { it.toolName == rule.toolName && it.commandPrefix == rule.commandPrefix }) {
            existing += rule
            alwaysFile.writeText(json.encodeToString(AlwaysRulesFile(existing)))
        }
    }

    private fun loadAlwaysRules(): List<AlwaysRule> {
        if (!alwaysFile.isFile) return emptyList()
        return runCatching { json.decodeFromString<AlwaysRulesFile>(alwaysFile.readText()).rules }
            .getOrDefault(emptyList())
    }

    companion object {
        const val GUEST_BRIDGE_PATH = "/root/.andcode/claude-bridge"
        const val HOST_DIR_NAME = "claude-bridge"
    }
}
