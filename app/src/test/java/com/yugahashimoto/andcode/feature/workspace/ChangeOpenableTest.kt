package com.yugahashimoto.andcode.feature.workspace

import com.yugahashimoto.andcode.core.api.OpenCodeFileChange
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeOpenableTest {
    @Test
    fun `modified file is openable`() {
        assertTrue(OpenCodeFileChange(file = "src/Main.kt", status = "modified").isOpenable())
    }

    @Test
    fun `added file is openable`() {
        assertTrue(OpenCodeFileChange(file = "src/New.kt", status = "added").isOpenable())
    }

    @Test
    fun `deleted file is not openable`() {
        assertFalse(OpenCodeFileChange(file = "src/Old.kt", status = "deleted").isOpenable())
    }

    @Test
    fun `deleted status check is case-insensitive`() {
        assertFalse(OpenCodeFileChange(file = "src/Old.kt", status = "Deleted").isOpenable())
    }

    @Test
    fun `blank path is not openable`() {
        assertFalse(OpenCodeFileChange(file = "", status = "modified").isOpenable())
    }

    @Test
    fun `null status falls back to openable`() {
        assertTrue(OpenCodeFileChange(file = "src/Main.kt", status = null).isOpenable())
    }
}
