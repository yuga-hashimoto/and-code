package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Test

class CodexUpdateTest {
    @Test
    fun `an update reports both sides of the version change`() {
        assertEquals(
            CodexUpdateResult.Updated("0.160.0", "0.160.1"),
            codexUpdateResult(before = "0.160.0", after = "0.160.1"),
        )
        assertEquals(
            CodexUpdateResult.AlreadyLatest("0.160.1"),
            codexUpdateResult(before = "0.160.1", after = "0.160.1"),
        )
        assertEquals(
            CodexUpdateResult.AlreadyLatest("0.160.1"),
            codexUpdateResult(before = null, after = "0.160.1"),
        )
        assertEquals(
            CodexUpdateResult.AlreadyLatest("0.160.1"),
            codexUpdateResult(before = "", after = "0.160.1"),
        )
    }
}
