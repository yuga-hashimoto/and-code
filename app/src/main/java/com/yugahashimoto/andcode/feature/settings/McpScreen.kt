package com.yugahashimoto.andcode.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.api.McpServer
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.RuntimeRegistry
import com.yugahashimoto.andcode.ui.ViewModelFactory
import com.yugahashimoto.andcode.ui.components.StatusChip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpScreen(
    registry: RuntimeRegistry,
    agent: LocalAgent = LocalAgent.OPEN_CODE,
    onOpenBrowser: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: McpViewModel =
        viewModel(
            key = "mcp-${agent.id}",
            factory =
                ViewModelFactory {
                    McpViewModel(
                        registry,
                        agent,
                        authNotRemovedMessage = context.getString(R.string.mcp_auth_not_removed),
                        authFailedTemplate = context.getString(R.string.mcp_auth_failed_status),
                    )
                },
        )
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mcp_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::showAddDialog) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.mcp_add_server))
            }
        },
    ) { padding ->
        if (state.isLoading && state.servers.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
        } else if (state.servers.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.mcp_no_servers),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = padding.calculateBottomPadding() + 88.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.servers, key = { it.name }) { server ->
                    McpServerCard(
                        server = server,
                        onConnect = { viewModel.connect(server.name) },
                        onDisconnect = { viewModel.disconnect(server.name) },
                        onRemoveAuth = { viewModel.removeAuth(server.name) },
                        onAuthenticate = { viewModel.startAuth(server.name, onOpenBrowser) },
                        supportsConnectToggle = state.supportsConnectToggle,
                        supportsOAuth = state.supportsOAuth,
                        isAuthenticating = state.isAuthenticating,
                    )
                }
            }
        }
    }

    if (state.showAddDialog) {
        McpAddDialog(
            state = state,
            onNameChange = viewModel::updateAddName,
            onCommandChange = viewModel::updateAddCommand,
            onUrlChange = viewModel::updateAddUrl,
            onConfirm = viewModel::addServer,
            onDismiss = viewModel::dismissAddDialog,
        )
    }

    val oauthServerName = state.oauthServerName
    if (oauthServerName != null) {
        McpAuthCodeDialog(
            serverName = oauthServerName,
            code = state.oauthCode,
            isSubmitting = state.isAuthenticating,
            onCodeChange = viewModel::updateOAuthCode,
            onConfirm = viewModel::completeAuth,
            onDismiss = viewModel::dismissAuth,
        )
    }

    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text(stringResource(R.string.operation_failed_title)) },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = viewModel::clearError) {
                    Text(stringResource(R.string.close_description))
                }
            },
        )
    }
}

@Composable
private fun McpServerCard(
    server: McpServer,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRemoveAuth: () -> Unit,
    onAuthenticate: () -> Unit,
    supportsConnectToggle: Boolean,
    supportsOAuth: Boolean,
    isAuthenticating: Boolean,
) {
    val isConnected = server.status == "connected" || server.status == "running"
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    server.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusChip(
                    text = server.status ?: "unknown",
                    active = isConnected,
                )
            }
            server.url?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            server.command?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (server.tools.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.mcp_tools_count, server.tools.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            server.error?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // An agent that always connects to what it is configured with has nothing to toggle;
                // it offers removal instead, so the label matches what the button really does.
                if (supportsOAuth && server.status == "needs_auth") {
                    OutlinedButton(onClick = onAuthenticate, enabled = !isAuthenticating) {
                        Text(stringResource(R.string.mcp_authenticate))
                    }
                } else if (!supportsConnectToggle) {
                    OutlinedButton(onClick = onDisconnect) {
                        Icon(
                            Icons.Default.LinkOff,
                            contentDescription = stringResource(R.string.cd_disconnect),
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.mcp_remove_server))
                    }
                } else if (isConnected) {
                    OutlinedButton(onClick = onDisconnect) {
                        Icon(
                            Icons.Default.LinkOff,
                            contentDescription = stringResource(R.string.cd_disconnect),
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.mcp_disconnect))
                    }
                } else {
                    OutlinedButton(onClick = onConnect) {
                        Icon(Icons.Default.Link, contentDescription = stringResource(R.string.cd_connect), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.mcp_connect))
                    }
                }
                if (supportsOAuth) {
                    OutlinedButton(onClick = onRemoveAuth, enabled = !isAuthenticating) {
                        Text(stringResource(R.string.mcp_remove_auth))
                    }
                }
            }
        }
    }
}

@Composable
private fun McpAuthCodeDialog(
    serverName: String,
    code: String,
    isSubmitting: Boolean,
    onCodeChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mcp_auth_title, serverName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.mcp_auth_help))
                OutlinedTextField(
                    value = code,
                    onValueChange = onCodeChange,
                    label = { Text(stringResource(R.string.mcp_auth_code_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = code.isNotBlank() && !isSubmitting) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.mcp_auth_complete))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun McpAddDialog(
    state: McpUiState,
    onNameChange: (String) -> Unit,
    onCommandChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mcp_add_server)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = state.addName,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.mcp_name_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.addCommand,
                    onValueChange = onCommandChange,
                    label = { Text(stringResource(R.string.mcp_command_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.addUrl,
                    onValueChange = onUrlChange,
                    label = { Text(stringResource(R.string.mcp_url_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Text(
                    stringResource(R.string.mcp_add_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.risk_warning_mcp_server_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled =
                    !state.isAdding &&
                        state.addName.isNotBlank() &&
                        (state.addCommand.isNotBlank() || state.addUrl.isNotBlank()),
            ) {
                if (state.isAdding) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.mcp_add_server))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
