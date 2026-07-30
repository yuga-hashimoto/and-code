package com.yugahashimoto.andcode

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.core.ProjectLinks
import com.yugahashimoto.andcode.feature.support.GitHubStarPromptDialog
import com.yugahashimoto.andcode.feature.support.openProjectLink
import com.yugahashimoto.andcode.ui.AndCodeApp

class MainActivity : ComponentActivity() {
    private var targetSessionId by mutableStateOf<String?>(null)
    private var showInitialStarPrompt by mutableStateOf(false)

    private val app: AndCodeApplication
        get() = application as AndCodeApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)
        showInitialStarPrompt = app.githubStarCoordinator.shouldShowInitialPrompt()
        app.githubStarCoordinator.refresh()

        setContent {
            val snapshot by app.githubStarCoordinator.snapshot.collectAsState()
            val secondPromptRequested by app.githubStarCoordinator.secondPromptRequested.collectAsState()
            val thankYouRequested by app.githubStarCoordinator.thankYouRequested.collectAsState()
            val snackbarHostState = remember { SnackbarHostState() }
            val thankYouMessage = stringResource(R.string.github_star_thanks_snackbar)

            LaunchedEffect(thankYouRequested) {
                if (thankYouRequested) {
                    snackbarHostState.showSnackbar(thankYouMessage)
                    app.githubStarCoordinator.markThankYouShown()
                }
            }

            LaunchedEffect(secondPromptRequested) {
                if (secondPromptRequested) {
                    app.githubStarCoordinator.markSecondPromptPresented()
                }
            }

            Box {
                AndCodeApp(
                    onOpenAssistantSettings = ::openAssistantSettings,
                    targetSessionId = targetSessionId,
                )
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp),
                )
            }

            if (showInitialStarPrompt) {
                GitHubStarPromptDialog(
                    starCount = snapshot.stargazersCount,
                    secondPrompt = false,
                    onStar = {
                        app.githubStarCoordinator.markInitialStarOpened()
                        showInitialStarPrompt = false
                        openProjectLink(this@MainActivity, ProjectLinks.GITHUB_REPOSITORY)
                    },
                    onLater = {
                        app.githubStarCoordinator.markInitialDeferred()
                        showInitialStarPrompt = false
                    },
                )
            }

            if (secondPromptRequested) {
                GitHubStarPromptDialog(
                    starCount = snapshot.stargazersCount,
                    secondPrompt = true,
                    onStar = {
                        app.githubStarCoordinator.markSecondStarOpened()
                        openProjectLink(this@MainActivity, ProjectLinks.GITHUB_REPOSITORY)
                    },
                    onLater = app.githubStarCoordinator::dismissSecondPrompt,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        app.githubStarCoordinator.onAppResumed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        intent ?: return
        intent.getStringExtra("target_session_id")?.let { id ->
            targetSessionId = id
        }
    }

    private fun openAssistantSettings() {
        val roleOpened =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(RoleManager::class.java)
                if (roleManager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true) {
                    runCatching {
                        startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
                        true
                    }.getOrDefault(false)
                } else {
                    false
                }
            } else {
                false
            }

        if (roleOpened) return

        val opened =
            listOf(
                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
                Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            ).any { intent ->
                runCatching {
                    startActivity(intent)
                    true
                }.getOrDefault(false)
            }

        if (!opened) {
            Toast.makeText(this, R.string.could_not_open_settings, Toast.LENGTH_SHORT).show()
        }
    }
}
