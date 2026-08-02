package com.yugahashimoto.andcode.compliance

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Confirms the English and Japanese string tables both define the new login-explainer, risk-warning,
 * and Legal-screen strings with non-empty values. Runs as a plain JVM test against the raw XML so it
 * does not need Android resource compilation.
 */
class LegalStringResourcesTest {
    private fun findRepoRoot(): File {
        var dir = File(".").canonicalFile
        repeat(8) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("Could not locate repository root from ${File(".").canonicalPath}")
    }

    private val repoRoot: File by lazy { findRepoRoot() }

    private val requiredKeys =
        listOf(
            "claude_pre_auth_dialog_title",
            "claude_pre_auth_dialog_body",
            "antigravity_pre_auth_dialog_title",
            "antigravity_pre_auth_dialog_body",
            "pre_auth_dialog_continue",
            "risk_warning_title",
            "risk_warning_full_access_body",
            "risk_warning_all_files_title",
            "risk_warning_all_files_body",
            "risk_warning_mcp_server_title",
            "risk_warning_mcp_server_body",
            "risk_warning_schedule_title",
            "risk_warning_schedule_body",
            "risk_warning_understood",
            "legal_privacy_settings_row",
            "legal_screen_title",
            "legal_doc_privacy_policy",
            "legal_doc_terms_of_use",
            "legal_doc_third_party_services",
            "legal_doc_oss_licenses",
            "legal_doc_notice_aggregate",
            "legal_doc_trademarks",
            "legal_doc_auth_data_flow",
            "legal_app_version_row",
            "legal_build_version_row",
            "legal_non_affiliation_note",
        )

    private fun valuesFor(
        localeDir: String,
        key: String,
    ): String? {
        val file = File(repoRoot, "app/src/main/res/$localeDir/strings.xml")
        val text = file.readText()
        val match = Regex("<string name=\"${Regex.escape(key)}\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).find(text)
        return match?.groupValues?.get(1)
    }

    @Test
    fun `every required string exists with a non-empty value in English`() {
        requiredKeys.forEach { key ->
            val value = valuesFor("values", key)
            assertTrue("Missing English string '$key'", value != null)
            assertTrue("English string '$key' is blank", value!!.isNotBlank())
        }
    }

    @Test
    fun `every required string exists with a non-empty value in Japanese`() {
        requiredKeys.forEach { key ->
            val value = valuesFor("values-ja", key)
            assertTrue("Missing Japanese string '$key'", value != null)
            assertTrue("Japanese string '$key' is blank", value!!.isNotBlank())
        }
    }

    @Test
    fun `renamed sign-in button labels no longer claim AndCode itself provides the login`() {
        val englishClaude = valuesFor("values", "claude_sign_in_button")!!
        val englishAntigravity = valuesFor("values", "antigravity_sign_in_button")!!
        // The old wording was "Sign in to Claude" / "Sign in to Antigravity", implying AndCode
        // authenticates the account itself rather than handing off to the official CLI.
        assertTrue(englishClaude != "Sign in to Claude")
        assertTrue(englishAntigravity != "Sign in to Antigravity")
    }
}
