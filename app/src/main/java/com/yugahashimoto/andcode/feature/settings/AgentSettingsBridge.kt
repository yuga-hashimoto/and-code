package com.yugahashimoto.andcode.feature.settings

import com.yugahashimoto.andcode.core.util.pollWhileActive
import com.yugahashimoto.andcode.data.settings.AgentSettingDescriptor
import com.yugahashimoto.andcode.data.settings.AgentSettingType
import com.yugahashimoto.andcode.data.settings.AgentSettingsCatalog
import com.yugahashimoto.andcode.data.settings.AppPreferences
import com.yugahashimoto.andcode.data.settings.AppPreferencesRepository
import com.yugahashimoto.andcode.runtime.local.AgentMcpConfig
import com.yugahashimoto.andcode.runtime.local.McpConfigStore
import com.yugahashimoto.andcode.runtime.local.McpServerSpec
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Port that lets the [AgentSettingsBridge] read and write app settings without Android.
 * [AppAgentSettingsStore] backs it with [AppPreferencesRepository].
 */
interface AgentSettingsStore {
    fun current(): AppPreferences

    /** Applies an already validated value (see [AgentSettingsCatalog.coerce]) for a writable key. */
    fun write(
        key: String,
        value: Any?,
    )
}

/** Writes through [AppPreferencesRepository], so the UI flows observe agent changes live. */
class AppAgentSettingsStore(
    private val preferences: AppPreferencesRepository,
) : AgentSettingsStore {
    override fun current(): AppPreferences = preferences.state.value

    @Suppress("CyclomaticComplexMethod", "UNCHECKED_CAST")
    override fun write(
        key: String,
        value: Any?,
    ) {
        when (key) {
            "selectedModel" -> {
                val text = value as String?
                preferences.selectModel(text?.substringBefore('/'), text?.substringAfter('/'))
            }
            "selectedAgent" -> preferences.selectAgent(value as String?)
            "favoriteModelKeys" -> syncModelKeys(current().favoriteModelKeys, value as List<String>, preferences::toggleFavoriteModel)
            "hiddenModelKeys" -> syncModelKeys(current().hiddenModelKeys, value as List<String>, preferences::toggleModelVisibility)
            "pinnedSessionIds" -> {
                val wanted = (value as List<String>).toSet()
                val now = current().pinnedSessionIds
                if ((wanted - now).isNotEmpty()) preferences.setSessionsPinned(wanted - now, pin = true)
                if ((now - wanted).isNotEmpty()) preferences.setSessionsPinned(now - wanted, pin = false)
            }
            "theme" -> preferences.setTheme(value as String)
            "uiFontSize" -> preferences.setUiFontSize(value as Int)
            "codeFontSize" -> preferences.setCodeFontSize(value as Int)
            "syntaxTheme" -> preferences.setSyntaxTheme(value as String)
            "language" -> preferences.setLanguage(value as String)
            "toolCallDetailLevel" -> preferences.setToolCallDetailLevel(value as String)
            "autoExpandReasoning" -> preferences.setAutoExpandReasoning(value as Boolean)
            "sendBehavior" -> preferences.setSendBehavior(value as String)
            "enterToSend" -> preferences.setEnterToSend(value as Boolean)
            "codexFastMode" -> preferences.setCodexFastMode(value as Boolean?)
            "autoArchiveStaleEnabled" -> preferences.setAutoArchiveStaleEnabled(value as Boolean)
            "autoArchiveStaleDays" -> preferences.setAutoArchiveStaleDays(value as Int)
            "autoArchiveMaxSessionsEnabled" -> preferences.setAutoArchiveMaxSessionsEnabled(value as Boolean)
            "autoArchiveMaxSessions" -> preferences.setAutoArchiveMaxSessions(value as Int)
            "localRuntimeIdleStopEnabled" -> preferences.setLocalRuntimeIdleStopEnabled(value as Boolean)
            "ttsEnabled" -> preferences.setTtsEnabled(value as Boolean)
            "ttsProvider" -> preferences.setTtsProvider(value as String)
            "ttsAndroidEngine" -> preferences.setTtsAndroidEngine(value as String?)
            "ttsSpeechRate" -> preferences.setTtsSpeechRate(value as Float)
            "ttsPitch" -> preferences.setTtsPitch(value as Float)
            "ttsOpenAiApiKey" -> preferences.setTtsOpenAiApiKey(value as String)
            "ttsOpenAiVoice" -> preferences.setTtsOpenAiVoice(value as String)
            "ttsOpenAiModel" -> preferences.setTtsOpenAiModel(value as String)
            "ttsElevenLabsApiKey" -> preferences.setTtsElevenLabsApiKey(value as String)
            "ttsElevenLabsVoiceId" -> preferences.setTtsElevenLabsVoiceId(value as String)
            "ttsElevenLabsModel" -> preferences.setTtsElevenLabsModel(value as String)
            "ttsBargeInEnabled" -> preferences.setTtsBargeInEnabled(value as Boolean)
            "continuousConversation" -> preferences.setContinuousConversation(value as Boolean)
            "liveTranscriptEnabled" -> preferences.setLiveTranscriptEnabled(value as Boolean)
            "wakeWordPhrase" -> preferences.setWakeWordPhrase(value as String)
            "wakeWordSensitivity" -> preferences.setWakeWordSensitivity(value as Float)
            "wakeWordModelLanguage" -> preferences.setWakeWordModelLanguage(value as String?)
            else -> throw IllegalArgumentException("setting $key cannot be written by agents")
        }
    }

    /** The repository only exposes toggles for model key sets, so apply the symmetric difference. */
    private fun syncModelKeys(
        now: Set<String>,
        wanted: List<String>,
        toggle: (String, String) -> Unit,
    ) {
        val target = wanted.toSet()
        for (key in (target - now) + (now - target)) toggle(key.substringBefore('/'), key.substringAfter('/'))
    }
}

