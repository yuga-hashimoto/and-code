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

    @Test
    fun `documents with a Japanese translation resolve to it only for the ja language tag`() {
        val translated =
            listOf(
                LegalDocument.PRIVACY_POLICY,
                LegalDocument.TERMS_OF_USE,
                LegalDocument.THIRD_PARTY_SERVICES,
                LegalDocument.AUTH_DATA_FLOW,
            )
        translated.forEach { document ->
            val jaPath = requireNotNull(document.assetPathJa) { "${document.name} should have a Japanese translation" }
            assertEquals(jaPath, document.assetPathFor("ja"))
            assertEquals(document.assetPath, document.assetPathFor("en"))
            assertEquals(document.assetPath, document.assetPathFor("fr"))
        }
    }

    @Test
    fun `documents without a Japanese translation fall back to the English asset even for ja`() {
        val untranslated =
            listOf(LegalDocument.OSS_LICENSES, LegalDocument.NOTICE_AGGREGATE, LegalDocument.TRADEMARKS, LegalDocument.LICENSE_GPL_2_0)
        untranslated.forEach { document ->
            assertNull(document.assetPathJa)
            assertEquals(document.assetPath, document.assetPathFor("ja"))
        }
    }
}
