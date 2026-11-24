package com.yugahashimoto.andcode.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class PinnedSessionsTest {
    @Test
    fun `pinnedAfter pins and unpins the given ids`() {
        assertEquals(setOf("a", "b"), pinnedAfter(setOf("a"), setOf("a", "b"), pin = true))
        assertEquals(setOf("b"), pinnedAfter(setOf("a", "b"), setOf("a"), pin = false))
    }

    @Test
    fun `pinnedAfter ignores unknown ids when unpinning`() {
        assertEquals(setOf("a"), pinnedAfter(setOf("a"), setOf("unknown"), pin = false))
    }
}
