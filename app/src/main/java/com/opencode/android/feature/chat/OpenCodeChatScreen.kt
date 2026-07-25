package com.opencode.android.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.opencode.android.R
import com.opencode.android.core.api.OpenCodeAgent
import com.opencode.android.core.api.OpenCodeModel
import com.opencode.android.core.api.OpenCodeProvider
import com.opencode.android.core.api.PermissionRequest
import com.opencode.android.runtime.PermissionResponse
import com.opencode.android.runtime.RuntimeTarget
import com.opencode.android.runtime.WorkspaceRef
import com.opencode.android.ui.components.SectionCard
import com.opencode.android.ui.components.StatusChip
import com.opencode.android.ui.theme.OpenCodeSuccess
import com.opencode.android.ui.theme.OpenCodeWarning

@Composable
fun OpenCodeChatScreen(
    state: ChatUiState,
    providers: List<OpenCodeProvider>,
    agents: List<OpenCodeAgent>,
    workspaces: List<WorkspaceRef>,
    selectedProviderId: String?,
    selectedModelId: String?,
    selectedAgentId: String?,
    otherRuntimes: List<RuntimeTarget> = emptyList(),
    onSelectModel: (String, String) -> Unit,
    onSelectAgent: (String) -> Unit,
    onSelectWorkspace: (String?) -> Unit,
    onSendMessage: (String) -> Unit,
    onPermission: (String, PermissionResponse, Boolean) -> Unit,
    onAbort: () -> Unit,
    onMic: () -> Unit,
    onHandoff: (String) -> Unit = {}
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val tailSignature = state.messages.lastOrNull()?.contentSignature()

    LaunchedEffect(state.messages.size, state.permissions.size, tailSignature) {
        val totalItems = state.messages.size + state.permissions.size
        if (totalItems > 0) listState.animateScrollToItem(totalItems - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ChatHeader(
            state = state,
            providers = providers,
            agents = agents,
            workspaces = workspaces,
            selectedProviderId = selectedProviderId,
            selectedModelId = selectedModelId,
            selectedAgentId = selectedAgentId,
            otherRuntimes = otherRuntimes,
            onSelectModel = onSelectModel,
            onSelectAgent = onSelectAgent,
            onSelectWorkspace = onSelectWorkspace,
            onHandoff = onHandoff
        )

        if (state.backendName.isBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                SectionCard {
                    Text(stringResource(R.string.no_connection_chat))
                }
            }
            return
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.messages.isEmpty() && !state.isLoadingHistory) {
                item {
                    SectionCard {
                        Text(
                            text = stringResource(R.string.chat_intro_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.chat_intro_body),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(state.messages, key = { it.id }) { message ->
                if (message.isUser) {
                    MessageBubble(message)
                } else {
                    AssistantTimeline(message)
                }
            }

            items(state.permissions, key = { "permission-${it.id}" }) { permission ->
                PermissionCard(permission, onPermission)
            }

            if (state.isThinking) {
                item {
                    StatusChip(text = stringResource(R.string.thinking), active = true)
                }
            }
            state.error?.let { error ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                        )
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.padding(14.dp),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onMic) {
                Icon(Icons.Default.Mic, contentDescription = stringResource(R.string.voice))
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.message_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (input.isNotBlank()) {
                            onSendMessage(input)
                            input = ""
                        }
                    }
                ),
                maxLines = 5,
                shape = RoundedCornerShape(18.dp)
            )
            if (state.isRunning) {
                IconButton(onClick = onAbort) {
                    Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.stop_run))
                }
            } else {
                IconButton(
                    onClick = {
                        if (input.isNotBlank()) {
                            onSendMessage(input)
                            input = ""
                        }
                    },
                    enabled = input.isNotBlank()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send_description))
                }
            }
        }
    }
}

