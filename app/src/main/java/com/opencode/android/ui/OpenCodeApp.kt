package com.opencode.android.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.opencode.android.OpenCodeApplication
import com.opencode.android.R
import com.opencode.android.core.api.OpenCodeSession
import com.opencode.android.feature.activity.ActivityScreen
import com.opencode.android.feature.activity.ActivityViewModel
import com.opencode.android.feature.activity.SessionDetailScreen
import com.opencode.android.feature.activity.SessionDetailViewModel
import com.opencode.android.feature.assistant.SpeechRecognizerManager
import com.opencode.android.feature.assistant.SpeechResult
import com.opencode.android.feature.chat.ChatHomeScreen
import com.opencode.android.feature.chat.ChatViewModel
import com.opencode.android.feature.chat.buildHandoffPrompt
import com.opencode.android.feature.onboarding.AndroidSetupScreen
import com.opencode.android.feature.onboarding.OnboardingChoiceScreen
import com.opencode.android.feature.schedule.ScheduleScreen
import com.opencode.android.feature.schedule.ScheduleViewModel
import com.opencode.android.feature.settings.ProviderSettingsScreen
import com.opencode.android.feature.settings.SettingsScreenV2
import com.opencode.android.feature.settings.SettingsViewModel
import com.opencode.android.feature.settings.VoiceSettingsScreen
import com.opencode.android.feature.workspace.LocalRuntimeManagementScreen
import com.opencode.android.feature.workspace.LocalRuntimeManagementViewModel
import com.opencode.android.feature.workspace.RemoteConnectionScreen
import com.opencode.android.feature.workspace.WorkspaceExplorerScreen
import com.opencode.android.feature.workspace.WorkspaceExplorerViewModel
import com.opencode.android.feature.workspace.WorkspaceViewModel
import com.opencode.android.feature.workspace.WorkspacesScreen
import com.opencode.android.runtime.WorkspaceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ROUTE_ONBOARDING = "onboarding"
private const val ROUTE_ANDROID_SETUP = "android-setup"
private const val ROUTE_REMOTE_CONNECTION = "remote-connection"
private const val ROUTE_CHAT = "chat"
private const val ROUTE_SCHEDULE = "schedule"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_SETTINGS_VOICE = "settings-voice"
private const val ROUTE_SETTINGS_PROVIDERS = "settings-providers"
private const val ROUTE_WORKSPACES = "workspaces"
private const val ROUTE_ACTIVITY = "activity"
private const val WORKSPACE_DETAIL_ROUTE = "workspace-detail"
private const val SESSION_DETAIL_ROUTE = "session-detail"
private const val LOCAL_RUNTIME_MANAGEMENT_ROUTE = "local-runtime-management"

/** Routes whose top bar exposes the hamburger menu / drawer swipe gesture. */
private val DRAWER_ROOT_ROUTES = setOf(ROUTE_CHAT, ROUTE_SETTINGS, ROUTE_SCHEDULE)

private fun speechLocaleTag(context: android.content.Context): String {
    val locale = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
        context.resources.configuration.locales[0]
    } else {
        @Suppress("DEPRECATION")
        context.resources.configuration.locale
    }
    return locale?.toLanguageTag()?.takeIf { it.isNotBlank() } ?: "en-US"
}

/** Relative-time label ("3時間前" / "3 hours ago") for the drawer's recent-chats list. */
private fun relativeTimeLabel(context: android.content.Context, epochMillis: Long): String {
    val diffMillis = (System.currentTimeMillis() - epochMillis).coerceAtLeast(0L)
    val minutes = diffMillis / 60_000L
    val hours = minutes / 60L
    val days = hours / 24L
    return when {
        minutes < 1L -> context.getString(R.string.drawer_time_just_now)
        hours < 1L -> context.getString(R.string.drawer_time_minutes_ago, minutes)
        days < 1L -> context.getString(R.string.drawer_time_hours_ago, hours)
        else -> context.getString(R.string.drawer_time_days_ago, days)
    }
}

