package com.yugahashimoto.andcode.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.local.SystemPromptPreset

/**
 * Lets the user pick which system-prompt preset Claude Code's messages carry, and manage the
 * custom ones they have saved.
 *
 * Built-in presets (Coding, Debug, Research, Creative) cover the modes requested in issue #294 and
 * cannot be edited or deleted; a user's own presets can be added, edited, and removed freely.
 */
@Composable
fun SystemPromptScreen(
    presets: List<SystemPromptPreset>,
    selectedPresetId: String?,
    onSelect: (String?) -> Unit,
    onSave: (name: String, prompt: String, id: String?) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    var editing by remember { mutableStateOf<SystemPromptPreset?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SystemPromptPreset?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.system_prompt_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        editing = null
                        showEditor = true
                    }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_add_system_prompt_preset))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.system_prompt_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                )
            }
            item {
                PresetRow(
                    name = stringResource(R.string.system_prompt_none),
                    selected = selectedPresetId == null,
                    onSelect = { onSelect(null) },
                )
                HorizontalDivider()
            }
            items(presets, key = SystemPromptPreset::id) { preset ->
                PresetRow(
                    name = preset.name,
                    selected = preset.id == selectedPresetId,
                    onSelect = { onSelect(preset.id) },
                    builtIn = preset.builtIn,
                    onEdit =
                        {
                            editing = preset
                            showEditor = true
                        }.takeUnless { preset.builtIn },
                    onDelete = { pendingDelete = preset }.takeUnless { preset.builtIn },
                )
                HorizontalDivider()
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showEditor) {
        SystemPromptEditDialog(
            initial = editing,
            onSave = { name, prompt ->
                onSave(name, prompt, editing?.id)
                showEditor = false
            },
            onDismiss = { showEditor = false },
        )
    }

    pendingDelete?.let { preset ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.system_prompt_delete_title)) },
            text = { Text(stringResource(R.string.system_prompt_delete_body, preset.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(preset.id)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun PresetRow(
    name: String,
    selected: Boolean,
    onSelect: () -> Unit,
    builtIn: Boolean = false,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            if (builtIn) {
                Text(
                    text = stringResource(R.string.system_prompt_builtin_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        onEdit?.let {
            IconButton(onClick = it) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = stringResource(R.string.cd_edit_system_prompt_preset),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        onDelete?.let {
            IconButton(onClick = it) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.cd_delete_system_prompt_preset),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SystemPromptEditDialog(
    initial: SystemPromptPreset?,
    onSave: (name: String, prompt: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var prompt by remember { mutableStateOf(initial?.prompt.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.system_prompt_dialog_title_new else R.string.system_prompt_dialog_title_edit,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.system_prompt_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text(stringResource(R.string.system_prompt_text_label)) },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), prompt.trim()) },
                enabled = name.isNotBlank() && prompt.isNotBlank(),
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
