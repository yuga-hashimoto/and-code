package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalServerAuthTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `generates and persists a secret reused across calls`() {
        val runtime = temporaryFolder.newFolder("runtime")

        val first = LocalServerAuth.password(runtime)
        val second = LocalServerAuth.password(runtime)

        assertEquals(first, second)
        assertTrue(first.isNotBlank())
        assertEquals(first, File(runtime, "server-password").readText().trim())
    }

    @Test
    fun `distinct runtimes get distinct secrets`() {
        val a = LocalServerAuth.password(temporaryFolder.newFolder("a"))
        val b = LocalServerAuth.password(temporaryFolder.newFolder("b"))

        assertNotEquals(a, b)
    }
}