/**
 * Request/response file bridge between the guest `and-code-settings` MCP server and the app.
 *
 * It follows the same protocol as the schedule bridge: the server writes `pending/<id>.json`
 * under [workspaceDir]/.and-code/settings-bridge (the guest sees `/workspace/.and-code/settings-bridge`),
 * [pollOnce]/[run] execute it and write `responses/<id>.json`. Settings go through [AgentSettingsCatalog]
 * for validation and [AgentSettingsStore] for storage; MCP server registration goes through [mcp].
 */
class AgentSettingsBridge(
    private val workspaceDir: File,
    private val store: AgentSettingsStore,
    private val mcp: McpConfigStore,
    private val pollIntervalMillis: Long = POLL_INTERVAL_MILLIS,
    private val staleAfterMillis: Long = STALE_AFTER_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val bridgeDir: File = File(workspaceDir, BRIDGE_RELATIVE_PATH)

    /** Runs until cancelled. [active] says whether the guest could be talking to the bridge right now. */
    suspend fun run(active: Flow<Boolean>) {
        active.pollWhileActive(pollIntervalMillis) { runCatching { pollOnce() } }
    }

    /** Handles every currently pending request and prunes stale files. */
    fun pollOnce() {
        if (!bridgeDir.isDirectory) return
        val pendingDir = File(bridgeDir, "pending")
        val responsesDir = File(bridgeDir, "responses")
        val now = clock()
        for (file in pendingDir.listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.name }) {
            handlePending(file, responsesDir, now)
        }
        pruneStale(pendingDir, now)
        pruneStale(responsesDir, now)
    }

    private fun handlePending(
        file: File,
        responsesDir: File,
        now: Long,
    ) {
        val requestId = file.nameWithoutExtension
        val request = runCatching { JSONObject(file.readText()) }.getOrNull()
        if (request == null) {
            file.delete()
            writeResponse(responsesDir, requestId, errorResponse("Malformed request"), now)
            return
        }
        // The guest gives up after its reply timeout; running a request it already reported as
        // failed would apply a change the agent was told did not happen.
        val createdAtMs = request.optLong("createdAtMs", 0L)
        if (createdAtMs > 0L && now - createdAtMs > REQUEST_EXPIRES_AFTER_MILLIS) {
            file.delete()
            writeResponse(responsesDir, requestId, errorResponse("Request expired before the AndCode app could handle it"), now)
            return
        }
        val response =
            try {
                execute(request)
            } catch (error: Exception) {
                errorResponse(error.message ?: error::class.simpleName.orEmpty())
            }
        // Drop the request before replying so a crash in between can never re-apply a change.
        file.delete()
        writeResponse(responsesDir, requestId, response, now)
    }

    private fun execute(request: JSONObject): JSONObject {
        val args = request.optJSONObject("args") ?: JSONObject()
        return when (val op = request.optString("op", "")) {
            "list" -> list(args)
            "get" -> {
                val descriptor = AgentSettingsCatalog.find(requiredString(args, "key"))
                ok("setting" to AgentSettingsCatalog.toJson(descriptor, store.current()))
            }
            "set" -> setOne(args)
            "setMany" -> setMany(args)
            "reset" -> {
                val descriptor = AgentSettingsCatalog.find(requiredString(args, "key"))
                applyAll(listOf(descriptor to AgentSettingsCatalog.defaultOf(descriptor)))
            }
            "updateList" -> updateList(args)
            "mcpList" -> ok("servers" to mcp.list(), "builtIn" to JSONArray(AgentMcpConfig.BUILT_IN.sorted()))
            "mcpAdd" -> mcpAdd(args)
            "mcpRemove" -> {
                val name = requiredString(args, "name")
                val changed = mcp.remove(name, agentsArg(args))
                ok("name" to name, "configFilesChanged" to changed, "note" to RESTART_NOTE)
            }
            else -> throw IllegalArgumentException("unknown operation: $op")
        }
    }

    private fun setOne(args: JSONObject): JSONObject {
        val descriptor = AgentSettingsCatalog.find(requiredString(args, "key"))
        require(args.has("value")) { "value is required (use null to clear a nullable setting)" }
        return applyAll(listOf(descriptor to args.get("value")))
    }

    private fun setMany(args: JSONObject): JSONObject {
        val values = requireNotNull(args.optJSONObject("values")) { "values must be an object of key: value" }
        require(values.length() > 0) { "values is empty" }
        return applyAll(values.keys().asSequence().toList().map { AgentSettingsCatalog.find(it) to values.get(it) })
    }

    private fun list(args: JSONObject): JSONObject {
        val category = optionalString(args, "category")
        if (category != null) {
            require(category in AgentSettingsCatalog.categories) {
                "unknown category: $category (one of ${AgentSettingsCatalog.categories.joinToString()})"
            }
        }
        val prefs = store.current()
        val settings = JSONArray()
        AgentSettingsCatalog.descriptors
            .filter { category == null || it.category == category }
            .forEach { settings.put(AgentSettingsCatalog.toJson(it, prefs)) }
        return ok("settings" to settings, "categories" to JSONArray(AgentSettingsCatalog.categories))
    }

    /** Validates every entry first so a batch is applied completely or not at all. */
    private fun applyAll(entries: List<Pair<AgentSettingDescriptor, Any?>>): JSONObject {
        val validated =
            entries.map { (descriptor, raw) ->
                descriptor.protectedReason?.let { throw IllegalArgumentException("${descriptor.key} is read-only for agents. $it") }
                descriptor to AgentSettingsCatalog.coerce(descriptor, raw)
            }
        validated.forEach { (descriptor, value) -> store.write(descriptor.key, value) }
        val prefs = store.current()
        val changed = JSONArray()
        validated.forEach { (descriptor, _) -> changed.put(AgentSettingsCatalog.toJson(descriptor, prefs)) }
        return ok("applied" to changed)
    }

    private fun updateList(args: JSONObject): JSONObject {
        val descriptor = AgentSettingsCatalog.find(requiredString(args, "key"))
        require(descriptor.type == AgentSettingType.STRING_LIST) {
            "${descriptor.key} is not a list setting; use settings_set"
        }
        val add = stringList(args, "add")
        val remove = stringList(args, "remove")
        require(add.isNotEmpty() || remove.isNotEmpty()) { "provide add and/or remove" }
        val removeSet = remove.map { it.trim() }.toSet()

        @Suppress("UNCHECKED_CAST")
        val currentItems = descriptor.read(store.current()) as Collection<String>
        val updated = (currentItems.filterNot { it in removeSet } + add).distinct()
        return applyAll(listOf(descriptor to updated))
    }

    private fun mcpAdd(args: JSONObject): JSONObject {
        val command = optionalStringList(args, "command")
        val spec =
            McpServerSpec(
                name = requiredString(args, "name"),
                command = command,
                url = optionalString(args, "url"),
                env = stringMap(args, "env"),
                headers = stringMap(args, "headers"),
                agents = agentsArg(args),
            )
        mcp.add(spec)
        return ok("name" to spec.name, "agents" to JSONArray(spec.agents.sorted()), "note" to RESTART_NOTE)
    }

    private fun agentsArg(args: JSONObject): Set<String> {
        val given = optionalStringList(args, "agents") ?: return AgentMcpConfig.AGENTS
        return given.toSet()
    }

    private fun writeResponse(
        responsesDir: File,
        requestId: String,
        payload: JSONObject,
        now: Long,
    ) {
        responsesDir.mkdirs()
        val target = File(responsesDir, "$requestId.json")
        val tmp = File(responsesDir, "$requestId.json.tmp")
        tmp.writeText(payload.put("at", now).toString())
        if (!tmp.renameTo(target)) {
            target.writeText(payload.toString())
            tmp.delete()
        }
    }

    private fun pruneStale(
        dir: File,
        now: Long,
    ) {
        for (file in dir.listFiles().orEmpty().filter { it.name.endsWith(".json") || it.name.endsWith(".json.tmp") }) {
            if (now - file.lastModified() > staleAfterMillis) file.delete()
        }
    }

    private fun ok(vararg pairs: Pair<String, Any>): JSONObject {
        val data = JSONObject().apply { pairs.forEach { (key, value) -> put(key, value) } }
        return JSONObject().put("ok", true).put("data", data)
    }

    private fun errorResponse(message: String): JSONObject = JSONObject().put("ok", false).put("error", message)

    private fun requiredString(
        args: JSONObject,
        key: String,
    ): String {
        require(hasValue(args, key)) { "$key is required" }
        return (args.get(key) as? String)?.trim()?.takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("$key must be a non-empty string")
    }

    private fun optionalString(
        args: JSONObject,
        key: String,
    ): String? {
        if (!hasValue(args, key)) return null
        return args.get(key) as? String ?: throw IllegalArgumentException("$key must be a string")
    }

    private fun optionalStringList(
        args: JSONObject,
        key: String,
    ): List<String>? {
        if (!hasValue(args, key)) return null
        val array = args.get(key) as? JSONArray ?: throw IllegalArgumentException("$key must be an array of strings")
        return (0 until array.length()).map { index ->
            array.opt(index) as? String ?: throw IllegalArgumentException("$key must be an array of strings")
        }
    }

    private fun stringList(
        args: JSONObject,
        key: String,
    ): List<String> = optionalStringList(args, key).orEmpty()

    private fun stringMap(
        args: JSONObject,
        key: String,
    ): Map<String, String> {
        if (!hasValue(args, key)) return emptyMap()
        val obj = args.get(key) as? JSONObject ?: throw IllegalArgumentException("$key must be an object of strings")
        return obj.keys().asSequence().associateWith { name ->
            obj.opt(name) as? String ?: throw IllegalArgumentException("$key.$name must be a string")
        }
    }

    private fun hasValue(
        args: JSONObject,
        key: String,
    ): Boolean = args.has(key) && !args.isNull(key)

    companion object {
        /** Path under the workspace (guest-visible at /workspace) where the MCP server writes. */
        const val BRIDGE_RELATIVE_PATH = ".and-code/settings-bridge"
        const val POLL_INTERVAL_MILLIS = 800L
        const val STALE_AFTER_MILLIS = 15 * 60_000L

        /** Just past the guest's 90 s reply timeout (`RESPONSE_TIMEOUT_S` in andcode-settings-mcp.py; keep in step). */
        const val REQUEST_EXPIRES_AFTER_MILLIS = 95_000L

        private const val RESTART_NOTE = "MCP changes apply the next time each agent starts a session."
    }
}
