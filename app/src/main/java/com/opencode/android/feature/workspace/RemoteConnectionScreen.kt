package com.opencode.android.feature.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.nsd.NsdManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.opencode.android.R
import com.opencode.android.core.api.OpenCodeHealth
import com.opencode.android.core.security.ConnectionQrPayload
import com.opencode.android.ui.theme.OpenCodeAndroidTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Minimal connection setup for an existing OpenCode server. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteConnectionScreen(
    onTestConnection: suspend (ConnectionFormState) -> Result<OpenCodeHealth>,
    onSaveConnection: (ConnectionFormState) -> Unit,
    onBack: () -> Unit,
    onConnected: () -> Unit,
) {
    var form by remember { mutableStateOf(ConnectionFormState()) }
    var passwordVisible by remember { mutableStateOf(false) }
    var discoveryDialogOpen by remember { mutableStateOf(false) }
    var isDiscovering by remember { mutableStateOf(false) }
    var discoveredServers by remember { mutableStateOf<List<DiscoveredServer>>(emptyList()) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val qrScanLauncher =
        rememberLauncherForActivityResult(ScanContract()) { result ->
            val text = result.contents ?: return@rememberLauncherForActivityResult
            ConnectionQrPayload.parse(text)?.let { payload ->
                form =
                    ConnectionFormState(
                        name = payload.name.orEmpty(),
                        baseUrl = payload.url.orEmpty(),
                        username = payload.username?.takeIf { it.isNotBlank() } ?: "opencode",
                        password = payload.password.orEmpty(),
                        allowInsecureLan = payload.insecure,
                    )
            }
        }

    fun startLanDiscovery() {
        discoveryDialogOpen = true
        discoveredServers = emptyList()
        // mDNS browsing is best-effort: the service may be unavailable and the platform throws from
        // several of its callbacks. A failure has to leave the dialog showing "nothing found" so the
        // user can still type the address, never take the app down mid-setup.
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsdManager == null) {
            isDiscovering = false
            return
        }
        isDiscovering = true
        scope.launch {
            runCatching {
                withTimeoutOrNull(DISCOVERY_TIMEOUT_MILLIS) {
                    LanDiscovery(nsdManager).discover().collect { server ->
                        discoveredServers = (discoveredServers + server).distinctBy { it.host to it.port }
                    }
                }
            }
            isDiscovering = false
        }
    }

    fun testConnection() {
        scope.launch {
            form = form.copy(isTesting = true, testMessage = null)
            onTestConnection(form).fold(
                onSuccess = { health ->
                    form =
                        form.copy(
                            isTesting = false,
                            testSucceeded = health.healthy,
                            testMessage =
                                if (health.healthy) {
                                    "OpenCode ${health.version}"
                                } else {
                                    context.getString(R.string.remote_connection_unhealthy)
                                },
                        )
                },
                onFailure = { error ->
                    form =
                        form.copy(
                            isTesting = false,
                            testSucceeded = false,
                            testMessage = error.message ?: context.getString(R.string.remote_connection_failed),
                        )
                },
            )
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.remote_connection_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
            )
        },
        bottomBar = {
            RemoteConnectionBottomBar(
                form = form,
                onTest = ::testConnection,
                onSave = {
                    onSaveConnection(form)
                    onConnected()
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = stringResource(R.string.remote_connection_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CompactStepRow(
                    number = 1,
                    title = stringResource(R.string.remote_step1_title),
                    description = stringResource(R.string.remote_step1_desc),
                )
                val serveCommand = stringResource(R.string.remote_serve_command)
                Surface(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("opencode serve", serveCommand))
                                Toast.makeText(context, R.string.remote_command_copied, Toast.LENGTH_SHORT).show()
                            },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.9f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.remote_serve_command),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.remote_copy_command),
                            tint = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                CompactStepRow(
                    number = 2,
                    title = stringResource(R.string.remote_step2_title),
                    description = stringResource(R.string.remote_step2_desc),
                )
                CompactStepRow(
                    number = 3,
                    title = stringResource(R.string.remote_step3_title),
                    description = stringResource(R.string.remote_step3_desc),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = form.name,
                    onValueChange = {
                        form = form.copy(name = it, testSucceeded = false, testMessage = null)
                    },
                    label = { Text(stringResource(R.string.connection_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                val urlInvalid = form.baseUrl.isNotBlank() && form.normalizedUrl == null
                OutlinedTextField(
                    value = form.baseUrl,
                    onValueChange = {
                        form = form.copy(baseUrl = it, testSucceeded = false, testMessage = null)
                    },
                    label = { Text(stringResource(R.string.server_url)) },
                    placeholder = { Text("192.168.1.10:4096") },
                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = stringResource(R.string.cd_server_url)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = urlInvalid,
                    supportingText =
                        if (urlInvalid) {
                            { Text(stringResource(R.string.remote_url_invalid)) }
                        } else {
                            null
                        },
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = form.username,
                    onValueChange = {
                        form = form.copy(username = it, testSucceeded = false, testMessage = null)
                    },
                    label = { Text(stringResource(R.string.username)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = form.password,
                    onValueChange = {
                        form = form.copy(password = it, testSucceeded = false, testMessage = null)
                    },
                    label = { Text(stringResource(R.string.password)) },
                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = stringResource(R.string.cd_password)) },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(R.string.cd_toggle_password),
                            )
                        }
                    },
                    visualTransformation =
                        if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        qrScanLauncher.launch(
                            ScanOptions().setBeepEnabled(false).setOrientationLocked(false),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Default.QrCodeScanner,
                        contentDescription = stringResource(R.string.cd_scan_qr),
                        modifier = Modifier.size(19.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.add_via_qr), maxLines = 1)
                }
                OutlinedButton(
                    onClick = { startLanDiscovery() },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Default.WifiFind,
                        contentDescription = stringResource(R.string.cd_lan_discovery),
                        modifier = Modifier.size(19.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.discover_on_lan), maxLines = 1)
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = stringResource(R.string.cd_info),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        stringResource(R.string.remote_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            form.testMessage?.let { message ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color =
                        if (form.testSucceeded) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        } else {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                        },
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(14.dp),
                        color =
                            if (form.testSucceeded) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (discoveryDialogOpen) {
        AlertDialog(
            onDismissRequest = { discoveryDialogOpen = false },
            title = { Text(stringResource(R.string.discovered_servers_title)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isDiscovering) {
                        Text(
                            stringResource(R.string.discovering_servers),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else if (discoveredServers.isEmpty()) {
                        Text(
                            stringResource(R.string.no_servers_found),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    discoveredServers.forEach { server ->
                        Surface(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        form =
                                            ConnectionFormState(
                                                name = server.name,
                                                baseUrl = server.baseUrl,
                                                allowInsecureLan = true,
                                            )
                                        discoveryDialogOpen = false
                                    },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(server.name, fontWeight = FontWeight.Medium)
                                Text(
                                    server.baseUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { discoveryDialogOpen = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private const val DISCOVERY_TIMEOUT_MILLIS = 10_000L

@Composable
private fun CompactStepRow(
    number: Int,
    title: String,
    description: String,
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.size(28.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(number.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RemoteConnectionBottomBar(
    form: ConnectionFormState,
    onTest: () -> Unit,
    onSave: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
    ) {
        Button(
            onClick = if (form.testSucceeded) onSave else onTest,
            enabled = if (form.testSucceeded) true else form.canSave && !form.isTesting,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .height(48.dp),
        ) {
            if (form.isTesting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.Default.NetworkCheck,
                    contentDescription = stringResource(R.string.cd_test_connection),
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (form.testSucceeded) {
                    stringResource(R.string.remote_save_connection_button)
                } else {
                    stringResource(R.string.remote_test_connection_button)
                },
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RemoteConnectionScreenPreview() {
    OpenCodeAndroidTheme {
        RemoteConnectionScreen(
            onTestConnection = { Result.success(OpenCodeHealth(true, "1.0.0")) },
            onSaveConnection = {},
            onBack = {},
            onConnected = {},
        )
    }
}
