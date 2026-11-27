package com.yugahashimoto.andcode.feature.report

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import kotlinx.coroutines.launch

/** Nothing from the conversation is sent unless the user explicitly types or pastes it. */
@Composable
fun AiContentReportDialog(
    hasGitHubAccount: Boolean,
    gitHubAccountName: String? = null,
    privateReportingEnabled: Boolean = false,
    onSubmit: suspend (AiReportReason, String) -> Result<Unit>,
    onDismiss: () -> Unit,
) {
    var reason by remember { mutableStateOf(AiReportReason.HARMFUL) }
    var excerpt by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(stringResource(R.string.ai_report_title)) },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                if (submitted) {
                    Text(stringResource(R.string.ai_report_success))
                } else {
                    AiReportWarning(privateReportingEnabled, hasGitHubAccount, gitHubAccountName)
                    AiReportReasons(reason, sending) { reason = it }
                    OutlinedTextField(
                        value = excerpt,
                        onValueChange = { if (it.length <= AiContentReportClient.MAX_EXCERPT_LENGTH) excerpt = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(
                                stringResource(
                                    if (privateReportingEnabled) R.string.ai_report_private_excerpt else R.string.ai_report_excerpt,
                                ),
                            )
                        },
                        enabled = !sending,
                    )
                    if (failed) {
                        Text(
                            stringResource(if (privateReportingEnabled) R.string.ai_report_private_failure else R.string.ai_report_failure),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (submitted) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close_description)) }
            } else {
                TextButton(
                    enabled = (privateReportingEnabled || hasGitHubAccount) && excerpt.isNotBlank() && !sending,
                    onClick = {
                        sending = true
                        failed = false
                        scope.launch {
                            try {
                                val result = onSubmit(reason, excerpt)
                                submitted = result.isSuccess
                                failed = result.isFailure
                            } finally {
                                sending = false
                            }
                        }
                    },
                ) {
                    if (sending) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text(
                        stringResource(
                            if (sending) {
                                R.string.ai_report_sending
                            } else if (privateReportingEnabled) {
                                R.string.ai_report_private_send
                            } else {
                                R.string.ai_report_send
                            },
                        ),
                    )
                }
            }
        },
        dismissButton = {
            if (!submitted) TextButton(onClick = onDismiss, enabled = !sending) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun AiReportWarning(
    privateReportingEnabled: Boolean,
    hasGitHubAccount: Boolean,
    gitHubAccountName: String?,
) {
    Text(
        stringResource(if (privateReportingEnabled) R.string.ai_report_private_warning else R.string.ai_report_warning),
        color = MaterialTheme.colorScheme.error,
    )
    if (!privateReportingEnabled && !hasGitHubAccount) {
        Text(stringResource(R.string.ai_report_account), modifier = Modifier.padding(top = 8.dp))
    } else if (!privateReportingEnabled && !gitHubAccountName.isNullOrBlank()) {
        Text(stringResource(R.string.ai_report_account_name, gitHubAccountName), modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun AiReportReasons(
    reason: AiReportReason,
    sending: Boolean,
    onSelect: (AiReportReason) -> Unit,
) {
    Text(stringResource(R.string.ai_report_reason), modifier = Modifier.padding(top = 12.dp))
    AiReportReason.entries.forEach { item ->
        val label =
            when (item) {
                AiReportReason.HARMFUL -> R.string.ai_report_harmful
                AiReportReason.HARASSMENT -> R.string.ai_report_harassment
                AiReportReason.DECEPTIVE -> R.string.ai_report_deceptive
                AiReportReason.OTHER -> R.string.ai_report_other
            }
        Row(
            modifier = Modifier.fillMaxWidth().clickable(enabled = !sending) { onSelect(item) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = reason == item, onClick = { onSelect(item) }, enabled = !sending)
            Text(stringResource(label))
        }
    }
}
