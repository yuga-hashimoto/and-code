package com.opencode.android.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.opencode.android.R
import com.opencode.android.core.api.OpenCodeAgent
import com.opencode.android.core.api.OpenCodeProvider
import com.opencode.android.runtime.PermissionResponse
import com.opencode.android.runtime.RuntimeTarget
import com.opencode.android.runtime.WorkspaceRef
import com.opencode.android.ui.components.StatusChip
import com.opencode.android.ui.theme.OpenCodeAndroidTheme

/** Chat-first home screen. Runtime selection remains inside the model picker. */
@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHomeScreen(
    state: ChatUiState,
    providers: List<OpenCodeProvider>,
    agents: List<OpenCodeAgent>,
    workspaces: List<WorkspaceRef>,
    selectedProviderId: String?,
    selectedModelId: String?,
    selectedAgentId: String?,
    runtimeTargets: List<RuntimeTarget>,
    selectedRuntimeId: String?,
    onSelectRuntime: (String) -> Unit,
    onSelectModel: (String, String) -> Unit,
    onSelectAgent: (String) -> Unit,
    onSelectWorkspace: (String?) -> Unit,
    onSendMessage: (String) -> Unit,
    onPermission: (String, PermissionResponse, Boolean) -> Unit,
    onAbort: () -> Unit,
    onMic: () -> Unit,
    onNewChat: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenLocalSetup: () -> Unit,
    onOpenRemoteSetup: () -> Unit,
    onOpenDrawer: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var showModelPicker by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val errorKind = classifyChatError(state.error)
    val runtimeNotReady = errorKind == ChatErrorKind.RUNTIME_NOT_READY && state.messages.isEmpty()

    // Follow the tail while the assistant streams: the message count stays put as a single
    // message grows, so the trailing message's own content has to drive the scroll too.
    val tailSignature = state.messages.lastOrNull()?.contentSignature()

    LaunchedEffect(state.messages.size, state.permissions.size, tailSignature) {
        val totalItems = state.messages.size + state.permissions.size
        if (totalItems > 0) listState.animateScrollToItem(totalItems - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = state.sessionTitle.ifBlank { stringResource(R.string.chat_home_title) },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                actionIconContentColor = MaterialTheme.colorScheme.onBackground
            )
        )

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.isLoadingHistory -> LoadingState()
                runtimeNotReady -> RuntimeSetupRequiredState(
                    onOpenLocalSetup = onOpenLocalSetup,
                    onOpenRemoteSetup = onOpenRemoteSetup
                )
                state.messages.isEmpty() && state.permissions.isEmpty() && state.error == null -> {
                    EmptyChatState(onSuggestionClick = { text -> input = text })
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(state.messages, key = { it.id }) { message ->
                            if (message.isUser) MessageBubble(message) else AssistantTimeline(message)
                        }
                        items(state.permissions, key = { "permission-${it.id}" }) { permission ->
                            PermissionCard(permission, onPermission)
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
                                    onOpenRemoteSetup = onOpenRemoteSetup
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!runtimeNotReady) {
            ChatComposer(
                input = input,
                onInputChange = { input = it },
                isRunning = state.isRunning,
                onSend = {
                    if (input.isNotBlank()) {
                        onSendMessage(input)
                        input = ""
                    }
                },
                onAbort = onAbort,
                onMic = onMic,
                modelLabel = selectedModelId ?: stringResource(R.string.chat_model_short_default),
                onModelChipClick = { showModelPicker = true },
                agents = agents,
                selectedAgentId = selectedAgentId,
                onSelectAgent = onSelectAgent,
                workspaces = workspaces,
                selectedWorkspacePath = state.selectedWorkspacePath,
                workspaceSelectable = state.sessionId == null,
                onSelectWorkspace = onSelectWorkspace
            )
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
            onSelectModel = onSelectModel,
            onDismiss = { showModelPicker = false }
        )
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
    onOpenRemoteSetup: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        OpenCodeMark()
        Spacer(Modifier.height(22.dp))
        Text(
            text = stringResource(R.string.runtime_setup_required_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.runtime_setup_required_body_compact),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onOpenLocalSetup,
            modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth()
        ) {
            Text(stringResource(R.string.setup_this_android_action))
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onOpenRemoteSetup,
            modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth()
        ) {
            Text(stringResource(R.string.connect_pc_mac_action))
        }
    }
}

