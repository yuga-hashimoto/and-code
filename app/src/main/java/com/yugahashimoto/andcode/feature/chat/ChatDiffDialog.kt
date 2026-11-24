package com.yugahashimoto.andcode.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.api.OpenCodeFileChange
import com.yugahashimoto.andcode.feature.workspace.DiffViewMode
import com.yugahashimoto.andcode.feature.workspace.SplitDiffView
import com.yugahashimoto.andcode.feature.workspace.UnifiedDiffView
import com.yugahashimoto.andcode.ui.theme.CodeFontFamily

/**
 * Diff for a tapped [ChatPart.Patch] card, opened by [ChatViewModel.openPatchDiff].
 *
 * The file list ([PatchDiffState.files]) always shows — it comes straight from the event stream and
 * is available for every backend — while the diff body underneath each file only renders once the
 * backend has actually produced one; see [PatchDiffState] for why an empty result reads the same as
 * [PatchDiffState.Unavailable].
 */
@Composable
fun ChatDiffDialog(
    state: PatchDiffState,
    onDismiss: () -> Unit,
) {
    var diffViewMode by remember(state.partId) { mutableStateOf(DiffViewMode.UNIFIED) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.activity_details_close))
                }
                Text(
                    text = stringResource(R.string.file_changes_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            if (state is PatchDiffState.Loaded) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = diffViewMode == DiffViewMode.UNIFIED,
                        onClick = { diffViewMode = DiffViewMode.UNIFIED },
                        label = { Text(stringResource(R.string.unified_view)) },
                    )
                    FilterChip(
                        selected = diffViewMode == DiffViewMode.SPLIT,
                        onClick = { diffViewMode = DiffViewMode.SPLIT },
                        label = { Text(stringResource(R.string.split_view)) },
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (state is PatchDiffState.Unavailable) {
                    item {
                        Text(
                            text = stringResource(R.string.diff_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.files, key = { it }) { file ->
                    val change = (state as? PatchDiffState.Loaded)?.changes?.firstOrNull { it.displayPath == file }
                    PatchFileEntry(file, change, diffViewMode)
                }
                if (state is PatchDiffState.Loading) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PatchFileEntry(
    file: String,
    change: OpenCodeFileChange?,
    diffViewMode: DiffViewMode,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = file,
            fontFamily = CodeFontFamily,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodySmall,
        )
        // A file the event stream reported but the diff response left out (a subset result, or no
        // diff available at all) still gets its name shown above - just without a diff underneath.
        val patch = change?.patch
        if (!patch.isNullOrBlank()) {
            when (diffViewMode) {
                DiffViewMode.UNIFIED -> UnifiedDiffView(patch)
                DiffViewMode.SPLIT -> SplitDiffView(patch)
            }
        }
    }
}
