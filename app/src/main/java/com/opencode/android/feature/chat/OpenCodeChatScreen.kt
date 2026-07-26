package com.opencode.android.feature.chat

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pending
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onHandoff: (String) -> Unit = {},
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val isAtBottom = remember { mutableStateOf(true) }
    var activityGroupId by remember { mutableStateOf<String?>(null) }
    val timelineEntries = remember(state.messages) { groupConversationTimeline(state.messages) }

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = info.totalItemsCount
            totalItems == 0 || lastVisible >= totalItems - 1
        }.collect { atBottom -> isAtBottom.value = atBottom }
    }

    LaunchedEffect(timelineEntries.size, state.permissions.size) {
        val totalItems = timelineEntries.size + state.permissions.size
        if (totalItems > 0 && isAtBottom.value) listState.animateScrollToItem(totalItems - 1)
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
            onHandoff = onHandoff,
        )

        if (state.backendName.isBlank()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                contentAlignment = Alignment.Center,
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.messages.isEmpty() && !state.isLoadingHistory) {
                item {
                    SectionCard {
                        Text(
                            text = stringResource(R.string.chat_intro_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.chat_intro_body),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(timelineEntries, key = { it.id }) { entry ->
                TimelineEntryRow(entry, onOpenActivity = { activityGroupId = it })
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
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                            ),
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.padding(14.dp),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                keyboardActions =
                    KeyboardActions(
                        onSend = {
                            if (input.isNotBlank()) {
                                onSendMessage(input)
                                input = ""
                            }
                        },
                    ),
                maxLines = 5,
                shape = RoundedCornerShape(18.dp),
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
                    enabled = input.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send_description))
                }
            }
        }
    }

    activityGroupId?.let { groupId ->
        val parts = findActivityParts(state.messages, groupId)
        if (parts.isNotEmpty()) {
            AssistantActivitySheet(parts = parts, onDismiss = { activityGroupId = null })
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
    onHandoff: (String) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showHandoffDialog by remember { mutableStateOf(false) }
    val canHandoff = state.sessionId != null && otherRuntimes.isNotEmpty()
    val context = LocalContext.current

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.sessionTitle.ifBlank { stringResource(R.string.new_chat) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    text = state.backendName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            StatusChip(
                text = if (state.isConnected) stringResource(R.string.active) else stringResource(R.string.not_set),
                active = state.isConnected,
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
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.copy_resume_command)) },
                        enabled = state.sessionId != null,
                        onClick = {
                            menuExpanded = false
                            state.sessionId?.let { ResumeCommandHelper.copyToClipboard(context, it) }
                        },
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
                },
            )
        }
        WorkspaceSelector(
            workspaces = workspaces,
            selectedPath = state.selectedWorkspacePath,
            enabled = state.sessionId == null,
            onSelect = onSelectWorkspace,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModelSelector(
                providers = providers,
                providerId = selectedProviderId,
                modelId = selectedModelId,
                onSelect = onSelectModel,
                modifier = Modifier.weight(1f),
            )
            AgentSelector(
                agents = agents,
                selectedAgentId = selectedAgentId,
                onSelect = onSelectAgent,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun HandoffDialog(
    runtimes: List<RuntimeTarget>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.handoff_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                runtimes.forEach { runtime ->
                    Text(
                        text = runtime.displayName,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(runtime.id) }
                                .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun WorkspaceSelector(
    workspaces: List<WorkspaceRef>,
    selectedPath: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = workspaces.firstOrNull { it.path == selectedPath }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled && workspaces.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Folder, contentDescription = stringResource(R.string.cd_folder))
            Spacer(Modifier.padding(horizontal = 4.dp))
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(stringResource(R.string.workspace_folder_label), style = MaterialTheme.typography.labelSmall)
                Text(
                    selected?.name ?: selectedPath ?: stringResource(R.string.default_folder),
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.cd_expand_dropdown))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(R.string.default_folder))
                        Text(
                            stringResource(R.string.default_folder_description),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
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
                                maxLines = 1,
                            )
                        }
                    },
                    onClick = {
                        onSelect(workspace.path)
                        expanded = false
                    },
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
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val provider = providers.firstOrNull { it.id == providerId }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(stringResource(R.string.model), style = MaterialTheme.typography.labelSmall)
                Text(modelId ?: stringResource(R.string.model_default), maxLines = 1, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.cd_expand_dropdown))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            providers.forEach { itemProvider ->
                val models =
                    itemProvider.models.values
                        .filter { it.status == null || it.status == "active" }
                        .sortedBy { it.name.lowercase() }
                        .take(60)
                if (models.isNotEmpty()) {
                    Text(
                        text = itemProvider.name,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
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
                                        maxLines = 1,
                                    )
                                }
                            },
                            onClick = {
                                onSelect(itemProvider.id, model.id)
                                expanded = false
                            },
                        )
                    }
                }
            }
            if (provider == null && providers.isEmpty()) {
                DropdownMenuItem(text = { Text(stringResource(R.string.no_connected_providers)) }, onClick = { expanded = false })
            }
        }
    }
}

