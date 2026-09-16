package com.yugahashimoto.andcode.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun ProviderIcon(
    providerId: String,
    modifier: Modifier = Modifier,
    size: Int = 18,
) {
    val icon: ImageVector =
        when {
            providerId.contains("claude", ignoreCase = true) ||
                providerId.contains("anthropic", ignoreCase = true) -> Icons.Default.AutoAwesome
            providerId.contains("openai", ignoreCase = true) ||
                providerId.contains("codex", ignoreCase = true) ||
                providerId.contains("gpt", ignoreCase = true) -> Icons.Default.SmartToy
            providerId.contains("github", ignoreCase = true) -> Icons.Default.Code
            providerId.contains("gemini", ignoreCase = true) ||
                providerId.contains("google", ignoreCase = true) -> Icons.Default.Android
            providerId.contains("opencode", ignoreCase = true) -> Icons.Default.Terminal
            else -> Icons.Default.SmartToy
        }
    Icon(
        imageVector = icon,
        contentDescription = providerId,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(size.dp),
    )
}
