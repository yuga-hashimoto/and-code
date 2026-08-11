package com.yugahashimoto.andcode.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yugahashimoto.andcode.AndCodeApplication
import com.yugahashimoto.andcode.BuildConfig
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.diagnostics.CrashLog
import com.yugahashimoto.andcode.feature.activity.ActivityViewModel
import com.yugahashimoto.andcode.feature.assistant.SpeechRecognizerManager
import com.yugahashimoto.andcode.feature.assistant.SpeechResult
import com.yugahashimoto.andcode.feature.assistant.SpeechTranscriptAccumulator
import com.yugahashimoto.andcode.feature.assistant.VoiceDictationOutcome
import com.yugahashimoto.andcode.feature.assistant.VoiceDictationPolicy
import com.yugahashimoto.andcode.feature.browser.GuestBrowserCommandWatcher
import com.yugahashimoto.andcode.feature.chat.ChatHomeScreen
import com.yugahashimoto.andcode.feature.chat.ChatViewModel
import com.yugahashimoto.andcode.feature.chat.SubagentInfo
import com.yugahashimoto.andcode.feature.chat.buildHandoffPrompt
import com.yugahashimoto.andcode.feature.onboarding.AndroidSetupScreen
import com.yugahashimoto.andcode.feature.onboarding.OnboardingChoiceScreen
import com.yugahashimoto.andcode.feature.schedule.ScheduleEditorScreen
import com.yugahashimoto.andcode.feature.schedule.ScheduleListScreen
import com.yugahashimoto.andcode.feature.schedule.ScheduleRunsScreen
import com.yugahashimoto.andcode.feature.schedule.ScheduleViewModel
import com.yugahashimoto.andcode.feature.settings.DiagnosticsSheet
import com.yugahashimoto.andcode.feature.settings.GitHubRepo
import com.yugahashimoto.andcode.feature.settings.SettingsViewModel
import com.yugahashimoto.andcode.feature.wakeword.WakeWordService
import com.yugahashimoto.andcode.feature.wakeword.WakeWordSettingsPolicy
import com.yugahashimoto.andcode.feature.workspace.WorkspaceViewModel
import com.yugahashimoto.andcode.runtime.RuntimeState
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import com.yugahashimoto.andcode.runtime.local.GitCloneResult
import com.yugahashimoto.andcode.ui.components.SessionStatus
import com.yugahashimoto.andcode.ui.navigation.ClaudeSettingsActions
import com.yugahashimoto.andcode.ui.navigation.DRAWER_ROOT_ROUTES
import com.yugahashimoto.andcode.ui.navigation.ROUTE_ANDROID_SETUP
import com.yugahashimoto.andcode.ui.navigation.ROUTE_CHAT
import com.yugahashimoto.andcode.ui.navigation.ROUTE_ONBOARDING
import com.yugahashimoto.andcode.ui.navigation.ROUTE_REMOTE_CONNECTION
import com.yugahashimoto.andcode.ui.navigation.ROUTE_SCHEDULES
import com.yugahashimoto.andcode.ui.navigation.ROUTE_SCHEDULE_EDIT
import com.yugahashimoto.andcode.ui.navigation.ROUTE_SETTINGS_PROVIDERS
import com.yugahashimoto.andcode.ui.navigation.SCHEDULE_DETAIL_ROUTE_PATTERN
import com.yugahashimoto.andcode.ui.navigation.SCHEDULE_EDIT_ARG_ID
import com.yugahashimoto.andcode.ui.navigation.SCHEDULE_EDIT_ROUTE_PATTERN
import com.yugahashimoto.andcode.ui.navigation.decodeRouteArg
import com.yugahashimoto.andcode.ui.navigation.guestBrowserRoute
import com.yugahashimoto.andcode.ui.navigation.scheduleDetailRoute
import com.yugahashimoto.andcode.ui.navigation.scheduleEditRoute
import com.yugahashimoto.andcode.ui.navigation.settingsNavGraph
import com.yugahashimoto.andcode.ui.navigation.workspaceNavGraph
import com.yugahashimoto.andcode.ui.theme.AndCodeTheme
import com.yugahashimoto.andcode.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** How often the open chat asks GitHub whether its pull requests have moved on. */
private const val PULL_REQUEST_REFRESH_INTERVAL_MS = 30_000L
private const val VOICE_RESTART_DELAY_MS = 200L

private fun speechLocaleTag(context: android.content.Context): String {
    val locale =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale
        }
    return locale?.toLanguageTag()?.takeIf { it.isNotBlank() } ?: "en-US"
}