@Composable
private fun AgentSelector(
    agents: List<OpenCodeAgent>,
    selectedAgentId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(stringResource(R.string.agent), style = MaterialTheme.typography.labelSmall)
                Text(selectedAgentId ?: "build", maxLines = 1, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.cd_expand_dropdown))
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
                                    maxLines = 2,
                                )
                            }
                        }
                    },
                    onClick = {
                        onSelect(agent.name)
                        expanded = false
                    },
                )
            }
        }
    }
}

private val TOOL_CALL_ECHO_REGEX =
    Regex("""Called the [A-Za-z][A-Za-z ]*? tool with the following input: \{(?:[^{}]|\{[^{}]*\})*\}""")

private fun String.hideToolCallEcho(): String = TOOL_CALL_ECHO_REGEX.replace(this, "").replace(Regex("[ \t]+\n"), "\n").trim()

// Not private: reused by ChatHomeScreen.kt (same package) for the redesigned chat screen.
@Composable
fun MessageBubble(message: ChatMessage) {
    val displayText = remember(message.text) { message.text.hideToolCallEcho() }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 340.dp),
            shape = RoundedCornerShape(20.dp, 20.dp, 5.dp, 20.dp),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                message.imagePreviews.forEach { preview ->
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = stringResource(R.string.cd_image_preview),
                        modifier =
                            Modifier
                                .widthIn(max = 280.dp)
                                .heightIn(max = 220.dp)
                                .padding(bottom = 8.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
                message.attachments.forEach { attachment ->
                    if (attachment.mime.startsWith("image/")) return@forEach
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(bottom = 8.dp),
                    ) {
                        Icon(Icons.Default.Description, contentDescription = stringResource(R.string.cd_attachment))
                        Text(
                            text = attachment.filename,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
                if (displayText.isNotBlank()) {
                    Text(text = displayText)
                }
            }
        }
    }
}

/**
 * Renders one row produced by [groupConversationTimeline].
 *
 * Not private: reused by ChatHomeScreen.kt (same package) for the redesigned chat screen.
 */
@Composable
fun TimelineEntryRow(
    entry: TimelineEntry,
    onOpenActivity: (String) -> Unit = {},
) {
    when (entry) {
        is TimelineEntry.UserMessage -> MessageBubble(entry.message)
        is TimelineEntry.Body -> MarkdownText(entry.part.text)
        is TimelineEntry.Activity ->
            AssistantActivityRow(
                parts = entry.parts,
                onClick = { onOpenActivity(entry.id) },
            )
    }
}

@Composable
private fun InlineText(
    inlines: List<MarkdownInline>,
    style: TextStyle,
    linkColor: Color,
    codeBackground: Color,
    onFilePathClick: (String) -> Unit,
) {
    val context = LocalContext.current
    val annotated =
        remember(inlines, linkColor, codeBackground) {
            annotateFilePaths(renderInline(inlines, codeBackground, linkColor), linkColor)
        }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    SelectionContainer {
        Text(
            text = annotated,
            style = style,
            modifier =
                Modifier.pointerInput(annotated) {
                    detectTapGestures { offset ->
                        layoutResult?.let { layout ->
                            val position = layout.getOffsetForPosition(offset)
                            annotated.getStringAnnotations("link", position, position)
                                .firstOrNull()?.let { ann ->
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(ann.item)))
                                    }
                                } ?: annotated.getStringAnnotations("filepath", position, position)
                                .firstOrNull()?.let { onFilePathClick(it.item) }
                        }
                    }
                },
            onTextLayout = { layoutResult = it },
        )
    }
}

