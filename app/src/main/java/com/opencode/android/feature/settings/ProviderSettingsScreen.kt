package com.opencode.android.feature.settings

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.android.R
import com.opencode.android.core.api.OpenCodeProvider
import com.opencode.android.core.api.ProviderAuthMethod
import com.opencode.android.ui.theme.OpenCodeAndroidTheme

@Composable
fun ProviderSettingsScreen(
    state: SettingsUiState,
    onOpenProviderAuth: (String) -> Unit,
    onSelectProviderAuthMethod: (Int) -> Unit,
    onProviderAuthInput: (String, String) -> Unit,
    onProviderApiKey: (String) -> Unit,
    onSubmitProviderAuth: () -> Unit,
    onCompleteProviderOAuth: (String) -> Unit,
    onDisconnectProvider: (String) -> Unit,
    onLaunchOAuthBrowser: (String) -> Unit,
    onDismissProviderAuth: () -> Unit,
    onConnectGitHub: () -> Unit = {},
    onDisconnectGitHub: () -> Unit = {},
    onOpenGitHubVerification: (String) -> Unit = {},
    onBack: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.provider_settings_row)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.setup_provider_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.cd_search)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.cd_clear_search))
                        }
                    }
                },
                shape = RoundedCornerShape(14.dp),
            )

            val filtered =
                state.availableProviders
                    .sortedBy { it.name.lowercase() }
                    .filter { provider ->
                        searchQuery.isBlank() ||
                            provider.name.contains(searchQuery, ignoreCase = true) ||
                            provider.id.contains(searchQuery, ignoreCase = true)
                    }

            if (filtered.isEmpty()) {
                Text(
                    stringResource(R.string.setup_provider_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                filtered.forEach { provider ->
                    val methods = state.providerAuthMethods[provider.id].orEmpty()
                    val connected = provider.id in state.connectedProviderIds

                    ProviderRow(
                        providerName = provider.name,
                        methodSummary =
                            if (methods.isNotEmpty()) {
                                methods.joinToString(" · ") { it.label }
                            } else {
                                stringResource(R.string.setup_provider_api_key_only)
                            },
                        connected = connected,
                        onConnect = { onOpenProviderAuth(provider.id) },
                        onDisconnect = { onDisconnectProvider(provider.id) },
                    )
                }
            }

            state.providerAuthNotice?.let { notice ->
                Text(
                    text =
                        stringResource(
                            when (notice) {
                                ProviderAuthNotice.CONNECTED -> R.string.provider_connected_success
                                ProviderAuthNotice.DISCONNECTED -> R.string.provider_disconnected_success
                            },
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            state.oauthMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = stringResource(R.string.github_git_operations),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = state.githubLogin ?: stringResource(R.string.github_not_connected),
                style = MaterialTheme.typography.bodyMedium,
            )
            state.githubMessage?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            val userCode = state.githubUserCode
            if (userCode != null) {
                GithubDeviceCodeCard(
                    code = userCode,
                    verificationUrl = state.githubVerificationUrl,
                    onOpenVerification = onOpenGitHubVerification,
                )
            } else {
                OutlinedButton(
                    onClick = if (state.githubLogin == null) onConnectGitHub else onDisconnectGitHub,
                    enabled = state.githubConfigured,
                ) {
                    Text(
                        if (state.githubLogin == null) {
                            stringResource(R.string.github_connect)
                        } else {
                            stringResource(R.string.github_disconnect)
                        },
                    )
                }
            }
        }
    }

    state.providerAuthDialog?.let { dialog ->
        ProviderAuthDialog(
            state = dialog,
            onSelectMethod = onSelectProviderAuthMethod,
            onInputChange = onProviderAuthInput,
            onApiKeyChange = onProviderApiKey,
            onSubmit = onSubmitProviderAuth,
            onCompleteCode = onCompleteProviderOAuth,
            onLaunchBrowser = onLaunchOAuthBrowser,
            onDismiss = onDismissProviderAuth,
        )
    }
}

@Composable
private fun GithubDeviceCodeCard(
    code: String,
    verificationUrl: String?,
    onOpenVerification: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.github_device_flow_instructions),
                style = MaterialTheme.typography.bodyMedium,
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = code,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(code))
                        Toast.makeText(context, R.string.github_code_copied, Toast.LENGTH_SHORT).show()
                    }) {
                        Text(stringResource(R.string.github_copy_code))
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.github_waiting_for_authorization),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            verificationUrl?.let { url ->
                Button(
                    onClick = { onOpenVerification(url) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.github_open_verification))
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(
    providerName: String,
    methodSummary: String,
    connected: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = providerName,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text =
                    if (connected) {
                        stringResource(R.string.provider_connected)
                    } else {
                        stringResource(R.string.provider_not_connected)
                    },
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (connected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
        Text(
            text = methodSummary,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onConnect,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (connected) {
                        stringResource(R.string.provider_change_connection)
                    } else {
                        stringResource(R.string.provider_connect)
                    },
                )
            }
            if (connected) {
                TextButton(onClick = onDisconnect) {
                    Text(stringResource(R.string.provider_disconnect))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ProviderSettingsScreenPreview() {
    OpenCodeAndroidTheme {
        ProviderSettingsScreen(
            state =
                SettingsUiState(
                    availableProviders =
                        listOf(
                            OpenCodeProvider(id = "openai", name = "OpenAI"),
                            OpenCodeProvider(id = "anthropic", name = "Anthropic"),
                            OpenCodeProvider(id = "ollama", name = "Ollama"),
                        ),
                    providerAuthMethods =
                        mapOf(
                            "openai" to
                                listOf(
                                    ProviderAuthMethod(type = "oauth", label = "ChatGPT Plus/Pro"),
                                    ProviderAuthMethod(type = "api", label = "API key"),
                                ),
                            "anthropic" to
                                listOf(
                                    ProviderAuthMethod(type = "api", label = "API key"),
                                ),
                        ),
                    connectedProviderIds = setOf("openai"),
                ),
            onOpenProviderAuth = {},
            onSelectProviderAuthMethod = {},
            onProviderAuthInput = { _, _ -> },
            onProviderApiKey = {},
            onSubmitProviderAuth = {},
            onCompleteProviderOAuth = {},
            onDisconnectProvider = {},
            onLaunchOAuthBrowser = {},
            onDismissProviderAuth = {},
            onConnectGitHub = {},
            onDisconnectGitHub = {},
            onOpenGitHubVerification = {},
            onBack = {},
        )
    }
}
