package com.yugahashimoto.andcode.feature.settings

import com.yugahashimoto.andcode.R

/**
 * Legal/OSS documents bundled as app assets so they can be read from **Settings → Legal & Privacy**
 * without a network connection. Each asset is a plain-text mirror of the corresponding root-level
 * Markdown file in the repository (see `docs/AUTHENTICATION_AND_DATA_FLOW.md` for the authentication
 * one) - keep the two in sync when either changes.
 *
 * [assetPathJa], where present, is a full Japanese translation of the same document - not just a
 * translated title - picked by [LegalDocumentScreen] when the app's current language is Japanese.
 * Documents without one (trademark notices, individual bundled license texts, and the OSS notices
 * overview) fall back to the English asset for every language, same as before this field existed.
 */
enum class LegalDocument(val assetPath: String, val titleRes: Int, val assetPathJa: String? = null) {
    PRIVACY_POLICY("legal/privacy.md", R.string.legal_doc_privacy_policy, "legal/privacy.ja.md"),
    TERMS_OF_USE("legal/terms.md", R.string.legal_doc_terms_of_use, "legal/terms.ja.md"),
    THIRD_PARTY_SERVICES(
        "legal/third_party_services.md",
        R.string.legal_doc_third_party_services,
        "legal/third_party_services.ja.md",
    ),
    OSS_LICENSES("legal/oss_licenses.md", R.string.legal_doc_oss_licenses),
    NOTICE_AGGREGATE("legal/notice_aggregate.md", R.string.legal_doc_notice_aggregate),
    TRADEMARKS("legal/trademarks.md", R.string.legal_doc_trademarks),
    AUTH_DATA_FLOW(
        "legal/auth_data_flow.md",
        R.string.legal_doc_auth_data_flow,
        "legal/auth_data_flow.ja.md",
    ),

    // Full license texts referenced from OSS_LICENSES, made individually reachable so a user can
    // actually open one from the Legal screen instead of only seeing it linked from Markdown that
    // LegalDocumentScreen's line-based renderer doesn't turn into a working in-app link.
    LICENSE_GPL_2_0("legal/licenses/GPL-2.0.txt", R.string.legal_license_gpl2),
    LICENSE_GPL_3_0("legal/licenses/GPL-3.0.txt", R.string.legal_license_gpl3),
    LICENSE_LGPL_3_0("legal/licenses/LGPL-3.0.txt", R.string.legal_license_lgpl3),
    LICENSE_BSD_3_CLAUSE("legal/licenses/BSD-3-Clause-libandroid-shmem.txt", R.string.legal_license_bsd3),
    LICENSE_APACHE_2_0("legal/licenses/Apache-2.0.txt", R.string.legal_license_apache2),
    LICENSE_CC_BY_NC_SA_4_0("legal/licenses/CC-BY-NC-SA-4.0.txt", R.string.legal_license_ccbyncsa4),
    ;

    /** Resolves to the Japanese asset when one exists and [languageTag] is Japanese, else [assetPath]. */
    fun assetPathFor(languageTag: String): String = if (languageTag == "ja") assetPathJa ?: assetPath else assetPath

    companion object {
        fun fromId(id: String?): LegalDocument? = entries.firstOrNull { it.name == id }
    }
}