@Composable
private fun MarkdownText(
    text: String,
    onFilePathClick: (String) -> Unit = {},
) {
    val blocks = remember(text) { MarkdownLite.parse(text) }
    val codeInlineBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    val bodyStyle =
        MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading ->
                    InlineText(
                        inlines = block.inlines,
                        style =
                            when (block.level) {
                                1 -> MaterialTheme.typography.titleLarge
                                2 -> MaterialTheme.typography.titleMedium
                                else -> MaterialTheme.typography.titleSmall
                            }.copy(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface),
                        linkColor = linkColor,
                        codeBackground = codeInlineBackground,
                        onFilePathClick = onFilePathClick,
                    )
                is MarkdownBlock.Paragraph ->
                    InlineText(
                        inlines = block.inlines,
                        style = bodyStyle,
                        linkColor = linkColor,
                        codeBackground = codeInlineBackground,
                        onFilePathClick = onFilePathClick,
                    )
                is MarkdownBlock.CodeBlock ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Text(
                            text = block.code,
                            modifier =
                                Modifier
                                    .horizontalScroll(rememberScrollState())
                                    .padding(10.dp),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                is MarkdownBlock.BulletList ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        block.items.forEach { item ->
                            Row {
                                Text("•  ", color = MaterialTheme.colorScheme.onSurface)
                                InlineText(item, bodyStyle, linkColor, codeInlineBackground, onFilePathClick)
                            }
                        }
                    }
                is MarkdownBlock.OrderedList ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        block.items.forEachIndexed { index, item ->
                            Row {
                                Text("${index + 1}.  ", color = MaterialTheme.colorScheme.onSurface)
                                InlineText(item, bodyStyle, linkColor, codeInlineBackground, onFilePathClick)
                            }
                        }
                    }
                is MarkdownBlock.Blockquote ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Box(
                            modifier =
                                Modifier.padding(
                                    start = 10.dp,
                                    end = 8.dp,
                                    top = 6.dp,
                                    bottom = 6.dp,
                                ),
                        ) {
                            InlineText(
                                inlines = block.inlines,
                                style = bodyStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                linkColor = linkColor,
                                codeBackground = codeInlineBackground,
                                onFilePathClick = onFilePathClick,
                            )
                        }
                    }
                is MarkdownBlock.Table ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                block.headers.forEach { header ->
                                    Text(
                                        text = header,
                                        modifier =
                                            Modifier
                                                .weight(1f)
                                                .padding(end = 8.dp),
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                            block.rows.forEach { row ->
                                val padded =
                                    row.take(block.headers.size) +
                                        List((block.headers.size - row.size).coerceAtLeast(0)) { "" }
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    padded.forEach { cell ->
                                        Text(
                                            text = cell,
                                            modifier =
                                                Modifier
                                                    .weight(1f)
                                                    .padding(end = 8.dp),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                            }
                        }
                    }
                is MarkdownBlock.HorizontalRule ->
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    )
            }
        }
    }
}

private val FILE_PATH_REGEX = Regex("[\\w./-]+\\.\\w+")

private fun annotateFilePaths(
    source: AnnotatedString,
    linkColor: Color,
): AnnotatedString {
    return buildAnnotatedString {
        append(source)
        FILE_PATH_REGEX.findAll(source.text).forEach { match ->
            addStyle(
                SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                match.range.first,
                match.range.last + 1,
            )
            addStringAnnotation("filepath", match.value, match.range.first, match.range.last + 1)
        }
    }
}

private fun renderInline(
    inlines: List<MarkdownInline>,
    codeBackground: Color,
    linkColor: Color,
): AnnotatedString =
    buildAnnotatedString {
        inlines.forEach { inline ->
            when (inline) {
                is MarkdownInline.Plain -> append(inline.text)
                is MarkdownInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline.text) }
                is MarkdownInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline.text) }
                is MarkdownInline.Strikethrough ->
                    withStyle(
                        SpanStyle(textDecoration = TextDecoration.LineThrough),
                    ) { append(inline.text) }
                is MarkdownInline.Code ->
                    withStyle(
                        SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground),
                    ) { append(inline.text) }
                is MarkdownInline.Link -> {
                    val start = length
                    withStyle(
                        SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                    ) { append(inline.text) }
                    addStringAnnotation("link", inline.url, start, length)
                }
            }
        }
    }

