package com.opencode.android.feature.onboarding

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.opencode.android.R
import com.opencode.android.core.api.OpenCodeProvider
import com.opencode.android.core.api.ProviderAuthMethod
import com.opencode.android.feature.settings.ProviderAuthDialog
import com.opencode.android.feature.settings.SettingsUiState
import com.opencode.android.runtime.LocalRuntimeStatus
import com.opencode.android.ui.theme.OpenCodeAndroidTheme
import kotlinx.coroutines.delay

private const val TOTAL_STEPS = 3

/** Guided two-step setup: runtime download, then optional provider connection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndroidSetupScreen(
    runtimeStatus: LocalRuntimeStatus,
    onStartRuntimeSetup: () -> Unit,
    settingsState: SettingsUiState,
    onOpenProviderAuth: (String) -> Unit,
    onSelectProviderAuthMethod: (Int) -> Unit,
    onProviderAuthInput: (String, String) -> Unit,
    onProviderApiKey: (String) -> Unit,
    onSubmitProviderAuth: () -> Unit,
    onCompleteProviderOAuth: (String) -> Unit,
    onDisconnectProvider: (String) -> Unit,
    onDismissProviderAuth: () -> Unit,
    onRefreshProviderAuth: () -> Unit,
    onRefreshCatalog: () -> Unit,
    onConnectGitHub: () -> Unit = {},
    onOpenGitHubVerification: (String) -> Unit = {},
    onDisconnectGitHub: () -> Unit = {},
    onBack: () -> Unit,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    val runtimeReady = runtimeStatus is LocalRuntimeStatus.Ready || runtimeStatus is LocalRuntimeStatus.Stopped
    var currentStep by remember { mutableIntStateOf(if (runtimeReady) 2 else 1) }

    LaunchedEffect(runtimeReady) {
        if (runtimeReady && currentStep == 1) currentStep = 2
    }

    LaunchedEffect(runtimeReady, settingsState.availableProviders, settingsState.providerAuthMethods) {
        if (!runtimeReady) return@LaunchedEffect
        if (settingsState.availableProviders.isNotEmpty() && settingsState.providerAuthMethods.isNotEmpty()) return@LaunchedEffect
        delay(2000)
        onRefreshCatalog()
        onRefreshProviderAuth()
    }

    val primaryAction: SetupPrimaryAction? =
        when (currentStep) {
            1 ->
                when (runtimeStatus) {
                    LocalRuntimeStatus.NotInstalled,
                    is LocalRuntimeStatus.Broken,
                    ->
                        SetupPrimaryAction(
                            label = stringResource(R.string.setup_this_device_button),
                            enabled = true,
                            onClick = onStartRuntimeSetup,
                        )
                    is LocalRuntimeStatus.UnsupportedAbi,
                    is LocalRuntimeStatus.Installing,
                    is LocalRuntimeStatus.Starting,
                    is LocalRuntimeStatus.Updating,
                    -> null
                    is LocalRuntimeStatus.Ready,
                    is LocalRuntimeStatus.Stopped,
                    ->
                        SetupPrimaryAction(
                            label = stringResource(R.string.setup_next_action),
                            enabled = true,
                            onClick = { currentStep = 2 },
                        )
                }
            2 ->
                SetupPrimaryAction(
                    label = stringResource(R.string.setup_next_action),
                    enabled = true,
                    onClick = { currentStep = 3 },
                )
            else ->
                SetupPrimaryAction(
                    label = stringResource(R.string.setup_complete_button),
                    enabled = true,
                    onClick = onFinish,
                )
        }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.android_setup_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (currentStep > 1) currentStep -= 1 else onBack()
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
            )
        },
        bottomBar = {
            SetupBottomBar(
                currentStep = currentStep,
                primaryAction = primaryAction,
                onSkip = if (currentStep >= 2) onFinish else null,
                onBackStep = { currentStep -= 1 },
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            SetupProgress(currentStep = currentStep)
            when (currentStep) {
                1 -> RuntimeDownloadStep(runtimeStatus)
                2 ->
                    ProviderConnectionStep(
                        settingsState = settingsState,
                        onOpenProviderAuth = onOpenProviderAuth,
                        onDisconnectProvider = onDisconnectProvider,
                    )
                else ->
                    GitHubConnectionStep(
                        settingsState = settingsState,
                        onConnect = onConnectGitHub,
                        onDisconnect = onDisconnectGitHub,
                        onOpenVerification = onOpenGitHubVerification,
                    )
            }
        }
    }

    settingsState.providerAuthDialog?.let { dialog ->
        ProviderAuthDialog(
            state = dialog,
            onSelectMethod = onSelectProviderAuthMethod,
            onInputChange = onProviderAuthInput,
            onApiKeyChange = onProviderApiKey,
            onSubmit = onSubmitProviderAuth,
            onCompleteCode = onCompleteProviderOAuth,
            onLaunchBrowser = { url ->
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                }
            },
            onDismiss = onDismissProviderAuth,
        )
    }
}

@Composable
private fun GitHubConnectionStep(
    settingsState: SettingsUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenVerification: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StepHeader(
            title = stringResource(R.string.github_git_operations),
            description = stringResource(R.string.setup_github_optional_description),
        )
        Text(settingsState.githubLogin ?: stringResource(R.string.github_not_connected))
        settingsState.githubUserCode?.let { code ->
            Text(stringResource(R.string.github_verification_code, code), fontWeight = FontWeight.SemiBold)
        }
        settingsState.githubMessage?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = if (settingsState.githubLogin == null) onConnect else onDisconnect,
            enabled = settingsState.githubConfigured && !settingsState.githubPolling,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (settingsState.githubPolling) {
                    stringResource(R.string.github_waiting_for_authorization)
                } else if (settingsState.githubLogin == null) {
                    stringResource(R.string.github_connect)
                } else {
                    stringResource(R.string.github_disconnect)
                },
            )
        }
        settingsState.githubVerificationUrl?.let { url ->
            OutlinedButton(onClick = { onOpenVerification(url) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.github_open_verification))
            }
        }
    }
}

private data class SetupPrimaryAction(
    val label: String,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun SetupProgress(currentStep: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.setup_step_counter, currentStep, TOTAL_STEPS),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            (1..TOTAL_STEPS).forEach { step ->
                val completed = step < currentStep
                val active = step == currentStep
                Surface(
                    modifier = Modifier.size(30.dp),
                    shape = CircleShape,
                    color =
                        when {
                            active -> MaterialTheme.colorScheme.primary
                            completed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                    contentColor =
                        when {
                            active -> MaterialTheme.colorScheme.onPrimary
                            completed -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (completed) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = stringResource(R.string.cd_step_completed),
                                modifier = Modifier.size(17.dp),
                            )
                        } else {
                            Text(step.toString(), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (step < TOTAL_STEPS) {
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .padding(horizontal = 6.dp)
                                .height(1.dp),
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color =
                                if (step < currentStep) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                } else {
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                                },
                        ) {}
                    }
                }
            }
        }
    }
}

@Composable
private fun StepHeader(
    title: String,
    description: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RuntimeDownloadStep(runtimeStatus: LocalRuntimeStatus) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        StepHeader(
            title = stringResource(R.string.setup_step_download),
            description = stringResource(R.string.setup_download_description),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (runtimeStatus) {
                    LocalRuntimeStatus.NotInstalled -> {
                        Text(
                            stringResource(R.string.setup_runtime_not_installed),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is LocalRuntimeStatus.Installing -> {
                        Text(runtimeStatus.step, fontWeight = FontWeight.Medium)
                        if (runtimeStatus.progress != null) {
                            LinearProgressIndicator(
                                progress = { runtimeStatus.progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = "${(runtimeStatus.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    is LocalRuntimeStatus.Starting -> {
                        Text(stringResource(R.string.starting_opencode_version, runtimeStatus.version))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    is LocalRuntimeStatus.Updating -> {
                        Text(runtimeStatus.step)
                        LinearProgressIndicator(
                            progress = { runtimeStatus.progress?.coerceIn(0f, 1f) ?: 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is LocalRuntimeStatus.Ready -> ReadyRuntimeRow(runtimeStatus.version)
                    is LocalRuntimeStatus.Stopped -> ReadyRuntimeRow(runtimeStatus.version)
                    is LocalRuntimeStatus.Broken -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = stringResource(R.string.cd_error),
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Text(runtimeStatus.reason, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    is LocalRuntimeStatus.UnsupportedAbi -> {
                        Text(
                            stringResource(R.string.unsupported_abi, runtimeStatus.abi),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadyRuntimeRow(version: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = stringResource(R.string.cd_runtime_ready),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column {
            Text(stringResource(R.string.setup_runtime_ready), fontWeight = FontWeight.Medium)
            Text(
                text = "OpenCode $version",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProviderConnectionStep(
    settingsState: SettingsUiState,
    onOpenProviderAuth: (String) -> Unit,
    onDisconnectProvider: (String) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        StepHeader(
            title = stringResource(R.string.setup_step_provider),
            description = stringResource(R.string.setup_provider_optional_description),
        )

        if (settingsState.availableProviders.isEmpty()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.setup_provider_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
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
                settingsState.availableProviders
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
                    val methods = settingsState.providerAuthMethods[provider.id].orEmpty()
                    val connected = provider.id in settingsState.connectedProviderIds

                    ProviderConnectionRow(
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
        }

        settingsState.providerAuthNotice?.let {
            Text(
                text = stringResource(R.string.provider_connected_success),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        settingsState.oauthMessage?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ProviderConnectionRow(
    providerName: String,
    methodSummary: String,
    connected: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = providerName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (connected) {
                    Surface(
                        shape = RoundedCornerShape(100.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        contentColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Text(
                            text = stringResource(R.string.provider_connected),
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            Text(
                text = methodSummary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (connected) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onConnect, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.provider_change_connection))
                    }
                    TextButton(onClick = onDisconnect) {
                        Text(stringResource(R.string.provider_disconnect))
                    }
                }
            } else {
                OutlinedButton(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.provider_connect))
                }
            }
        }
    }
}

@Composable
private fun SetupBottomBar(
    currentStep: Int,
    primaryAction: SetupPrimaryAction?,
    onSkip: (() -> Unit)?,
    onBackStep: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        border =
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (currentStep > 1) {
                OutlinedButton(
                    onClick = onBackStep,
                    modifier = Modifier.width(96.dp),
                ) {
                    Text(stringResource(R.string.setup_back_action))
                }
            }
            if (onSkip != null) {
                TextButton(onClick = onSkip) {
                    Text(stringResource(R.string.setup_skip_action))
                }
            }
            if (primaryAction != null) {
                Button(
                    onClick = primaryAction.onClick,
                    enabled = primaryAction.enabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(primaryAction.label, textAlign = TextAlign.Center)
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AndroidSetupScreenPreview() {
    OpenCodeAndroidTheme {
        AndroidSetupScreen(
            runtimeStatus = LocalRuntimeStatus.Installing(0.68f, "Downloading runtime"),
            onStartRuntimeSetup = {},
            settingsState = SettingsUiState(),
            onOpenProviderAuth = {},
            onSelectProviderAuthMethod = {},
            onProviderAuthInput = { _, _ -> },
            onProviderApiKey = {},
            onSubmitProviderAuth = {},
            onCompleteProviderOAuth = {},
            onDisconnectProvider = {},
            onDismissProviderAuth = {},
            onRefreshProviderAuth = {},
            onRefreshCatalog = {},
            onBack = {},
            onFinish = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AndroidSetupProviderStepPreview() {
    OpenCodeAndroidTheme {
        AndroidSetupScreen(
            runtimeStatus = LocalRuntimeStatus.Ready("1.0.0", 4097),
            onStartRuntimeSetup = {},
            settingsState =
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
                            "ollama" to
                                listOf(
                                    ProviderAuthMethod(type = "api", label = "No key needed"),
                                ),
                        ),
                    connectedProviderIds = setOf("ollama"),
                ),
            onOpenProviderAuth = {},
            onSelectProviderAuthMethod = {},
            onProviderAuthInput = { _, _ -> },
            onProviderApiKey = {},
            onSubmitProviderAuth = {},
            onCompleteProviderOAuth = {},
            onDisconnectProvider = {},
            onDismissProviderAuth = {},
            onRefreshProviderAuth = {},
            onRefreshCatalog = {},
            onBack = {},
            onFinish = {},
        )
    }
}
