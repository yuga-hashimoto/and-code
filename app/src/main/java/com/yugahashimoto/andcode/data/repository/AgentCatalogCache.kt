package com.yugahashimoto.andcode.data.repository

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.selectablePrimaryAgents
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Disk cache for a runtime's agent list.
 *
 * A local OpenCode installs its plugins on the first `GET /agent` after every start, which can take
 * over a minute on a phone. Without this the mode chip shows its spinner for that whole window even
 * though the list rarely changes between restarts, and the user cannot tell which agent a new chat
 * would use. The last list is shown straight away and replaced as soon as the fresh read lands.
 *
 * Keyed on the runtime only, not on its version: unlike the provider catalogue, an agent list stays
 * meaningful across an OpenCode upgrade until the new server says otherwise, and every load refreshes
 * it in the background anyway. An empty list is never stored, because that is the shape a server
 * gives while it is still starting.
 */
class AgentCatalogCache(
    private val directory: File,
    private val json: Json,
) {
    fun read(runtimeId: String): List<OpenCodeAgent>? =
        runCatching { json.decodeFromString<List<OpenCodeAgent>>(file(runtimeId).readText()) }
            .getOrNull()
            ?.takeIf { it.selectablePrimaryAgents().isNotEmpty() }

    fun write(
        runtimeId: String,
        agents: List<OpenCodeAgent>,
    ) {
        if (agents.selectablePrimaryAgents().isEmpty()) return
        runCatching {
            directory.mkdirs()
            file(runtimeId).writeText(json.encodeToString(agents))
        }
    }

    private fun file(runtimeId: String) = File(directory, "agents-${runtimeId.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")
}
