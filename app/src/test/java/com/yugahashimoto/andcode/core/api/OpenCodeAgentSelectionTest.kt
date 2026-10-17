package com.yugahashimoto.andcode.core.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenCodeAgentSelectionTest {
    // The shape OpenCode's GET /agent returns with oh-my-opencode-slim installed and
    // `default_agent: "orchestrator"`: the default first, then build, then the rest by name.
    private val pluginAgents =
        listOf(
            OpenCodeAgent("orchestrator", mode = "primary"),
            OpenCodeAgent("build", mode = "primary", native = true),
            OpenCodeAgent("compaction", mode = "primary", native = true, hidden = true),
            OpenCodeAgent("council", mode = "subagent"),
            OpenCodeAgent("councillor-gpt", mode = "subagent", hidden = true),
            OpenCodeAgent("fixer", mode = "subagent"),
            OpenCodeAgent("plan", mode = "primary", native = true),
            OpenCodeAgent("summary", mode = "primary", native = true, hidden = true),
            OpenCodeAgent("title", mode = "primary", native = true, hidden = true),
        )

    @Test
    fun `offers plugin primaries alongside build and plan, but not subagents or hidden agents`() {
        assertEquals(
            listOf("orchestrator", "build", "plan"),
            pluginAgents.selectablePrimaryAgents().map { it.name },
        )
    }

    @Test
    fun `offers agents whose mode is all or unreported`() {
        val agents =
            listOf(
                OpenCodeAgent("build", mode = "primary"),
                OpenCodeAgent("both", mode = "all"),
                OpenCodeAgent("legacy"),
            )

        assertEquals(listOf("build", "both", "legacy"), agents.selectablePrimaryAgents().map { it.name })
    }

    @Test
    fun `defaults to the agent the server lists first, which is its default_agent`() {
        assertEquals("orchestrator", pluginAgents.defaultAgentName())
    }

    @Test
    fun `defaults to build on a stock server`() {
        val stock =
            listOf(
                OpenCodeAgent("build", mode = "primary", native = true),
                OpenCodeAgent("plan", mode = "primary", native = true),
            )

        assertEquals("build", stock.defaultAgentName())
    }

    @Test
    fun `has no default when the runtime reports no agents`() {
        assertNull(emptyList<OpenCodeAgent>().defaultAgentName())
    }

    @Test
    fun `decodes the hidden flag and treats a missing one as visible`() {
        val json = Json { ignoreUnknownKeys = true }
        val agents =
            json.decodeFromString<List<OpenCodeAgent>>(
                """[{"name":"title","mode":"primary","hidden":true,"native":true},{"name":"orchestrator","mode":"primary"}]""",
            )

        assertEquals(true, agents[0].hidden)
        assertEquals(listOf("orchestrator"), agents.selectablePrimaryAgents().map { it.name })
    }

    @Test
    fun `decodes plugin agents that send an explicit null hidden flag`() {
        // Shape of OpenCode 1.19.3's GET /agent with oh-my-opencode-slim installed: plugin agents
        // carry "hidden": null, which used to fail decoding of the whole list.
        val agents =
            OpenCodeApiClient.defaultJson.decodeFromString<List<OpenCodeAgent>>(
                """
                [
                  {"name":"orchestrator","mode":"primary","native":false,"hidden":null,"variant":"high"},
                  {"name":"build","description":"Default agent","mode":"primary","native":true},
                  {"name":"compaction","mode":"primary","native":true,"hidden":true},
                  {"name":"fixer","description":"Fast implementation","mode":"subagent","native":false,"hidden":null},
                  {"name":"plan","description":"Plan mode","mode":"primary","native":true}
                ]
                """.trimIndent(),
            )

        assertEquals(listOf("orchestrator", "build", "plan"), agents.selectablePrimaryAgents().map { it.name })
        assertEquals("orchestrator", agents.defaultAgentName())
    }
}
