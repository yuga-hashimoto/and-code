package com.yugahashimoto.andcode.compliance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static checks over the repository's public-facing legal disclosures (README, GitHub Pages site).
 * These run as plain JUnit tests so they execute as part of `:app:testDebugUnitTest` without needing
 * a device - they read files directly off disk rather than through Android resources.
 */
class LegalDisclosureComplianceTest {
    private val repoRoot: File by lazy { findRepoRoot() }

    private fun findRepoRoot(): File {
        var dir = File(".").canonicalFile
        repeat(8) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("Could not locate repository root (looked for settings.gradle.kts) from ${File(".").canonicalPath}")
    }

    private fun readRepoFile(relativePath: String): String {
        val file = File(repoRoot, relativePath)
        assertTrue("Expected $relativePath to exist at ${file.absolutePath}", file.exists())
        return file.readText()
    }

    // English phrases that overstate AndCode's relationship to third-party providers or their terms.
    private val forbiddenEnglishPhrases =
        listOf(
            "fully compliant",
            "officially approved by anthropic",
            "officially approved by google",
            "no legal issues",
            "official android app",
            "claude pro is included",
            "unlimited use",
            "unlimited usage",
            "bypass rate limits",
            "bypasses rate limits",
        )

    // Japanese equivalents from the task's explicit "do not write this" list.
    private val forbiddenJapanesePhrases =
        listOf(
            "規約に完全準拠",
            "公式に許可されている",
            "法的に一切問題がない",
            "公式Android版",
            "無制限に利用できる",
            "レート制限や利用上限を回避できる",
        )

    @Test
    fun `README does not contain overstated compliance or affiliation claims`() {
        val text = readRepoFile("README.md").lowercase()
        forbiddenEnglishPhrases.forEach { phrase ->
            assertFalse("README.md should not contain '$phrase'", text.contains(phrase))
        }
    }

    @Test
    fun `README ja does not contain overstated compliance or affiliation claims`() {
        val text = readRepoFile("README.ja.md")
        forbiddenJapanesePhrases.forEach { phrase ->
            assertFalse("README.ja.md should not contain '$phrase'", text.contains(phrase))
        }
    }

    @Test
    fun `GitHub Pages site does not contain overstated compliance or affiliation claims`() {
        val text = readRepoFile("pages/index.html").lowercase()
        forbiddenEnglishPhrases.forEach { phrase ->
            assertFalse("pages/index.html should not contain '$phrase'", text.contains(phrase))
        }
    }

    @Test
    fun `README states AndCode is not affiliated with OpenCode, Anthropic, or Google`() {
        val text = readRepoFile("README.md")
        assertTrue(text.contains("not affiliated with"))
    }

    @Test
    fun `GitHub Pages footer states non-affiliation`() {
        val text = readRepoFile("pages/index.html")
        assertTrue(text.contains("not affiliated with"))
    }