@Composable
private fun ChatHeader(
    state: ChatUiState,
    providers: List<OpenCodeProvider>,
    agents: List<OpenCodeAgent>,
    workspaces: List<WorkspaceRef>,
    selectedProviderId: String?,
    selectedModelId: String?,
    selectedAgentId: String?,
    otherRuntimes: List<RuntimeTarget>,
    onSelectModel: (String, String) -> Unit,
    onSelectAgent: (String) -> Unit,
    onSelectWorkspace: (String?) -> Unit,
    onHandoff: (String) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showHandoffDialog by remember { mutableStateOf(false) }
    val canHandoff = state.sessionId != null && otherRuntimes.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.sessionTitle.ifBlank { stringResource(R.string.new_chat) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    text = state.backendName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            StatusChip(
                text = if (state.isConnected) stringResource(R.string.active) else stringResource(R.string.not_set),
                active = state.isConnected
            )
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.chat_options_description))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.continue_on_other_runtime)) },
                        enabled = canHandoff,
                        onClick = {
                            menuExpanded = false
                            showHandoffDialog = true
                        }
                    )
                }
            }
        }
        if (showHandoffDialog) {
            HandoffDialog(
                runtimes = otherRuntimes,
                onDismiss = { showHandoffDialog = false },
                onSelect = { runtimeId ->
                    showHandoffDialog = false
                    onHandoff(runtimeId)
                }
            )
        }
        WorkspaceSelector(
            workspaces = workspaces,
            selectedPath = state.selectedWorkspacePath,
            enabled = state.sessionId == null,
            onSelect = onSelectWorkspace
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModelSelector(
                providers = providers,
                providerId = selectedProviderId,
                modelId = selectedModelId,
                onSelect = onSelectModel,
                modifier = Modifier.weight(1f)
            )
            AgentSelector(
                agents = agents,
                selectedAgentId = selectedAgentId,
                onSelect = onSelectAgent,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun HandoffDialog(
    runtimes: List<RuntimeTarget>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.handoff_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                runtimes.forEach { runtime ->
                    Text(
                        text = runtime.displayName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(runtime.id) }
                            .padding(vertical = 12.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun WorkspaceSelector(
    workspaces: List<WorkspaceRef>,
    selectedPath: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = workspaces.firstOrNull { it.path == selectedPath }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled && workspaces.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Folder, contentDescription = null)
            Spacer(Modifier.padding(horizontal = 4.dp))
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(stringResource(R.string.workspace_folder_label), style = MaterialTheme.typography.labelSmall)
                Text(
                    selected?.name ?: selectedPath ?: stringResource(R.string.default_folder),
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(R.string.default_folder))
                        Text(
                            stringResource(R.string.default_folder_description),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                onClick = {
                    onSelect(null)
                    expanded = false
                }
            )
            workspaces.forEach { workspace ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(workspace.name, maxLines = 1)
                            Text(
                                workspace.path,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    },
                    onClick = {
                        onSelect(workspace.path)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ModelSelector(
    providers: List<OpenCodeProvider>,
    providerId: String?,
    modelId: String?,
    onSelect: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val provider = providers.firstOrNull { it.id == providerId }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(stringResource(R.string.model), style = MaterialTheme.typography.labelSmall)
                Text(modelId ?: "Default", maxLines = 1, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            providers.forEach { itemProvider ->
                val models = itemProvider.models.values
                    .filter { it.status == null || it.status == "active" }
                    .sortedBy { it.name.lowercase() }
                    .take(60)
                if (models.isNotEmpty()) {
                    Text(
                        text = itemProvider.name,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    models.forEach { model: OpenCodeModel ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(model.name, maxLines = 1)
                                    Text(
                                        model.id,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            },
                            onClick = {
                                onSelect(itemProvider.id, model.id)
                                expanded = false
                            }
                        )
                    }
                }
            }
            if (provider == null && providers.isEmpty()) {
                DropdownMenuItem(text = { Text("No connected providers") }, onClick = { expanded = false })
            }
        }
    }
}

@Composable
private fun AgentSelector(
    agents: List<OpenCodeAgent>,
    selectedAgentId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(stringResource(R.string.agent), style = MaterialTheme.typography.labelSmall)
                Text(selectedAgentId ?: "build", maxLines = 1, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            agents.forEach { agent ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(agent.name)
                            agent.description?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                        }
                    },
                    onClick = {
                        onSelect(agent.name)
                        expanded = false
                    }
                )
            }
        }
    }
}

// Not private: reused by ChatHomeScreen.kt (same package) for the redesigned chat screen.
@Composable
fun MessageBubble(message: ChatMessage) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 340.dp),
            shape = RoundedCornerShape(20.dp, 20.dp, 5.dp, 20.dp),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(text = message.text)
            }
        }
    }
}

