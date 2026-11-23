package com.yugahashimoto.andcode.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R

@Composable
fun GitHubSettingsScreen(
    state: SettingsUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenVerification: (String) -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(state.githubVerificationUrl) {
        state.githubVerificationUrl?.let(onOpenVerification)
    }
    SettingsScaffold(
        title = stringResource(R.string.github_screen_title),
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Default.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.github_intro))
            Text(state.githubLogin ?: stringResource(R.string.github_not_connected))
            state.githubMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            // Without a client ID the connect button below is disabled forever, and a dead button
            // with no explanation is indistinguishable from a broken screen. The ID is not baked
            // into the repo any more, so a build that was not given one cannot do device flow.
            if (!state.githubConfigured) {
                Text(stringResource(R.string.github_client_id_missing), color = MaterialTheme.colorScheme.error)
            }
            state.githubUserCode?.let { code ->
                GithubDeviceCodeCard(code, state.githubVerificationUrl, onOpenVerification)
            } ?: if (state.githubLogin == null) {
                Button(onClick = onConnect, enabled = state.githubConfigured && !state.githubPolling, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (state.githubPolling) {
                            stringResource(
                                R.string.github_waiting_for_authorization,
                            )
                        } else {
                            stringResource(R.string.github_connect)
                        },
                    )
                }
            } else {
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.github_disconnect))
                }
            }
        }
    }
}
