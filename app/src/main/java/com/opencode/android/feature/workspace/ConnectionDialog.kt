package com.opencode.android.feature.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.opencode.android.R
import com.opencode.android.core.api.OpenCodeHealth
import kotlinx.coroutines.launch

@Composable
internal fun ConnectionDialog(
    initial: ConnectionFormState,
    onDismiss: () -> Unit,
    onSave: (ConnectionFormState) -> Unit,
    onDelete: (() -> Unit)?,
    onTest: suspend (ConnectionFormState) -> Result<OpenCodeHealth>,
) {
    var form by remember(initial.id) { mutableStateOf(initial) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (onDelete == null) {
                    stringResource(R.string.add_connection)
                } else {
                    stringResource(R.string.edit_connection)
                },
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { form = form.copy(name = it, testSucceeded = false, testMessage = null) },
                    label = { Text(stringResource(R.string.connection_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = form.baseUrl,
                    onValueChange = { form = form.copy(baseUrl = it, testSucceeded = false, testMessage = null) },
                    label = { Text(stringResource(R.string.server_url)) },
                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = stringResource(R.string.cd_server_url)) },
                    placeholder = { Text("192.168.1.10:4096") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = form.baseUrl.isNotBlank() && form.normalizedUrl == null,
                    supportingText =
                        if (form.baseUrl.isNotBlank() && form.normalizedUrl == null) {
                            { Text(stringResource(R.string.remote_url_invalid)) }
                        } else {
                            null
                        },
                )
                OutlinedTextField(
                    value = form.username,
                    onValueChange = { form = form.copy(username = it) },
                    label = { Text(stringResource(R.string.username)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = form.password,
                    onValueChange = { form = form.copy(password = it, testSucceeded = false, testMessage = null) },
                    label = { Text(stringResource(R.string.password)) },
                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = stringResource(R.string.cd_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                // No cleartext opt-in here: OpenCodeUrl.normalize already limits plain HTTP to
                // loopback, RFC1918, link-local, Tailscale CGNAT and .local hosts, and anything
                // beyond that has to be https. A checkbox would only add a step in front of the
                // ordinary `opencode serve` on the LAN.
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            form = form.copy(isTesting = true, testMessage = null)
                            onTest(form).fold(
                                onSuccess = { health ->
                                    form =
                                        form.copy(
                                            isTesting = false,
                                            testSucceeded = health.healthy,
                                            testMessage = context.getString(R.string.connection_test_success, health.version),
                                        )
                                },
                                onFailure = { error ->
                                    form =
                                        form.copy(
                                            isTesting = false,
                                            testSucceeded = false,
                                            testMessage = error.message ?: context.getString(R.string.connection_test_failed),
                                        )
                                },
                            )
                        }
                    },
                    enabled = form.canSave && !form.isTesting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (form.isTesting) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            if (form.testSucceeded) Icons.Default.CheckCircle else Icons.Default.NetworkCheck,
                            contentDescription = stringResource(R.string.cd_test_connection),
                        )
                    }
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(form.testMessage ?: stringResource(R.string.test_connection))
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(form) }, enabled = form.canSave && !form.isTesting) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                onDelete?.let {
                    TextButton(onClick = it) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_delete))
                        Text(stringResource(R.string.delete))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}
