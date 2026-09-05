package com.yugahashimoto.andcode.feature.onboarding

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.api.OpenCodeProvider
import com.yugahashimoto.andcode.core.api.ProviderAuthMethod
import com.yugahashimoto.andcode.feature.settings.ProviderAuthDialog
import com.yugahashimoto.andcode.feature.settings.SettingsUiState
import com.yugahashimoto.andcode.feature.workspace.ClaudeCodeCard
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import com.yugahashimoto.andcode.runtime.local.AntigravityControllerState
import com.yugahashimoto.andcode.runtime.local.ClaudeCodeUiState
import com.yugahashimoto.andcode.runtime.local.ClaudeInstallStatus
import com.yugahashimoto.andcode.runtime.local.ClaudePermissionMode
import com.yugahashimoto.andcode.ui.theme.AndCodeTheme
import kotlinx.coroutines.delay

private const val TOTAL_STEPS = 5

internal fun shouldStartRuntimeInstall(
    installComplete: Boolean,
    installFullDevelopmentTools: Boolean,
    fullDevelopmentToolsInstalled: Boolean = false,
): Boolean = !installComplete || (installFullDevelopmentTools && !fullDevelopmentToolsInstalled)

/**
 * Guided setup: choose agents, install them, sign in, then connect GitHub.
 *
 * The agent choice comes first because it decides what is downloaded — installing Claude Code alone
 * skips the OpenCode binary entirely — and which sign-in the third step has to offer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndroidSetupScreen(
    runtimeStatus: LocalRuntimeStatus,
    claude: ClaudeCodeUiState,
    antigravity: AntigravityControllerState = AntigravityControllerState(),
    fullDevelopmentToolsInstalled: Boolean = false,
    fullDevelopmentToolsInstallFailed: Boolean = false,
    onStartSetup: (Set<LocalAgent>, Boolean) -> Unit,
    onSelectClaudePermissionMode: (ClaudePermissionMode) -> Unit,
    onBeginClaudeSignIn: () -> Unit,
    onSubmitClaudeSignInCode: (String) -> Unit,
    onCancelClaudeSignIn: () -> Unit,
    onSignOutClaude: () -> Unit,
    onBeginAntigravitySignIn: () -> Unit = {},
    onSubmitAntigravitySignInCode: (String) -> Unit = {},
    onCancelAntigravitySignIn: () -> Unit = {},
    onSignOutAntigravity: () -> Unit = {},
    onSelectAntigravityPermissionMode: (com.yugahashimoto.andcode.runtime.local.AntigravityPermissionMode) -> Unit = {},
    onOpenUrl: (String) -> Unit,
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
    /** Re-reads whether the agent is installed after the runtime service provisioned it. */
    onRefreshClaudeState: () -> Unit,
    onRefreshAntigravityState: () -> Unit,
    onConnectGitHub: () -> Unit = {},
    onOpenGitHubVerification: (String) -> Unit = {},
    onDisconnectGitHub: () -> Unit = {},
    onBack: () -> Unit,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    var selectedAgents by rememberSaveable(
        stateSaver =
            listSaver<Set<LocalAgent>, String>(
                save = { agents -> agents.map(LocalAgent::id) },
                restore = { ids -> ids.mapNotNull(LocalAgent::fromId).toSet() },
            ),
        // OpenCode, the runtime every other install path in the app already defaults to. This used
        // to pre-tick Claude Code, which arrived as a side effect of the commit that added agent
        // selection while Claude Code was the subject of that work - never a decision about what a
        // new user should get. Leaving nothing ticked is worse: the step shows "select at least one
        // agent" in red the moment it opens.
    ) { mutableStateOf(setOf(LocalAgent.OPEN_CODE)) }

    val openCodeSelected = LocalAgent.OPEN_CODE in selectedAgents
    val claudeSelected = LocalAgent.CLAUDE_CODE in selectedAgents
    val antigravitySelected = LocalAgent.ANTIGRAVITY in selectedAgents
    val openCodeReady = runtimeStatus is LocalRuntimeStatus.Ready || runtimeStatus is LocalRuntimeStatus.Stopped
    val antigravityReady = antigravitySelected && antigravity.installed && !antigravity.busy
    val claudeReady = claude.installed && claude.install !is ClaudeInstallStatus.Installing && claude.install !is ClaudeInstallStatus.Failed
    // Only what is selected *and* actually on the device: an agent whose binary is missing has no
    // sign-in to offer, and Claude Code's card would shell out to /usr/bin/claude and fail there.
    // OpenCode, Claude Code, Antigravity - the same order the picker lists them in, so the guide
    // does not reshuffle the agents between the step that chooses them and the step that signs in.
    val signInAgents =
        listOfNotNull(
            LocalAgent.OPEN_CODE.takeIf { openCodeSelected && openCodeReady },
            LocalAgent.CLAUDE_CODE.takeIf { claudeSelected && claude.installed },
            LocalAgent.ANTIGRAVITY.takeIf { antigravitySelected && antigravity.installed },
        )
    var signInIndex by rememberSaveable { mutableIntStateOf(0) }
    val signInAgent = signInAgents.getOrNull(signInIndex.coerceAtMost(signInAgents.lastIndex.coerceAtLeast(0)))

    var currentStep by rememberSaveable { mutableIntStateOf(1) }
    var installFullDevelopmentTools by rememberSaveable { mutableStateOf(false) }
    var fullToolsInstallPending by rememberSaveable { mutableStateOf(false) }
    var fullToolsInstallObserved by rememberSaveable { mutableStateOf(false) }
    val packageInstallRunning =
        runtimeStatus is LocalRuntimeStatus.Installing ||
            claude.install is ClaudeInstallStatus.Installing ||
            antigravity.busy
    val fullToolsReady = !installFullDevelopmentTools || fullDevelopmentToolsInstalled
    val agentsInstallComplete =
        (!openCodeSelected || openCodeReady) &&
            (!claudeSelected || claudeReady) &&
            (!antigravitySelected || antigravityReady) &&
            fullToolsReady
    val installComplete = agentsInstallComplete && !fullToolsInstallPending

    LaunchedEffect(packageInstallRunning, fullToolsInstallPending, agentsInstallComplete, fullDevelopmentToolsInstallFailed) {
        if (!fullToolsInstallPending) return@LaunchedEffect
        if (fullDevelopmentToolsInstallFailed) {
            fullToolsInstallPending = false
            fullToolsInstallObserved = false
            currentStep = 3
        } else {
            if (packageInstallRunning) fullToolsInstallObserved = true
            if (fullToolsInstallObserved && !packageInstallRunning && agentsInstallComplete) {
                fullToolsInstallPending = false
                fullToolsInstallObserved = false
                currentStep = 3
            }
        }
    }

    // One install provisions every selected agent, so the guide no longer chains a second and third
    // install off this screen once the first finishes. It used to, and that made the outcome depend
    // on the screen staying in composition: leave the guide during the several-minute OpenCode
    // download and the agents queued behind it were simply never installed, which is how a setup
    // that reported success could still leave Claude Code missing. What is left is a re-read of the
    // install state, because the runtime service - not these controllers - ran the install.
    LaunchedEffect(openCodeReady) {
        if (!openCodeReady) return@LaunchedEffect
        if (claudeSelected) onRefreshClaudeState()
        if (antigravitySelected) onRefreshAntigravityState()
    }

    LaunchedEffect(openCodeReady, openCodeSelected, settingsState.availableProviders, settingsState.providerAuthMethods) {
        if (!openCodeSelected || !openCodeReady) return@LaunchedEffect
        if (settingsState.availableProviders.isNotEmpty() && settingsState.providerAuthMethods.isNotEmpty()) return@LaunchedEffect
        delay(2000)
        onRefreshCatalog()
        onRefreshProviderAuth()
    }

    val primaryAction: SetupPrimaryAction? =
        when (currentStep) {
            1 ->
                SetupPrimaryAction(
                    label = stringResource(R.string.setup_next_action),
                    enabled = selectedAgents.isNotEmpty(),
                    onClick = {
                        currentStep = 2
                    },
                )
            2 ->
                SetupPrimaryAction(stringResource(R.string.setup_next_action), !fullToolsInstallPending) {
                    if (
                        shouldStartRuntimeInstall(
                            agentsInstallComplete,
                            installFullDevelopmentTools,
                            fullDevelopmentToolsInstalled,
                        )
                    ) {
                        if (installFullDevelopmentTools) fullToolsInstallPending = true
                        onStartSetup(selectedAgents, installFullDevelopmentTools)
                        if (!installFullDevelopmentTools) currentStep = 3
                    } else {
                        currentStep = 3
                    }
                }
            3 ->
                if (installComplete) {
                    SetupPrimaryAction(stringResource(R.string.setup_next_action), true) { currentStep = 4 }
                } else if (
                    runtimeStatus is LocalRuntimeStatus.Broken ||
                    claude.install is ClaudeInstallStatus.Failed ||
                    antigravity.error != null ||
                    fullDevelopmentToolsInstallFailed
                ) {
                    SetupPrimaryAction(stringResource(R.string.claude_retry_install_button), true) {
                        onStartSetup(
                            if (antigravity.error != null) setOf(LocalAgent.ANTIGRAVITY) else selectedAgents,
                            installFullDevelopmentTools,
                        )
                    }
                } else {
                    null
                }
            // "Next" walks the sign-in tabs before it leaves the step, so signing in to three
            // agents is three taps of one button rather than a hunt for the chip the user has not
            // visited yet.
            4 ->
                SetupPrimaryAction(stringResource(R.string.setup_next_action), true) {
                    if (signInIndex < signInAgents.lastIndex) signInIndex++ else currentStep = 5
                }
            else -> SetupPrimaryAction(stringResource(R.string.setup_complete_button), true, onFinish)
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
                onSkip = if (currentStep >= 4) onFinish else null,
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
                1 ->
                    AgentSelectionStep(
                        selectedAgents = selectedAgents,
                        onToggle = { agent ->
                            selectedAgents =
                                if (agent in selectedAgents) selectedAgents - agent else selectedAgents + agent
                        },
                    )
                2 ->
                    DevelopmentToolsStep(
                        installFullDevelopmentTools = installFullDevelopmentTools,
                        installPending = fullToolsInstallPending,
                        onInstallFullDevelopmentToolsChanged = { installFullDevelopmentTools = it },
                    )
                3 ->
                    RuntimeDownloadStep(
                        runtimeStatus = runtimeStatus,
                        claude = claude,
                        antigravity = antigravity,
                        openCodeSelected = openCodeSelected,
                        claudeSelected = claudeSelected,
                        antigravitySelected = antigravitySelected,
                    )
                4 ->
                    SignInStep(
                        agents = signInAgents,
                        current = signInAgent,
                        onSelectAgent = { agent -> signInIndex = signInAgents.indexOf(agent).coerceAtLeast(0) },
                        claude = claude,
                        antigravity = antigravity,
                        onBeginClaudeSignIn = onBeginClaudeSignIn,
                        onSubmitClaudeSignInCode = onSubmitClaudeSignInCode,
                        onCancelClaudeSignIn = onCancelClaudeSignIn,
                        onSignOutClaude = onSignOutClaude,
                        onBeginAntigravitySignIn = onBeginAntigravitySignIn,
                        onSubmitAntigravitySignInCode = onSubmitAntigravitySignInCode,
                        onCancelAntigravitySignIn = onCancelAntigravitySignIn,
                        onSignOutAntigravity = onSignOutAntigravity,
                        onOpenUrl = onOpenUrl,
                        onSelectClaudePermissionMode = onSelectClaudePermissionMode,
                        onSelectAntigravityPermissionMode = onSelectAntigravityPermissionMode,
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
private fun AgentSelectionStep(
    selectedAgents: Set<LocalAgent>,
    onToggle: (LocalAgent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StepHeader(
            title = stringResource(R.string.setup_step_agents),
            description = stringResource(R.string.setup_agents_description),
        )
        // OpenCode, Claude Code, Antigravity - and the sign-in step follows the same order.
        AgentOption(
            title = stringResource(R.string.agent_opencode_name),
            description = stringResource(R.string.setup_agent_opencode_desc),
            selected = LocalAgent.OPEN_CODE in selectedAgents,
            onToggle = { onToggle(LocalAgent.OPEN_CODE) },
        )
        AgentOption(
            title = stringResource(R.string.agent_claude_code_name),
            description = stringResource(R.string.setup_agent_claude_code_desc),
            selected = LocalAgent.CLAUDE_CODE in selectedAgents,
            onToggle = { onToggle(LocalAgent.CLAUDE_CODE) },
        )
        AgentOption(
            title = stringResource(R.string.agent_antigravity_name),
            description = stringResource(R.string.setup_agent_antigravity_desc),
            selected = LocalAgent.ANTIGRAVITY in selectedAgents,
            onToggle = { onToggle(LocalAgent.ANTIGRAVITY) },
        )
        if (selectedAgents.size >= 2) {
            Text(
                text = stringResource(R.string.setup_runtime_shared_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selectedAgents.isEmpty()) {
            Text(
                text = stringResource(R.string.setup_agents_required),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun AgentOption(
    title: String,
    description: String,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
        border =
            BorderStroke(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DevelopmentToolsStep(
    installFullDevelopmentTools: Boolean,
    installPending: Boolean,
    onInstallFullDevelopmentToolsChanged: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StepHeader(
            title = stringResource(R.string.setup_step_development_tools),
            description = stringResource(R.string.setup_development_tools_description),
        )
        SetupPanel {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Checkbox(checked = true, onCheckedChange = null, enabled = false)
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.required_tools_title), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.setup_required_tools_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Checkbox(
                    checked = installFullDevelopmentTools,
                    onCheckedChange = onInstallFullDevelopmentToolsChanged,
                    enabled = !installPending,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.setup_install_full_development_tools),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.setup_install_full_development_tools_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            stringResource(R.string.setup_development_tools_settings_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (installPending) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.install_step_installing_dev_tools),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RuntimeDownloadStep(
    runtimeStatus: LocalRuntimeStatus,
    claude: ClaudeCodeUiState,
    antigravity: AntigravityControllerState,
    openCodeSelected: Boolean,
    claudeSelected: Boolean,
    antigravitySelected: Boolean,
) {
    // One install provisions the whole selection and reports through the shared runtime status, so
    // each step is shown under the agent it names. Without this the OpenCode panel displayed
    // "Installing Claude Code" while the Claude Code panel below it still read "Not installed".
    val installing = runtimeStatus as? LocalRuntimeStatus.Installing
    val sharedStep = installing?.takeIf { it.agent == null }
    val stepFor = { agent: LocalAgent -> installing?.takeIf { it.agent == agent } }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        StepHeader(
            title = stringResource(R.string.setup_step_download),
            description = stringResource(R.string.setup_download_agents_description),
        )
        if (openCodeSelected) {
            SetupPanel {
                Text(stringResource(R.string.agent_opencode_name), fontWeight = FontWeight.SemiBold)
                // While another agent's step is running, OpenCode is neither idle nor the subject of
                // that step, so it keeps a bar under the generic setting-up label.
                val generic = stringResource(R.string.runtime_status_setting_up)
                OpenCodeRuntimeProgress(
                    if (installing != null && sharedStep == null) installing.copy(step = generic) else runtimeStatus,
                )
            }
        }
        if (claudeSelected) {
            SetupPanel {
                Text(stringResource(R.string.agent_claude_code_name), fontWeight = FontWeight.SemiBold)
                val step = stepFor(LocalAgent.CLAUDE_CODE)
                if (step != null) SharedInstallProgress(step) else ClaudeInstallProgress(claude)
            }
        }
        if (antigravitySelected) {
            SetupPanel {
                Text(stringResource(R.string.agent_antigravity_name), fontWeight = FontWeight.SemiBold)
                val step = stepFor(LocalAgent.ANTIGRAVITY)
                if (step != null) SharedInstallProgress(step) else AntigravityInstallProgress(antigravity)
            }
        }
    }
}

/** A step of the one shared install, shown under whichever agent it belongs to. */
@Composable
private fun SharedInstallProgress(status: LocalRuntimeStatus.Installing) {
    Text(status.step, fontWeight = FontWeight.Medium)
    if (status.progress != null) {
        LinearProgressIndicator(progress = { status.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun AntigravityInstallProgress(antigravity: AntigravityControllerState) {
    when (val install = antigravity.install) {
        is com.yugahashimoto.andcode.runtime.local.AntigravityInstallStatus.Installing -> {
            if (install.step.isNotBlank()) Text(install.step, fontWeight = FontWeight.Medium)
            if (install.progress != null) {
                LinearProgressIndicator(
                    progress = { install.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${(install.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        is com.yugahashimoto.andcode.runtime.local.AntigravityInstallStatus.Ready ->
            // Name and version, the way the OpenCode and Claude Code cards read. This showed a bare
            // "1.1.7" next to "OpenCode 1.18.5" and "Claude Code 2.1.212".
            ReadyAgentRow(
                stringResource(R.string.antigravity_installed_version, antigravity.version ?: install.version),
            )
        is com.yugahashimoto.andcode.runtime.local.AntigravityInstallStatus.Failed ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    Icons.Default.Error,
                    contentDescription = stringResource(R.string.cd_error),
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(install.message, color = MaterialTheme.colorScheme.error)
            }
        com.yugahashimoto.andcode.runtime.local.AntigravityInstallStatus.Idle ->
            if (antigravity.installed) {
                // The pinned release is the fallback rather than a literal, so the version here can
                // never drift from the one the installer actually writes.
                ReadyAgentRow(
                    stringResource(
                        R.string.antigravity_installed_version,
                        antigravity.version ?: com.yugahashimoto.andcode.runtime.local.AntigravityManifest.VERSION,
                    ),
                )
            } else {
                Text(stringResource(R.string.setup_runtime_not_installed), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
    }
}

@Composable
private fun SetupPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun ClaudeInstallProgress(claude: ClaudeCodeUiState) {
    when (val install = claude.install) {
        is ClaudeInstallStatus.Installing -> {
            Text(stringResource(install.step), fontWeight = FontWeight.Medium)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is ClaudeInstallStatus.Ready -> ReadyAgentRow(stringResource(R.string.claude_installed_version, install.version))
        is ClaudeInstallStatus.Failed ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    Icons.Default.Error,
                    contentDescription = stringResource(R.string.cd_error),
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(install.message, color = MaterialTheme.colorScheme.error)
            }
        ClaudeInstallStatus.Idle ->
            if (claude.installed) {
                ReadyAgentRow(stringResource(R.string.claude_installed_version, claude.version.orEmpty()))
            } else {
                // The same string as the OpenCode and Antigravity cards above: this screen listed
                // one "not installed" state in three different wordings, two of them full
                // sentences, next to each other in the same list.
                Text(
                    stringResource(R.string.setup_runtime_not_installed),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
    }
}

@Composable
private fun OpenCodeRuntimeProgress(runtimeStatus: LocalRuntimeStatus) {
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
        is LocalRuntimeStatus.Ready -> ReadyAgentRow("OpenCode ${runtimeStatus.version}")
        is LocalRuntimeStatus.Stopped -> ReadyAgentRow("OpenCode ${runtimeStatus.version}")
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

@Composable
private fun ReadyAgentRow(detail: String) {
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
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One agent's sign-in at a time, picked from a chip row.
 *
 * [agents] are the ones that are both selected and actually installed, and nothing else is offered.
 * Offering sign-in for an agent that is not installed is not merely untidy: Claude Code's sign-in
 * shells out to `/usr/bin/claude`, so the card ran it and reported "Claude Code sign-in finished
 * (exit code 127) - bash: /usr/bin/claude: No such file or directory" to the user.
 *
 * Stacking all three vertically also made the step unusable - each card carries its own permission
 * mode list, so the sign-in the user came for sat several screens down.
 */
@Composable
private fun SignInStep(
    agents: List<LocalAgent>,
    current: LocalAgent?,
    onSelectAgent: (LocalAgent) -> Unit,
    claude: ClaudeCodeUiState,
    antigravity: AntigravityControllerState,
    onBeginClaudeSignIn: () -> Unit,
    onSubmitClaudeSignInCode: (String) -> Unit,
    onCancelClaudeSignIn: () -> Unit,
    onSignOutClaude: () -> Unit,
    onBeginAntigravitySignIn: () -> Unit,
    onSubmitAntigravitySignInCode: (String) -> Unit,
    onCancelAntigravitySignIn: () -> Unit,
    onSignOutAntigravity: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onSelectClaudePermissionMode: (ClaudePermissionMode) -> Unit,
    onSelectAntigravityPermissionMode: (com.yugahashimoto.andcode.runtime.local.AntigravityPermissionMode) -> Unit,
    settingsState: SettingsUiState,
    onOpenProviderAuth: (String) -> Unit,
    onDisconnectProvider: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        StepHeader(
            title = stringResource(R.string.setup_step_sign_in),
            description = stringResource(R.string.setup_sign_in_description),
        )
        if (current == null) {
            Text(
                text = stringResource(R.string.setup_sign_in_none_installed),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        if (agents.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                agents.forEach { agent ->
                    FilterChip(
                        selected = agent == current,
                        onClick = { onSelectAgent(agent) },
                        label = { Text(stringResource(agent.displayNameRes)) },
                    )
                }
            }
        }
        SetupPanel {
            Text(stringResource(current.displayNameRes), fontWeight = FontWeight.SemiBold)
            when (current) {
                LocalAgent.CLAUDE_CODE ->
                    ClaudeCodeCard(
                        claude = claude,
                        onInstall = {},
                        onUpdate = {},
                        onSelectPermissionMode = onSelectClaudePermissionMode,
                        onSignIn = onBeginClaudeSignIn,
                        onSubmitCode = onSubmitClaudeSignInCode,
                        onCancelSignIn = onCancelClaudeSignIn,
                        onSignOut = onSignOutClaude,
                        onOpenUrl = onOpenUrl,
                        showInstallActions = false,
                    )
                LocalAgent.ANTIGRAVITY ->
                    com.yugahashimoto.andcode.feature.workspace.AntigravityCard(
                        antigravity = antigravity,
                        onInstall = {},
                        onUpdate = {},
                        onSelectPermissionMode = onSelectAntigravityPermissionMode,
                        onSignIn = onBeginAntigravitySignIn,
                        onSubmitCode = onSubmitAntigravitySignInCode,
                        onCancelSignIn = onCancelAntigravitySignIn,
                        onSignOut = onSignOutAntigravity,
                        onOpenUrl = onOpenUrl,
                        showInstallActions = false,
                    )
                LocalAgent.OPEN_CODE ->
                    ProviderConnectionStep(
                        settingsState = settingsState,
                        onOpenProviderAuth = onOpenProviderAuth,
                        onDisconnectProvider = onDisconnectProvider,
                        header = false,
                    )
            }
        }
    }
}

@Composable
private fun ProviderConnectionStep(
    settingsState: SettingsUiState,
    onOpenProviderAuth: (String) -> Unit,
    onDisconnectProvider: (String) -> Unit,
    /** False inside the sign-in step's own panel, which already names the agent above this. */
    header: Boolean = true,
) {
    var searchQuery by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (header) {
            StepHeader(
                title = stringResource(R.string.setup_step_provider),
                description = stringResource(R.string.setup_provider_optional_description),
            )
        }

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
    AndCodeTheme {
        AndroidSetupScreen(
            runtimeStatus = LocalRuntimeStatus.Installing(0.68f, "Downloading runtime"),
            claude = ClaudeCodeUiState(),
            onStartSetup = { _, _ -> },
            onBeginClaudeSignIn = {},
            onSubmitClaudeSignInCode = {},
            onCancelClaudeSignIn = {},
            onSignOutClaude = {},
            onOpenUrl = {},
            onSelectClaudePermissionMode = {},
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
            onRefreshClaudeState = {},
            onRefreshAntigravityState = {},
            onBack = {},
            onFinish = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AndroidSetupProviderStepPreview() {
    AndCodeTheme {
        AndroidSetupScreen(
            runtimeStatus = LocalRuntimeStatus.Ready("1.0.0", 4097),
            claude = ClaudeCodeUiState(installed = true, version = "2.1.212"),
            onStartSetup = { _, _ -> },
            onBeginClaudeSignIn = {},
            onSubmitClaudeSignInCode = {},
            onCancelClaudeSignIn = {},
            onSignOutClaude = {},
            onOpenUrl = {},
            onSelectClaudePermissionMode = {},
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
            onRefreshClaudeState = {},
            onRefreshAntigravityState = {},
            onBack = {},
            onFinish = {},
        )
    }
}
