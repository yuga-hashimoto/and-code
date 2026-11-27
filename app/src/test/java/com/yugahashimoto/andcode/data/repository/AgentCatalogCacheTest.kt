package com.yugahashimoto.andcode.data.repository

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentCatalogCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun cache() = AgentCatalogCache(temporaryFolder.newFolder("agents"), Json { ignoreUnknownKeys = true })

    @Test
    fun `stores and reads back a selectable list`() {
        val cache = cache()
        val agents =
            listOf(
                OpenCodeAgent("build", mode = "primary"),
                OpenCodeAgent("plan", mode = "primary"),
            )

        cache.write("mac", agents)

        assertEquals(agents, cache.read("mac"))
    }

    @Test
    fun `does not store a list with nothing selectable`() {
        val cache = cache()

        cache.write(
            "mac",
            listOf(
                OpenCodeAgent("title", mode = "subagent"),
                OpenCodeAgent("memory", hidden = true),
            ),
        )

        assertNull(cache.read("mac"))
    }

    @Test
    fun `returns null before anything is stored`() {
        assertNull(cache().read("mac"))
    }
}
