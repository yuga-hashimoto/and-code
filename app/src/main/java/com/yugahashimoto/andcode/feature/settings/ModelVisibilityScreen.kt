package com.yugahashimoto.andcode.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.api.OpenCodeProvider

@Composable
fun ModelVisibilityScreen(
    providers: List<OpenCodeProvider>,
    hiddenModelKeys: Set<String>,
    onToggleModelVisibility: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.model_visibility_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                        )
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
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.model_visibility_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.cd_search)) },
                    label = { Text(stringResource(R.string.picker_search)) },
                )
            }

            providers.forEach { provider ->
                val models =
                    provider.models.values
                        .filter { it.status != "deprecated" }
                        .filter { query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true) }
                        .sortedBy { it.name.lowercase() }

                if (models.isNotEmpty()) {
                    item {
                        Text(
                            text = "${provider.name} (${models.size})",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(models, key = { "vis-${provider.id}-${it.id}" }) { model ->
                        val key = "${provider.id}/${model.id}"
                        val visible = key !in hiddenModelKeys
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleModelVisibility(provider.id, model.id) }
                                    .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(
                                checked = visible,
                                onCheckedChange = { onToggleModelVisibility(provider.id, model.id) },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(model.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(
                                    model.id,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    item {
                        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }

            item {
                Spacer(Modifier.padding(bottom = 24.dp))
            }
        }
    }
}
