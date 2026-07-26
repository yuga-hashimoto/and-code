package com.opencode.android.feature.support

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opencode.android.OpenCodeApplication
import com.opencode.android.R
import com.opencode.android.core.ProjectLinks

@Composable
fun GitHubStarPromptDialog(
    starCount: Int?,
    secondPrompt: Boolean,
    onStar: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        icon = { Icon(Icons.Default.Star, contentDescription = null) },
        title = {
            Text(
                stringResource(
                    if (secondPrompt) R.string.github_star_second_prompt_title else R.string.github_star_prompt_title,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(
                        if (secondPrompt) R.string.github_star_second_prompt_body else R.string.github_star_prompt_body,
                    ),
                )
                starCount?.let {
                    Text(
                        stringResource(R.string.github_star_count, it),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onStar) {
                Icon(Icons.Default.Star, contentDescription = null)
                Text(
                    text = stringResource(R.string.github_star_action),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onLater) {
                Text(stringResource(if (secondPrompt) R.string.github_star_not_now else R.string.github_star_later))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubSupportSheetHost(
    appVersion: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OpenCodeApplication
    val snapshot by app.githubStarCoordinator.snapshot.collectAsState()

    LaunchedEffect(Unit) {
        app.githubStarCoordinator.refresh()
    }

    GitHubSupportSheet(
        snapshot = snapshot,
        appVersion = appVersion,
        onDismiss = onDismiss,
        onStar = {
            app.githubStarCoordinator.markRepositoryOpenedFromSettings()
            openProjectLink(context, ProjectLinks.GITHUB_REPOSITORY)
        },
        onOpenRepository = { openProjectLink(context, ProjectLinks.GITHUB_REPOSITORY) },
        onOpenIssues = { openProjectLink(context, ProjectLinks.GITHUB_ISSUES) },
        onOpenLicense = { openProjectLink(context, ProjectLinks.LICENSE) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GitHubSupportSheet(
    snapshot: GitHubStarSnapshot,
    appVersion: String,
    onDismiss: () -> Unit,
    onStar: () -> Unit,
    onOpenRepository: () -> Unit,
    onOpenIssues: () -> Unit,
    onOpenLicense: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GitHubSupportHeader(snapshot)
            GitHubSupportActions(
                starred = snapshot.starred == true,
                onStar = onStar,
                onOpenRepository = onOpenRepository,
                onOpenIssues = onOpenIssues,
                onOpenLicense = onOpenLicense,
            )
            VersionRow(appVersion)
        }
    }
}

@Composable
private fun GitHubSupportHeader(snapshot: GitHubStarSnapshot) {
    Text(
        stringResource(R.string.github_support_title),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        if (snapshot.starred == true) {
            stringResource(R.string.github_star_verified)
        } else {
            stringResource(R.string.github_support_body)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    snapshot.stargazersCount?.let {
        Text(
            stringResource(R.string.github_star_count, it),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun GitHubSupportActions(
    starred: Boolean,
    onStar: () -> Unit,
    onOpenRepository: () -> Unit,
    onOpenIssues: () -> Unit,
    onOpenLicense: () -> Unit,
) {
    Button(
        modifier = Modifier.fillMaxWidth(),
        onClick = onStar,
    ) {
        Icon(Icons.Default.Star, contentDescription = null)
        Text(
            text = stringResource(if (starred) R.string.github_repo_action else R.string.github_star_action),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
    SupportLinkButton(
        icon = Icons.Default.Code,
        label = stringResource(R.string.github_repo_action),
        onClick = onOpenRepository,
    )
    SupportLinkButton(
        icon = Icons.Default.BugReport,
        label = stringResource(R.string.github_issue_action),
        onClick = onOpenIssues,
    )
    SupportLinkButton(
        icon = Icons.Default.Info,
        label = stringResource(R.string.github_license_action),
        onClick = onOpenLicense,
    )
}

@Composable
private fun VersionRow(appVersion: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.github_version_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            appVersion,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SupportLinkButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Icon(icon, contentDescription = null)
        Text(
            text = label,
            modifier = Modifier.padding(start = 8.dp).weight(1f),
        )
        Icon(Icons.Default.OpenInNew, contentDescription = null)
    }
}

fun openProjectLink(
    context: Context,
    url: String,
) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}
