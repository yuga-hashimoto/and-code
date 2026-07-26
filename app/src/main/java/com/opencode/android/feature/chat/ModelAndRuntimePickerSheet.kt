package com.opencode.android.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opencode.android.R
import com.opencode.android.core.api.OpenCodeModel
import com.opencode.android.core.api.OpenCodeProvider
import com.opencode.android.runtime.RuntimeTarget
import com.opencode.android.runtime.RuntimeType

private const val MAX_RECENT_MODELS = 3

/**
 * Bottom sheet opened from the chat screen's model chip. Lets the user pick both
 * the execution target (this Android device or a registered remote runtime) and
 * the model to use from the currently selected provider's catalog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelAndRuntimePickerSheet(
    sheetState: SheetState,
    runtimeTargets: List<RuntimeTarget>,
    selectedRuntimeId: String?,
    onSelectRuntime: (String) -> Unit,
    providers: List<OpenCodeProvider>,
    selectedProviderId: String?,
    selectedModelId: String?,
    onSelectModel: (String, String) -> Unit,
    favoriteModelKeys: Set<String> = emptySet(),
    recentModelKeys: List<String> = emptyList(),
    hiddenModelKeys: Set<String> = emptySet(),
    onToggleFavorite: (String, String) -> Unit = { _, _ -> },
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Column {
                    Text(
                        text = stringResource(R.string.picker_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.picker_caption),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.picker_search)) },
                )
            }

            item {
                SheetSectionHeader(stringResource(R.string.section_runtime))
            }
            items(runtimeTargets, key = { "runtime-${it.id}" }) { target ->
                RuntimeRow(
                    target = target,
                    selected = target.id == selectedRuntimeId,
                    onClick = { onSelectRuntime(target.id) },
                )
            }

            item {
                Column {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    SheetSectionHeader(stringResource(R.string.section_model))
                }
            }

            if (providers.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.picker_no_providers),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            } else {
                data class FavoriteEntry(
                    val provider: OpenCodeProvider,
                    val model: OpenCodeModel,
                )

                val favoriteEntries =
                    providers.flatMap { provider ->
                        provider.models.values
                            .filter { "${provider.id}/${it.id}" in favoriteModelKeys }
                            .filter { query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true) }
                            .map { FavoriteEntry(provider, it) }
                    }.sortedBy { it.model.name.lowercase() }

                val recentEntries =
                    recentModelKeys.mapNotNull { key ->
                        val providerId = key.substringBefore('/')
                        val modelId = key.substringAfter('/')
                        val provider = providers.firstOrNull { it.id == providerId } ?: return@mapNotNull null
                        val model = provider.models[modelId] ?: return@mapNotNull null
                        FavoriteEntry(provider, model)
                    }.filter { entry -> "${entry.provider.id}/${entry.model.id}" !in favoriteModelKeys }
                        .filter { query.isBlank() || it.model.name.contains(query, true) || it.model.id.contains(query, true) }
                        .take(MAX_RECENT_MODELS)

                if (favoriteEntries.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.section_favorites),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(favoriteEntries, key = { "fav-${it.provider.id}-${it.model.id}" }) { entry ->
                        ModelRow(
                            model = entry.model,
                            selected = entry.provider.id == selectedProviderId && entry.model.id == selectedModelId,
                            isFavorite = true,
                            onClick = { onSelectModel(entry.provider.id, entry.model.id) },
                            onToggleFavorite = { onToggleFavorite(entry.provider.id, entry.model.id) },
                        )
                    }
                    item {
                        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                    }
                }

                if (recentEntries.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.section_recent),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(recentEntries, key = { "recent-${it.provider.id}-${it.model.id}" }) { entry ->
                        ModelRow(
                            model = entry.model,
                            selected = entry.provider.id == selectedProviderId && entry.model.id == selectedModelId,
                            isFavorite = "${entry.provider.id}/${entry.model.id}" in favoriteModelKeys,
                            onClick = { onSelectModel(entry.provider.id, entry.model.id) },
                            onToggleFavorite = { onToggleFavorite(entry.provider.id, entry.model.id) },
                        )
                    }
                    item {
                        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                    }
                }

                providers.forEach { provider ->
                    val models =
                        provider.models.values
                            // Keep preview and beta models selectable; hide only deprecated entries.
                            .filter { it.status != "deprecated" }
                            .filter { "${provider.id}/${it.id}" !in hiddenModelKeys }
                            .filter { query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true) }
                            .sortedBy { it.name.lowercase() }
                    if (models.isNotEmpty()) {
                        item {
                            Text(
                                text = "${provider.name} (${models.size})",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            )
                        }
                        items(models, key = { "model-${provider.id}-${it.id}" }) { model ->
                            ModelRow(
                                model = model,
                                selected = provider.id == selectedProviderId && model.id == selectedModelId,
                                isFavorite = "${provider.id}/${model.id}" in favoriteModelKeys,
                                onClick = { onSelectModel(provider.id, model.id) },
                                onToggleFavorite = { onToggleFavorite(provider.id, model.id) },
                            )
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SheetSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun RuntimeRow(
    target: RuntimeTarget,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (target.type == RuntimeType.LOCAL) Icons.Default.Android else Icons.Default.Computer,
            contentDescription = stringResource(R.string.cd_runtime_type),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text =
                if (target.type == RuntimeType.LOCAL) {
                    stringResource(R.string.this_android)
                } else {
                    target.displayName
                },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        RadioButton(selected = selected, onClick = onClick)
    }
}

@Composable
private fun ModelRow(
    model: OpenCodeModel,
    selected: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconButton(onClick = onToggleFavorite, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = stringResource(R.string.cd_favorite),
                tint =
                    if (isFavorite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier = Modifier.size(18.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(model.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                model.id,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        RadioButton(selected = selected, onClick = onClick)
    }
}
