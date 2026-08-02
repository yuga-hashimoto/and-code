package com.yugahashimoto.andcode.feature.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.yugahashimoto.andcode.R

/**
 * Explains, before either official CLI's own sign-in flow starts, that AndCode is only relaying the
 * browser URL and code between this device and that CLI process - not authenticating on the user's
 * behalf. Shown once per tap of the sign-in button; the CLI's actual auth flow (unchanged) only
 * starts once the user taps through.
 */
@Composable
fun AgentAuthExplainerDialog(
    titleRes: Int,
    bodyRes: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(bodyRes))
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.pre_auth_dialog_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * Confirms an action that widens what an official CLI (or AndCode itself) can do without asking
 * again - full access permission modes, all-files access, adding a third-party MCP server, and so
 * on. Shown every time the action is taken rather than gated behind a persisted flag, since each of
 * these is itself a deliberate settings change rather than something that happens on every message.
 */
@Composable
fun RiskWarningDialog(
    titleRes: Int,
    bodyRes: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(bodyRes))
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.risk_warning_understood))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
