package com.yugahashimoto.andcode.runtime.local

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A user-managed MCP server to register with the guest agents. Exactly one of [command] / [url] is set. */
data class McpServerSpec(
    val name: String,
    val command: List<String>? = null,
    val url: String? = null,
    val env: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val agents: Set<String> = AgentMcpConfig.AGENTS,
)

/**
 * Port the settings bridge uses to manage MCP servers across every guest agent at once.
 * Implemented by [AgentMcpConfig] against the rootfs config files.
 */
interface McpConfigStore {
    fun list(): JSONArray

    fun add(spec: McpServerSpec)

    fun remove(
        name: String,
        agents: Set<String>,
    ): Int
}

/**
 * Reads and writes the MCP sections of the three agent config files inside every guest rootfs,
 * the same files [LocalRuntimeInstaller] seeds with the built-in servers:
 *
 * - OpenCode: `root/.config/opencode/opencode.json` (`mcp`)
 * - Claude Code: `root/.claude.json` (`mcpServers`)
 * - Antigravity: `root/.gemini/config/mcp_config.json` (`mcpServers`)
 *
 * Built-in servers are read-only here: the installer re-seeds them on every start anyway, so a
 * removal would silently come back.
 */
class AgentMcpConfig(
    private val rootfsDirs: () -> List<File>,
) : McpConfigStore {
    override fun list(): JSONArray {
        val servers = linkedMapOf<String, JSONObject>()
        for (agent in AGENT_ORDER) {
            for (file in configFiles(agent)) {
                collectServers(agent, file, servers)
            }
        }
        return JSONArray(servers.values.toList())
    }

    private fun collectServers(
        agent: String,
        file: File,
        servers: MutableMap<String, JSONObject>,
    ) {
        val section = readSection(file, agent) ?: return
        for (name in section.keys().asSequence().toList().sorted()) {
            val entry = section.optJSONObject(name) ?: continue
            val json =
                servers.getOrPut(name) {
                    JSONObject()
                        .put("name", name)
                        .put("builtIn", name in BUILT_IN)
                        .put("agents", JSONArray())
                }
            val agents = json.getJSONArray("agents")
            if ((0 until agents.length()).none { agents.getString(it) == agent }) agents.put(agent)
            describe(agent, entry, json)
        }
    }

    override fun add(spec: McpServerSpec) {
        validate(spec)
        val targets =
            AGENT_ORDER
                .filter { it in spec.agents }
                .flatMap { agent -> configFiles(agent).map { agent to it } }
        // Validate every existing target file up front so a corrupt config does not leave partial writes.
        targets.forEach { (_, file) -> readOrCreateRoot(file) }
        for ((agent, file) in targets) {
            mutate(file) { root ->
                val section = root.optJSONObject(sectionKey(agent)) ?: JSONObject()
                section.put(spec.name, entryFor(agent, spec))
                root.put(sectionKey(agent), section)
            }
        }
    }

    /** Removes [name] from [agents]' configs and returns how many config files changed. */
    override fun remove(
        name: String,
        agents: Set<String>,
    ): Int {
        require(name !in BUILT_IN) { "$name is a built-in AndCode server and cannot be removed" }
        require(agents.isNotEmpty() && agents.all { it in AGENTS }) { "agents must be a subset of ${AGENT_ORDER.joinToString()}" }
        var changed = 0
        for (agent in AGENT_ORDER.filter { it in agents }) {
            for (file in configFiles(agent)) {
                val hadIt = readSection(file, agent)?.has(name) == true
                if (!hadIt) continue
                mutate(file) { root -> root.optJSONObject(sectionKey(agent))?.remove(name) }
                changed++
            }
        }
        return changed
    }

    private fun configFiles(agent: String): List<File> = rootfsDirs().map { File(it, CONFIG_PATH.getValue(agent)) }

    private fun sectionKey(agent: String): String = if (agent == OPENCODE) "mcp" else "mcpServers"

    private fun readSection(
        file: File,
        agent: String,
    ): JSONObject? {
        if (!file.isFile) return null
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        return root.optJSONObject(sectionKey(agent))
    }

    private fun readOrCreateRoot(file: File): JSONObject =
        if (file.isFile) {
            // An unreadable file is left alone rather than overwritten with a near-empty one.
            runCatching { JSONObject(file.readText()) }.getOrNull()
                ?: error("${file.name} is not valid JSON; fix it before changing MCP servers")
        } else {
            JSONObject()
        }

    private fun mutate(
        file: File,
        change: (JSONObject) -> Unit,
    ) {
        file.parentFile?.mkdirs()
        val root = readOrCreateRoot(file)
        change(root)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString(2) + "\n")
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun validate(spec: McpServerSpec) {
        require(NAME_PATTERN.matches(spec.name)) { "name must be 1-64 letters, digits, '-' or '_'" }
        require(spec.name !in BUILT_IN) { "${spec.name} is a built-in AndCode server and cannot be replaced" }
        val hasCommand = spec.command != null
        val hasUrl = spec.url != null
        require(hasCommand != hasUrl) { "provide exactly one of command (local server) or url (remote server)" }
        if (hasCommand) {
            require(spec.command!!.isNotEmpty() && spec.command.all { it.isNotBlank() }) {
                "command must be a non-empty list of non-blank arguments"
            }
        } else {
            val url = spec.url.orEmpty()
            require(url.startsWith("http://") || url.startsWith("https://")) { "url must be http(s)" }
        }
        require(spec.agents.isNotEmpty() && spec.agents.all { it in AGENTS }) { "agents must be a subset of ${AGENT_ORDER.joinToString()}" }
    }

    private fun entryFor(
        agent: String,
        spec: McpServerSpec,
    ): JSONObject {
        val command = spec.command
        return if (command != null) {
            localEntry(agent, command, spec.env)
        } else {
            remoteEntry(agent, requireNotNull(spec.url), spec.headers)
        }
    }

    private fun localEntry(
        agent: String,
        command: List<String>,
        env: Map<String, String>,
    ): JSONObject {
        val entry =
            when (agent) {
                OPENCODE ->
                    JSONObject()
                        .put("type", "local")
                        .put("command", JSONArray(command))
                        .put("enabled", true)
                CLAUDE ->
                    JSONObject()
                        .put("type", "stdio")
                        .put("command", command.first())
                        .put("args", JSONArray(command.drop(1)))
                else ->
                    JSONObject()
                        .put("command", command.first())
                        .put("args", JSONArray(command.drop(1)))
            }
        if (env.isNotEmpty()) entry.put(if (agent == OPENCODE) "environment" else "env", JSONObject(env))
        return entry
    }

    private fun remoteEntry(
        agent: String,
        url: String,
        headers: Map<String, String>,
    ): JSONObject {
        val entry =
            when (agent) {
                OPENCODE -> JSONObject().put("type", "remote").put("url", url).put("enabled", true)
                CLAUDE -> JSONObject().put("type", "http").put("url", url)
                else -> JSONObject().put("serverUrl", url)
            }
        if (headers.isNotEmpty()) entry.put("headers", JSONObject(headers))
        return entry
    }

    /** Adds the shape-neutral facts about [entry] to [json]. Env values and headers are never echoed: they can hold secrets. */
    private fun describe(
        agent: String,
        entry: JSONObject,
        json: JSONObject,
    ) {
        val url = entry.optString("url", "").ifEmpty { entry.optString("serverUrl", "") }
        if (url.isNotEmpty()) {
            json.put("kind", "remote").put("url", url)
        } else {
            val command = entry.opt("command")
            val parts =
                if (command is JSONArray) {
                    (0 until command.length()).map { command.getString(it) }
                } else {
                    listOf(entry.optString("command", "")) + (
                        entry.optJSONArray("args")?.let {
                                a ->
                            (0 until a.length()).map { a.getString(it) }
                        }.orEmpty()
                    )
                }
            json.put("kind", "stdio").put("command", JSONArray(parts))
        }
        val envKeys = (entry.optJSONObject("env") ?: entry.optJSONObject("environment"))?.keys()?.asSequence()?.toList().orEmpty()
        if (envKeys.isNotEmpty()) json.put("envKeys", JSONArray(envKeys.sorted()))
        if (agent == OPENCODE && entry.has("enabled")) json.put("enabledInOpenCode", entry.optBoolean("enabled", true))
    }

    companion object {
        const val OPENCODE = "opencode"
        const val CLAUDE = "claude"
        const val ANTIGRAVITY = "antigravity"
        val AGENT_ORDER = listOf(OPENCODE, CLAUDE, ANTIGRAVITY)
        val AGENTS: Set<String> = AGENT_ORDER.toSet()

        val BUILT_IN = setOf("and-code-browser", "and-code-schedule", "and-code-settings")

        private val NAME_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        private val CONFIG_PATH =
            mapOf(
                OPENCODE to "root/.config/opencode/opencode.json",
                CLAUDE to "root/.claude.json",
                ANTIGRAVITY to "root/.gemini/config/mcp_config.json",
            )
    }
}