    /**
     * Every "MIT licensed" claim in README.md / pages/index.html must be scoped to AndCode's own
     * source, not the whole product (bundled third-party CLIs and runtimes keep their own license).
     */
    @Test
    fun `MIT licensed claims are scoped to AndCode source, not the whole app`() {
        listOf("README.md", "pages/index.html").forEach { relativePath ->
            val text = readRepoFile(relativePath)
            val matches = Regex("MIT licensed", RegexOption.IGNORE_CASE).findAll(text).toList()
            assertTrue("$relativePath should mention 'MIT licensed' at least once", matches.isNotEmpty())
            matches.forEach { match ->
                val windowStart = (match.range.first - 120).coerceAtLeast(0)
                val window = text.substring(windowStart, match.range.first)
                assertTrue(
                    "'MIT licensed' in $relativePath at index ${match.range.first} is not clearly scoped to " +
                        "AndCode's own source (preceding text: \"$window\")",
                    window.contains("AndCode", ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun `all six legal documents are bundled as offline app assets`() {
        val legalAssetsDir = File(repoRoot, "app/src/main/assets/legal")
        assertTrue("Expected ${legalAssetsDir.absolutePath} to exist", legalAssetsDir.exists())
        listOf(
            "privacy.md",
            "terms.md",
            "third_party_services.md",
            "oss_licenses.md",
            "trademarks.md",
            "auth_data_flow.md",
        ).forEach { name ->
            val asset = File(legalAssetsDir, name)
            assertTrue("Expected bundled legal asset ${asset.absolutePath} to exist", asset.exists())
            assertTrue("Expected bundled legal asset ${asset.absolutePath} to be non-empty", asset.readText().isNotBlank())
        }
    }

    @Test
    fun `Japanese translations of privacy, terms, third-party services, and auth data flow are bundled offline`() {
        listOf(
            "app/src/main/assets/legal/privacy.ja.md",
            "app/src/main/assets/legal/terms.ja.md",
            "app/src/main/assets/legal/third_party_services.ja.md",
            "app/src/main/assets/legal/auth_data_flow.ja.md",
        ).forEach { relativePath ->
            val text = readRepoFile(relativePath)
            assertTrue("$relativePath should contain Japanese text", text.any { it.code in 0x3040..0x30FF || it.code in 0x4E00..0x9FFF })
        }
    }

    @Test
    fun `full license texts referenced from THIRD_PARTY_NOTICES are bundled offline in the APK assets`() {
        listOf(
            "GPL-2.0.txt",
            "GPL-3.0.txt",
            "LGPL-3.0.txt",
            "BSD-3-Clause-libandroid-shmem.txt",
            "Apache-2.0.txt",
            "CC-BY-NC-SA-4.0.txt",
        ).forEach { name ->
            val text = readRepoFile("app/src/main/assets/legal/licenses/$name")
            assertTrue("$name should be non-trivial license text", text.length > 200)
        }
    }

    @Test
    fun `THIRD_PARTY_NOTICES documents the Vosk wake-word model as downloaded rather than bundled`() {
        val text = readRepoFile("THIRD_PARTY_NOTICES.md")
        assertTrue("Vosk should be named as the wake-word engine", text.contains("vosk-android"))
        assertTrue(
            "The document should say the speech model is downloaded rather than shipped",
            text.contains("not part of the APK"),
        )
        VOSK_MODEL_DIRECTORIES.forEach { name ->
            assertTrue("THIRD_PARTY_NOTICES.md should name the $name model", text.contains(name))
        }
    }

    @Test
    fun `no wake-word model files are bundled in the APK`() {
        // The previous openWakeWord models were CC BY-NC-SA 4.0 - a NonCommercial license this
        // project could not clear for downstream distribution. Nothing may quietly reappear under
        // assets without that question coming back with it.
        val wakewordDir = File(repoRoot, "app/src/main/assets/wakeword")
        assertFalse(
            "Wake-word model files must not be bundled: ${wakewordDir.absolutePath} exists",
            wakewordDir.exists(),
        )
    }

    @Test
    fun `the retired openWakeWord models are no longer claimed as bundled`() {
        val text = readRepoFile("THIRD_PARTY_NOTICES.md")
        assertFalse(
            "THIRD_PARTY_NOTICES.md should not still present openWakeWord models as bundled",
            text.contains("| `hey_mycroft_v0.1.tflite` |"),
        )
    }

    @Test
    fun `THIRD_PARTY_NOTICES does not overclaim GPL corresponding-source compliance is fully satisfied`() {
        val text = readRepoFile("THIRD_PARTY_NOTICES.md").lowercase()
        assertFalse(
            "THIRD_PARTY_NOTICES.md should not assert GPLv2 corresponding-source is satisfied outright",
            text.contains("this project treats as satisfying gplv2"),
        )
        assertTrue(text.contains("requires_license_review"))
    }

    /**
     * A prior version of this document claimed Gradle preserves every dependency's embedded
     * `META-INF/NOTICE` file unmodified in the built APK. Unzipping the actual CI-produced debug
     * APK showed that is false (AGP's resource merging keeps only one arbitrarily-picked copy),
     * so the doc must point at a generated aggregate instead of asserting nothing needs collecting.
     */
    @Test
    fun `THIRD_PARTY_NOTICES no longer claims Gradle preserves every dependency NOTICE file untouched`() {
        val text = readRepoFile("THIRD_PARTY_NOTICES.md").lowercase()
        assertFalse(
            "THIRD_PARTY_NOTICES.md should not claim no manual NOTICE aggregation step is needed",
            text.replace("\n", " ").contains("no separate manual notice aggregation step is needed"),
        )
        assertTrue(
            "THIRD_PARTY_NOTICES.md should reference the generated NOTICE aggregate",
            text.contains("notice-aggregate.txt"),
        )
    }

    @Test
    fun `NOTICE aggregate is bundled offline and documents at least one dependency's NOTICE text`() {
        val rootText = readRepoFile("THIRD_PARTY_LICENSES/NOTICE-aggregate.txt")
        assertTrue(rootText.contains("Apache Software Foundation"))
        val assetText = readRepoFile("app/src/main/assets/legal/notice_aggregate.md")
        assertTrue(
            "app/src/main/assets/legal/notice_aggregate.md has drifted from THIRD_PARTY_LICENSES/NOTICE-aggregate.txt",
            rootText == assetText,
        )
    }

    @Test
    fun `required standalone legal documents exist at the repository root`() {
        listOf("PRIVACY.md", "TERMS.md", "THIRD_PARTY_SERVICES.md", "TRADEMARKS.md", "docs/AUTHENTICATION_AND_DATA_FLOW.md")
            .forEach { relativePath -> readRepoFile(relativePath) }
    }

    /**
     * The offline copies under `app/src/main/assets/legal/` are maintained by hand (no Gradle copy
     * task), so they can silently drift from the root documents they are supposed to mirror. This
     * fails loudly the moment one edit is made without the other, instead of shipping a stale
     * in-app document.
     */
    @Test
    fun `bundled legal doc assets are byte-for-byte identical to their root source files`() {
        val pairs =
            listOf(
                "PRIVACY.md" to "app/src/main/assets/legal/privacy.md",
                "TERMS.md" to "app/src/main/assets/legal/terms.md",
                "THIRD_PARTY_SERVICES.md" to "app/src/main/assets/legal/third_party_services.md",
                "TRADEMARKS.md" to "app/src/main/assets/legal/trademarks.md",
                "THIRD_PARTY_NOTICES.md" to "app/src/main/assets/legal/oss_licenses.md",
                "docs/AUTHENTICATION_AND_DATA_FLOW.md" to "app/src/main/assets/legal/auth_data_flow.md",
            )
        pairs.forEach { (source, asset) ->
            val sourceText = readRepoFile(source)
            val assetText = readRepoFile(asset)
            assertTrue(
                "$asset has drifted from $source - re-copy $source over $asset after editing either one",
                sourceText == assetText,
            )
        }
    }

    /**
     * Same drift check for the bundled full license texts: the copy inside the APK's assets must
     * match the copy kept in the repository root exactly.
     */
    @Test
    fun `bundled license texts are byte-for-byte identical to the repository copies`() {
        val licenseFiles = listOf("GPL-2.0.txt", "GPL-3.0.txt", "LGPL-3.0.txt", "BSD-3-Clause-libandroid-shmem.txt")
        licenseFiles.forEach { name ->
            val sourceText = readRepoFile("THIRD_PARTY_LICENSES/$name")
            val assetText = readRepoFile("app/src/main/assets/legal/licenses/$name")
            assertTrue(
                "app/src/main/assets/legal/licenses/$name has drifted from THIRD_PARTY_LICENSES/$name",
                sourceText == assetText,
            )
        }
    }

    private companion object {
        val VOSK_MODEL_DIRECTORIES = listOf("vosk-model-small-en-us-0.15", "vosk-model-small-ja-0.22")
    }
}
