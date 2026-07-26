package com.opencode.android.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.opencode.android.R

private val extensionIconMap =
    mapOf(
        "kt" to Icons.Default.Code,
        "kts" to Icons.Default.Code,
        "java" to Icons.Default.Code,
        "py" to Icons.Default.Code,
        "js" to Icons.Default.Code,
        "ts" to Icons.Default.Code,
        "tsx" to Icons.Default.Code,
        "jsx" to Icons.Default.Code,
        "c" to Icons.Default.Code,
        "cpp" to Icons.Default.Code,
        "h" to Icons.Default.Code,
        "rs" to Icons.Default.Code,
        "go" to Icons.Default.Code,
        "rb" to Icons.Default.Code,
        "swift" to Icons.Default.Code,
        "md" to Icons.Default.Description,
        "txt" to Icons.Default.Description,
        "rst" to Icons.Default.Description,
        "json" to Icons.Default.DataObject,
        "yaml" to Icons.Default.DataObject,
        "yml" to Icons.Default.DataObject,
        "toml" to Icons.Default.DataObject,
        "xml" to Icons.Default.DataObject,
        "html" to Icons.Default.DataObject,
        "css" to Icons.Default.DataObject,
        "gradle" to Icons.Default.Build,
        "png" to Icons.Default.Image,
        "jpg" to Icons.Default.Image,
        "jpeg" to Icons.Default.Image,
        "gif" to Icons.Default.Image,
        "svg" to Icons.Default.Image,
        "webp" to Icons.Default.Image,
        "mp3" to Icons.Default.MusicNote,
        "wav" to Icons.Default.MusicNote,
        "mp4" to Icons.Default.VideoFile,
        "mov" to Icons.Default.VideoFile,
        "sh" to Icons.Default.Terminal,
        "bash" to Icons.Default.Terminal,
        "zsh" to Icons.Default.Terminal,
        "bat" to Icons.Default.Terminal,
        "cfg" to Icons.Default.Settings,
        "ini" to Icons.Default.Settings,
        "properties" to Icons.Default.Settings,
        "env" to Icons.Default.Settings,
    )

@Composable
fun FileTypeIcon(
    fileName: String,
    isDirectory: Boolean = false,
    isOpen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val icon: ImageVector =
        if (isDirectory) {
            if (isOpen) Icons.Default.FolderOpen else Icons.Default.Folder
        } else {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            extensionIconMap[ext] ?: Icons.Default.InsertDriveFile
        }
    Icon(
        imageVector = icon,
        contentDescription = stringResource(R.string.cd_file_type),
        tint =
            if (isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        modifier = modifier,
    )
}
