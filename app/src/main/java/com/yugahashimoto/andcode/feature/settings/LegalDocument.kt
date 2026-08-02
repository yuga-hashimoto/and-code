package com.yugahashimoto.andcode.feature.settings

import com.yugahashimoto.andcode.R

/**
 * Legal/OSS documents bundled as app assets so they can be read from **Settings → Legal & Privacy**
 * without a network connection. Each asset is a plain-text mirror of the corresponding root-level
 * Markdown file in the repository (see `docs/AUTHENTICATION_AND_DATA_FLOW.md` for the authentication
 * one) - keep the two in sync when either changes.
 */
enum class LegalDocument(val assetPath: String, val titleRes: Int) {
    PRIVACY_POLICY("legal/privacy.md", R.string.legal_doc_privacy_policy),
    TERMS_OF_USE("legal/terms.md", R.string.legal_doc_terms_of_use),
    THIRD_PARTY_SERVICES("legal/third_party_services.md", R.string.legal_doc_third_party_services),
    OSS_LICENSES("legal/oss_licenses.md", R.string.legal_doc_oss_licenses),
    TRADEMARKS("legal/trademarks.md", R.string.legal_doc_trademarks),
    AUTH_DATA_FLOW("legal/auth_data_flow.md", R.string.legal_doc_auth_data_flow),
    ;

    companion object {
        fun fromId(id: String?): LegalDocument? = entries.firstOrNull { it.name == id }
    }
}