// Not private: reused by ChatHomeScreen.kt (same package) for the redesigned chat screen.
@Composable
fun AssistantTimeline(message: ChatMessage) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        message.parts.forEach { part ->
            key(part.id) {
                when (part) {
                    is ChatPart.Text -> MarkdownText(part.text)
                    is ChatPart.Reasoning -> ReasoningCard(part)
                    is ChatPart.Tool -> ToolCard(part)
                    is ChatPart.Patch -> PatchCard(part)
                }
            }
        }
        if (message.isStreaming) {
            Text(
                text = stringResource(R.string.processing),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MarkdownText(text: String) {
    val blocks = remember(text) { MarkdownLite.parse(text) }
    val codeInlineBackground = MaterialTheme.colorScheme.surfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> Text(
                    text = renderInline(block.inlines, codeInlineBackground),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.SemiBold
                )
                is MarkdownBlock.Paragraph -> Text(text = renderInline(block.inlines, codeInlineBackground))
                is MarkdownBlock.CodeBlock -> Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Text(
                        text = block.code,
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                is MarkdownBlock.BulletList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEach { item ->
                        Row {
                            Text("•  ")
                            Text(renderInline(item, codeInlineBackground))
                        }
                    }
                }
            }
        }
    }
}

private fun renderInline(inlines: List<MarkdownInline>, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    inlines.forEach { inline ->
        when (inline) {
            is MarkdownInline.Plain -> append(inline.text)
            is MarkdownInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline.text) }
            is MarkdownInline.Code -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
            ) { append(inline.text) }
        }
    }
}

@Composable
private fun ReasoningCard(part: ChatPart.Reasoning) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(
                    text = stringResource(R.string.reasoning_card_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded && part.text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = part.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun ToolCard(part: ChatPart.Tool) {
    var expanded by remember { mutableStateOf(part.status == ToolStatus.RUNNING) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Build, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(part.name, fontWeight = FontWeight.Medium, maxLines = 1)
                    part.title?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
                ToolStatusChip(part.status)
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }
            if (expanded) {
                part.input?.let { input ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.tool_input_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = input,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                part.output?.let { output ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.tool_output_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = output,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (part.outputTruncated) {
                        Text(
                            text = stringResource(R.string.tool_output_truncated),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                part.error?.let { error ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolStatusChip(status: ToolStatus) {
    val (label, color) = when (status) {
        ToolStatus.PENDING -> stringResource(R.string.tool_status_pending) to MaterialTheme.colorScheme.onSurfaceVariant
        ToolStatus.RUNNING -> stringResource(R.string.tool_status_running) to MaterialTheme.colorScheme.primary
        ToolStatus.COMPLETED -> stringResource(R.string.tool_status_completed) to OpenCodeSuccess
        ToolStatus.ERROR -> stringResource(R.string.tool_status_error) to MaterialTheme.colorScheme.error
        ToolStatus.UNKNOWN -> stringResource(R.string.tool_status_pending) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = color.copy(alpha = 0.14f),
        contentColor = color,
        shape = RoundedCornerShape(100.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun PatchCard(part: ChatPart.Patch) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(stringResource(R.string.file_changes_title), fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(6.dp))
            if (part.files.isEmpty()) {
                Text(
                    text = stringResource(R.string.file_changes_generic),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                part.files.forEach { file ->
                    Text(file, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// Not private: reused by ChatHomeScreen.kt (same package) for the redesigned chat screen.
@Composable
fun PermissionCard(
    permission: PermissionRequest,
    onPermission: (String, PermissionResponse, Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = OpenCodeWarning.copy(alpha = 0.10f)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, contentDescription = null, tint = OpenCodeWarning)
                Spacer(Modifier.padding(horizontal = 5.dp))
                Text(stringResource(R.string.permission_required), fontWeight = FontWeight.SemiBold)
            }
            Text(
                text = permission.permission,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
            permission.patterns.forEach { pattern ->
                Text(
                    text = pattern,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onPermission(permission.id, PermissionResponse.REJECT, false) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.reject))
                }
                FilledTonalButton(
                    onClick = { onPermission(permission.id, PermissionResponse.ONCE, false) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.allow_once))
                }
                Button(
                    onClick = { onPermission(permission.id, PermissionResponse.ALWAYS, true) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.always_allow))
                }
            }
        }
    }
}
