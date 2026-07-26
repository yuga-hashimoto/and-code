package com.opencode.android.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.opencode.android.R
import com.opencode.android.ui.theme.OpenCodeAndroidTheme

/** Compact settings landing screen backed only by real destinations and state. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SettingsScreenV2(
    assistantConfigured: Boolean,
    notificationsEnabled: Boolean,
    onToggleNotifications: (Boolean) -> Unit,
    appVersion: String,
    onOpenDrawer: () -> Unit,
    onOpenAssistantSettings: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    onOpenProviderSettings: () -> Unit,
    onOpenLocalRuntime: () -> Unit,
    onOpenRemoteConnection: () -> Unit,
    onOpenWorkspaces: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSupport: () -> Unit = {},
    onOpenMcp: () -> Unit = {},
    onOpenServerInfo: () -> Unit = {},
    onOpenModelVisibility: () -> Unit = {},
    currentTheme: String = "dark",
    onThemeChange: (String) -> Unit = {},
    uiFontSize: Int = 16,
    onUiFontSizeChange: (Int) -> Unit = {},
    codeFontSize: Int = 12,
    onCodeFontSizeChange: (Int) -> Unit = {},
    syntaxTheme: String = "one-dark",
    onSyntaxThemeChange: (String) -> Unit = {},
    isTablet: Boolean = false,
    toolCallDetailLevel: String = "detailed",
    onToolCallDetailLevelChange: (String) -> Unit = {},
    autoExpandReasoning: Boolean = false,
    onAutoExpandReasoningChange: (Boolean) -> Unit = {},
    sendBehavior: String = "interrupt",
    onSendBehaviorChange: (String) -> Unit = {},
) {
    var showThemeDialog by remember { mutableStateOf(false) }
    var showUiFontDialog by remember { mutableStateOf(false) }
    var showCodeFontDialog by remember { mutableStateOf(false) }
    var showSyntaxThemeDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    stringResource(R.string.nav_settings),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            navigationIcon = {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.menu_description))
                }
            },
            colors =
                TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
        )

        val settingsListContent: @Composable () -> Unit = {
            SettingsSection(title = stringResource(R.string.section_assistant_settings)) {
                SettingsRow(
                    icon = Icons.Default.Home,
                    title = stringResource(R.string.settings_home_assistant_row),
                    trailing = {
                        StatusPill(
                            text =
                                if (assistantConfigured) {
                                    stringResource(R.string.assistant_enabled_pill)
                                } else {
                                    stringResource(R.string.assistant_disabled_pill)
                                },
                            active = assistantConfigured,
                        )
                    },
                    onClick = onOpenAssistantSettings,
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.Mic,
                    title = stringResource(R.string.settings_wake_word_row),
                    value = stringResource(R.string.settings_wake_word_value),
                    onClick = onOpenVoiceSettings,
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.RecordVoiceOver,
                    title = stringResource(R.string.voice_settings_row),
                    onClick = onOpenVoiceSettings,
                )
            }

            SettingsSection(title = stringResource(R.string.section_appearance_settings)) {
                SettingsRow(
                    icon = Icons.Default.Palette,
                    title = stringResource(R.string.theme_row),
                    value = currentTheme.replaceFirstChar { it.uppercase() },
                    onClick = { showThemeDialog = true },
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.Palette,
                    title = stringResource(R.string.ui_font_size_row),
                    value = "${uiFontSize}sp",
                    onClick = { showUiFontDialog = true },
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.Palette,
                    title = stringResource(R.string.code_font_size_row),
                    value = "${codeFontSize}sp",
                    onClick = { showCodeFontDialog = true },
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.Code,
                    title = stringResource(R.string.syntax_theme_row),
                    value = syntaxTheme,
                    onClick = { showSyntaxThemeDialog = true },
                )
            }

            SettingsSection(title = stringResource(R.string.section_chat_settings)) {
                SettingsRow(
                    icon = Icons.Default.Chat,
                    title = stringResource(R.string.tool_call_detail_row),
                    value =
                        if (toolCallDetailLevel == "detailed") {
                            stringResource(R.string.tool_call_detailed)
                        } else {
                            stringResource(R.string.tool_call_overview)
                        },
                    onClick = {
                        onToolCallDetailLevelChange(
                            if (toolCallDetailLevel == "detailed") "overview" else "detailed",
                        )
                    },
                )
                SettingsDivider()
                SettingsToggleRow(
                    icon = Icons.Default.Chat,
                    title = stringResource(R.string.auto_expand_reasoning_row),
                    checked = autoExpandReasoning,
                    onCheckedChange = onAutoExpandReasoningChange,
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.Chat,
                    title = stringResource(R.string.send_behavior_row),
                    value =
                        if (sendBehavior == "interrupt") {
                            stringResource(R.string.send_behavior_interrupt)
                        } else {
                            stringResource(R.string.send_behavior_queue)
                        },
                    onClick = {
                        onSendBehaviorChange(
                            if (sendBehavior == "interrupt") "queue" else "interrupt",
                        )
                    },
                )
            }

            SettingsSection(title = stringResource(R.string.section_system_settings)) {
                SettingsRow(
                    icon = Icons.Default.Key,
                    title = stringResource(R.string.provider_settings_row),
                    onClick = onOpenProviderSettings,
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.Visibility,
                    title = stringResource(R.string.model_visibility_row),
                    onClick = onOpenModelVisibility,
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.Extension,
                    title = stringResource(R.string.mcp_settings_row),
                    onClick = onOpenMcp,
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.Build,
                    title = stringResource(R.string.server_info_settings_row),
                    onClick = onOpenServerInfo,
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.Terminal,
                    title = stringResource(R.string.settings_local_runtime_row),
                    onClick = onOpenLocalRuntime,
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.Router,
                    title = stringResource(R.string.remote_connection_row),
                    onClick = onOpenRemoteConnection,
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.Folder,
                    title = stringResource(R.string.settings_workspace_row),
                    onClick = onOpenWorkspaces,
                )
            }

            SettingsSection(title = stringResource(R.string.section_app_settings)) {
                SettingsToggleRow(
                    icon = Icons.Default.Notifications,
                    title = stringResource(R.string.notifications_row),
                    checked = notificationsEnabled,
                    onCheckedChange = onToggleNotifications,
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.BugReport,
                    title = stringResource(R.string.diagnostics_row),
                    onClick = onOpenDiagnostics,
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Default.Star,
                    title = stringResource(R.string.github_support_button),
                    onClick = onOpenSupport,
                )
            }
        }

        if (isTablet) {
            Row(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.width(320.dp),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    item { settingsListContent() }
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                ) {}
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item { settingsListContent() }
            }
        }
    }

    if (showThemeDialog) {
        val themes = listOf("dark", "light", "zinc", "midnight", "claude", "ghostty", "auto")
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text(stringResource(R.string.theme_dialog_title)) },
            text = {
                Column {
                    themes.forEach { theme ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onThemeChange(theme)
                                        showThemeDialog = false
                                    }
                                    .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = currentTheme == theme,
                                onClick = {
                                    onThemeChange(theme)
                                    showThemeDialog = false
                                },
                            )
                            Text(
                                text = theme.replaceFirstChar { it.uppercase() },
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text(stringResource(R.string.close_description))
                }
            },
        )
    }

    if (showUiFontDialog) {
        var sliderValue by remember { mutableFloatStateOf(uiFontSize.toFloat()) }
        AlertDialog(
            onDismissRequest = { showUiFontDialog = false },
            title = { Text(stringResource(R.string.ui_font_size_row)) },
            text = {
                Column {
                    Text("${sliderValue.toInt()}sp")
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        valueRange = 12f..22f,
                        steps = 9,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onUiFontSizeChange(sliderValue.toInt())
                    showUiFontDialog = false
                }) {
                    Text(stringResource(R.string.close_description))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUiFontDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showCodeFontDialog) {
        var sliderValue by remember { mutableFloatStateOf(codeFontSize.toFloat()) }
        AlertDialog(
            onDismissRequest = { showCodeFontDialog = false },
            title = { Text(stringResource(R.string.code_font_size_row)) },
            text = {
                Column {
                    Text("${sliderValue.toInt()}sp")
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        valueRange = 10f..20f,
                        steps = 9,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onCodeFontSizeChange(sliderValue.toInt())
                    showCodeFontDialog = false
                }) {
                    Text(stringResource(R.string.close_description))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCodeFontDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showSyntaxThemeDialog) {
        val syntaxThemes = listOf("one-dark", "monokai", "github-dark", "solarized-dark")
        AlertDialog(
            onDismissRequest = { showSyntaxThemeDialog = false },
            title = { Text(stringResource(R.string.syntax_theme_dialog_title)) },
            text = {
                Column {
                    syntaxThemes.forEach { theme ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSyntaxThemeChange(theme)
                                        showSyntaxThemeDialog = false
                                    }
                                    .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = syntaxTheme == theme,
                                onClick = {
                                    onSyntaxThemeChange(theme)
                                    showSyntaxThemeDialog = false
                                },
                            )
                            Text(
                                text = theme,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSyntaxThemeDialog = false }) {
                    Text(stringResource(R.string.close_description))
                }
            },
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Column { content() }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    value: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        value?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing?.invoke()
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 36.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.38f),
    )
}

@Composable
private fun StatusPill(
    text: String,
    active: Boolean,
) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = color.copy(alpha = 0.14f),
        contentColor = color,
        shape = RoundedCornerShape(100.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenV2Preview() {
    OpenCodeAndroidTheme {
        SettingsScreenV2(
            assistantConfigured = true,
            notificationsEnabled = true,
            onToggleNotifications = {},
            appVersion = "0.2.0",
            onOpenDrawer = {},
            onOpenAssistantSettings = {},
            onOpenVoiceSettings = {},
            onOpenProviderSettings = {},
            onOpenLocalRuntime = {},
            onOpenRemoteConnection = {},
            onOpenWorkspaces = {},
            onOpenDiagnostics = {},
        )
    }
}