@Composable
fun OpenCodeApp(
    onOpenAssistantSettings: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as OpenCodeApplication
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    var pendingSession by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingHandoffPrompt by remember { mutableStateOf<Pair<String, String>?>(null) }
    var selectedWorkspace by remember { mutableStateOf<WorkspaceRef?>(null) }
    var selectedSession by remember { mutableStateOf<OpenCodeSession?>(null) }
    var notificationsEnabled by remember { mutableStateOf(true) }

    val selectedRuntime by app.runtimeRegistry.selected.collectAsState()
    val runtimeTargets by app.runtimeRegistry.targets.collectAsState()
    val preferences by app.preferences.state.collectAsState()

    val workspaceViewModel: WorkspaceViewModel = viewModel(
        key = "workspaces",
        factory = ViewModelFactory {
            WorkspaceViewModel(
                app.runtimeRegistry,
                app.catalogRepository,
                app.localRuntimeManager,
                app.localRuntimeController
            )
        }
    )
    val workspaceState by workspaceViewModel.state.collectAsState()

    val activityViewModel: ActivityViewModel = viewModel(
        key = "activity",
        factory = ViewModelFactory {
            ActivityViewModel(
                catalog = app.catalogRepository,
                activity = app.activityRepository,
                registry = app.runtimeRegistry,
                onPermissionResolved = app.notifications::cancelPermission
            )
        }
    )
    val activityState by activityViewModel.state.collectAsState()

    val settingsViewModel: SettingsViewModel = viewModel(
        key = "settings",
        factory = ViewModelFactory {
            SettingsViewModel(
                catalog = app.catalogRepository,
                preferences = app.preferences,
                credentials = app.providerCredentials,
                settings = app.settings,
                registry = app.runtimeRegistry
            )
        }
    )
    val settingsState by settingsViewModel.state.collectAsState()

    val chatViewModel: ChatViewModel = viewModel(
        key = "chat-${selectedRuntime?.id ?: "none"}",
        factory = ViewModelFactory {
            // The chat subscribes to the runtime's event stream directly rather than to the
            // app-wide activity stream: it then reconnects (and re-syncs) on its own instead
            // of going silent whenever the shared stream is torn down.
            ChatViewModel(
                backend = selectedRuntime,
                onPermissionResolved = app.activityRepository::resolvePermission
            )
        }
    )
    val chatState by chatViewModel.uiState.collectAsState()

    val speechManager = remember { SpeechRecognizerManager(context.applicationContext) }
    val voiceScope = rememberCoroutineScope()
    var voiceJob by remember { mutableStateOf<Job?>(null) }
    var startVoiceAfterPermission by remember { mutableStateOf(false) }

    fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun startOrStopVoiceInput() {
        if (voiceJob?.isActive == true) {
            voiceJob?.cancel()
            chatViewModel.stopListening()
            return
        }
        chatViewModel.startListening()
        voiceJob = voiceScope.launch {
            try {
                speechManager.startListening(speechLocaleTag(context)).collect { result ->
                    when (result) {
                        SpeechResult.Ready,
                        SpeechResult.Listening,
                        SpeechResult.Processing,
                        is SpeechResult.RmsChanged -> Unit
                        is SpeechResult.PartialResult -> chatViewModel.updateSpeechPartial(result.text)
                        is SpeechResult.Result -> {
                            chatViewModel.stopListening()
                            chatViewModel.sendMessage(result.text)
                        }
                        is SpeechResult.Error -> chatViewModel.reportSpeechError(result.message)
                    }
                }
            } finally {
                chatViewModel.stopListening()
            }
        }
    }

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && startVoiceAfterPermission) {
            startVoiceAfterPermission = false
            startOrStopVoiceInput()
        } else if (!granted) {
            startVoiceAfterPermission = false
            chatViewModel.reportSpeechError(context.getString(R.string.mic_permission_required))
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* optional */ }

    val workspaceImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        voiceScope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                val imported = withContext(Dispatchers.IO) {
                    com.opencode.android.runtime.local.SafWorkspaceImporter(context).importTree(uri)
                }
                val existing = app.settings.safWorkspaceUris.toMutableList()
                if (uri.toString() !in existing) {
                    existing += uri.toString()
                    app.settings.safWorkspaceUris = existing
                }
                settingsViewModel.setAssistantWorkspacePath(imported.absolutePath)
                workspaceViewModel.refresh()
            }
        }
    }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    DisposableEffect(speechManager) {
        onDispose {
            voiceJob?.cancel()
            speechManager.destroy()
        }
    }

    val requestVoiceInput: () -> Unit = {
        if (hasMicrophonePermission()) {
            startOrStopVoiceInput()
        } else {
            startVoiceAfterPermission = true
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(preferences.providerId, preferences.modelId, preferences.agentId) {
        chatViewModel.selectConfiguration(
            preferences.providerId,
            preferences.modelId,
            preferences.agentId
        )
    }

    LaunchedEffect(selectedRuntime?.id, workspaceState.workspaces, chatState.sessionId) {
        if (chatState.sessionId != null) return@LaunchedEffect
        val currentPath = chatState.selectedWorkspacePath
        val available = workspaceState.workspaces
        if (available.isNotEmpty() && available.none { it.path == currentPath }) {
            chatViewModel.selectWorkspace(available.first().path)
        }
    }

    val onHandoff: (String) -> Unit = { targetRuntimeId ->
        val prompt = buildHandoffPrompt(chatState.messages)
        pendingHandoffPrompt = targetRuntimeId to prompt
        app.runtimeRegistry.select(targetRuntimeId)
    }

    // First-run gate: onboarding is the start destination until completed or skipped.
    val startDestination = remember { if (app.settings.onboardingCompleted) ROUTE_CHAT else ROUTE_ONBOARDING }
    val completeOnboardingAndGoToChat: () -> Unit = {
        app.settings.onboardingCompleted = true
        navController.navigate(ROUTE_CHAT) {
            popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
            launchSingleTop = true
        }
    }

    val appVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    val recentSessions = remember(activityState.sessions) {
        activityState.sessions.take(8).map { session ->
            DrawerRecentSession(
                id = session.id,
                title = session.title.ifBlank { session.slug ?: session.id },
                relativeTime = relativeTimeLabel(context, session.time.updated ?: session.time.created)
            )
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    val currentRoute = backStackEntry?.destination?.route

    fun closeDrawer() {
        drawerScope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = currentRoute in DRAWER_ROOT_ROUTES,
        drawerContent = {
            ModalDrawerSheet {
                AppDrawerContent(
                    recentSessions = recentSessions,
                    onNewChat = {
                        closeDrawer()
                        pendingSession = null
                        chatViewModel.newSession()
                        navController.navigate(ROUTE_CHAT) { launchSingleTop = true }
                    },
                    onOpenSession = { id, title ->
                        closeDrawer()
                        pendingSession = id to title
                        navController.navigate(ROUTE_CHAT) { launchSingleTop = true }
                    },
                    onNavigate = { route ->
                        closeDrawer()
                        navController.navigate(route) { launchSingleTop = true }
                    }
                )
            }
        }
    ) {
        NavHost(
            navController = navController,
            startDestination = startDestination
        ) {
            composable(ROUTE_ONBOARDING) {
                OnboardingChoiceScreen(
                    onSelectAndroid = { navController.navigate(ROUTE_ANDROID_SETUP) },
                    onSelectRemote = { navController.navigate(ROUTE_REMOTE_CONNECTION) },
                    onAddRemoteLater = { navController.navigate(ROUTE_REMOTE_CONNECTION) }
                )
            }

            composable(ROUTE_ANDROID_SETUP) {
                val localRuntimeStatus by app.localRuntimeManager.state.collectAsState()
                AndroidSetupScreen(
                    runtimeStatus = localRuntimeStatus,
                    onStartRuntimeSetup = workspaceViewModel::setupLocalRuntime,
                    onSaveApiKey = settingsViewModel::saveLocalBootstrapApiKey,
                    onBack = { navController.popBackStack() },
                    onFinish = completeOnboardingAndGoToChat
                )
            }

            composable(ROUTE_REMOTE_CONNECTION) {
                RemoteConnectionScreen(
                    onTestConnection = workspaceViewModel::testConnection,
                    onSaveConnection = workspaceViewModel::saveConnection,
                    onBack = { navController.popBackStack() },
                    onConnected = completeOnboardingAndGoToChat
                )
            }

            composable(ROUTE_CHAT) {
                LaunchedEffect(pendingSession) {
                    pendingSession?.let { (id, title) ->
                        chatViewModel.openSession(id, title)
                        pendingSession = null
                    }
                }
                LaunchedEffect(pendingHandoffPrompt, selectedRuntime?.id) {
                    val pending = pendingHandoffPrompt
                    if (pending != null && selectedRuntime?.id == pending.first) {
                        chatViewModel.sendMessage(pending.second)
                        pendingHandoffPrompt = null
                    }
                }
                ChatHomeScreen(
                    state = chatState,
                    providers = settingsState.providers,
                    agents = settingsState.agents,
                    workspaces = workspaceState.workspaces,
                    selectedProviderId = settingsState.providerId,
                    selectedModelId = settingsState.modelId,
                    selectedAgentId = settingsState.agentId,
                    runtimeTargets = runtimeTargets,
                    selectedRuntimeId = selectedRuntime?.id,
                    onSelectRuntime = { id ->
                        if (id != selectedRuntime?.id) {
                            if (chatState.messages.isNotEmpty()) {
                                onHandoff(id)
                            } else {
                                app.runtimeRegistry.select(id)
                            }
                        }
                    },
                    onSelectModel = settingsViewModel::selectModel,
                    onSelectAgent = settingsViewModel::selectAgent,
                    onSelectWorkspace = chatViewModel::selectWorkspace,
                    onSendMessage = chatViewModel::sendMessage,
                    onPermission = chatViewModel::respondToPermission,
                    onAbort = chatViewModel::abort,
                    onMic = requestVoiceInput,
                    onNewChat = {
                        pendingSession = null
                        chatViewModel.newSession()
                    },
                    onOpenHistory = {
                        navController.navigate(ROUTE_ACTIVITY) { launchSingleTop = true }
                    },
                    onOpenLocalSetup = {
                        navController.navigate(ROUTE_ANDROID_SETUP) { launchSingleTop = true }
                    },
                    onOpenRemoteSetup = {
                        navController.navigate(ROUTE_REMOTE_CONNECTION) { launchSingleTop = true }
                    },
                    onOpenDrawer = { drawerScope.launch { drawerState.open() } }
                )
            }

            composable(ROUTE_SCHEDULE) {
                val scheduleViewModel: ScheduleViewModel = viewModel(
                    key = "schedule",
                    factory = ViewModelFactory { ScheduleViewModel() }
                )
                val scheduleItems by scheduleViewModel.state.collectAsState()
                ScheduleScreen(
                    items = scheduleItems,
                    onToggle = scheduleViewModel::toggle,
                    onAdd = scheduleViewModel::add,
                    onOpenDrawer = { drawerScope.launch { drawerState.open() } }
                )
            }

            composable(ROUTE_SETTINGS) {
                SettingsScreenV2(
                    assistantConfigured = settingsState.assistantRuntimeId != null,
                    notificationsEnabled = notificationsEnabled,
                    onToggleNotifications = { enabled ->
                        // Local UI toggle only — Android has no API to programmatically revoke
                        // notification access, so turning this off is visual until the user
                        // also disables notifications from system settings. TODO: reflect the
                        // real system permission state instead of local-only UI state.
                        notificationsEnabled = enabled
                        if (enabled && android.os.Build.VERSION.SDK_INT >= 33) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    appVersion = appVersion,
                    onOpenDrawer = { drawerScope.launch { drawerState.open() } },
                    onOpenAssistantSettings = onOpenAssistantSettings,
                    onOpenVoiceSettings = { navController.navigate(ROUTE_SETTINGS_VOICE) },
                    onOpenProviderSettings = { navController.navigate(ROUTE_SETTINGS_PROVIDERS) },
                    onOpenLocalRuntime = { navController.navigate(LOCAL_RUNTIME_MANAGEMENT_ROUTE) },
                    onOpenRemoteConnection = { navController.navigate(ROUTE_REMOTE_CONNECTION) },
                    onOpenWorkspaces = { navController.navigate(ROUTE_WORKSPACES) },
                    onOpenDiagnostics = { navController.navigate(ROUTE_ACTIVITY) }
                )
            }

            composable(ROUTE_SETTINGS_VOICE) {
                VoiceSettingsScreen(
                    ttsEnabled = settingsState.ttsEnabled,
                    continuousConversation = settingsState.continuousConversation,
                    onTtsChange = settingsViewModel::setTtsEnabled,
                    onContinuousChange = settingsViewModel::setContinuousConversation,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(ROUTE_SETTINGS_PROVIDERS) {
                ProviderSettingsScreen(
                    state = settingsState,
                    onOpenProviderAuth = settingsViewModel::openProviderAuth,
                    onSelectProviderAuthMethod = settingsViewModel::selectProviderAuthMethod,
                    onProviderAuthInput = settingsViewModel::updateProviderAuthInput,
                    onProviderApiKey = settingsViewModel::updateProviderApiKey,
                    onSubmitProviderAuth = settingsViewModel::submitProviderAuth,
                    onCompleteProviderOAuth = settingsViewModel::completeProviderOAuth,
                    onDisconnectProvider = settingsViewModel::disconnectProvider,
                    onLaunchOAuthBrowser = { url ->
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            )
                        }.onFailure { error ->
                            settingsViewModel.reportOAuthError(error.message.orEmpty())
                        }
                    },
                    onDismissProviderAuth = settingsViewModel::dismissProviderAuth,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(ROUTE_WORKSPACES) {
                WorkspacesScreen(
                    state = workspaceState,
                    onSelectRuntime = workspaceViewModel::selectRuntime,
                    onSaveConnection = workspaceViewModel::saveConnection,
                    onDeleteConnection = workspaceViewModel::deleteConnection,
                    onTestConnection = workspaceViewModel::testConnection,
                    onRefresh = workspaceViewModel::refresh,
                    onOpenWorkspace = { workspace ->
                        selectedWorkspace = workspace
                        navController.navigate(WORKSPACE_DETAIL_ROUTE)
                    },
                    onSetupLocal = workspaceViewModel::setupLocalRuntime,
                    onStartLocal = workspaceViewModel::startLocalRuntime,
                    onStopLocal = workspaceViewModel::stopLocalRuntime,
                    onReinstallLocal = workspaceViewModel::reinstallLocalRuntime,
                    onOpenLocalManagement = {
                        navController.navigate(LOCAL_RUNTIME_MANAGEMENT_ROUTE)
                    }
                )
            }

            composable(LOCAL_RUNTIME_MANAGEMENT_ROUTE) {
                val managementViewModel: LocalRuntimeManagementViewModel = viewModel(
                    key = "local-runtime-management",
                    factory = ViewModelFactory {
                        LocalRuntimeManagementViewModel(
                            runtimeState = app.localRuntimeManager.state,
                            lastOperationState = app.localRuntimeManager.lastOperation,
                            diagnosticsProvider = {
                                withContext(Dispatchers.IO) {
                                    app.localRuntimeDiagnosticsCollector.collect()
                                }
                            },
                            updateCheckProvider = app.localRuntimeManager::checkForUpdate,
                            rollbackVersionProvider = app.localRuntimeManager::rollbackVersion,
                            repairAction = app.localRuntimeController::reinstall,
                            updateAction = app.localRuntimeController::update,
                            rollbackAction = app.localRuntimeController::rollback,
                            deleteAction = app.localRuntimeController::delete
                        )
                    }
                )
                val managementState by managementViewModel.state.collectAsState()
                LaunchedEffect(managementState.deleteCompleted) {
                    if (managementState.deleteCompleted) {
                        managementViewModel.consumeDeleteCompleted()
                        workspaceViewModel.refresh()
                        navController.popBackStack()
                    }
                }
                LocalRuntimeManagementScreen(
                    state = managementState,
                    onBack = { navController.popBackStack() },
                    onRefresh = managementViewModel::refresh,
                    onCheckForUpdate = managementViewModel::checkForUpdate,
                    onRepair = managementViewModel::repair,
                    onRequestUpdate = managementViewModel::requestUpdate,
                    onDismissUpdate = managementViewModel::dismissUpdate,
                    onConfirmUpdate = managementViewModel::confirmUpdate,
                    onRequestRollback = managementViewModel::requestRollback,
                    onDismissRollback = managementViewModel::dismissRollback,
                    onConfirmRollback = managementViewModel::confirmRollback,
                    onRequestDelete = managementViewModel::requestDelete,
                    onDismissDelete = managementViewModel::dismissDelete,
                    onConfirmDelete = managementViewModel::confirmDelete
                )
            }

            composable(WORKSPACE_DETAIL_ROUTE) {
                val workspace = selectedWorkspace
                val runtime = selectedRuntime
                if (workspace == null || runtime == null) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    val explorerViewModel: WorkspaceExplorerViewModel = viewModel(
                        key = "workspace-explorer-${runtime.id}-${workspace.id}",
                        factory = ViewModelFactory {
                            WorkspaceExplorerViewModel(runtime, workspace)
                        }
                    )
                    val explorerState by explorerViewModel.state.collectAsState()
                    WorkspaceExplorerScreen(
                        state = explorerState,
                        onBack = { navController.popBackStack() },
                        onRefresh = explorerViewModel::refresh,
                        onOpenNode = explorerViewModel::open,
                        onCloseFile = explorerViewModel::closeFile,
                        onNavigateUp = explorerViewModel::navigateUp,
                        onSearch = explorerViewModel::search,
                        onRefreshChanges = explorerViewModel::refreshChanges
                    )
                }
            }

            composable(ROUTE_ACTIVITY) {
                ActivityScreen(
                    state = activityState,
                    onRefresh = activityViewModel::refresh,
                    onInspectSession = { session ->
                        selectedSession = session
                        navController.navigate(SESSION_DETAIL_ROUTE)
                    },
                    onOpenSession = { id, title ->
                        pendingSession = id to title
                        navController.navigate(ROUTE_CHAT) { launchSingleTop = true }
                    },
                    onPermission = activityViewModel::respondToPermission,
                    onRenameSession = activityViewModel::renameSession,
                    onDeleteSession = activityViewModel::deleteSession
                )
            }

            composable(SESSION_DETAIL_ROUTE) {
                val session = selectedSession
                val runtime = selectedRuntime
                if (session == null || runtime == null) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    val detailViewModel: SessionDetailViewModel = viewModel(
                        key = "session-detail-${runtime.id}-${session.id}",
                        factory = ViewModelFactory {
                            SessionDetailViewModel(runtime, session)
                        }
                    )
                    val detailState by detailViewModel.state.collectAsState()
                    SessionDetailScreen(
                        state = detailState,
                        onBack = { navController.popBackStack() },
                        onRefresh = detailViewModel::refresh,
                        onContinueChat = {
                            pendingSession = session.id to session.title
                            navController.navigate(ROUTE_CHAT) { launchSingleTop = true }
                        }
                    )
                }
            }
        }
    }
}
