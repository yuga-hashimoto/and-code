package com.yugahashimoto.andcode.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import com.yugahashimoto.andcode.R

/**
 * Legal, privacy, and licensing information, reachable from Settings and readable entirely offline
 * - every document it links to is bundled as an app asset rather than fetched from the network.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalScreen(
    onOpenDocument: (LegalDocument) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val (versionName, versionCode) =
        remember {
            runCatching {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                info.versionName.orEmpty() to PackageInfoCompat.getLongVersionCode(info).toString()
            }.getOrDefault("" to "")
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.legal_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                SettingsSection(title = stringResource(R.string.legal_screen_title)) {
                    SettingsRow(
                        icon = Icons.Default.Policy,
                        title = stringResource(R.string.legal_doc_privacy_policy),
                        onClick = { onOpenDocument(LegalDocument.PRIVACY_POLICY) },
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Default.Gavel,
                        title = stringResource(R.string.legal_doc_terms_of_use),
                        onClick = { onOpenDocument(LegalDocument.TERMS_OF_USE) },
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Default.Sync,
                        title = stringResource(R.string.legal_doc_third_party_services),
                        onClick = { onOpenDocument(LegalDocument.THIRD_PARTY_SERVICES) },
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Default.Description,
                        title = stringResource(R.string.legal_doc_oss_licenses),
                        onClick = { onOpenDocument(LegalDocument.OSS_LICENSES) },
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Default.Description,
                        title = stringResource(R.string.legal_doc_notice_aggregate),
                        onClick = { onOpenDocument(LegalDocument.NOTICE_AGGREGATE) },
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Default.Shield,
                        title = stringResource(R.string.legal_doc_trademarks),
                        onClick = { onOpenDocument(LegalDocument.TRADEMARKS) },
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Default.Info,
                        title = stringResource(R.string.legal_doc_auth_data_flow),
                        onClick = { onOpenDocument(LegalDocument.AUTH_DATA_FLOW) },
                    )
                }
            }
            item {
                // Individually reachable so a user can actually read one of these from the app,
                // not only via the Markdown links inside the "Open-source Licenses" overview above
                // (LegalDocumentScreen's line renderer doesn't turn those into working in-app links).
                SettingsSection(title = stringResource(R.string.legal_license_texts_section)) {
                    val licenseTexts =
                        listOf(
                            LegalDocument.LICENSE_GPL_2_0,
                            LegalDocument.LICENSE_GPL_3_0,
                            LegalDocument.LICENSE_LGPL_3_0,
                            LegalDocument.LICENSE_BSD_3_CLAUSE,
                            LegalDocument.LICENSE_APACHE_2_0,
                            LegalDocument.LICENSE_CC_BY_NC_SA_4_0,
                        )
                    licenseTexts.forEachIndexed { index, document ->
                        SettingsRow(
                            icon = Icons.Default.Description,
                            title = stringResource(document.titleRes),
                            onClick = { onOpenDocument(document) },
                        )
                        if (index != licenseTexts.lastIndex) SettingsDivider()
                    }
                }
            }
            item {
                SettingsSection(title = stringResource(R.string.app_info_row)) {
                    SettingsRow(
                        icon = Icons.Default.Info,
                        title = stringResource(R.string.legal_app_version_row),
                        value = versionName,
                        onClick = {},
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Default.Info,
                        title = stringResource(R.string.legal_build_version_row),
                        value = versionCode,
                        onClick = {},
                    )
                }
            }
            item {
                Column {
                    Text(
                        text = stringResource(R.string.legal_non_affiliation_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