// Not private: reused by AssistantActivityRow.kt (same package) for the activity detail sheet.
@Composable
fun ReasoningCard(
    part: ChatPart.Reasoning,
    autoExpand: Boolean = false,
) {
    var expanded by remember { mutableStateOf(autoExpand) }
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        shape = RoundedCornerShape(14.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Psychology,
                    contentDescription = stringResource(R.string.cd_reasoning),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(
                    text = stringResource(R.string.reasoning_card_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(R.string.cd_expand_collapse),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded && part.text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = part.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
        }
    }
}

// Not private: reused by AssistantActivityRow.kt (same package) for the activity detail sheet.
@Composable
fun ToolCard(part: ChatPart.Tool) {
    var expanded by remember { mutableStateOf(part.status == ToolStatus.RUNNING) }
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    toolCategoryIcon(part.name.toToolCategory()),
                    contentDescription = stringResource(R.string.cd_tool),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = part.name,
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                part.title?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                ToolStatusChip(part.status)
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(R.string.cd_expand_collapse),
                    modifier = Modifier.size(18.dp),
                )
            }
            if (expanded) {
                part.input?.let { input ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.tool_input_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = input,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 4.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                part.output?.let { output ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.tool_output_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = output,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 4.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (part.outputTruncated) {
                        Text(
                            text = stringResource(R.string.tool_output_truncated),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                part.error?.let { error ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

// Not private: reused by AssistantActivityRow.kt (same package) for the collapsed activity row.
@Composable
fun ToolStatusChip(status: ToolStatus) {
    val (label, color) =
        when (status) {
            ToolStatus.PENDING -> stringResource(R.string.tool_status_pending) to MaterialTheme.colorScheme.onSurfaceVariant
            ToolStatus.RUNNING -> stringResource(R.string.tool_status_running) to MaterialTheme.colorScheme.primary
            ToolStatus.COMPLETED -> stringResource(R.string.tool_status_completed) to OpenCodeSuccess
            ToolStatus.ERROR -> stringResource(R.string.tool_status_error) to MaterialTheme.colorScheme.error
            ToolStatus.UNKNOWN -> stringResource(R.string.tool_status_pending) to MaterialTheme.colorScheme.onSurfaceVariant
        }
    Surface(
        color = color.copy(alpha = 0.14f),
        contentColor = color,
        shape = RoundedCornerShape(100.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

// Not private: reused by AssistantActivityRow.kt (same package) for the activity detail sheet.
@Composable
fun PatchCard(part: ChatPart.Patch) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Description,
                    contentDescription = stringResource(R.string.cd_file_changes),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.padding(horizontal = 3.dp))
                Text(
                    stringResource(R.string.file_changes_title),
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(6.dp))
            if (part.files.isEmpty()) {
                Text(
                    text = stringResource(R.string.file_changes_generic),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    onPermission: (String, PermissionResponse, Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = OpenCodeWarning.copy(alpha = 0.10f)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, contentDescription = stringResource(R.string.cd_permission), tint = OpenCodeWarning)
                Spacer(Modifier.padding(horizontal = 5.dp))
                Text(stringResource(R.string.permission_required), fontWeight = FontWeight.SemiBold)
            }
            Text(
                text = stringResource(R.string.permission_chat_confirmation),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = permission.permission,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            permission.patterns.forEach { pattern ->
                Text(
                    text = pattern,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onPermission(permission.id, PermissionResponse.REJECT, false) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.reject))
                }
                FilledTonalButton(
                    onClick = { onPermission(permission.id, PermissionResponse.ONCE, false) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.allow_once))
                }
                Button(
                    onClick = { onPermission(permission.id, PermissionResponse.ALWAYS, true) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.always_allow))
                }
            }
        }
    }
}

@Composable
fun TurnBoundary(
    timestamp: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
        )
        Text(
            text = timestamp,
            modifier = Modifier.padding(horizontal = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
        )
    }
}

@Composable
fun ToolCallSummaryCard(
    toolNames: List<String>,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard(
        modifier =
            modifier.clickable {
                expanded = !expanded
                onExpand()
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.tool_call_count, toolNames.size),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = stringResource(R.string.cd_expand_collapse),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                toolNames.forEach { name ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(100.dp),
                    ) {
                        Text(
                            text = name,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CompactionMarker(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
        )
        Icon(
            Icons.Default.Compress,
            contentDescription = stringResource(R.string.cd_compacted),
            modifier = Modifier.padding(horizontal = 4.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.context_compacted_marker),
            modifier = Modifier.padding(horizontal = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
        )
    }
}

@Composable
fun PlanCard(
    planContent: String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(true) }
    SectionCard(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Checklist,
                contentDescription = stringResource(R.string.cd_plan),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.padding(horizontal = 4.dp))
            Text(
                text = stringResource(R.string.plan_card_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = stringResource(R.string.cd_expand_collapse),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 10.dp)) {
                MarkdownText(planContent)
            }
        }
    }
}

data class TodoItem(
    val content: String,
    val status: String,
    val priority: String,
)

@Composable
fun TodoListCard(
    todos: List<TodoItem>,
    modifier: Modifier = Modifier,
) {
    val completedCount = todos.count { it.status == "completed" }
    SectionCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.tasks_card_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.tasks_progress, completedCount, todos.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            todos.forEach { todo ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (todo.status) {
                        "completed" ->
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = stringResource(R.string.cd_task_completed),
                                tint = OpenCodeSuccess,
                            )
                        "in_progress" ->
                            Icon(
                                Icons.Default.Pending,
                                contentDescription = stringResource(R.string.cd_task_in_progress),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        else ->
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = stringResource(R.string.cd_task_pending),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                    }
                    Text(
                        text = todo.content,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    StatusChip(
                        text = todo.priority,
                        active = todo.priority == "high",
                    )
                }
            }
        }
    }
}
