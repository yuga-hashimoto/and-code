package com.yugahashimoto.andcode.feature.chat

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeProvider
import com.yugahashimoto.andcode.core.api.PromptAttachment
import com.yugahashimoto.andcode.feature.workspace.GitHubAutoAttachChips
import com.yugahashimoto.andcode.feature.workspace.GitHubReference
import com.yugahashimoto.andcode.runtime.PermissionResponse
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.runtime.local.ClaudePermissionMode
import com.yugahashimoto.andcode.ui.components.StatusChip
import com.yugahashimoto.andcode.ui.components.VolumeMeter
import com.yugahashimoto.andcode.ui.theme.AndCodeTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatHomeScreen(
    state: ChatUiState,
    providers: List<OpenCodeProvider>,
    agents: List<OpenCodeAgent>,
    selectedProviderId: String?,
    selectedModelId: String?,
    selectedAgentId: String?,
    runtimeTargets: List<RuntimeTarget>,
    selectedRuntimeId: String?,
    onSelectRuntime: (String) -> Unit,
    onSelectModel: (String, String) -> Unit,
    onSelectAgent: (String) -> Unit,
    selectedVariant: String? = null,
    onSelectVariant: (String?) -> Unit = {},
    attachments: List<PromptAttachment> = emptyList(),
    onAttach: () -> Unit = {},
    onRemoveAttachment: (Int) -> Unit = {},
    favoriteModelKeys: Set<String> = emptySet(),
    recentModelKeys: List<String> = emptyList(),
    hiddenModelKeys: Set<String> = emptySet(),
    onToggleFavorite: (String, String) -> Unit = { _, _ -> },
    /** Non-null only for runtimes that decide tool permissions per session, i.e. Claude Code. */
    claudePermissionMode: ClaudePermissionMode? = null,
    onSelectClaudePermissionMode: (ClaudePermissionMode) -> Unit = {},
    /**
     * Called once the model and runtime sheet closes.
     *
     * Switching runtime and picking a model happen in the same sheet, so anything that reacts to a
     * switch has to wait for the sheet to close or it acts on a half-made choice.
     */
    onModelPickerClosed: () -> Unit = {},
    onSelectQuestionAnswer: (String, Int, String) -> Unit,
    onSubmitQuestion: (String) -> Unit,
    onCancelQuestion: (String) -> Unit = {},
    onDismissQuestion: (String) -> Unit = {},
    autoAcceptPermissions: Boolean = false,
    onToggleAutoAccept: (Boolean) -> Unit = {},
    sendBehavior: String = "interrupt",
    onSendMessage: (String) -> Unit,
    onPermission: (String, PermissionResponse, Boolean) -> Unit,
    onAbort: () -> Unit,
    onMic: () -> Unit,
    onNewChat: () -> Unit,
    onOpenLocalSetup: () -> Unit,
    onOpenRemoteSetup: () -> Unit,
    onRefreshCatalog: () -> Unit = {},
    onOpenDrawer: () -> Unit,
    subagents: List<SubagentInfo> = emptyList(),
    onSubagentClick: (String) -> Unit = {},
    onReturnToParentSession: () -> Unit = {},
    githubRefs: List<GitHubReference> = emptyList(),
    onImageAttachment: (Bitmap) -> Unit = {},
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var showModelPicker by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val errorKind = classifyChatError(state.error)
    val runtimeNotReady = errorKind == ChatErrorKind.RUNTIME_NOT_READY && state.messages.isEmpty()
    val isAtBottom = remember { mutableStateOf(true) }
    var showActionSheet by remember { mutableStateOf<Pair<String, String>?>(null) }
    var activityGroupId by remember { mutableStateOf<String?>(null) }
    val timelineEntries = remember(state.messages) { groupConversationTimeline(state.messages) }
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    var showSlashCommands by remember { mutableStateOf(false) }
    var showSidePanel by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val attachedImages = remember { mutableStateListOf<Bitmap>() }
    DisposableEffect(Unit) {
        onDispose {
            attachedImages.forEach { if (!it.isRecycled) it.recycle() }
            attachedImages.clear()
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current

    val cameraLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.TakePicturePreview(),
        ) { bitmap ->
            if (bitmap != null) {
                attachedImages.add(bitmap)
                onImageAttachment(bitmap)
            }
        }
    val cameraPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) {
                cameraLauncher.launch(null)
            }
        }
    val galleryLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(),
        ) { uris ->
            uris.forEach { uri ->
                try {
                    val bitmap =
                        context.contentResolver.openInputStream(uri)?.use {
                            android.graphics.BitmapFactory.decodeStream(it)
                        }
                    if (bitmap != null) {
                        attachedImages.add(bitmap)
                        onImageAttachment(bitmap)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("ChatHomeScreen", "Failed to load image", e)
                }
            }
        }

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = info.totalItemsCount
            totalItems == 0 || lastVisible >= totalItems - 1
        }.collect { atBottom -> isAtBottom.value = atBottom }
    }

    LaunchedEffect(timelineEntries.size, state.permissions.size, state.pendingQuestions.size) {
        val totalItems = timelineEntries.size + state.permissions.size + state.pendingQuestions.size
        if (totalItems > 0 && isAtBottom.value) listState.animateScrollToItem(totalItems - 1)
    }

    LaunchedEffect(state.partialText) {
        if ((state.isListening || state.isSpeechProcessing) && state.partialText.isNotBlank()) {
            input = state.partialText
        }
    }

    // A subagent session is a detour, not a destination: the system back gesture returns to the
    // main agent instead of leaving the chat, matching the in-chat return banner.
    BackHandler(enabled = state.parentSession != null) { onReturnToParentSession() }

    LaunchedEffect(state.attachments) {
        if (state.attachments.isEmpty()) {
            attachedImages.clear()
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, dragAmount ->
                        val atEdge = change.position.x > size.width - 80f || showSidePanel
                        if (!atEdge) return@detectHorizontalDragGestures
                        change.consume()
                        dragOffset += dragAmount
                        if (dragOffset < -100f && change.position.x > size.width - 80f) {
                            showSidePanel = true
                            dragOffset = 0f
                        }
                        if (dragOffset > 100f && showSidePanel) {
                            showSidePanel = false
                            dragOffset = 0f
                        }
                    }
                },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
        ) {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = state.sessionTitle.ifBlank { stringResource(R.string.chat_home_title) },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.menu_description))
                    }
                },
                actions = {
                    IconButton(onClick = onNewChat) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_chat))
                    }
                },
                colors =
                    TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                        actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
            )

            state.parentSession?.let { parent ->
                SubagentSessionBanner(
                    parentTitle = parent.title,
                    onReturn = onReturnToParentSession,
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.isLoadingHistory -> LoadingState()
                    runtimeNotReady ->
                        RuntimeSetupRequiredState(
                            onOpenLocalSetup = onOpenLocalSetup,
                            onOpenRemoteSetup = onOpenRemoteSetup,
                        )
                    else -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(timelineEntries, key = { it.id }) { entry ->
                                val copyable =
                                    when (entry) {
                                        is TimelineEntry.UserMessage -> entry.message.id to entry.message.text
                                        is TimelineEntry.Body -> entry.messageId to entry.part.text
                                        is TimelineEntry.Activity -> null
                                        is TimelineEntry.Todo -> null
                                    }
                                Box(
                                    modifier =
                                        if (copyable == null) {
                                            Modifier
                                        } else {
                                            Modifier.combinedClickable(
                                                onClick = {},
                                                onLongClick = { showActionSheet = copyable },
                                            )
                                        },
                                ) {
                                    TimelineEntryRow(entry, onOpenActivity = { activityGroupId = it })
                                }
                            }
                            if (state.isRunning && timelineEntries.isNotEmpty()) {
                                item(key = "processing") {
                                    Text(
                                        text = stringResource(R.string.processing),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            items(state.permissions, key = { "permission-${it.id}" }) { permission ->
                                PermissionCard(permission, onPermission)
                            }
                            items(state.pendingQuestions, key = { "question-${it.request.id}" }) { question ->
                                QuestionCard(
                                    question = question,
                                    onAnswerSelected = onSelectQuestionAnswer,
                                    onSubmit = onSubmitQuestion,
                                    onCancel = onCancelQuestion,
                                    onDismiss = onDismissQuestion,
                                )
                            }
                            if (state.isThinking) {
                                item { StatusChip(text = stringResource(R.string.thinking), active = true) }
                            }
                            state.error?.let { error ->
                                item {
                                    ChatErrorCard(
                                        error = error,
                                        kind = errorKind ?: ChatErrorKind.GENERIC,
                                        onOpenLocalSetup = onOpenLocalSetup,
                                        onOpenRemoteSetup = onOpenRemoteSetup,
                                    )
                                }
                            }
                        }
                    }
                }

                if (!isAtBottom.value) {
                    SmallFloatingActionButton(
                        onClick = {
                            isAtBottom.value = true
                            coroutineScope.launch {
                                val totalItems = timelineEntries.size + state.permissions.size + state.pendingQuestions.size
                                if (totalItems > 0) listState.animateScrollToItem(totalItems - 1)
                            }
                        },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    ) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = stringResource(R.string.cd_scroll_to_bottom))
                    }
                }
            }

            SubagentsTrack(subagents = subagents, onSubagentClick = onSubagentClick)

            val activeTodos =
                timelineEntries
                    .filterIsInstance<TimelineEntry.Todo>()
                    .lastOrNull()
                    ?.todos
                    ?.takeIf { list -> list.any { it.status != "completed" } }
            if (activeTodos != null) {
                StickyTodoBar(todos = activeTodos)
            }

            if (!runtimeNotReady) {
                ChatComposer(
                    input = input,
                    onInputChange = {
                        input = it
                        showSlashCommands = it.startsWith("/")
                    },
                    isRunning = state.isRunning,
                    onSend = {
                        if (input.isNotBlank() || state.attachments.isNotEmpty()) {
                            onSendMessage(input)
                            input = ""
                            showSlashCommands = false
                        }
                    },
                    onAbort = onAbort,
                    onMic = onMic,
                    isListening = state.isListening,
                    isSpeechProcessing = state.isSpeechProcessing,
                    modelLabel =
                        providers
                            .firstOrNull { it.id == selectedProviderId }
                            ?.models?.get(selectedModelId)
                            ?.name
                            ?: selectedModelId
                            ?: stringResource(R.string.chat_model_short_default),
                    onModelChipClick = {
                        onRefreshCatalog()
                        showModelPicker = true
                    },
                    agents = agents,
                    selectedAgentId = selectedAgentId,
                    onSelectAgent = onSelectAgent,
                    thinkingOptions =
                        providers
                            .firstOrNull { it.id == selectedProviderId }
                            ?.models?.get(selectedModelId)
                            ?.variants?.keys?.toList() ?: emptyList(),
                    selectedVariant = state.selectedVariant,
                    onSelectVariant = onSelectVariant,
                    attachments = state.attachments,
                    onAttach = onAttach,
                    onRemoveAttachment = onRemoveAttachment,
                    autoAcceptPermissions = autoAcceptPermissions,
                    claudePermissionMode = claudePermissionMode,
                    onSelectClaudePermissionMode = onSelectClaudePermissionMode,
                    onToggleAutoAccept = onToggleAutoAccept,
                    sendBehavior = sendBehavior,
                    contextTokensUsed = state.contextTokensUsed,
                    contextLimit =
                        providers
                            .firstOrNull { it.id == selectedProviderId }
                            ?.models?.get(selectedModelId)
                            ?.limit?.context ?: 0L,
                    showSlashCommands = showSlashCommands,
                    onSlashCommandSelect = { command ->
                        input = command.name + " "
                        showSlashCommands = false
                    },
                    githubRefs = githubRefs,
                    attachedImages = attachedImages,
                    onRemoveImage = {
                        val removed = attachedImages.removeAt(it)
                        if (!removed.isRecycled) removed.recycle()
                        onRemoveAttachment(it)
                    },
                    onCameraLaunch = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED
                        ) {
                            cameraLauncher.launch(null)
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    onGalleryLaunch = {
                        galleryLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                )
            }
        }

        LiveTranscriptOverlay(
            isRecording = state.isListening,
            transcript = "",
            amplitude = 0.5f,
            onAccept = {},
            onCancel = {},
            onAcceptAndSend = {},
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        AnimatedVisibility(
            visible = showSidePanel,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Surface(
                modifier =
                    Modifier
                        .width(280.dp)
                        .fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.side_panel_files_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.side_panel_files_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showModelPicker) {
        ModelAndRuntimePickerSheet(
            sheetState = sheetState,
            runtimeTargets = runtimeTargets,
            selectedRuntimeId = selectedRuntimeId,
            onSelectRuntime = onSelectRuntime,
            providers = providers,
            selectedProviderId = selectedProviderId,
            selectedModelId = selectedModelId,
            onSelectModel = { providerId, modelId ->
                onSelectModel(providerId, modelId)
                showModelPicker = false
                onModelPickerClosed()
            },
            favoriteModelKeys = favoriteModelKeys,
            recentModelKeys = recentModelKeys,
            hiddenModelKeys = hiddenModelKeys,
            onToggleFavorite = onToggleFavorite,
            onDismiss = {
                showModelPicker = false
                onModelPickerClosed()
            },
        )
    }

    activityGroupId?.let { groupId ->
        val parts = findActivityParts(state.messages, groupId)
        if (parts.isNotEmpty()) {
            AssistantActivitySheet(parts = parts, onDismiss = { activityGroupId = null })
        }
    }

    showActionSheet?.let { (_, content) ->
        ModalBottomSheet(onDismissRequest = { showActionSheet = null }) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_copy)) },
                    onClick = {
                        clipboardManager.setText(AnnotatedString(content))
                        showActionSheet = null
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_copy_markdown)) },
                    onClick = {
                        clipboardManager.setText(AnnotatedString(content))
                        showActionSheet = null
                    },
                )
            }
        }
    }
}