private fun relativeTimeLabel(
    context: android.content.Context,
    epochMillis: Long,
): String {
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
fun AndCodeApp(
    onOpenAssistantSettings: () -> Unit,
    assistantActive: Boolean = false,
    appTheme: AppTheme = AppTheme.DARK,
    uiFontSize: Int = 16,
    chatDeepLink: ChatDeepLink? = null,
    onChatDeepLinkConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val app = context.applicationContext as AndCodeApplication
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    var pendingSession by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingHandoffPrompt by remember { mutableStateOf<Pair<String, String>?>(null) }
    // A handoff waits for the model sheet to close: the runtime and the model are chosen in the
    // same sheet, and sending the moment the runtime changes would use whichever model happened
    // to be selected first.
    var handoffReady by remember { mutableStateOf(false) }
    var selectedWorkspace by remember { mutableStateOf<WorkspaceRef?>(null) }
    var notificationsEnabled by remember { mutableStateOf(true) }
    var showCloneDialog by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }

    val selectedRuntime by app.runtimeRegistry.selected.collectAsState()
    val runtimeTargets by app.runtimeRegistry.targets.collectAsState()
    val preferences by app.preferences.state.collectAsState()
    val antigravityState by app.antigravityController.state.collectAsState()

    var collapsedSections by remember { mutableStateOf(setOf<String>()) }

    val workspaceViewModel: WorkspaceViewModel =
        viewModel(
            key = "workspaces",
            factory =
                ViewModelFactory {
                    WorkspaceViewModel(
                        app.runtimeRegistry,
                        app.catalogRepository,
                        app.localRuntimeManager,
                        app.localRuntimeController,
                        app.settings,
                        java.io.File(context.filesDir, "runtime/workspace"),
                        incompleteConnectionMessage = context.getString(R.string.connection_info_incomplete),
                        claudeCode = app.claudeCodeController,
                    )
                },
        )
    val workspaceState by workspaceViewModel.state.collectAsState()

    val activityViewModel: ActivityViewModel =
        viewModel(
            key = "activity",
            factory =
                ViewModelFactory {
                    ActivityViewModel(
                        catalog = app.catalogRepository,
                        activity = app.activityRepository,
                    )
                },
        )
    val activityState by activityViewModel.state.collectAsState()

    val settingsViewModel: SettingsViewModel =
        viewModel(
            key = "settings",
            factory =
                ViewModelFactory {
                    SettingsViewModel(
                        catalog = app.catalogRepository,
                        preferences = app.preferences,
                        credentials = app.providerCredentials,
                        settings = app.settings,
                        registry = app.runtimeRegistry,
                        voskModels = app.voskModels,
                        providerDisconnectRejectedMessage = context.getString(R.string.provider_disconnect_rejected),
                    )
                },
        )
    val settingsState by settingsViewModel.state.collectAsState()

    val scheduleViewModel: ScheduleViewModel =
        viewModel(
            key = "schedules",
            factory =
                ViewModelFactory {
                    ScheduleViewModel(app.scheduleRepository, app.scheduleManager)
                },
        )
    val schedules by scheduleViewModel.schedules.collectAsState()
    val scheduleRuns by scheduleViewModel.runs.collectAsState()

    // A crash the user hit while away from a computer is only recoverable from here. Copying is
    // what discards the record; closing the dialog only hides it, and the diagnostics sheet still
    // has it until it has been copied or a later crash replaces it.
    var lastCrash by remember { mutableStateOf(CrashLog.read(context)) }
    lastCrash?.let { report ->
        CrashReportDialog(
            report = report,
            onCopied = {
                CrashLog.clear(context)
                lastCrash = null
            },
            onDismiss = { lastCrash = null },
        )
    }

    // Which chat the user is actually looking at. A run that finishes while they are elsewhere has
    // to stay unread, so this is tracked separately from the chat view model's own session.
    val visibleChatSessionId = remember { mutableStateOf<String?>(null) }

    val chatViewModel: ChatViewModel =
        viewModel(
            key = "chat-${selectedRuntime?.id ?: "none"}",
            factory =
                ViewModelFactory {
                    ChatViewModel(
                        backend = selectedRuntime,
                        eventFlow = app.activityRepository.events,
                        onPermissionResolved = app.activityRepository::resolvePermission,
                        onQuestionResolved = app.notifications::cancelQuestion,
                        onSessionCreated = app.catalogRepository::refreshSessionsOnly,
                        onSessionAborted = { sessionId ->
                            app.activityRepository.markSessionAborted(sessionId)
                            // Stopping the run answers the "this run has gone quiet" notice.
                            app.notifications.cancelStalled(sessionId)
                        },
                        onRunStateChanged = { sessionId, running ->
                            if (running) {
                                app.activityRepository.markSessionRunning(sessionId)
                            } else {
                                // A chat the user is not currently looking at stays unread; the one
                                // on screen has just been read by definition.
                                app.activityRepository.markSessionFinished(
                                    sessionId = sessionId,
                                    unread = visibleChatSessionId.value != sessionId,
                                )
                            }
                        },
                        monitorConnectionQuality = true,
                        resolvedPermissionFlow = app.activityRepository.resolvedPermissions,
                        pullRequestStatuses = app.pullRequestStatusRepository,
                        monitorStalls = true,
                        // Every activity event updates this state, so only real transitions of the
                        // stream's own health are forwarded.
                        streamErrorFlow =
                            app.activityRepository.state
                                .map { it.streamError }
                                .distinctUntilChanged(),
                    )
                },
        )
    val chatState by chatViewModel.uiState.collectAsState()

    val speechManager = remember { SpeechRecognizerManager(context.applicationContext) }
    val voiceScope = rememberCoroutineScope()
    settingsViewModel.onProviderAuthCompleted = {
        navController.navigate(ROUTE_SETTINGS_PROVIDERS) {
            launchSingleTop = true
        }
    }
    settingsViewModel.onLocalRuntimeRestartNeeded = {
        workspaceViewModel.restartLocalRuntime()
    }
    var voiceJob by remember { mutableStateOf<Job?>(null) }
    var startVoiceAfterPermission by remember { mutableStateOf(false) }
    var startWakeWordAfterPermission by remember { mutableStateOf(false) }

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
        voiceJob =
            voiceScope.launch {
                val transcript = SpeechTranscriptAccumulator()
                // Wake-word detection records straight from the microphone, while this dictation
                // records through SpeechRecognizer in another process. Left running, detection wins
                // the platform's arbitration and the recogniser is handed silence.
                val micToken = UUID.randomUUID().toString()
                WakeWordService.holdMicrophone(micToken)
                try {
                    var silentSegments = 0
                    while (true) {
                        var outcome = VoiceDictationOutcome.RESTART
                        speechManager.startListening(speechLocaleTag(context)).collect { result ->
                            when (result) {
                                SpeechResult.Ready,
                                SpeechResult.Listening,
                                -> Unit
                                SpeechResult.Processing -> chatViewModel.showSpeechProcessing()
                                is SpeechResult.PartialResult -> {
                                    chatViewModel.updateSpeechPartial(transcript.preview(result.text))
                                }
                                is SpeechResult.Result -> {
                                    silentSegments = 0
                                    transcript.append(result.text)
                                    chatViewModel.updateSpeechPartial(transcript.text)
                                }
                                is SpeechResult.Error -> {
                                    silentSegments += 1
                                    outcome =
                                        VoiceDictationPolicy.outcomeFor(
                                            code = result.code,
                                            hasTranscript = transcript.text.isNotBlank(),
                                            consecutiveFailures = silentSegments,
                                        )
                                    if (outcome == VoiceDictationOutcome.REPORT) {
                                        chatViewModel.reportSpeechError(result.message)
                                    }
                                }
                            }
                        }
                        if (outcome != VoiceDictationOutcome.RESTART) break
                        delay(VOICE_RESTART_DELAY_MS)
                        chatViewModel.startListening()
                        if (transcript.text.isNotBlank()) {
                            chatViewModel.updateSpeechPartial(transcript.text)
                        }
                    }
                } finally {
                    WakeWordService.releaseMicrophone(micToken)
                    chatViewModel.stopListening()
                }
            }
    }

    val microphonePermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted && startVoiceAfterPermission) {
                startVoiceAfterPermission = false
                startOrStopVoiceInput()
            } else if (granted && startWakeWordAfterPermission) {
                startWakeWordAfterPermission = false
                if (WakeWordSettingsPolicy.canEnable(microphonePermission = true, assistantActive = assistantActive)) {
                    settingsViewModel.setWakeWordEnabled(true)
                    val started =
                        WakeWordService.start(
                            context,
                            settingsState.wakeWordModelLanguage,
                        )
                    if (!started) {
                        settingsViewModel.setWakeWordEnabled(false)
                        android.widget.Toast.makeText(
                            context,
                            R.string.wake_word_start_failed,
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                } else {
                    android.widget.Toast.makeText(
                        context,
                        R.string.wake_word_requires_assistant,
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    onOpenAssistantSettings()
                }
            } else if (!granted) {
                startVoiceAfterPermission = false
                startWakeWordAfterPermission = false
                chatViewModel.reportSpeechError(context.getString(R.string.mic_permission_required))
            }
        }

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { /* optional */ }

    val workspaceImportLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            voiceScope.launch {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    val imported =
                        withContext(Dispatchers.IO) {
                            com.yugahashimoto.andcode.runtime.local.SafWorkspaceImporter(context).importTree(uri)
                        }
                    val existing = app.settings.safWorkspaceUris.toMutableList()
                    if (uri.toString() !in existing) {
                        existing += uri.toString()
                        app.settings.safWorkspaceUris = existing
                    }
                    settingsViewModel.setAssistantWorkspacePath(imported.absolutePath)
                    workspaceViewModel.addProject("/workspace/${imported.name}")
                    workspaceViewModel.refresh()
                    chatViewModel.selectWorkspace("/workspace/${imported.name}")
                }
            }
        }

    val attachmentLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            voiceScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        com.yugahashimoto.andcode.runtime.local.AttachmentImporter(context).import(uri)
                    }
                }.onSuccess { attachment ->
                    chatViewModel.addAttachment(attachment)
                }
            }
        }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val granted =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(preferences.wakeWordEnabled, settingsState.wakeWordModelLanguage, assistantActive) {
        if (preferences.wakeWordEnabled && hasMicrophonePermission() && assistantActive) {
            val started =
                WakeWordService.start(
                    context,
                    language = settingsState.wakeWordModelLanguage,
                )
            if (!started) settingsViewModel.setWakeWordEnabled(false)
        } else {
            WakeWordService.stop(context)
            if (preferences.wakeWordEnabled) settingsViewModel.setWakeWordEnabled(false)
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

    LaunchedEffect(
        preferences.providerId,
        preferences.modelId,
        preferences.agentId,
        settingsState.providers,
        settingsState.agents,
    ) {
        // Provider, model and agent are all remembered globally, so a selection made against one
        // runtime survives a switch to another that has never heard of it. Forwarding one is not a
        // cosmetic problem: the runtime rejects the prompt and no message is created at all. Each is
        // only passed on once the active runtime's catalogue confirms it, and dropping it lets the
        // runtime fall back to its own default.
        val model =
            settingsState.providers
                .firstOrNull { it.id == preferences.providerId }
                ?.models?.get(preferences.modelId)
        chatViewModel.selectConfiguration(
            preferences.providerId?.takeIf { model != null },
            preferences.modelId?.takeIf { model != null },
            preferences.agentId?.takeIf { id -> settingsState.agents.any { it.name == id } },
            model?.limit?.context ?: 0L,
        )
    }

    // What the composer falls back to before the chat has a selection of its own.
    //
    // The global preference is not usable as-is: it belongs to whichever runtime the user last
    // picked a model on, so after switching agent it names a model this runtime has never heard of.
    // selectConfiguration above already drops such a value from the chat state - and the composer
    // then fell straight back to the same stale preference, which is why the chip still read
    // Claude's "sonnet" under OpenCode. Validated against the active runtime's own catalogue, with
    // its default as the last resort.
    val activeProvider = settingsState.providers.firstOrNull { it.id == preferences.providerId }
    val fallbackProviderId =
        preferences.providerId?.takeIf { activeProvider != null }
            ?: settingsState.providers.firstOrNull()?.id
    val fallbackModelId =
        preferences.modelId?.takeIf { activeProvider?.models?.containsKey(it) == true }
            ?: settingsState.providers.firstOrNull { it.id == fallbackProviderId }?.models?.keys?.firstOrNull()

    LaunchedEffect(preferences.autoAcceptPermissions) {
        chatViewModel.setAutoAcceptPermissions(preferences.autoAcceptPermissions)
    }

    LaunchedEffect(selectedRuntime?.id, workspaceState.workspaces, chatState.sessionId) {
        if (chatState.sessionId != null) return@LaunchedEffect
        val currentPath = chatState.selectedWorkspacePath
        val available = workspaceState.workspaces
        if (currentPath == null && available.isNotEmpty()) {
            chatViewModel.selectWorkspace(available.first().path)
        }
    }

    // A notification tap. The chat may belong to another agent than the selected one, so move to
    // its runtime first — opening it against the wrong backend only loads an error. The effect is
    // keyed on the whole link (token included), so tapping twice for the same session still works,
    // and consuming resets the state so a stale link never navigates again.
    LaunchedEffect(chatDeepLink) {
        val link = chatDeepLink ?: return@LaunchedEffect
        onChatDeepLinkConsumed()
        val knownSession = app.catalogRepository.allSessions.value.firstOrNull { it.session.id == link.sessionId }
        val runtimeId = link.runtimeId ?: knownSession?.runtimeId
        if (runtimeId != null && runtimeId != selectedRuntime?.id) {
            app.runtimeRegistry.select(runtimeId)
        }
        app.activityRepository.markSessionRead(link.sessionId)
        val title = knownSession?.session?.title?.takeIf(String::isNotBlank) ?: link.sessionId
        pendingSession = link.sessionId to title
        navController.navigate(ROUTE_CHAT) { launchSingleTop = true }
    }

    // Lets the in-guest agent pop the guest browser open for the user by dropping a command
    // file into the active workspace (see GuestBrowserCommandWatcher).
    GuestBrowserCommandWatcher(
        workspacePath = chatState.selectedWorkspacePath,
        onOpenUrl = { url ->
            navController.navigate(guestBrowserRoute(url)) { launchSingleTop = true }
        },
    )

    val onHandoff: (String) -> Unit = { targetRuntimeId ->
        val prompt = buildHandoffPrompt(chatState.messages)
        pendingHandoffPrompt = targetRuntimeId to prompt
        handoffReady = false
        app.runtimeRegistry.select(targetRuntimeId)
    }

    val startDestination = remember { if (app.settings.onboardingCompleted) ROUTE_CHAT else ROUTE_ONBOARDING }
    val completeOnboardingAndGoToChat: () -> Unit = {
        app.settings.onboardingCompleted = true
        navController.navigate(ROUTE_CHAT) {
            popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
            launchSingleTop = true
        }
    }

    val appVersion =
        remember {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull().orEmpty()
        }

    // Every runtime's chats, not just the selected one's: switching agent used to look like the
    // history had been wiped. Each row carries the runtime that owns it so opening one can switch.
    val allSessions by app.catalogRepository.allSessions.collectAsState()
    val recentSessions =
        remember(allSessions, activityState.activeSessionIds, activityState.completedSessionIds) {
            allSessions
                .filter { it.session.parentId == null }
                .take(25).map { ref ->
                    val session = ref.session
                    DrawerRecentSession(
                        id = session.id,
                        title = session.title.ifBlank { session.slug ?: session.id },
                        relativeTime = relativeTimeLabel(context, session.time.updated ?: session.time.created),
                        directory = session.directory,
                        isActive = session.id in activityState.activeSessionIds,
                        hasUnread = session.id in activityState.completedSessionIds,
                        status =
                            when {
                                session.id in activityState.activeSessionIds -> SessionStatus.RUNNING
                                session.id in activityState.completedSessionIds -> SessionStatus.COMPLETED_UNREAD
                                else -> SessionStatus.IDLE
                            },
                        runtimeId = ref.runtimeId,
                        agent = ref.agent,
                    )
                }
        }

    // Local agents that are actually provisioned. A target reports Unavailable while its agent is
    // missing, which is exactly the case the switcher must not offer.
    val drawerAgents =
        runtimeTargets.mapNotNull { target ->
            val agent = target.agent ?: return@mapNotNull null
            DrawerAgent(target.id, agent).takeIf { target.state.value !is RuntimeState.Unavailable }
        }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    val currentRoute = backStackEntry?.destination?.route

    LaunchedEffect(currentRoute, chatState.sessionId) {
        visibleChatSessionId.value = chatState.sessionId?.takeIf { currentRoute == ROUTE_CHAT }
    }

    // Opening a chat and watching it finish counts as reading it.
    LaunchedEffect(currentRoute, chatState.sessionId, chatState.isRunning) {
        val sessionId = chatState.sessionId
        if (currentRoute == ROUTE_CHAT && sessionId != null && !chatState.isRunning) {
            app.activityRepository.markSessionRead(sessionId)
        }
    }

    val subagentInfos =
        remember(activityState.sessions, chatState.sessionId, activityState.activeSessionIds) {
            val parentId = chatState.sessionId ?: return@remember emptyList()
            activityState.sessions
                .filter { it.parentId == parentId && it.id in activityState.activeSessionIds }
                .map { session ->
                    SubagentInfo(
                        id = session.id,
                        name = session.title.ifBlank { session.slug ?: session.id.take(8) },
                        status = "running",
                        providerId = "",
                    )
                }
        }

    fun openDrawer() {
        keyboardController?.hide()
        drawerScope.launch { drawerState.open() }
    }

    fun closeDrawer() {
        drawerScope.launch { drawerState.close() }
    }

    /** Opens a chat for [sessionId], switching to its runtime first like the drawer does. */
    fun openSessionInChat(
        sessionId: String,
        title: String,
        runtimeId: String?,
    ) {
        if (runtimeId != null && runtimeId != selectedRuntime?.id) {
            app.runtimeRegistry.select(runtimeId)
        }
        app.activityRepository.markSessionRead(sessionId)
        pendingSession = sessionId to title
        navController.navigate(ROUTE_CHAT) { launchSingleTop = true }
    }

    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) keyboardController?.hide()
    }

    val drawerGesturesEnabled = currentRoute in DRAWER_ROOT_ROUTES

    AndCodeTheme(
        appTheme = AppTheme.fromKey(preferences.theme),
        uiFontSize = preferences.uiFontSize,
    ) {
        ModalNavigationDrawer(
            modifier =
                if (drawerGesturesEnabled) {
                    Modifier.onlyAllowDrawerEdgeSwipe(drawerIsOpen = drawerState.isOpen)
                } else {
                    Modifier
                },
            drawerState = drawerState,
            gesturesEnabled = drawerGesturesEnabled,
            drawerContent = {
                ModalDrawerSheet {
                    AppDrawerContent(
                        recentSessions = recentSessions,
                        workspaces = workspaceState.workspaces,
                        selectedWorkspacePath = chatState.selectedWorkspacePath,
                        onNewChat = {
                            closeDrawer()
                            pendingSession = null
                            chatViewModel.newSession()
                            chatViewModel.selectWorkspace(null)
                            navController.navigate(ROUTE_CHAT) { launchSingleTop = true }
                        },
                        onSelectProject = { workspace ->
                            closeDrawer()
                            pendingSession = null
                            chatViewModel.newSession()
                            chatViewModel.selectWorkspace(workspace.path)
                            navController.navigate(ROUTE_CHAT) { launchSingleTop = true }
                        },
                        agents = drawerAgents,
                        selectedRuntimeId = selectedRuntime?.id,
                        onSelectAgent = { agent ->
                            closeDrawer()
                            if (agent.runtimeId != selectedRuntime?.id) {
                                app.runtimeRegistry.select(agent.runtimeId)
                                pendingSession = null
                            }
                            navController.navigate(ROUTE_CHAT) { launchSingleTop = true }
                        },
                        onOpenSession = { id, title, runtimeId ->
                            closeDrawer()
                            // The list spans every agent, so the chat may belong to one that is not
                            // selected; opening it has to move to its runtime first.
                            openSessionInChat(id, title, runtimeId)
                        },
                        onNavigate = { route ->
                            closeDrawer()
                            navController.navigate(route) { launchSingleTop = true }
                        },
                        onDeleteSession = { sessionId ->
                            voiceScope.launch {
                                val targets = app.runtimeRegistry.targets.value
                                coroutineScope {
                                    targets.map { target ->
                                        async { runCatching { target.deleteSession(sessionId) } }
                                    }.awaitAll()
                                }
                                app.catalogRepository.refreshSessionsOnly()
                            }
                        },
                        onArchiveSession = { sessionId ->
                            voiceScope.launch {
                                val targets = app.runtimeRegistry.targets.value
                                coroutineScope {
                                    targets.map { target ->
                                        async { runCatching { target.archiveSession(sessionId) } }
                                    }.awaitAll()
                                }
                                app.catalogRepository.refreshSessionsOnly()
                            }
                        },
                        onBatchDelete = { sessionIds ->
                            voiceScope.launch {
                                val targets = app.runtimeRegistry.targets.value
                                coroutineScope {
                                    sessionIds.flatMap { id ->
                                        targets.map { target ->
                                            async { runCatching { target.deleteSession(id) } }
                                        }
                                    }.awaitAll()
                                }
                                app.catalogRepository.refreshSessionsOnly()
                            }
                        },
                        onBatchArchive = { sessionIds ->
                            voiceScope.launch {
                                val targets = app.runtimeRegistry.targets.value
                                coroutineScope {
                                    sessionIds.flatMap { id ->
                                        targets.map { target ->
                                            async { runCatching { target.archiveSession(id) } }
                                        }
                                    }.awaitAll()
                                }
                                app.catalogRepository.refreshSessionsOnly()
                            }
                        },
                        collapsedSections = collapsedSections,
                        onToggleSection = { section ->
                            collapsedSections =
                                if (section in collapsedSections) {
                                    collapsedSections - section
                                } else {
                                    collapsedSections + section
                                }
                        },
                    )
                }
            },
        ) {
            NavHost(
                navController = navController,
                startDestination = startDestination,
            ) {
                composable(ROUTE_ONBOARDING) {
                    OnboardingChoiceScreen(
                        onSelectAndroid = { navController.navigate(ROUTE_ANDROID_SETUP) },
                        onSelectRemote = { navController.navigate(ROUTE_REMOTE_CONNECTION) },
                    )
                }

                composable(ROUTE_ANDROID_SETUP) {
                    val localRuntimeStatus by app.localRuntimeManager.state.collectAsState()
                    AndroidSetupScreen(
                        runtimeStatus = localRuntimeStatus,
                        claude = workspaceState.claude,
                        antigravity = antigravityState,
                        onStartSetup = { agents ->
                            // Ticking Claude Code or Antigravity next to OpenCode used to install
                            // neither of them: the two branches below were guarded on OpenCode
                            // *not* being selected, and the OpenCode path never received the
                            // selection at all, so it provisioned OpenCode alone. One install now
                            // carries the whole selection, which is what LocalRuntimeInstaller
                            // already knew how to do - and it must stay one install, because a
                            // second one would race it for the same staging directory.
                            if (com.yugahashimoto.andcode.runtime.LocalAgent.OPEN_CODE in agents) {
                                workspaceViewModel.setupLocalRuntime(agents)
                            } else if (com.yugahashimoto.andcode.runtime.LocalAgent.ANTIGRAVITY in agents) {
                                app.antigravityController.install(agents)
                            } else if (com.yugahashimoto.andcode.runtime.LocalAgent.CLAUDE_CODE in agents) {
                                workspaceViewModel.installClaudeCode()
                            }
                        },
                        onSelectClaudePermissionMode = { mode ->
                            workspaceViewModel.setClaudePermissionMode(mode, chatState.sessionId)
                        },
                        onBeginClaudeSignIn = workspaceViewModel::beginClaudeSignIn,
                        onSubmitClaudeSignInCode = workspaceViewModel::submitClaudeSignInCode,
                        onCancelClaudeSignIn = workspaceViewModel::cancelClaudeSignIn,
                        onSignOutClaude = workspaceViewModel::signOutClaude,
                        onBeginAntigravitySignIn = app.antigravityController::beginAuth,
                        onSubmitAntigravitySignInCode = app.antigravityController::submitAuthCode,
                        onCancelAntigravitySignIn = app.antigravityController::cancelAuth,
                        onSignOutAntigravity = app.antigravityController::logout,
                        onSelectAntigravityPermissionMode = { mode ->
                            app.antigravityController.setPermissionMode(mode, chatState.sessionId)
                        },
                        onOpenUrl = { url ->
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                        },
                        settingsState = settingsState,
                        onOpenProviderAuth = settingsViewModel::openProviderAuth,
                        onSelectProviderAuthMethod = settingsViewModel::selectProviderAuthMethod,
                        onProviderAuthInput = settingsViewModel::updateProviderAuthInput,
                        onProviderApiKey = settingsViewModel::updateProviderApiKey,
                        onSubmitProviderAuth = settingsViewModel::submitProviderAuth,
                        onCompleteProviderOAuth = settingsViewModel::completeProviderOAuth,
                        onDisconnectProvider = settingsViewModel::disconnectProvider,
                        onDismissProviderAuth = settingsViewModel::dismissProviderAuth,
                        onRefreshProviderAuth = settingsViewModel::refreshProviderAuth,
                        onRefreshCatalog = app.catalogRepository::refreshProvidersOnly,
                        onRefreshClaudeState = workspaceViewModel::refreshClaudeCode,
                        onRefreshAntigravityState = app.antigravityController::refresh,
                        onConnectGitHub = { settingsViewModel.beginGitHubDeviceFlow() },
                        onOpenGitHubVerification = { url ->
                            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                        },
                        onDisconnectGitHub = settingsViewModel::disconnectGitHub,
                        onBack = { navController.popBackStack() },
                        onFinish = completeOnboardingAndGoToChat,
                    )
                }

                composable(ROUTE_CHAT) {
                    // Keyed on the runtime too: a deep link that also switches the runtime must run
                    // against the view model of the new runtime, not the previous one's. While no
                    // runtime is selected yet (cold start) the chat backend does not exist and
                    // openSession would silently no-op — wait for one instead of dropping the request.
                    LaunchedEffect(pendingSession, selectedRuntime?.id) {
                        val pending = pendingSession ?: return@LaunchedEffect
                        if (selectedRuntime == null) return@LaunchedEffect
                        chatViewModel.openSession(pending.first, pending.second)
                        pendingSession = null
                    }
                    LaunchedEffect(pendingHandoffPrompt, selectedRuntime?.id, handoffReady) {
                        val pending = pendingHandoffPrompt
                        if (pending != null && handoffReady && selectedRuntime?.id == pending.first) {
                            chatViewModel.sendMessage(pending.second)
                            pendingHandoffPrompt = null
                            handoffReady = false
                        }
                    }
                    // A pull request is usually merged or closed on GitHub, not from here, so the
                    // badges are refreshed while the chat is on screen. The repository decides what
                    // is actually stale, so this only costs a request when something can change.
                    if (chatState.pullRequests.isNotEmpty()) {
                        LaunchedEffect(Unit) {
                            while (true) {
                                delay(PULL_REQUEST_REFRESH_INTERVAL_MS)
                                chatViewModel.refreshPullRequests()
                            }
                        }
                    }
                    ChatHomeScreen(
                        state = chatState,
                        providers = settingsState.providers,
                        agents = settingsState.agents,
                        selectedProviderId = chatState.selectedProviderId ?: fallbackProviderId,
                        selectedModelId = chatState.selectedModelId ?: fallbackModelId,
                        selectedAgentId = chatState.selectedAgentId ?: settingsState.agentId,
                        runtimeTargets = runtimeTargets,
                        selectedRuntimeId = selectedRuntime?.id,
                        claudePermissionMode =
                            workspaceState.claude
                                .takeIf { selectedRuntime?.agent == com.yugahashimoto.andcode.runtime.LocalAgent.CLAUDE_CODE }
                                ?.permissionMode,
                        supportsPermissions = selectedRuntime?.capabilities?.permissions != false,
                        onSelectClaudePermissionMode = { mode ->
                            workspaceViewModel.setClaudePermissionMode(mode, chatState.sessionId)
                        },
                        // The mode settings shows, so the chip is not left naming whatever agent id
                        // another runtime last remembered - see AntigravityTarget.listAgents.
                        antigravityPermissionMode =
                            antigravityState
                                .takeIf { selectedRuntime?.agent == com.yugahashimoto.andcode.runtime.LocalAgent.ANTIGRAVITY }
                                ?.permissionMode,
                        onSelectAntigravityPermissionMode = { mode ->
                            app.antigravityController.setPermissionMode(mode, chatState.sessionId)
                        },
                        onModelPickerClosed = { handoffReady = true },
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
                        selectedVariant = chatState.selectedVariant,
                        onSelectVariant = chatViewModel::selectVariant,
                        onAttach = { attachmentLauncher.launch("*/*") },
                        onRemoveAttachment = chatViewModel::removeAttachment,
                        onImageAttachment = { bitmap ->
                            voiceScope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        com.yugahashimoto.andcode.runtime.local.AttachmentImporter(context).import(bitmap)
                                    }
                                }.onSuccess { attachment ->
                                    chatViewModel.addImageAttachment(attachment, bitmap)
                                }
                            }
                        },
                        favoriteModelKeys = settingsState.favoriteModelKeys,
                        recentModelKeys = settingsState.recentModelKeys,
                        hiddenModelKeys =
                            settingsState.hiddenModelKeys.takeIf {
                                selectedRuntime?.capabilities?.providerModelList == true
                            }.orEmpty(),
                        onToggleFavorite = settingsViewModel::toggleFavoriteModel,
                        onSelectQuestionAnswer = chatViewModel::selectQuestionAnswer,
                        onSubmitQuestion = chatViewModel::submitQuestion,
                        onCancelQuestion = chatViewModel::cancelQuestion,
                        onDismissQuestion = chatViewModel::dismissQuestion,
                        autoAcceptPermissions = settingsState.autoAcceptPermissions,
                        onToggleAutoAccept = settingsViewModel::setAutoAcceptPermissions,
                        enterToSend = preferences.enterToSend,
                        onSendMessage = chatViewModel::sendMessage,
                        onPermission = chatViewModel::respondToPermission,
                        onAbort = chatViewModel::abort,
                        onRecheckStall = chatViewModel::checkForStall,
                        onMic = requestVoiceInput,
                        onNewChat = {
                            pendingSession = null
                            chatViewModel.newSession()
                        },
                        onOpenLocalSetup = {
                            navController.navigate(ROUTE_ANDROID_SETUP) { launchSingleTop = true }
                        },
                        onOpenRemoteSetup = {
                            navController.navigate(ROUTE_REMOTE_CONNECTION) { launchSingleTop = true }
                        },
                        onRefreshCatalog = app.catalogRepository::refreshProvidersOnly,
                        onOpenDrawer = {
                            app.catalogRepository.refreshSessionsOnly()
                            openDrawer()
                        },
                        subagents = subagentInfos,
                        onSubagentClick = { childSessionId ->
                            val childSession = activityState.sessions.firstOrNull { it.id == childSessionId }
                            app.activityRepository.markSessionRead(childSessionId)
                            // The chat this was opened from is the way back, so open the child
                            // directly instead of routing through pendingSession, which would drop
                            // the parent.
                            pendingSession = null
                            chatViewModel.openSubagentSession(
                                childSessionId,
                                childSession?.title ?: childSessionId,
                            )
                        },
                        onReturnToParentSession = {
                            pendingSession = null
                            chatViewModel.openParentSession()
                        },
                        onOpenUrl = { url ->
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                        },
                    )
                }

                settingsNavGraph(
                    navController = navController,
                    settingsViewModel = settingsViewModel,
                    notificationsEnabled = { notificationsEnabled },
                    onToggleNotifications = { enabled ->
                        notificationsEnabled = enabled
                        if (enabled && android.os.Build.VERSION.SDK_INT >= 33) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    appVersion = appVersion,
                    onOpenDrawer = { openDrawer() },
                    onOpenAssistantSettings = onOpenAssistantSettings,
                    assistantActive = { assistantActive },
                    runtimeTargets = { runtimeTargets },
                    workspaces = { workspaceState.workspaces },
                    onShowDiagnostics = { showDiagnostics = true },
                    preferences = { preferences },
                    appPreferences = app.preferences,
                    runtimeRegistry = app.runtimeRegistry,
                    context = context,
                    hasMicrophonePermission = { hasMicrophonePermission() },
                    claude = { workspaceState.claude },
                    claudeActions =
                        ClaudeSettingsActions(
                            onInstall = workspaceViewModel::installClaudeCode,
                            onUpdate = workspaceViewModel::updateClaudeCode,
                            onSelectPermissionMode = { mode ->
                                workspaceViewModel.setClaudePermissionMode(mode, chatState.sessionId)
                            },
                            onSignIn = workspaceViewModel::beginClaudeSignIn,
                            onSubmitCode = workspaceViewModel::submitClaudeSignInCode,
                            onCancelSignIn = workspaceViewModel::cancelClaudeSignIn,
                            onSignOut = workspaceViewModel::signOutClaude,
                        ),
                    antigravity = { antigravityState },
                    antigravityActions =
                        com.yugahashimoto.andcode.ui.navigation.AntigravitySettingsActions(
                            onInstall = app.antigravityController::install,
                            onUpdate = app.antigravityController::update,
                            onSelectPermissionMode = { mode ->
                                app.antigravityController.setPermissionMode(mode, chatState.sessionId)
                            },
                            onSignIn = app.antigravityController::beginAuth,
                            onSubmitCode = app.antigravityController::submitAuthCode,
                            onCancelSignIn = app.antigravityController::cancelAuth,
                            onSignOut = app.antigravityController::logout,
                        ),
                    onRequestWakeWordPermission = {
                        startWakeWordAfterPermission = true
                        microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                )

                composable(ROUTE_SCHEDULES) {
                    ScheduleListScreen(
                        schedules = schedules,
                        runs = scheduleRuns,
                        runtimeTargets = runtimeTargets,
                        nextFireAt = app.scheduleManager::nextFireAt,
                        onOpenDrawer = { openDrawer() },
                        onNewSchedule = { navController.navigate(ROUTE_SCHEDULE_EDIT) { launchSingleTop = true } },
                        onOpenSchedule = { scheduleId ->
                            navController.navigate(scheduleDetailRoute(scheduleId)) { launchSingleTop = true }
                        },
                        onEdit = { scheduleId ->
                            navController.navigate(scheduleEditRoute(scheduleId)) { launchSingleTop = true }
                        },
                        onRunNow = { scheduleId ->
                            scheduleViewModel.runNow(scheduleId)
                            android.widget.Toast.makeText(
                                context,
                                R.string.schedule_run_now_started,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        },
                        onDelete = scheduleViewModel::delete,
                        onToggleEnabled = scheduleViewModel::setEnabled,
                        exactAlarmsAllowed = app.scheduleManager::exactAlarmsAllowed,
                        // Alarms armed while the permission was missing are inexact; granting it
                        // only takes effect once they are re-armed.
                        onExactAlarmsGranted = app.scheduleManager::rescheduleAll,
                    )
                }

                composable(
                    route = SCHEDULE_DETAIL_ROUTE_PATTERN,
                    arguments = listOf(navArgument("scheduleId") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val scheduleId = decodeRouteArg(backStackEntry.arguments?.getString("scheduleId").orEmpty())
                    val schedule = schedules.firstOrNull { it.id == scheduleId }
                    if (schedule == null) {
                        // Deleted while on screen; drop back to the list.
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    } else {
                        ScheduleRunsScreen(
                            schedule = schedule,
                            runs = scheduleRuns.filter { it.scheduleId == scheduleId },
                            titleForSession = { sessionId ->
                                allSessions
                                    .firstOrNull { it.session.id == sessionId }
                                    ?.session?.title?.ifBlank { null }
                            },
                            onBack = { navController.popBackStack() },
                            onEdit = { id -> navController.navigate(scheduleEditRoute(id)) { launchSingleTop = true } },
                            onRunNow = { id ->
                                scheduleViewModel.runNow(id)
                                android.widget.Toast.makeText(
                                    context,
                                    R.string.schedule_run_now_started,
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            },
                            onOpenSession = { run ->
                                openSessionInChat(run.sessionId, schedule.displayName, run.runtimeId)
                            },
                        )
                    }
                }

                composable(
                    route = SCHEDULE_EDIT_ROUTE_PATTERN,
                    arguments =
                        listOf(
                            navArgument(SCHEDULE_EDIT_ARG_ID) {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                        ),
                ) { backStackEntry ->
                    val scheduleId =
                        backStackEntry.arguments?.getString(SCHEDULE_EDIT_ARG_ID)
                            ?.takeIf(String::isNotBlank)
                            ?.let(::decodeRouteArg)
                    ScheduleEditorScreen(
                        existing = scheduleId?.let { id -> schedules.firstOrNull { it.id == id } },
                        runtimeTargets = runtimeTargets,
                        providers = settingsState.providers,
                        workspaces = workspaceState.workspaces,
                        onSave = { built ->
                            if (scheduleId != null) {
                                scheduleViewModel.update(built)
                            } else {
                                scheduleViewModel.create(built)
                            }
                            navController.popBackStack()
                        },
                        onBack = { navController.popBackStack() },
                    )
                }

                workspaceNavGraph(
                    navController = navController,
                    workspaceViewModel = workspaceViewModel,
                    selectedWorkspace = { selectedWorkspace },
                    onSelectWorkspace = { selectedWorkspace = it },
                    selectedRuntime = { selectedRuntime },
                    app = app,
                    onImportFolder = { workspaceImportLauncher.launch(null) },
                    onShowCloneDialog = { showCloneDialog = true },
                    completeOnboardingAndGoToChat = completeOnboardingAndGoToChat,
                )
            }
        }

        if (showCloneDialog) {
            GithubCloneDialog(
                githubConfigured = !app.settings.githubToken.isNullOrBlank(),
                onClone = { url ->
                    val name = url.trim().removeSuffix("/").removeSuffix(".git").substringAfterLast('/')
                    withContext(Dispatchers.IO) { app.gitCloneRepository.clone(url, name) }
                },
                onListRepos = { settingsViewModel.listGitHubRepos() },
                onCloned = { serverPath ->
                    workspaceViewModel.addProject(serverPath)
                    workspaceViewModel.refresh()
                    chatViewModel.newSession()
                    chatViewModel.selectWorkspace(serverPath)
                },
                onDismiss = { showCloneDialog = false },
            )
        }

        if (showDiagnostics) {
            val runtimeStateFlow =
                remember(selectedRuntime) {
                    selectedRuntime?.state ?: MutableStateFlow(RuntimeState.Disconnected)
                }
            val runtimeState = runtimeStateFlow.collectAsState().value
            DiagnosticsSheet(
                onDismiss = { showDiagnostics = false },
                appVersion = BuildConfig.VERSION_NAME,
                connectionStatus =
                    when (runtimeState) {
                        is RuntimeState.Connected -> stringResource(R.string.connected_label)
                        RuntimeState.Connecting -> stringResource(R.string.runtime_status_starting)
                        else -> stringResource(R.string.disconnected_label)
                    },
                runtimeStatus =
                    when (runtimeState) {
                        RuntimeState.Disconnected -> stringResource(R.string.disconnected_label)
                        RuntimeState.Connecting -> stringResource(R.string.runtime_status_starting)
                        is RuntimeState.Connected -> stringResource(R.string.connected_version, runtimeState.version)
                        is RuntimeState.Unavailable -> runtimeState.reason
                        is RuntimeState.Failed -> runtimeState.message
                    },
            )
        }
    }
}

private enum class CloneSource { REPOS, URL }

@Composable
private fun GithubCloneDialog(
    githubConfigured: Boolean,
    onClone: suspend (String) -> GitCloneResult,
    onListRepos: suspend () -> List<GitHubRepo>,
    onCloned: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var source by remember { mutableStateOf(if (githubConfigured) CloneSource.REPOS else CloneSource.URL) }
    var url by remember { mutableStateOf("") }
    var repos by remember { mutableStateOf<List<GitHubRepo>>(emptyList()) }
    var isLoadingRepos by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var isCloning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val cloneFailedMessage = stringResource(R.string.workspace_clone_failed)

    LaunchedEffect(githubConfigured) {
        if (githubConfigured) {
            isLoadingRepos = true
            repos = onListRepos()
            isLoadingRepos = false
        }
    }

    fun startClone(cloneUrl: String) {
        isCloning = true
        error = null
        scope.launch {
            val result = onClone(cloneUrl)
            if (result.exitCode == 0) {
                onCloned(result.serverPath)
                onDismiss()
            } else {
                error = result.output.lineSequence().lastOrNull { it.isNotBlank() }
                    ?: cloneFailedMessage.format(result.exitCode)
                isCloning = false
            }
        }
    }

    val filteredRepos =
        remember(repos, search) {
            if (search.isBlank()) {
                repos
            } else {
                repos.filter {
                    it.fullName.contains(search, ignoreCase = true)
                }
            }
        }

    AlertDialog(
        onDismissRequest = { if (!isCloning) onDismiss() },
        title = { Text(stringResource(R.string.workspace_clone_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!githubConfigured) {
                    Text(
                        text = stringResource(R.string.workspace_clone_requires_auth),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = source == CloneSource.REPOS,
                            onClick = { source = CloneSource.REPOS },
                            label = { Text(stringResource(R.string.workspace_clone_my_repos)) },
                        )
                        FilterChip(
                            selected = source == CloneSource.URL,
                            onClick = { source = CloneSource.URL },
                            label = { Text(stringResource(R.string.workspace_clone_url_tab)) },
                        )
                    }
                }

                if (source == CloneSource.REPOS && githubConfigured) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = { Text(stringResource(R.string.workspace_clone_search)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (isLoadingRepos) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(vertical = 8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.workspace_clone_loading_repos), style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        LazyColumn(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 280.dp),
                        ) {
                            items(filteredRepos, key = { it.fullName }) { repo ->
                                RepoRow(
                                    repo = repo,
                                    enabled = !isCloning,
                                    onClick = { startClone(repo.cloneUrl) },
                                )
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = url,
                        onValueChange = {
                            url = it
                            error = null
                        },
                        label = { Text(stringResource(R.string.workspace_clone_url_label)) },
                        placeholder = { Text(stringResource(R.string.workspace_clone_url_placeholder)) },
                        singleLine = true,
                        enabled = !isCloning,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (isCloning) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.workspace_clone_running),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (source == CloneSource.URL || !githubConfigured) {
                Button(
                    enabled = url.isNotBlank() && !isCloning,
                    onClick = { startClone(url.trim()) },
                ) {
                    Text(stringResource(R.string.workspace_clone_action))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isCloning) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** Shows the stack trace of the crash the app came back from, so it can be reported by hand. */
@Composable
private fun CrashReportDialog(
    report: String,
    onCopied: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.crash_report_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.crash_report_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = report,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(report))
                onCopied()
            }) {
                Text(stringResource(R.string.crash_report_copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.crash_report_dismiss))
            }
        },
    )
}

@Composable
private fun RepoRow(
    repo: GitHubRepo,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                if (repo.isPrivate) Icons.Default.Lock else Icons.Default.Folder,
                contentDescription = stringResource(R.string.cd_repository),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                repo.fullName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
