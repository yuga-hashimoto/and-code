package com.yugahashimoto.andcode.feature.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantStatusTest {
    @Test
    fun `matches full and short active service names only`() {
        val full = "com.example/com.example.AssistantService"
        val short = "com.example/.AssistantService"

        assertTrue(AssistantStatus.matchesConfiguredService(full, full, short))
        assertTrue(AssistantStatus.matchesConfiguredService(short, full, short))
        assertFalse(AssistantStatus.matchesConfiguredService("other/.AssistantService", full, short))
        assertFalse(AssistantStatus.matchesConfiguredService(null, full, short))
    }
}