/**
 * Shown while a subagent session is open. Subagent chats are opened from the parent conversation,
 * so they need an explicit way back to the main agent.
 */
@Composable
private fun SubagentSessionBanner(
    parentTitle: String,
    onReturn: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 2.dp)
                .clickable(onClick = onReturn),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.subagent_session_label),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text =
                        stringResource(
                            R.string.subagent_return_to_parent,
                            parentTitle.ifBlank { stringResource(R.string.subagent_parent_untitled) },
                        ),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StickyTodoBar(todos: List<TodoItem>) {
    var expanded by remember { mutableStateOf(false) }
    val completedCount = todos.count { it.status == "completed" }
    val totalCount = todos.size
    val currentTask = todos.firstOrNull { it.status == "in_progress" }

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .clickable { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.todo_timeline_progress, completedCount, totalCount),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { if (totalCount > 0) completedCount.toFloat() / totalCount else 0f },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
            )
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                todos.forEach { todo ->
                    val isCompleted = todo.status == "completed"
                    val isInProgress = todo.status == "in_progress"
                    Row(
                        modifier = Modifier.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector =
                                when {
                                    isCompleted -> Icons.Default.CheckCircle
                                    isInProgress -> Icons.Default.PendingActions
                                    else -> Icons.Default.RadioButtonUnchecked
                                },
                            contentDescription = null,
                            tint =
                                when {
                                    isCompleted -> MaterialTheme.colorScheme.secondary
                                    isInProgress -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = todo.content,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color =
                                if (isCompleted) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                        )
                    }
                }
            } else if (currentTask != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = currentTask.content,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun RuntimeSetupRequiredState(
    onOpenLocalSetup: () -> Unit,
    onOpenRemoteSetup: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        OpenCodeMark()
        Spacer(Modifier.height(22.dp))
        Text(
            text = stringResource(R.string.runtime_setup_required_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.runtime_setup_required_body_compact),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onOpenLocalSetup,
            modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth(),
        ) {
            Text(stringResource(R.string.setup_this_android_action))
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onOpenRemoteSetup,
            modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth(),
        ) {
            Text(stringResource(R.string.connect_pc_mac_action))
        }
    }
}