@Composable
private fun ChatErrorCard(
    error: String,
    kind: ChatErrorKind,
    onOpenLocalSetup: () -> Unit,
    onOpenRemoteSetup: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (kind == ChatErrorKind.RUNTIME_NOT_READY) {
                Text(
                    text = stringResource(R.string.runtime_setup_required_title),
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.runtime_setup_required_body_compact),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
            } else {
                Text(text = error, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun EmptyChatState(onSuggestionClick: (String) -> Unit) {
    val prompts = listOf(
        stringResource(R.string.suggestion_implement_title),
        stringResource(R.string.suggestion_debug_title),
        stringResource(R.string.suggestion_organize_title)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        OpenCodeMark()
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.chat_build_headline),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.chat_build_subtitle_compact),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(22.dp))
        Column(
            modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            prompts.forEach { prompt ->
                PromptSuggestion(text = prompt, onClick = { onSuggestionClick(prompt) })
            }
        }
    }
}

@Composable
private fun OpenCodeMark() {
    Surface(
        modifier = Modifier.size(52.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(27.dp)
            )
        }
    }
}

@Composable
private fun PromptSuggestion(text: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.75f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Code,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp)
            )
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
    modelLabel: String,
    onModelChipClick: () -> Unit,
    agents: List<OpenCodeAgent>,
    selectedAgentId: String?,
    onSelectAgent: (String) -> Unit,
    workspaces: List<WorkspaceRef>,
    selectedWorkspacePath: String?,
    workspaceSelectable: Boolean,
    onSelectWorkspace: (String?) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            ) {
                if (input.isEmpty()) {
                    Text(
                        text = stringResource(R.string.chat_message_placeholder),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BasicTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 4,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                CompactContextButton(
                    label = modelLabel,
                    maxWidth = 84.dp,
                    onClick = onModelChipClick
                )
                CompactAgentButton(
                    agents = agents,
                    selectedAgentId = selectedAgentId,
                    onSelect = onSelectAgent
                )
                CompactWorkspaceButton(
                    workspaces = workspaces,
                    selectedPath = selectedWorkspacePath,
                    enabled = workspaceSelectable,
                    onSelect = onSelectWorkspace
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onMic, modifier = Modifier.size(38.dp)) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = stringResource(R.string.voice),
                        modifier = Modifier.size(21.dp)
                    )
                }
                if (isRunning) {
                    FilledIconButton(onClick = onAbort, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.stop_run))
                    }
                } else {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = input.isNotBlank(),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.send_description),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactContextButton(
    label: String,
    maxWidth: androidx.compose.ui.unit.Dp,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(100.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 1f else 0.55f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun CompactAgentButton(
    agents: List<OpenCodeAgent>,
    selectedAgentId: String?,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        CompactContextButton(
            label = selectedAgentId ?: "build",
            maxWidth = 66.dp,
            onClick = { expanded = true }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            agents.forEach { agent ->
                DropdownMenuItem(
                    text = { Text(agent.name) },
                    onClick = {
                        onSelect(agent.name)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun CompactWorkspaceButton(
    workspaces: List<WorkspaceRef>,
    selectedPath: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = workspaces.firstOrNull { it.path == selectedPath }
    Box {
        CompactContextButton(
            label = selected?.name ?: stringResource(R.string.chat_workspace_short_default),
            maxWidth = 78.dp,
            enabled = enabled,
            onClick = { expanded = true }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.default_folder)) },
                onClick = {
                    onSelect(null)
                    expanded = false
                }
            )
            workspaces.forEach { workspace ->
                DropdownMenuItem(
                    text = { Text(workspace.name) },
                    onClick = {
                        onSelect(workspace.path)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ChatHomeScreenEmptyPreview() {
    OpenCodeAndroidTheme {
        ChatHomeScreen(
            state = ChatUiState(backendName = "OpenCode · 1.0.0", isConnected = true),
            providers = emptyList(),
            agents = emptyList(),
            workspaces = emptyList(),
            selectedProviderId = null,
            selectedModelId = null,
            selectedAgentId = null,
            runtimeTargets = emptyList(),
            selectedRuntimeId = null,
            onSelectRuntime = {},
            onSelectModel = { _, _ -> },
            onSelectAgent = {},
            onSelectWorkspace = {},
            onSendMessage = {},
            onPermission = { _, _, _ -> },
            onAbort = {},
            onMic = {},
            onNewChat = {},
            onOpenHistory = {},
            onOpenLocalSetup = {},
            onOpenRemoteSetup = {},
            onOpenDrawer = {}
        )
    }
}
