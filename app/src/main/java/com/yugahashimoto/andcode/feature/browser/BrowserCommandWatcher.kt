package com.yugahashimoto.andcode.feature.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.yugahashimoto.andcode.core.storage.DeviceStorage
import com.yugahashimoto.andcode.core.util.pollWhileActive
import com.yugahashimoto.andcode.runtime.LocalAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

private const val POLL_INTERVAL_MILLIS = 1000L

/**
 * Watches the active workspace for a browser command written by the in-guest agent
 * (`.and-code/browser-command.json`). An `open` command names a page to show so the user can watch
 * and join in; a `handoff` command asks the user to take over (typically to sign in) and pauses the
 * agent until they hand the browser back.
 *
 * Only polls while [foreground] is `true`: opening a browser nobody is looking at achieves nothing,
 * and a composition outlives the activity being stopped, so without the gate this read the disk
 * every second for as long as the process was alive. A command written while the app was away is
 * carried out as soon as it returns.
 *
 * [foreground] keys the effect, so it must be a reference that stays the same across recompositions
 * (the app's own `StateFlow` is); a flow built inline would restart the watcher, and its immediate
 * first poll, on every recomposition.
 */
@Composable
fun GuestBrowserCommandWatcher(
    workspacePath: String?,
    agent: LocalAgent?,
    runtimeDirectory: File,
    foreground: Flow<Boolean>,
    onCommand: (BrowserCommand) -> Unit,
) {
    val deliver by rememberUpdatedState(onCommand)
    LaunchedEffect(workspacePath, agent, runtimeDirectory, foreground) {
        if (workspacePath == null || agent == null) return@LaunchedEffect
        watchBrowserCommands(
            active = foreground,
            poll = {
                withContext(Dispatchers.IO) {
                    consumeBrowserCommand(workspacePath, agent, runtimeDirectory, DeviceStorage.mounts())
                }
            },
            onCommand = { command -> deliver(command) },
        )
    }
}

/**
 * The watcher's loop, apart from Compose so the gating can be tested with a virtual clock.
 *
 * [poll] suspends while it reads the command file, so a gate that closes in that instant can drop
 * the command it has just consumed. That is a window of milliseconds at the moment the app is
 * leaving the screen, when nobody is left to watch the browser open.
 */
internal suspend fun watchBrowserCommands(
    active: Flow<Boolean>,
    poll: suspend () -> BrowserCommand?,
    onCommand: (BrowserCommand) -> Unit,
    intervalMillis: Long = POLL_INTERVAL_MILLIS,
) {
    active.pollWhileActive(intervalMillis) { poll()?.let(onCommand) }
}