@Composable
private fun OpenCodeMark() {
    Surface(
        modifier = Modifier.size(52.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = stringResource(R.string.cd_app_logo),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(27.dp),
            )
        }
    }
}

@Composable
private fun ChatErrorCard(
    error: String,
    kind: ChatErrorKind,
    onOpenLocalSetup: () -> Unit,
    onOpenRemoteSetup: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (kind == ChatErrorKind.RUNTIME_NOT_READY) {
                Text(
                    text = stringResource(R.string.runtime_setup_required_title),
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.runtime_setup_required_body_compact),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onOpenLocalSetup) {
                        Text(stringResource(R.string.setup_short_action))
                    }
                    OutlinedButton(onClick = onOpenRemoteSetup) {
                        Text(stringResource(R.string.connect_short_action))
                    }
                }
            } else if (kind == ChatErrorKind.TRANSIENT_CONNECTION) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.chat_reconnecting),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(text = error, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ChatComposer(
    input: String,
    onInputChange: (String) -> Unit,
    isRunning: Boolean,
    onSend: () -> Unit,
    onAbort: () -> Unit,
    onMic: () -> Unit,
    isListening: Boolean,
    isSpeechProcessing: Boolean,
    modelLabel: String,
    onModelChipClick: () -> Unit,
    agents: List<OpenCodeAgent>,
    selectedAgentId: String?,
    onSelectAgent: (String) -> Unit,
    thinkingOptions: List<String>,
    selectedVariant: String?,
    onSelectVariant: (String?) -> Unit,
    attachments: List<PromptAttachment>,
    onAttach: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    autoAcceptPermissions: Boolean,
    onToggleAutoAccept: (Boolean) -> Unit,
    claudePermissionMode: ClaudePermissionMode?,
    onSelectClaudePermissionMode: (ClaudePermissionMode) -> Unit,
    sendBehavior: String,
    contextTokensUsed: Long,
    contextLimit: Long,
    showSlashCommands: Boolean,
    onSlashCommandSelect: (SlashCommand) -> Unit,
    githubRefs: List<GitHubReference>,
    attachedImages: List<Bitmap>,
    onRemoveImage: (Int) -> Unit,
    onCameraLaunch: () -> Unit,
    onGalleryLaunch: () -> Unit,
) {
    var showAttachMenu by remember { mutableStateOf(false) }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (showSlashCommands) {
            val filtered = SlashCommandRegistry.filter(input)
            if (filtered.isNotEmpty()) {
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        filtered.forEach { command ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = command.name,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = command.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = { onSlashCommandSelect(command) },
                            )
                        }
                    }
                }
            }
        }

        GitHubAutoAttachChips(
            references = githubRefs,
            onAttach = {},
            modifier = Modifier.padding(bottom = 4.dp),
        )

        if (attachedImages.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(attachedImages) { index, bitmap ->
                    Box {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.cd_attached_image),
                            modifier =
                                Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop,
                        )
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.cd_remove_image),
                            modifier =
                                Modifier
                                    .size(18.dp)
                                    .align(Alignment.TopEnd)
                                    .clickable { onRemoveImage(index) },
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                if (attachments.isNotEmpty()) {
                    AttachmentTray(attachments = attachments, onRemove = onRemoveAttachment)
                    Spacer(Modifier.height(6.dp))
                }
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                ) {
                    if (input.isEmpty()) {
                        Text(
                            text = stringResource(R.string.chat_message_placeholder),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = onInputChange,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag("chat-message-input"),
                        minLines = 1,
                        maxLines = 4,
                        textStyle =
                            TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
                            ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { onSend() }),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    )
                }
                if (isListening || isSpeechProcessing) {
                    Spacer(Modifier.height(8.dp))
                    if (isListening) {
                        ListeningStatus(modifier = Modifier.fillMaxWidth())
                    } else {
                        ProcessingStatus(modifier = Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box {
                        AttachButton(onClick = { showAttachMenu = true })
                        DropdownMenu(
                            expanded = showAttachMenu,
                            onDismissRequest = { showAttachMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach_file)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.InsertDriveFile,
                                        contentDescription = stringResource(R.string.cd_attach_file),
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                onClick = {
                                    showAttachMenu = false
                                    onAttach()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach_camera)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.CameraAlt,
                                        contentDescription = stringResource(R.string.cd_camera),
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                onClick = {
                                    showAttachMenu = false
                                    onCameraLaunch()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach_gallery)) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.PhotoLibrary,
                                        contentDescription = stringResource(R.string.cd_gallery),
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                onClick = {
                                    showAttachMenu = false
                                    onGalleryLaunch()
                                },
                            )
                        }
                    }
                    CompactContextButton(
                        label = modelLabel,
                        maxWidth = 168.dp,
                        onClick = onModelChipClick,
                    )
                    Spacer(Modifier.weight(1f))
                    if (isListening) {
                        VolumeMeter(amplitude = 0.5f, idle = true)
                    }
                    val micContainerColor =
                        if (isListening) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    val micContentColor =
                        if (isListening) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    Surface(
                        modifier =
                            Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(19.dp))
                                .testTag("chat-mic-button"),
                        shape = RoundedCornerShape(19.dp),
                        color = micContainerColor,
                    ) {
                        IconButton(onClick = onMic, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = stringResource(R.string.voice),
                                modifier = Modifier.size(21.dp),
                                tint = micContentColor,
                            )
                        }
                    }
                    if (isRunning) {
                        if (sendBehavior == "queue") {
                            Surface(
                                shape = RoundedCornerShape(100.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            ) {
                                Text(
                                    text = stringResource(R.string.status_queued),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        FilledIconButton(onClick = onAbort, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.stop_run))
                        }
                    } else {
                        FilledIconButton(
                            onClick = onSend,
                            enabled = input.isNotBlank() || attachments.isNotEmpty(),
                            modifier = Modifier.size(38.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.send_description),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (claudePermissionMode != null) {
                // Claude Code has a single agent and decides permissions per session, so the mode
                // chip selects the permission mode. Auto-accept is an OpenCode concept and has no
                // meaning here, so it is left out rather than shown as a dead toggle.
                PermissionModeChip(
                    selected = claudePermissionMode,
                    onSelect = onSelectClaudePermissionMode,
                )
            } else {
                ModeChip(
                    agents = agents,
                    selectedAgentId = selectedAgentId,
                    onSelect = onSelectAgent,
                )
                AutoAcceptChip(
                    enabled = autoAcceptPermissions,
                    onToggle = onToggleAutoAccept,
                )
            }
            if (thinkingOptions.isNotEmpty()) {
                ThinkingChip(
                    options = thinkingOptions,
                    selected = selectedVariant,
                    onSelect = onSelectVariant,
                )
            }
            if (contextLimit > 0L) {
                CompactContextMeter(
                    tokensUsed = contextTokensUsed,
                    contextLimit = contextLimit,
                )
            }
        }
    }
}

@Composable
private fun AttachButton(onClick: () -> Unit) {
    Surface(
        modifier =
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(17.dp))
                .testTag("chat-attach-button"),
        shape = RoundedCornerShape(17.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
            Icon(
                Icons.Default.Add,
                contentDescription = stringResource(R.string.chat_attach),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThinkingChip(
    options: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(100.dp))
                    .clickable(onClick = { expanded = true }),
            shape = RoundedCornerShape(100.dp),
            color =
                if (selected != null) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            contentColor =
                if (selected != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Default.Psychology, contentDescription = stringResource(R.string.cd_thinking), modifier = Modifier.size(14.dp))
                Text(
                    selected ?: stringResource(R.string.chat_thinking_default),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_thinking_default)) },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.replaceFirstChar { it.uppercase() }) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AttachmentTray(
    attachments: List<PromptAttachment>,
    onRemove: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        attachments.forEachIndexed { index, attachment ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = stringResource(R.string.cd_attachment),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        attachment.filename,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp),
                    )
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.chat_remove_attachment),
                        modifier =
                            Modifier
                                .size(14.dp)
                                .clickable { onRemove(index) },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeChip(
    agents: List<OpenCodeAgent>,
    selectedAgentId: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // The selection is remembered across runtimes, so an id this runtime does not offer would
    // otherwise label the chip with another agent's name. An empty list is not a licence to trust
    // it either: that is exactly the state a stopped runtime is in.
    val label = selectedAgentId?.takeIf { id -> agents.any { it.name == id } } ?: "build"
    Box {
        Surface(
            modifier =
                Modifier
                    .widthIn(max = 92.dp)
                    .clickable(onClick = { expanded = true }),
            shape = RoundedCornerShape(100.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    Icons.Outlined.Shield,
                    contentDescription = stringResource(R.string.cd_agent_mode),
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = stringResource(R.string.cd_expand_dropdown),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            agents.forEach { agent ->
                DropdownMenuItem(
                    text = { Text(agent.name) },
                    onClick = {
                        onSelect(agent.name)
                        expanded = false
                    },
                    modifier = Modifier.testTag("chat-mode-${agent.name}"),
                )
            }
        }
    }
}

@Composable
private fun PermissionModeChip(
    selected: ClaudePermissionMode,
    onSelect: (ClaudePermissionMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(100.dp))
                    .clickable(onClick = { expanded = true })
                    .testTag("chat-permission-mode"),
            shape = RoundedCornerShape(100.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    Icons.Outlined.VerifiedUser,
                    contentDescription = stringResource(R.string.claude_permission_mode_label),
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    stringResource(selected.labelRes),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = stringResource(R.string.cd_expand_dropdown),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ClaudePermissionMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(stringResource(mode.labelRes)) },
                    onClick = {
                        onSelect(mode)
                        expanded = false
                    },
                    modifier = Modifier.testTag("chat-permission-mode-${mode.cliValue}"),
                )
            }
        }
    }
}

