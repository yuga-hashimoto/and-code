package com.yugahashimoto.andcode.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegalDocumentTest {
    @Test
    fun `every legal document resolves by its own enum name`() {
        LegalDocument.entries.forEach { document ->
            assertEquals(document, LegalDocument.fromId(document.name))
        }
    }

    @Test
    fun `unknown or missing id resolves to null`() {
        assertNull(LegalDocument.fromId("NOT_A_REAL_DOCUMENT"))
        assertNull(LegalDocument.fromId(null))
    }

    @Test
    fun `every document points at a distinct asset path under legal`() {
        val paths = LegalDocument.entries.map { it.assetPath }
        assertEquals(paths.size, paths.toSet().size)
        paths.forEach { path -> assertEquals(true, path.startsWith("legal/")) }
    }
}