@Composable
private fun AutoAcceptChip(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val containerColor =
        if (enabled) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    val contentColor =
        if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    Surface(
        modifier =
            Modifier
                .clip(RoundedCornerShape(100.dp))
                .clickable(onClick = { onToggle(!enabled) })
                .testTag("chat-auto-accept"),
        shape = RoundedCornerShape(100.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                if (enabled) Icons.Filled.VerifiedUser else Icons.Outlined.VerifiedUser,
                contentDescription = stringResource(R.string.chat_mode_auto_accept),
                modifier = Modifier.size(14.dp),
            )
            Text(
                stringResource(R.string.chat_auto_accept_short),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ListeningStatus(modifier: Modifier = Modifier) {
    Row(
        modifier =
            modifier
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.voice_state_listening),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ProcessingStatus(modifier: Modifier = Modifier) {
    Row(
        modifier =
            modifier
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("chat-voice-processing"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )
        Text(
            text = stringResource(R.string.processing),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CompactContextButton(
    label: String,
    maxWidth: androidx.compose.ui.unit.Dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .widthIn(max = maxWidth)
                .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(100.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 1f else 0.55f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = stringResource(R.string.cd_expand_dropdown),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun CompactContextMeter(
    tokensUsed: Long,
    contextLimit: Long,
) {
    // Session token totals can exceed the model window; the meter represents window usage.
    val displayedTokens = tokensUsed.coerceIn(0L, contextLimit)
    val fraction = displayedTokens.toFloat() / contextLimit.toFloat()
    val barColor =
        when {
            fraction >= 0.9f -> MaterialTheme.colorScheme.error
            fraction >= 0.7f -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(horizontal = 2.dp),
    ) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier =
                Modifier
                    .width(34.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
            color = barColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Text(
            text = "${formatTokenCount(displayedTokens)}/${formatTokenCount(contextLimit)}",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatTokenCount(tokens: Long): String =
    when {
        tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000.0)
        tokens >= 1_000 -> "%.0fk".format(tokens / 1_000.0)
        else -> tokens.toString()
    }

@Preview(showBackground = true)
@Composable
private fun ChatHomeScreenEmptyPreview() {
    AndCodeTheme {
        ChatHomeScreen(
            state = ChatUiState(backendName = "OpenCode · 1.0.0", isConnected = true),
            providers = emptyList(),
            agents = emptyList(),
            selectedProviderId = null,
            selectedModelId = null,
            selectedAgentId = null,
            runtimeTargets = emptyList(),
            selectedRuntimeId = null,
            onSelectRuntime = {},
            onSelectModel = { _, _ -> },
            onSelectAgent = {},
            onSelectQuestionAnswer = { _, _, _ -> },
            onSubmitQuestion = {},
            onSendMessage = {},
            onPermission = { _, _, _ -> },
            onAbort = {},
            onMic = {},
            onNewChat = {},
            onOpenLocalSetup = {},
            onOpenRemoteSetup = {},
            onOpenDrawer = {},
        )
    }
}
